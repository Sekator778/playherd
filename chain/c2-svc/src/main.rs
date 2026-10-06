//! `c2-svc`: FIXED INTERFACE (3) HTTP service ("chainsvc", port 8090) --
//! Sage signature verification (`/v1/auth/verify`) and the multi-record
//! oracle singleton publisher (`/v1/oracle/records`, `/v1/oracle/status`).
//! See `docs/research/chain_service.md` for the full write-up.

use std::sync::Arc;

use anyhow::{Context, Result};
use clap::Parser;

use c2_svc::app;
use c2_svc::config::{Config, check_startup_auth_config};
use c2_svc::fee_allocator::FeeCoinAllocator;
use c2_svc::fee_reserve::FeeCoinPolicy;
use c2_svc::oracle::chain_client::CoinsetChainClient;
use c2_svc::oracle::publisher::{Publisher, TickFailureState};
use c2_svc::oracle::queue::QueueCache;
use c2_svc::play::deposit::DepositService;
use c2_svc::play::withdrawal_publisher::WithdrawalPublisher;

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

    // FIXED INTERFACE (a): refuse to start with no auth token configured,
    // unless the operator explicitly opted out (tests only).
    check_startup_auth_config(&config.svc_token, config.allow_no_auth).map_err(|e| anyhow::anyhow!(e))?;

    tracing::info!(
        data_dir = %config.data_dir.display(),
        operator_name = %config.operator_name,
        push_enabled = config.push_enabled,
        port = config.port,
        bind_addrs = ?config.bind_addrs,
        auth_enabled = config.svc_token.is_some(),
        fee_coins_min = config.fee_coins_min,
        fee_coin_mojos = config.fee_coin_mojos,
        max_fee_mojos = config.max_fee_mojos,
        fallback_fee_rate_millimojos_per_cost = config.fallback_fee_rate_millimojos_per_cost,
        mempool_replace_after_secs = config.mempool_replace_after.as_secs(),
        "starting c2-svc"
    );

    // Shared across every publisher that funds spends from the operator's
    // plain-XCH wallet (task fix, `docs/research/chain_service.md` §15: the
    // oracle publisher and the PLAY withdrawal publisher used to pick a fee
    // coin independently, via a plain deterministic gap-scan, and reliably
    // collided whenever both needed one at once). One `Arc` instance, given
    // to both publishers below -- see `fee_allocator`'s module doc.
    //
    // The same allocator owns the fee-coin reserve (`fee_reserve.rs`, `docs/research/
    // chain_service.md` §16): `C2_FEE_COINS_MIN` free coins of `C2_FEE_COIN_MOJOS` each, kept by
    // splitting a big coin from the oracle publisher's tick (only while the oracle has nothing in flight).
    //
    // It also carries the fee rule every publisher and the fee-coin split share
    // (`fee_estimate.rs`, `docs/research/chain_service.md` §17.2): what to pay when
    // `get_fee_estimate` fails or answers 0 -- the last successful estimate's rate, else the floor
    // `C2_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST`.
    let fee_allocator = Arc::new(
        FeeCoinAllocator::with_policy(FeeCoinPolicy {
            min_free: config.fee_coins_min,
            coin_mojos: config.fee_coin_mojos,
        })
        .with_fallback_fee_rate(config.fallback_fee_rate_millimojos_per_cost),
    );

    let chain = CoinsetChainClient::new(config.coinset_base_url.clone());
    let publisher = Arc::new(Publisher {
        data_dir: config.data_dir.clone(),
        operator_name: config.operator_name.clone(),
        push_enabled: config.push_enabled,
        chain,
        retry_backoff: config.retry_backoff,
        max_retry_attempts: config.max_retry_attempts,
        max_fee_mojos: config.max_fee_mojos,
        fee_target_time_secs: 60,
        mempool_replace_after: config.mempool_replace_after,
        balance_snapshot: tokio::sync::RwLock::new(None),
        queue_lock: tokio::sync::Mutex::new(()),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: config.tick_failure_threshold,
        fee_allocator: Arc::clone(&fee_allocator),
        queue_cache: QueueCache::default(),
    });

    // PLAY withdraw/deposit (Р-6, decision Р-53 2026-09-26): a separate
    // `WithdrawalPublisher`/`DepositService`, each with their own
    // `CoinsetChainClient` (cheap to construct again -- same pattern as the
    // oracle `Publisher` above; no shared mutable state between them besides
    // the chain itself and the operator's own key/data files, which are
    // reloaded from disk on every use, never cached across these instances)
    // -- except `fee_allocator`, which is deliberately the *same* instance
    // as the oracle `Publisher`'s (see above).
    let withdrawal_publisher = Arc::new(WithdrawalPublisher {
        data_dir: config.data_dir.clone(),
        operator_name: config.operator_name.clone(),
        push_enabled: config.push_enabled,
        chain: CoinsetChainClient::new(config.coinset_base_url.clone()),
        retry_backoff: config.retry_backoff,
        max_retry_attempts: config.max_retry_attempts,
        max_fee_mojos: config.max_fee_mojos,
        fee_target_time_secs: 60,
        mempool_replace_after: config.mempool_replace_after,
        queue_lock: tokio::sync::Mutex::new(()),
        treasury_stuck: tokio::sync::RwLock::new(None),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: config.tick_failure_threshold,
        fee_allocator: Arc::clone(&fee_allocator),
    });
    let deposit_service = Arc::new(DepositService {
        data_dir: config.data_dir.clone(),
        operator_name: config.operator_name.clone(),
        chain: CoinsetChainClient::new(config.coinset_base_url.clone()),
        registry_lock: tokio::sync::Mutex::new(()),
    });

    // Task fix (`docs/research/chain_service.md` §11): "before accepting or
    // publishing anything" -- walk the oracle singleton lineage and
    // reconcile the local queue against it *before* any listener opens or
    // the periodic loops below start ticking. Never trust a possibly stale
    // or race-corrupted `<name>.oracle_queue.json` on its own: this is what
    // the incident that motivated this fix got wrong (76 already-confirmed
    // records republished after a redeploy onto exactly such a file). A
    // no-op (not an error) when there's no oracle state yet (`c2
    // oracle-launch` not run) -- only a genuine chain-access failure refuses
    // startup here, the same "fail closed on a safety invariant" precedent
    // `check_startup_auth_config` above already sets for this service.
    let bootstrap_outcome = publisher
        .bootstrap_from_chain()
        .await
        .context("startup chain-lineage bootstrap (docs/research/chain_service.md §11) failed")?;
    tracing::info!(
        generations_advanced = bootstrap_outcome.generations_advanced,
        dropped_confirmed = bootstrap_outcome.dropped_confirmed.len(),
        conflicts = bootstrap_outcome.new_conflicts.len(),
        "startup chain-lineage bootstrap complete"
    );

    // PLAY treasury self-heal (decision Р-53 follow-up, `docs/research/
    // chain_service.md` §13): same "before accepting or publishing
    // anything" precedent as the oracle bootstrap just above -- reconcile
    // the cached treasury PLAY CAT coin against the chain before the
    // periodic loops below start ticking. A no-op (not an error) when
    // there's no treasury minted yet (`c2 issue-play` not run).
    withdrawal_publisher
        .bootstrap_treasury_from_chain()
        .await
        .context("startup PLAY treasury reconciliation (docs/research/chain_service.md §13) failed")?;

    // Task fix (`docs/research/chain_service.md` §15): seed the shared
    // `FeeCoinAllocator` from whatever in-flight bundle each publisher's own
    // durable queue file already committed to, *before* either publisher's
    // background tick loop starts below -- otherwise a freshly restarted
    // process would forget a fee coin a surviving in-flight bundle still
    // depends on, and could immediately hand that same coin to the other
    // publisher on its very first tick.
    if let Some(coin_id) = publisher.in_flight_fee_coin_id()? {
        fee_allocator.reserve_specific(coin_id).await;
    }
    if let Some(coin_id) = withdrawal_publisher.in_flight_fee_coin_id()? {
        fee_allocator.reserve_specific(coin_id).await;
    }

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

    // Background PLAY withdrawal publisher loop -- same tick shape as the
    // oracle publisher's own loop above, independent `tokio::spawn` so a
    // slow/stuck withdrawal batch can never delay oracle publishing (or vice
    // versa); each has its own `queue_lock`, so they never contend either.
    {
        let withdrawal_publisher = Arc::clone(&withdrawal_publisher);
        let tick = config.publisher_tick;
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(tick);
            loop {
                interval.tick().await;
                match withdrawal_publisher.tick().await {
                    Ok(outcome) => tracing::debug!(?outcome, "play withdrawal publisher tick"),
                    Err(e) => tracing::error!("play withdrawal publisher tick failed: {e:#}"),
                }
            }
        });
    }

    // Separate background loop refreshing `operatorBalanceMojos` for
    // `GET /v1/oracle/status` (see `Publisher::balance_snapshot`'s doc
    // comment for the bug this fixes). Deliberately its own loop, not folded
    // into the tick loop above: the wallet scan (`ChainClient::unspent_coins`,
    // 20 sequential address lookups, `ADDRESS_GAP`) is the slowest chain call
    // this service makes, and it must never delay `tick()`'s own
    // confirmation/retry timing (**предложение**: reuses `publisher_tick`'s
    // interval, no new config surface). The same scan also feeds the fee-coin
    // gauge in `GET /v1/oracle/status` (`feeCoinsFree`) and tells the fee-coin
    // reserve (`fee_reserve.rs`) whether the wallet needs topping up.
    {
        let publisher = Arc::clone(&publisher);
        let tick = config.publisher_tick;
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(tick);
            loop {
                interval.tick().await;
                publisher.refresh_balance_snapshot().await;
            }
        });
    }

    // Task instruction: bind exactly the enumerated `C2_BIND_ADDR` addresses
    // (default `127.0.0.1` alone), never `0.0.0.0` -- on the stand this is
    // `172.28.77.1,127.0.0.1` (see `config.rs::Config::bind_addrs`'s doc
    // comment). One listener + one `axum::serve` task per address, all
    // serving the same `Router` (cheap to `.clone()`, it's an `Arc` inside).
    let router = app::router(publisher, withdrawal_publisher, deposit_service, config.svc_token.clone());
    let mut listeners = Vec::with_capacity(config.bind_addrs.len());
    for addr in &config.bind_addrs {
        let listener = tokio::net::TcpListener::bind((*addr, config.port))
            .await
            .with_context(|| format!("binding {addr}:{}", config.port))?;
        tracing::info!(addr = %listener.local_addr()?, "listening");
        listeners.push(listener);
    }

    let mut serve_tasks = Vec::with_capacity(listeners.len());
    for listener in listeners {
        let router = router.clone();
        serve_tasks.push(tokio::spawn(async move { axum::serve(listener, router).await }));
    }
    for task in serve_tasks {
        task.await.context("serve task panicked")??;
    }
    Ok(())
}
