//! `c2-svc`: FIXED INTERFACE (3) HTTP service ("chainsvc", port 8090) --
//! Sage signature verification (`/v1/auth/verify`) and the multi-record
//! oracle singleton publisher (`/v1/oracle/records`, `/v1/oracle/status`).
//! See `docs/research/chain_service.md` for the full write-up.

use std::sync::Arc;

use anyhow::Result;
use clap::Parser;

use c2_svc::app;
use c2_svc::config::Config;
use c2_svc::oracle::chain_client::CoinsetChainClient;
use c2_svc::oracle::publisher::Publisher;

#[derive(Parser)]
#[command(name = "c2-svc", about = "C2 chain service: auth/verify + oracle publisher (FIXED INTERFACE 3)")]
struct Cli {
    /// Same effect as `C2_PUSH=0`: build and sign spends, never call
    /// `push_tx`, never advance persisted oracle state/queue. For tests /
    /// local runs without touching testnet11.
    #[arg(long, default_value_t = false)]
    dry_run: bool,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env().add_directive("c2_svc=info".parse()?))
        .init();

    let cli = Cli::parse();
    let config = Config::from_env().with_dry_run(cli.dry_run);

    tracing::info!(
        data_dir = %config.data_dir.display(),
        operator_name = %config.operator_name,
        push_enabled = config.push_enabled,
        port = config.port,
        "starting c2-svc"
    );

    let chain = CoinsetChainClient::new(config.coinset_base_url.clone());
    let publisher = Arc::new(Publisher {
        data_dir: config.data_dir.clone(),
        operator_name: config.operator_name.clone(),
        push_enabled: config.push_enabled,
        chain,
        retry_backoff: config.retry_backoff,
        max_retry_attempts: config.max_retry_attempts,
        fee_target_time_secs: 60,
    });

    // Background oracle-publisher loop: advances in-flight spends /
    // starts new batches from the durable queue on a fixed tick, so a
    // `POST /v1/oracle/records` handler never blocks its HTTP response on a
    // chain round-trip (task instruction doesn't require synchronous
    // publish-on-POST, only durable queueing -- **предложение**).
    {
        let publisher = Arc::clone(&publisher);
        let tick = config.publisher_tick;
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(tick);
            loop {
                interval.tick().await;
                match publisher.tick().await {
                    Ok(outcome) => tracing::debug!(?outcome, "publisher tick"),
                    Err(e) => tracing::error!("publisher tick failed: {e:#}"),
                }
            }
        });
    }

    let listener = tokio::net::TcpListener::bind(("0.0.0.0", config.port)).await?;
    tracing::info!(addr = %listener.local_addr()?, "listening");
    axum::serve(listener, app::router(publisher)).await?;
    Ok(())
}
