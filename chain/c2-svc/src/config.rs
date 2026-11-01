//! Service configuration, read from the environment (task instruction:
//! "loads the operator key and oracle state from a data dir (env
//! `C2_DATA_DIR`, default `/data`; on the stand it is `~/.c2/testnet11`
//! mounted read-write)"). No config file, no CLI flags for these -- a
//! container gets its whole configuration from its environment (12-factor,
//! **предложение**: consistent with how the rest of this project runs
//! services in Docker, `the decision log` D-44).

use std::net::IpAddr;
use std::path::PathBuf;

use crate::fee_estimate::DEFAULT_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST;
use crate::fee_reserve::FeeCoinPolicy;

/// `C2_MEMPOOL_REPLACE_AFTER_SECS`'s default (`Config::mempool_replace_after`).
pub const DEFAULT_MEMPOOL_REPLACE_AFTER_SECS: u64 = 180;

/// `C2_ORACLE_RETAIN_CONFIRMED_SECS`'s default (`Config::oracle_retain_confirmed`): a day -- decided, `docs/research/chain_service.md` §19.
pub const DEFAULT_ORACLE_RETAIN_CONFIRMED_SECS: u64 = 86_400;

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
    /// Cap on retry attempts for one broadcast (bundle version -- a
    /// fee-bumped rebuild starts its own fresh count, see
    /// `publisher.rs::advance_in_flight`) before this service stops
    /// auto-retrying it and only reports the stuck spend via
    /// `GET /v1/oracle/status` (`inFlightBundleId`) for the operator to
    /// look at (D-43: "операторский CLI + публичная страница статуса").
    /// **предложение**.
    pub max_retry_attempts: u32,
    /// Hard ceiling on the fee (mojos) a rebuilt spend may pay when the node
    /// keeps rejecting a broadcast for being under the mempool's current
    /// minimum (task instruction: "on each consecutive fee rejection
    /// multiply the fee ... up to a configurable cap"). `C2_MAX_FEE_MOJOS`,
    /// **предложение** default 50 000 000 mojos = 0.00005 TXCH (task
    /// instruction's own suggested default) -- generous enough to clear
    /// testnet11's observed ~90-100% full mempool (`docs/research/
    /// week1_testnet_results.md` §5.1: a real accepted fee there was
    /// 3 000 000 mojos at cost≈38.7M) while still bounding the operator's
    /// TXCH spend if the mempool is congested indefinitely.
    pub max_fee_mojos: u64,
    /// The rate a spend is priced at when `get_fee_estimate` fails or answers 0 and no estimate has
    /// succeeded yet in this process (afterwards the last successful estimate's rate is used,
    /// `fee_estimate.rs`), in **millimojos per unit of cost** -- an integer, so 100 means 0.1 mojo
    /// per cost. `C2_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST`, **предложение** default 100 (derived
    /// from the numbers of 2026-10-02, `docs/research/chain_service.md` §17.2); 0 = no floor. The
    /// result is capped at `max_fee_mojos` like every other fee.
    pub fallback_fee_rate_millimojos_per_cost: u64,
    /// How long an in-flight bundle may sit in the node's mempool, unconfirmed, before the
    /// publisher replaces it with one that pays more (`Publisher::replace_in_mempool`,
    /// `docs/research/chain_service.md` §17.3). `C2_MEMPOOL_REPLACE_AFTER_SECS`, **предложение**
    /// default 180 s: a healthy batch took about two minutes on 2026-10-02, and 180 s is also the
    /// cadence at which the server queues records, so at most one more batch waits behind a slow
    /// one. 0 turns the replacement off (a bundle in the mempool is then left alone, as before).
    pub mempool_replace_after: std::time::Duration,
    /// How long a confirmed oracle record stays in the queue file after its confirmation
    /// (`oracle/queue.rs::QueueFile::apply_retention`). `C2_ORACLE_RETAIN_CONFIRMED_SECS`, default
    /// 86 400 (a day), decided by the main session on 2026-10-06: `docs/research/chain_service.md` §19 (§16.9
    /// and §18.2 had left the term open). What a day costs: the queue file holds an entry of about 410 bytes (measured, §18.2)
    /// for every confirmed record -- some 10 000 a day on the stand (`the internal notes` §6.1 item 1), so
    /// about 4 MB -- while the server's own database holds the public journal of the records and the
    /// chain the records themselves. After the retention a seq still answers `confirmed` (no height, coin
    /// or content) and is never accepted again; a record that is not confirmed is never dropped. 0 keeps
    /// every entry for good, as before. Two costs of dropping are accepted and written down in
    /// `chain/README.md`: a different record under a dropped seq is not reported (a seq issued twice is
    /// caught only inside the retention), and a build older than the retention must not be started on a
    /// queue file that a pass has pruned.
    pub oracle_retain_confirmed: std::time::Duration,
    /// N consecutive `tick()` failures before `GET /v1/oracle/status`'s
    /// `stuckReason` and `GET /health` report degraded (task fix,
    /// `docs/research/chain_service.md` §12: "any repeated tick failure (N
    /// consecutive, e.g. 3)"). `C2_TICK_FAILURE_THRESHOLD`, **предложение**
    /// default 3 -- the task's own suggested example, and small enough
    /// (`publisher_tick`'s 10s default => ~30s) that an operator/the Docker
    /// healthcheck notices promptly without flapping on one transient
    /// coinset hiccup.
    pub tick_failure_threshold: u32,
    /// Fee-coin reserve (`fee_reserve.rs`, `docs/research/chain_service.md` §16): the
    /// operator wallet is kept at no fewer than `C2_FEE_COINS_MIN` free standard coins of at
    /// least `C2_FEE_COIN_MOJOS` each -- when it falls below, the oracle publisher's tick splits
    /// the largest coin (while the oracle has nothing in flight and no fee coin is reserved).
    /// **предложение**: 4 coins of 200 000 000 mojos (0.0002 TXCH, four times `max_fee_mojos`'s
    /// default). `C2_FEE_COINS_MIN=0` turns the reserve off.
    pub fee_coins_min: usize,
    pub fee_coin_mojos: u64,
    /// FIXED INTERFACE (a): the token every `/v1/*` request (except
    /// `GET /health`) must present as `Authorization: Bearer <token>`.
    /// `C2_SVC_TOKEN`, matched against the server's `HERD_CHAINSVC_TOKEN` --
    /// the two env var names differ because each side names it from its own
    /// perspective, the *value* is the shared secret. `None` only when unset
    /// -- `main.rs` refuses to start on `None` unless `allow_no_auth`.
    pub svc_token: Option<String>,
    /// `C2_SVC_ALLOW_NO_AUTH=1`: permits starting with no `C2_SVC_TOKEN`
    /// configured (task instruction: "for tests"). Every request is then let
    /// through unchecked (`service_auth::require_bearer_token`) -- never set
    /// this on the stand or in production.
    pub allow_no_auth: bool,
    /// `C2_BIND_ADDR`: comma-separated list of addresses to listen on,
    /// default `127.0.0.1` alone. Task instruction: on the stand (`chainsvc`
    /// runs with `network_mode: host`) the server reaches this service via
    /// the compose gateway IP (`172.28.77.1`), not through the compose
    /// network's service-name DNS -- so the stand's `docker-compose.yml`
    /// must set this to `172.28.77.1,127.0.0.1` (both: the gateway IP for
    /// the server, loopback for anything running in this same network
    /// namespace, e.g. the healthcheck). Never `0.0.0.0` -- an explicit,
    /// enumerated bind list, not "every interface", is the point of this
    /// variable existing at all.
    pub bind_addrs: Vec<IpAddr>,
}

/// An environment value parsed as `T`, or `default` when it is absent or not a valid `T`. Pure
/// and env-independent for the same reason `parse_bind_addrs` is (`tests/config.rs`-style unit
/// tests without mutating process-wide variables).
pub fn parse_or<T: std::str::FromStr>(raw: Option<&str>, default: T) -> T {
    raw.and_then(|v| v.trim().parse().ok()).unwrap_or(default)
}

/// Parses `C2_BIND_ADDR`'s comma-separated address list. Pure and
/// env-independent so it can be unit tested directly (`tests/config.rs`)
/// without mutating process-wide environment variables (which would race
/// against other `#[test]` functions running in parallel in the same
/// binary).
pub fn parse_bind_addrs(raw: &str) -> Result<Vec<IpAddr>, String> {
    let addrs: Result<Vec<IpAddr>, String> = raw
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| s.parse::<IpAddr>().map_err(|e| format!("C2_BIND_ADDR: invalid address {s:?}: {e}")))
        .collect();
    match addrs {
        Ok(a) if a.is_empty() => Err("C2_BIND_ADDR: must name at least one address".to_string()),
        other => other,
    }
}

/// Whether the service may start with this token configuration (task
/// instruction: "chainsvc refuses to start without a token (unless
/// C2_SVC_ALLOW_NO_AUTH=1 for tests)"). Pure so `main.rs`'s startup gate can
/// be unit tested without touching real env vars or actually starting a
/// server.
pub fn check_startup_auth_config(svc_token: &Option<String>, allow_no_auth: bool) -> Result<(), String> {
    if svc_token.is_none() && !allow_no_auth {
        return Err(
            "C2_SVC_TOKEN is required to start c2-svc (or set C2_SVC_ALLOW_NO_AUTH=1 for tests)".to_string(),
        );
    }
    Ok(())
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
        let max_fee_mojos = std::env::var("C2_MAX_FEE_MOJOS")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(50_000_000);
        let fallback_fee_rate_millimojos_per_cost = parse_or(
            std::env::var("C2_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST").ok().as_deref(),
            DEFAULT_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST,
        );
        let mempool_replace_after = std::time::Duration::from_secs(parse_or(
            std::env::var("C2_MEMPOOL_REPLACE_AFTER_SECS").ok().as_deref(),
            DEFAULT_MEMPOOL_REPLACE_AFTER_SECS,
        ));
        let oracle_retain_confirmed = std::time::Duration::from_secs(parse_or(
            std::env::var("C2_ORACLE_RETAIN_CONFIRMED_SECS").ok().as_deref(),
            DEFAULT_ORACLE_RETAIN_CONFIRMED_SECS,
        ));
        let tick_failure_threshold = std::env::var("C2_TICK_FAILURE_THRESHOLD")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(3);
        let fee_reserve_default = FeeCoinPolicy::default();
        let fee_coins_min = std::env::var("C2_FEE_COINS_MIN")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(fee_reserve_default.min_free);
        let fee_coin_mojos = std::env::var("C2_FEE_COIN_MOJOS")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(fee_reserve_default.coin_mojos);
        let svc_token = std::env::var("C2_SVC_TOKEN").ok().filter(|s| !s.is_empty());
        let allow_no_auth = std::env::var("C2_SVC_ALLOW_NO_AUTH").is_ok_and(|v| v == "1");
        let bind_addrs = parse_bind_addrs(&std::env::var("C2_BIND_ADDR").unwrap_or_else(|_| "127.0.0.1".to_string()))
            .expect("C2_BIND_ADDR");
        Self {
            data_dir,
            operator_name,
            push_enabled,
            port,
            coinset_base_url,
            publisher_tick: std::time::Duration::from_secs(10),
            retry_backoff: std::time::Duration::from_secs(45),
            max_retry_attempts: 8,
            max_fee_mojos,
            fallback_fee_rate_millimojos_per_cost,
            mempool_replace_after,
            oracle_retain_confirmed,
            tick_failure_threshold,
            fee_coins_min,
            fee_coin_mojos,
            svc_token,
            allow_no_auth,
            bind_addrs,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_new_settings_default_to_a_tenth_of_a_mojo_per_cost_and_three_minutes() {
        assert_eq!(parse_or(None, DEFAULT_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST), 100);
        assert_eq!(parse_or(None, DEFAULT_MEMPOOL_REPLACE_AFTER_SECS), 180);
    }

    #[test]
    fn the_oracle_retention_defaults_to_a_day_and_zero_keeps_everything() {
        assert_eq!(parse_or(None, DEFAULT_ORACLE_RETAIN_CONFIRMED_SECS), 86_400);
        assert_eq!(parse_or(Some("3600"), DEFAULT_ORACLE_RETAIN_CONFIRMED_SECS), 3_600);
        assert_eq!(parse_or(Some("0"), DEFAULT_ORACLE_RETAIN_CONFIRMED_SECS), 0, "0 is a setting: no retention");
        assert_eq!(parse_or(Some("a day"), DEFAULT_ORACLE_RETAIN_CONFIRMED_SECS), 86_400);
    }

    #[test]
    fn parse_or_takes_a_valid_value_including_zero_and_falls_back_on_anything_else() {
        assert_eq!(parse_or(Some("250"), 100u64), 250);
        assert_eq!(parse_or(Some(" 7 "), 100u64), 7, "surrounding whitespace is ignored");
        assert_eq!(parse_or(Some("0"), 100u64), 0, "0 is a real setting (no floor / replacement off), not 'unset'");
        assert_eq!(parse_or(Some("lots"), 100u64), 100);
        assert_eq!(parse_or(Some("-5"), 100u64), 100);
        assert_eq!(parse_or(Some(""), 100u64), 100);
        assert_eq!(parse_or(None, 100u64), 100);
    }

    #[test]
    fn parses_single_default_address() {
        assert_eq!(parse_bind_addrs("127.0.0.1"), Ok(vec!["127.0.0.1".parse().unwrap()]));
    }

    #[test]
    fn parses_comma_separated_addresses_with_whitespace() {
        // Task instruction: on the stand, bind to the compose gateway IP
        // *and* loopback.
        assert_eq!(
            parse_bind_addrs("172.28.77.1, 127.0.0.1"),
            Ok(vec!["172.28.77.1".parse().unwrap(), "127.0.0.1".parse().unwrap()])
        );
    }

    #[test]
    fn rejects_empty_string() {
        assert!(parse_bind_addrs("").is_err());
    }

    #[test]
    fn rejects_garbage_address() {
        assert!(parse_bind_addrs("not-an-ip").is_err());
    }

    #[test]
    fn startup_refuses_with_no_token_and_no_bypass() {
        assert!(check_startup_auth_config(&None, false).is_err());
    }

    #[test]
    fn startup_allows_no_token_with_explicit_bypass() {
        assert!(check_startup_auth_config(&None, true).is_ok());
    }

    #[test]
    fn startup_allows_a_configured_token_regardless_of_bypass_flag() {
        assert!(check_startup_auth_config(&Some("secret".to_string()), false).is_ok());
        assert!(check_startup_auth_config(&Some("secret".to_string()), true).is_ok());
    }
}
