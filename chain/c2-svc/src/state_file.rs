//! Reads/atomically-writes `<data_dir>/<name>.oracle.json` -- the exact
//! same `OracleState` JSON shape `c2 oracle-launch`/`c2 oracle-publish`
//! already produce (`c2_cli::state::OracleState`), from an arbitrary data
//! directory instead of `c2-cli`'s hardcoded `~/.c2/testnet11`
//! (`docs/research/chain_service.md` §1). `chain/c2-cli` is left
//! unmodified: `OracleState`'s fields are `pub` and it already derives
//! `Serialize`/`Deserialize`, so this only supplies the path + atomicity
//! (`atomic_file.rs`), matching a real file already on the stand
//! (verified today: `~/.c2/testnet11/operator.oracle.json`, launcher
//! `0xc5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20`,
//! `next_sequence: 2`, `current_coin` confirmed on testnet11 at height
//! 4735705 -- `docs/research/chain_service.md` §1).

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use c2_cli::state::OracleState;

use crate::atomic_file::write_json_atomic;

pub fn oracle_state_path(data_dir: &Path, name: &str) -> PathBuf {
    data_dir.join(format!("{name}.oracle.json"))
}

pub fn load_oracle_state(data_dir: &Path, name: &str) -> Result<OracleState> {
    let path = oracle_state_path(data_dir, name);
    let data = std::fs::read_to_string(&path)
        .with_context(|| format!("reading oracle state {} (run `c2 oracle-launch` first?)", path.display()))?;
    serde_json::from_str(&data).with_context(|| format!("parsing {}", path.display()))
}

pub fn save_oracle_state_atomic(data_dir: &Path, name: &str, state: &OracleState) -> Result<PathBuf> {
    let path = oracle_state_path(data_dir, name);
    write_json_atomic(&path, state)?;
    Ok(path)
}
