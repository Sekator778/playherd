//! Reads/atomically-writes `<data_dir>/<name>.play.json` -- the exact same
//! `PlayCoinState` JSON shape `c2 issue-play` already produces
//! (`c2_cli::state::PlayCoinState`), from an arbitrary data directory instead
//! of `c2-cli`'s hardcoded `~/.c2/testnet11` (same reasoning as
//! `state_file.rs` for `OracleState` -- see that file's doc comment).
//! Tracks the treasury's *current* PLAY CAT coin the withdrawal publisher
//! (`play/withdrawal_publisher.rs`) spends from and recreates (with change)
//! on every batch, the same self-recreation principle
//! `oracle/publisher.rs`/`state_file.rs` already use for the oracle
//! singleton. `chain/c2-cli` is left unmodified: `PlayCoinState`'s fields are
//! already `pub`/`Serialize`/`Deserialize`.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use c2_cli::state::PlayCoinState;

use crate::atomic_file::write_json_atomic;

pub fn play_state_path(data_dir: &Path, name: &str) -> PathBuf {
    data_dir.join(format!("{name}.play.json"))
}

pub fn load_play_state(data_dir: &Path, name: &str) -> Result<PlayCoinState> {
    let path = play_state_path(data_dir, name);
    let data = std::fs::read_to_string(&path)
        .with_context(|| format!("reading PLAY treasury state {} (run `c2 issue-play` first?)", path.display()))?;
    serde_json::from_str(&data).with_context(|| format!("parsing {}", path.display()))
}

pub fn save_play_state_atomic(data_dir: &Path, name: &str, state: &PlayCoinState) -> Result<PathBuf> {
    let path = play_state_path(data_dir, name);
    write_json_atomic(&path, state)?;
    Ok(path)
}
