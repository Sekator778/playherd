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

/// What tells one version of a file from another without reading it: its length, the time it was last
/// modified and -- on Unix -- its inode. A caller that holds a parsed copy of the file keeps the stamp
/// the file had when it was read, and a later [`file_stamp`] that is equal says the copy is still the
/// file, with one `stat` instead of a read and a parse (`oracle/queue.rs::QueueCache`).
///
/// Every version `write_json_atomic` writes is a new file renamed over the old one, and a new inode
/// by necessity: the previous version still exists while the new one is being created, so the two
/// never share a number. A file changed in place (an editor that truncates, a test that overwrites)
/// keeps its inode and shows in the length or the modification time -- unless both stay the same, which a filesystem
/// with a coarse clock (a whole second) allows for two writes of equal length within that second. That
/// is the limit of this stamp, and nothing in this service writes that way: the one writer of the files
/// it caches is `write_json_atomic`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FileStamp {
    len: u64,
    modified: Option<std::time::SystemTime>,
    #[cfg(unix)]
    inode: u64,
}

impl FileStamp {
    fn of(meta: &fs::Metadata) -> Self {
        Self {
            len: meta.len(),
            modified: meta.modified().ok(),
            #[cfg(unix)]
            inode: std::os::unix::fs::MetadataExt::ino(meta),
        }
    }
}

/// The stamp of the file at `path`; `Ok(None)` when there is no such file (a stamp of its own kind: the
/// file that is not there yet and then appears differs from it). Any other failure to look at the file
/// is an error -- the caller then does not trust a copy it holds.
pub fn file_stamp(path: &Path) -> Result<Option<FileStamp>> {
    match fs::metadata(path) {
        Ok(meta) => Ok(Some(FileStamp::of(&meta))),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e).with_context(|| format!("looking at {}", path.display())),
    }
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

    /// A file that is not there has no stamp, one that is has the same stamp for as long as nobody writes
    /// it, and every atomic write gives it another -- also when the new content is exactly as long as the
    /// old (the inode and the modification time say so, the length cannot).
    #[test]
    fn a_stamp_is_stable_until_the_file_is_written_and_every_atomic_write_changes_it() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state.json");
        assert_eq!(file_stamp(&path).unwrap(), None, "no file, no stamp");

        write_json_atomic(&path, &Doc { name: "same length".into(), n: 1 }).unwrap();
        let first = file_stamp(&path).unwrap().expect("a stamp once the file exists");
        assert_eq!(file_stamp(&path).unwrap(), Some(first), "nothing wrote it: the same stamp");

        let mut stamps = vec![first];
        for n in 2..=6 {
            write_json_atomic(&path, &Doc { name: "same length".into(), n }).unwrap();
            let stamp = file_stamp(&path).unwrap().expect("stamp");
            assert_ne!(Some(&stamp), stamps.last(), "write {n} left the stamp as it was");
            stamps.push(stamp);
        }
        std::fs::remove_file(&path).unwrap();
        assert_eq!(file_stamp(&path).unwrap(), None, "a removed file has no stamp again");
    }

    /// A file changed in place -- no rename -- shows in its length.
    #[test]
    fn a_stamp_notices_a_file_that_is_overwritten_in_place() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state.json");
        std::fs::write(&path, b"{\"name\":\"a\",\"n\":1}").unwrap();
        let before = file_stamp(&path).unwrap();
        std::fs::write(&path, b"{\"name\":\"a longer one\",\"n\":1}").unwrap();
        assert_ne!(file_stamp(&path).unwrap(), before);
    }

    /// Anything but "no such file" is an error: a stamp that could not be taken must not pass for "unchanged".
    #[cfg(unix)]
    #[test]
    fn a_file_that_cannot_be_looked_at_is_an_error_and_not_a_missing_file() {
        let dir = tempfile::tempdir().unwrap();
        let plain_file = dir.path().join("plain");
        std::fs::write(&plain_file, b"x").unwrap();
        // "plain/state.json": a path below a regular file is "not a directory", not "not found"
        let err = file_stamp(&plain_file.join("state.json")).unwrap_err();
        assert!(format!("{err:#}").contains("looking at"), "{err:#}");
    }
}
