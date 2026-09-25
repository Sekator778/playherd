//! Service configuration, read from the environment (task instruction:
//! "loads the operator key and oracle state from a data dir (env
//! `C2_DATA_DIR`, default `/data`; on the stand it is `~/.c2/testnet11`
//! mounted read-write)"). No config file, no CLI flags for these -- a
//! container gets its whole configuration from its environment (12-factor,
//! **предложение**: consistent with how the rest of this project runs
//! services in Docker, `docs/product/decisions.md` Р-44).

use std::path::PathBuf;

/// `C2_PUSH=0` (or `--dry-run` on the CLI, see `main.rs`) disables every
/// real chain write this service would otherwise make: `push_tx` is never
/// called, and neither the oracle state file nor the durable record queue
/// is advanced/persisted past a dry-run publish. Mirrors `c2-cli`'s own
/// `--push` convention (dry-run unless explicitly asked to broadcast) --
/// see `chain/c2-cli/README.md`.
#[derive(Debug, Clone)]
pub struct Config {
    /// Base directory holding `<operator_name>.json` (the operator's
    /// keystore `KeyFile`, `c2_cli::keystore::KeyFile`'s shape) and
    /// `<operator_name>.oracle.json` (`c2_cli::state::OracleState`'s
    /// shape), plus this service's own durable queue file. Default `/data`
    /// (a Docker volume mount point); on the local stand this is bind-mounted
    /// to `~/.c2/testnet11` so it's *the same* directory `c2 oracle-launch`
    /// already wrote to (task instruction, launcher id
    /// `c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20`).
    pub data_dir: PathBuf,
    /// Wallet/oracle-state name inside `data_dir` (task instruction + real
    /// testnet11 run both use `operator`, see
    /// `docs/research/week1_testnet_results.md`).
    pub operator_name: String,
    /// `false` = dry-run: build and sign spends, never call `push_tx`, never
    /// persist oracle-state/queue advancement.
    pub push_enabled: bool,
    /// TCP port this service listens on. FIXED INTERFACE (3) names the
    /// service `chainsvc`, port 8090.
    pub port: u16,
    /// Coinset.org base URL. Overridable only for tests; production always
    /// runs testnet11 (project constraint: "testnet11 only").
    pub coinset_base_url: String,
    /// How often the background oracle publisher loop wakes up to check
    /// in-flight-spend confirmation / start a new batch from the queue.
    /// **предложение**: 10s balances "publish promptly after a round
    /// boundary" against not hammering coinset.org.
    pub publisher_tick: std::time::Duration,
    /// How long an in-flight (broadcast, unconfirmed) spend is left alone
    /// before this service retries `push_tx` again (task instruction:
    /// "retries with backoff"). **предложение**: 45s, comfortably above
    /// testnet11's ~average block time (see
    /// `docs/research/week1_chain_access.md`) so a normal confirmation
    /// isn't mistaken for a stuck push.
    pub retry_backoff: std::time::Duration,
    /// Cap on retry attempts for one broadcast before this service stops
    /// auto-retrying and only reports the stuck spend via
    /// `GET /v1/oracle/status` (`inFlightBundleId`) for the operator to
    /// look at (Р-43: "операторский CLI + публичная страница статуса").
    /// **предложение**.
    pub max_retry_attempts: u32,
}

impl Config {
    pub fn from_env() -> Self {
        let data_dir = std::env::var("C2_DATA_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("/data"));
        let operator_name =
            std::env::var("C2_OPERATOR_NAME").unwrap_or_else(|_| "operator".to_string());
        let push_enabled = std::env::var("C2_PUSH")
            .map(|v| v != "0")
            .unwrap_or(true);
        let port = std::env::var("C2_SVC_PORT")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(8090);
        let coinset_base_url = std::env::var("C2_COINSET_BASE_URL")
            .unwrap_or_else(|_| "https://testnet11.api.coinset.org".to_string());
        Self {
            data_dir,
            operator_name,
            push_enabled,
            port,
            coinset_base_url,
            publisher_tick: std::time::Duration::from_secs(10),
            retry_backoff: std::time::Duration::from_secs(45),
            max_retry_attempts: 8,
        }
    }

    /// Forces dry-run regardless of `C2_PUSH` -- used by `main.rs`'s
    /// `--dry-run` CLI flag.
    pub fn with_dry_run(mut self, dry_run: bool) -> Self {
        if dry_run {
            self.push_enabled = false;
        }
        self
    }
}
