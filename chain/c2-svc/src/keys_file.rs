//! Loads the operator's key from `<data_dir>/<name>.json` -- the exact same
//! `KeyFile` shape `c2 keygen --name operator` already wrote
//! (`c2_cli::keystore::KeyFile`), just read from an arbitrary data
//! directory instead of `c2-cli`'s hardcoded `~/.c2/testnet11`
//! (`c2_cli::keystore::keys_dir`). `chain/c2-cli` is left untouched (see
//! `docs/research/chain_service.md` §1), so this is a small, local
//! re-implementation of the read path only -- `KeyFile`'s fields are `pub`
//! and it already derives `Deserialize`, so no new (de)serialization logic
//! is needed, only the path.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use bip39::Mnemonic;
use c2_cli::keystore::KeyFile;
use c2_cli::keys::{self, WalletKey};
use chia_bls::SecretKey;

pub fn key_path(data_dir: &Path, name: &str) -> PathBuf {
    data_dir.join(format!("{name}.json"))
}

pub fn load_key_file(data_dir: &Path, name: &str) -> Result<KeyFile> {
    let path = key_path(data_dir, name);
    let data = std::fs::read_to_string(&path)
        .with_context(|| format!("reading operator key file {}", path.display()))?;
    serde_json::from_str(&data).with_context(|| format!("parsing {}", path.display()))
}

/// Loads the operator's Chia master secret key (BIP-39 mnemonic -> master
/// key, `c2_cli::keys::master_sk_from_mnemonic`). Needed on its own (not
/// just the index-0 `WalletKey`) for `chain::find_funding_coin`/`balance`,
/// which scan a gap of derived indices themselves.
pub fn load_operator_master_sk(data_dir: &Path, name: &str) -> Result<SecretKey> {
    let key_file = load_key_file(data_dir, name)?;
    let mnemonic = Mnemonic::parse(&key_file.mnemonic).context("parsing operator mnemonic")?;
    Ok(keys::master_sk_from_mnemonic(&mnemonic))
}

/// Loads the operator's master secret key and derives its address-index-0
/// standard wallet key (same derivation `c2-cli` uses for every wallet,
/// `c2_cli::keys::derive_wallet_key`; every c2-cli spending command also
/// only ever uses index 0, see `docs/research/week1_testnet_cli.md`).
pub fn load_operator_wallet_key(data_dir: &Path, name: &str) -> Result<WalletKey> {
    let master_sk = load_operator_master_sk(data_dir, name)?;
    Ok(keys::derive_wallet_key(&master_sk, 0))
}
