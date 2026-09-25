//! Atomic JSON file writes: write to a fresh temp file in the same
//! directory, `fsync`, then `rename` over the final path. A `rename` within
//! one filesystem is atomic on both Linux and macOS -- any reader (this
//! process on restart, or a concurrent read) always sees either the old
//! file or the fully-written new one, never a partial write. Used for
//! `operator.oracle.json` (task instruction: "update operator.oracle.json
//! atomically") and this service's own durable record queue file.

use std::fs::{self, File};
use std::io::Write;
use std::path::Path;

use anyhow::{Context, Result};

pub fn write_json_atomic<T: serde::Serialize>(path: &Path, value: &T) -> Result<()> {
    let dir = path
        .parent()
        .with_context(|| format!("{} has no parent directory", path.display()))?;
    fs::create_dir_all(dir).with_context(|| format!("creating {}", dir.display()))?;

    let tmp_file_name = format!(
        ".{}.tmp-{}-{}",
        path.file_name().and_then(|n| n.to_str()).unwrap_or("state"),
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0),
    );
    let tmp_path = dir.join(tmp_file_name);

    let json = serde_json::to_string_pretty(value).context("serializing to JSON")?;
    {
        let mut f = File::create(&tmp_path)
            .with_context(|| format!("creating temp file {}", tmp_path.display()))?;
        f.write_all(json.as_bytes())
            .with_context(|| format!("writing temp file {}", tmp_path.display()))?;
        f.sync_all()
            .with_context(|| format!("fsync temp file {}", tmp_path.display()))?;
    }
    fs::rename(&tmp_path, path)
        .with_context(|| format!("renaming {} into place at {}", tmp_path.display(), path.display()))?;
    Ok(())
}

/// Loads JSON from `path` if it exists, or returns `default()` if it
/// doesn't (first run: no queue file / no oracle state written yet).
pub fn read_json_or_default<T: serde::de::DeserializeOwned>(path: &Path, default: impl FnOnce() -> T) -> Result<T> {
    if !path.exists() {
        return Ok(default());
    }
    let data = fs::read_to_string(path).with_context(|| format!("reading {}", path.display()))?;
    serde_json::from_str(&data).with_context(|| format!("parsing {}", path.display()))
}
