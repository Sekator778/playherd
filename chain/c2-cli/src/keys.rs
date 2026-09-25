//! Key derivation: BIP-39 mnemonic -> Chia master key -> standard wallet key
//! -> synthetic key -> p2 puzzle hash / `txch1...` address.
//!
//! ## Exact path and algorithm (with sources)
//!
//! 1. **Mnemonic -> seed.** A 24-word BIP-39 English mnemonic is turned into
//!    a 64-byte seed with the *plain* BIP-39 algorithm (PBKDF2-HMAC-SHA512,
//!    salt `"mnemonic" + passphrase`, 2048 rounds), passphrase = `""`.
//!    `chia-blockchain`'s `chia.util.keychain.mnemonic_to_seed` does exactly
//!    this (no Chia-specific twist) -- here it's `bip39::Mnemonic::to_seed("")`
//!    (`bip39-2.2.2/src/lib.rs::to_seed`).
//! 2. **Seed -> master secret key.** `chia_bls::SecretKey::from_seed(seed)`
//!    implements EIP-2333 `derive_master_SK`
//!    (`chia-bls-0.36.1/src/secret_key.rs::from_seed`, doc comment there
//!    cites <https://eips.ethereum.org/EIPS/eip-2333#derive_master_sk>).
//!    This is the same primitive `AugSchemeMPL.key_gen` wraps in the Python
//!    `chia-blockchain` / `blspy`.
//! 3. **Master -> standard wallet key at index `i`: UNHARDENED path
//!    `m/12381/8444/2/i`.** `chia_bls::master_to_wallet_unhardened(&master_sk, i)`
//!    (`chia-bls-0.36.1/src/derive_keys.rs`), which derives all four path
//!    components (`12381`, `8444`, `2`, `i`) with *unhardened* EIP-2333 child
//!    derivation (`DerivableKey::derive_unhardened`), never hardened. This is
//!    the modern "observer" key path chia-blockchain (>=1.7, watch-only
//!    wallets) and Sage use by default for standard-wallet receive addresses
//!    -- as opposed to the fully hardened legacy path, which the same source
//!    file also implements (`master_to_wallet_hardened`) but which is
//!    intentionally not used here.
//! 4. **Wallet key -> synthetic key.** The actual p2 puzzle
//!    (`p2_delegated_puzzle_or_hidden_puzzle`, i.e. a normal Chia address) is
//!    curried with a *synthetic* key, not the wallet key directly:
//!    `synthetic_sk = wallet_sk.derive_synthetic()`
//!    (`chia-puzzle-types-0.36.1/src/derive_synthetic.rs`, trait
//!    `DeriveSynthetic`), which adds a fixed BLS offset derived from
//!    `sha256(wallet_pk || DEFAULT_HIDDEN_PUZZLE_HASH)` -- the same
//!    transform chia-blockchain's `calculate_synthetic_secret_key` performs.
//!    Every spend of this address must be signed with `synthetic_sk`, never
//!    the plain `wallet_sk` (a signature under `wallet_sk` would not satisfy
//!    the puzzle's `AGG_SIG_ME` condition).
//! 5. **Puzzle hash / address.**
//!    `puzzle_hash = StandardArgs::curry_tree_hash(synthetic_pk)`
//!    (`chia-puzzle-types-0.36.1/src/puzzles/standard.rs`), and
//!    `address = bech32m(prefix = "txch", data = puzzle_hash)` via
//!    `chia_sdk_utils::Address` (`chia-sdk-utils-0.36.0/src/bech32.rs`).
//!    `txch` is testnet11's address prefix, confirmed live
//!    (`docs/research/week1_chain_access.md` §1-2: `get_network_info` ->
//!    `"network_prefix":"txch"`).

use anyhow::{Context, Result};
use bip39::Mnemonic;
use chia_bls::{PublicKey, SecretKey, master_to_wallet_unhardened};
use chia_protocol::Bytes32;
use chia_puzzle_types::DeriveSynthetic;
use chia_puzzle_types::standard::StandardArgs;
use chia_sdk_utils::Address;

pub const TXCH_PREFIX: &str = "txch";

/// A derived standard-wallet keypair at one address index.
pub struct WalletKey {
    pub index: u32,
    /// The plain (non-synthetic) wallet secret key at this index. Kept for
    /// reference only -- puzzle construction and signing use the synthetic
    /// key below, never this one directly.
    pub wallet_sk: SecretKey,
    /// The key the p2 puzzle is curried with, and that must sign spends of
    /// this address's coins.
    pub synthetic_sk: SecretKey,
    pub synthetic_pk: PublicKey,
    pub puzzle_hash: Bytes32,
}

/// Generates a fresh 24-word English BIP-39 mnemonic.
pub fn generate_mnemonic() -> Result<Mnemonic> {
    Mnemonic::generate(24).context("generating 24-word BIP-39 mnemonic")
}

/// Derives the Chia master secret key from a mnemonic (empty passphrase, see
/// module docs point 1-2).
pub fn master_sk_from_mnemonic(mnemonic: &Mnemonic) -> SecretKey {
    let seed = mnemonic.to_seed("");
    SecretKey::from_seed(&seed)
}

/// Derives the standard-wallet key (wallet + synthetic key + puzzle hash) at
/// address index `index` (see module docs points 3-5).
pub fn derive_wallet_key(master_sk: &SecretKey, index: u32) -> WalletKey {
    let wallet_sk = master_to_wallet_unhardened(master_sk, index);
    let synthetic_sk = wallet_sk.derive_synthetic();
    let synthetic_pk = synthetic_sk.public_key();
    let puzzle_hash: Bytes32 = StandardArgs::curry_tree_hash(synthetic_pk).into();
    WalletKey {
        index,
        wallet_sk,
        synthetic_sk,
        synthetic_pk,
        puzzle_hash,
    }
}

/// Bech32m-encodes a puzzle hash as a `txch1...` address.
pub fn address_of(puzzle_hash: Bytes32) -> Result<String> {
    Address::new(puzzle_hash, TXCH_PREFIX.to_string())
        .encode()
        .context("bech32m-encoding txch address")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A mnemonic must always re-derive the same address index 0 -- this is
    /// the guarantee the whole keystore relies on (lose the mnemonic, lose
    /// the wallet; keep it, always get the same coins back).
    #[test]
    fn derivation_is_deterministic() {
        let mnemonic = generate_mnemonic().unwrap();
        let sk1 = master_sk_from_mnemonic(&mnemonic);
        let sk2 = master_sk_from_mnemonic(&mnemonic);
        assert_eq!(sk1.to_bytes(), sk2.to_bytes());

        let w1 = derive_wallet_key(&sk1, 0);
        let w2 = derive_wallet_key(&sk2, 0);
        assert_eq!(w1.puzzle_hash, w2.puzzle_hash);

        let addr1 = address_of(w1.puzzle_hash).unwrap();
        assert!(addr1.starts_with("txch1"));
        assert_eq!(addr1, address_of(w2.puzzle_hash).unwrap());
    }

    /// Different indices of the same mnemonic must produce different
    /// addresses (otherwise the whole point of an HD wallet is lost).
    #[test]
    fn different_indices_differ() {
        let mnemonic = generate_mnemonic().unwrap();
        let master_sk = master_sk_from_mnemonic(&mnemonic);
        let w0 = derive_wallet_key(&master_sk, 0);
        let w1 = derive_wallet_key(&master_sk, 1);
        assert_ne!(w0.puzzle_hash, w1.puzzle_hash);
    }
}
