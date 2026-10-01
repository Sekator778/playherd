//! Atomic JSON file writes: write to a fresh temp file in the same
//! directory, `fsync` it, `rename` it over the final path, then `fsync` the
//! directory. A `rename` within one filesystem is atomic on both Linux and
//! macOS -- any reader (this process on restart, or a concurrent read) always
//! sees either the old file or the fully-written new one, never a partial
//! write. Used for `operator.oracle.json` (task instruction: "update
//! operator.oracle.json atomically") and this service's own durable queue
//! files (oracle records, PLAY withdrawals).
//!
//! **Why the directory fsync** (`docs/research/chain_service.md` §10.4, §16):
//! fsyncing the temp file makes its *data* durable, but the rename is a change
//! to the *directory*, and until the directory is flushed a power loss or a
//! kernel crash can bring back the old entry -- on Linux (ext4, xfs, the
//! container's bind mount) a file whose rename was acknowledged but not yet
//! flushed can come back as the previous version. For the oracle queue that
//! means a `POST /v1/oracle/records` answered `202` and then lost. A graceful
//! stop (`docker compose up -d --build` sends SIGTERM) never shows it; a hard
//! crash can.

use std::fs::{self, File};
use std::io::Write;
use std::path::Path;

use anyhow::{Context, Result};

pub fn write_json_atomic<T: serde::Serialize>(path: &Path, value: &T) -> Result<()> {
    let dir = path
        .parent()
        .with_context(|| format!("{} has no parent directory", path.display()))?;
    // A bare file name ("state.json") has the empty path as its parent; that is the current directory.
    let dir = if dir.as_os_str().is_empty() { Path::new(".") } else { dir };
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
    sync_dir(dir)
}

/// Flushes `dir`'s own entry table to disk, which is what makes a `rename` into it durable.
///
/// Platform notes. **Unix** (Linux, macOS): a directory opens read-only like a file and
/// `fsync` on it is the standard way (the same call SQLite and every database use after a
/// rename). On macOS `fsync` reaches the drive but, for directories as for files, does not ask
/// the drive to empty its own cache (`F_FULLFSYNC` would) -- the same limit the file `fsync`
/// above has, not made worse here. A filesystem that does not support syncing a directory at
/// all answers `EINVAL` / `ENOTSUP` (some FUSE and network mounts): that cannot be fixed by
/// retrying, and refusing every write on such a mount would take the whole service down for a
/// guarantee the mount cannot give, so those two are tolerated; every other error (an `EIO`
/// that says the flush really failed) is returned and fails the write, exactly like a failed
/// file `fsync`. **Windows** (not a deployment target here: the service runs in Linux
/// containers): `std` cannot open a directory as a file, and NTFS journals directory metadata
/// itself, so this is a no-op there.
fn sync_dir(dir: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        let handle = File::open(dir).with_context(|| format!("opening directory {} to fsync it", dir.display()))?;
        match handle.sync_all() {
            Ok(()) => {}
            Err(e) if matches!(e.kind(), std::io::ErrorKind::InvalidInput | std::io::ErrorKind::Unsupported) => {}
            Err(e) => return Err(e).with_context(|| format!("fsync directory {}", dir.display())),
        }
    }
    #[cfg(not(unix))]
    {
        let _ = dir;
    }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(serde::Serialize, serde::Deserialize, PartialEq, Debug, Default)]
    struct Doc {
        name: String,
        n: u64,
    }

    #[test]
    fn a_write_round_trips_and_a_second_write_replaces_it() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state.json");
        assert_eq!(read_json_or_default(&path, Doc::default).unwrap(), Doc::default(), "absent file -> default");

        write_json_atomic(&path, &Doc { name: "first".into(), n: 1 }).unwrap();
        assert_eq!(read_json_or_default(&path, Doc::default).unwrap(), Doc { name: "first".into(), n: 1 });
        write_json_atomic(&path, &Doc { name: "second".into(), n: 2 }).unwrap();
        assert_eq!(read_json_or_default(&path, Doc::default).unwrap(), Doc { name: "second".into(), n: 2 });
    }

    #[test]
    fn no_temp_file_is_left_behind_and_a_missing_parent_directory_is_created() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("nested").join("deeper").join("state.json");
        write_json_atomic(&path, &Doc { name: "x".into(), n: 7 }).unwrap();
        write_json_atomic(&path, &Doc { name: "y".into(), n: 8 }).unwrap();

        let names: Vec<String> = std::fs::read_dir(path.parent().unwrap())
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        assert_eq!(names, vec!["state.json".to_string()], "only the final file remains: {names:?}");
    }

    /// The directory flush really opens the directory: a directory that does not exist is an error,
    /// not a silent no-op (so the call is not dead code), and an existing one syncs fine.
    #[cfg(unix)]
    #[test]
    fn sync_dir_opens_and_flushes_the_directory() {
        let dir = tempfile::tempdir().unwrap();
        sync_dir(dir.path()).unwrap();
        let err = sync_dir(&dir.path().join("does-not-exist")).unwrap_err();
        assert!(format!("{err:#}").contains("opening directory"), "{err:#}");
    }
}
