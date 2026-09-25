//! On-disk key storage.
//!
//! Task instruction: "Key files go OUTSIDE the repo in `~/.c2/testnet11/`
//! (dir 0700, files 0600) -- never inside the project." Layout:
//! `~/.c2/testnet11/<name>.json` = `{mnemonic, created_utc, network}`.
//! `keygen` refuses to overwrite an existing file for a name.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize)]
pub struct KeyFile {
    pub mnemonic: String,
    pub created_utc: String,
    pub network: String,
}

fn home_dir() -> Result<PathBuf> {
    std::env::var("HOME")
        .map(PathBuf::from)
        .context("HOME is not set")
}

pub fn keys_dir() -> Result<PathBuf> {
    Ok(home_dir()?.join(".c2").join("testnet11"))
}

fn chmod_0700(dir: &Path) -> Result<()> {
    fs::set_permissions(dir, fs::Permissions::from_mode(0o700))
        .with_context(|| format!("chmod 0700 {}", dir.display()))
}

/// Creates `~/.c2` and `~/.c2/testnet11` if missing, and makes sure both are
/// `0700` (not just the leaf directory -- `create_dir_all` would otherwise
/// leave `~/.c2` at the umask-derived default).
pub fn ensure_key_dirs() -> Result<PathBuf> {
    let c2_dir = home_dir()?.join(".c2");
    fs::create_dir_all(&c2_dir).with_context(|| format!("creating {}", c2_dir.display()))?;
    chmod_0700(&c2_dir)?;

    let testnet_dir = keys_dir()?;
    fs::create_dir_all(&testnet_dir)
        .with_context(|| format!("creating {}", testnet_dir.display()))?;
    chmod_0700(&testnet_dir)?;

    Ok(testnet_dir)
}

pub fn key_path(name: &str) -> Result<PathBuf> {
    Ok(keys_dir()?.join(format!("{name}.json")))
}

/// Writes a new key file for `name`. Fails (without touching the file) if
/// one already exists -- this CLI never overwrites a wallet's keys.
pub fn save_new(name: &str, key_file: &KeyFile) -> Result<PathBuf> {
    ensure_key_dirs()?;
    let path = key_path(name)?;

    let json = serde_json::to_string_pretty(key_file)?;

    // `create_new` atomically fails if the file already exists, so a
    // concurrent `keygen --name <same>` can't race the `save_new` above.
    let mut f = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&path)
        .with_context(|| {
            format!(
                "key file already exists, refusing to overwrite: {}",
                path.display()
            )
        })?;
    f.set_permissions(fs::Permissions::from_mode(0o600))
        .with_context(|| format!("chmod 0600 {}", path.display()))?;
    f.write_all(json.as_bytes())
        .with_context(|| format!("writing {}", path.display()))?;

    Ok(path)
}

pub fn load(name: &str) -> Result<KeyFile> {
    let path = key_path(name)?;
    let data = fs::read_to_string(&path).with_context(|| {
        format!(
            "reading key file {} (run `c2 keygen --name {name}` first?)",
            path.display()
        )
    })?;
    let kf: KeyFile =
        serde_json::from_str(&data).with_context(|| format!("parsing {}", path.display()))?;
    Ok(kf)
}

pub fn exists(name: &str) -> Result<bool> {
    Ok(key_path(name)?.exists())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `save_new` must not silently overwrite an existing wallet's mnemonic.
    /// Uses a throwaway `$HOME` so it never touches the real
    /// `~/.c2/testnet11`.
    #[test]
    fn refuses_to_overwrite() {
        let tmp = tempdir();
        // SAFETY: test-only process-wide env mutation, single-threaded test.
        unsafe {
            std::env::set_var("HOME", tmp.path());
        }

        let kf = KeyFile {
            mnemonic: "abandon ".repeat(23) + "art",
            created_utc: "2026-09-25T00:00:00Z".to_string(),
            network: "testnet11".to_string(),
        };
        let path = save_new("dup-test", &kf).expect("first save must succeed");
        assert!(path.exists());

        let perms = fs::metadata(&path).unwrap().permissions();
        assert_eq!(perms.mode() & 0o777, 0o600);

        let dir_perms = fs::metadata(path.parent().unwrap()).unwrap().permissions();
        assert_eq!(dir_perms.mode() & 0o777, 0o700);

        let err = save_new("dup-test", &kf).expect_err("second save must fail");
        assert!(err.to_string().contains("refusing to overwrite"));

        let loaded = load("dup-test").unwrap();
        assert_eq!(loaded.mnemonic, kf.mnemonic);
    }

    fn tempdir() -> tempfile_lite::TempDir {
        tempfile_lite::TempDir::new()
    }

    /// Minimal `mkdtemp`-backed temp dir so this test doesn't need an extra
    /// `tempfile` dev-dependency just for one test.
    mod tempfile_lite {
        use std::path::{Path, PathBuf};

        pub struct TempDir(PathBuf);

        impl TempDir {
            pub fn new() -> Self {
                let base = std::env::temp_dir();
                let unique = format!(
                    "c2-cli-test-{}-{}",
                    std::process::id(),
                    std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .unwrap()
                        .as_nanos()
                );
                let dir = base.join(unique);
                std::fs::create_dir_all(&dir).unwrap();
                Self(dir)
            }

            pub fn path(&self) -> &Path {
                &self.0
            }
        }

        impl Drop for TempDir {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
    }
}
