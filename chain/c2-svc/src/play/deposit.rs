//! Deposit-address derivation and the durable registry of every player index
//! a deposit address has ever been issued for (Р-6, decision Р-53
//! 2026-09-26): `GET /v1/play/deposits` needs to know which CAT puzzle
//! hashes to scan, and -- unlike the oracle singleton -- there is no on-chain
//! lineage to walk to rediscover that list from scratch.
//!
//! **Derivation path (документируется по заданию: "document the path")**:
//! the same unhardened standard-wallet path every other key in this project
//! uses (`c2_cli::keys::derive_wallet_key`, `m/12381/8444/2/i` -- see that
//! module's own doc comment for the full chain of primitives, sources
//! included), just at index `DEPOSIT_INDEX_OFFSET + playerIndex` instead of
//! index 0. `c2-svc` itself only ever derives index 0 from the operator's
//! mnemonic for anything else (`keys_file::load_operator_wallet_key`, used as
//! both the oracle manager key, `oracle/lineage.rs`, and the PLAY treasury
//! owner key, `withdrawal_publisher.rs`) -- `DEPOSIT_INDEX_OFFSET`
//! (**предложение**: 1_000_000) is far above any index this project's
//! operator wallet is ever expected to use by hand, so a player deposit
//! address can never collide with the operator's own oracle/treasury key
//! regardless of `playerIndex` (checked explicitly below, not just assumed).

use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use c2_cli::cat_chain::cat_puzzle_hash;
use c2_cli::keys::{self, WalletKey};
use chia_bls::SecretKey;
use chia_protocol::Bytes32;
use serde::{Deserialize, Serialize};

use crate::atomic_file::{read_json_or_default, write_json_atomic};
use crate::keys_file::load_operator_master_sk;
use crate::play_state_file::load_play_state;

use super::chain_client::PlayChainClient;
use super::types::{DepositAddressResponse, DepositDto, DepositsResponse};

/// See module doc: player deposit addresses are derived at
/// `DEPOSIT_INDEX_OFFSET + playerIndex`, never at any index <= this one.
pub const DEPOSIT_INDEX_OFFSET: u32 = 1_000_000;

pub struct DerivedDepositAddress {
    pub wallet_key: WalletKey,
    pub address: String,
    pub cat_puzzle_hash: Bytes32,
}

/// Derives player `player_index`'s deposit address (task instruction: "index
/// = playerIndex + an offset that never collides with the oracle/treasury
/// keys" -- see `DEPOSIT_INDEX_OFFSET`).
pub fn deposit_address(master_sk: &SecretKey, asset_id: Bytes32, player_index: u32) -> Result<DerivedDepositAddress> {
    let index = DEPOSIT_INDEX_OFFSET
        .checked_add(player_index)
        .context("playerIndex too large: DEPOSIT_INDEX_OFFSET + playerIndex overflows u32")?;
    let wallet_key = keys::derive_wallet_key(master_sk, index);
    let address = keys::address_of(wallet_key.puzzle_hash)?;
    let cat_puzzle_hash = cat_puzzle_hash(asset_id, wallet_key.puzzle_hash);
    Ok(DerivedDepositAddress { wallet_key, address, cat_puzzle_hash })
}

/// Durable registry of every player index a deposit address has ever been
/// issued for (`<data_dir>/<name>.play_deposit_registry.json`) -- `GET
/// /v1/play/deposits` scans exactly these CAT puzzle hashes (task
/// instruction's "the known deposit CAT puzzle hashes"), never an unbounded
/// index range.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct DepositRegistry {
    /// `playerIndex -> catPuzzleHash`, so a restart never needs to re-guess
    /// which players were already handed an address.
    pub players: BTreeMap<u32, Bytes32>,
}

impl DepositRegistry {
    fn path(data_dir: &Path, name: &str) -> PathBuf {
        data_dir.join(format!("{name}.play_deposit_registry.json"))
    }

    pub fn load(data_dir: &Path, name: &str) -> Result<Self> {
        read_json_or_default(&Self::path(data_dir, name), Self::default)
    }

    pub fn save_atomic(&self, data_dir: &Path, name: &str) -> Result<()> {
        write_json_atomic(&Self::path(data_dir, name), self)
    }

    /// Records `player_index -> cat_puzzle_hash`; `true` iff this is a newly
    /// seen player index. Idempotent -- re-issuing the same player's address
    /// is a pure re-derivation, always the same puzzle hash (`deposit_address`
    /// is deterministic), so re-registering it is a harmless no-op.
    pub fn register(&mut self, player_index: u32, cat_puzzle_hash: Bytes32) -> bool {
        self.players.insert(player_index, cat_puzzle_hash).is_none()
    }
}

pub struct ScannedDeposit {
    pub coin_id: Bytes32,
    pub player_index: u32,
    pub amount_mojos: u64,
    pub height: u32,
}

/// Scans every puzzle hash in `registry` for confirmed coins at or after
/// `since_height`, maps each back to its `playerIndex`, and de-duplicates by
/// `coinId` (task instruction: "each coinId reported exactly once per
/// sinceHeight window; the server de-duplicates by coinId"). A coin whose
/// puzzle hash isn't in `registry` at all is structurally impossible to see
/// here (only registered puzzle hashes are ever queried), which is also what
/// makes a wrong-asset coin at the *same inner puzzle hash* harmless without
/// extra checking: `cat_puzzle_hash` curries the asset id in, so a coin of a
/// different asset (or plain XCH) has a completely different puzzle hash and
/// is never among the ones queried in the first place.
pub async fn scan_deposits<C: PlayChainClient>(
    chain: &C,
    registry: &DepositRegistry,
    since_height: u32,
) -> Result<(Vec<ScannedDeposit>, u32)> {
    let tip_height = chain.peak_height().await.context("fetching chain tip height")?;
    if registry.players.is_empty() {
        return Ok((Vec::new(), tip_height));
    }

    let by_puzzle_hash: HashMap<Bytes32, u32> = registry.players.iter().map(|(&idx, &ph)| (ph, idx)).collect();
    let puzzle_hashes: Vec<Bytes32> = by_puzzle_hash.keys().copied().collect();

    let records = chain
        .coin_records_by_puzzle_hashes(puzzle_hashes, Some(since_height))
        .await
        .context("scanning deposit CAT puzzle hashes")?;

    let mut seen = std::collections::BTreeSet::new();
    let mut deposits = Vec::new();
    for record in records {
        if !seen.insert(record.coin_id) {
            continue; // task instruction: "each coinId reported exactly once"
        }
        let Some(&player_index) = by_puzzle_hash.get(&record.puzzle_hash) else {
            continue; // defensive: every record's puzzle hash was one we asked for
        };
        deposits.push(ScannedDeposit {
            coin_id: record.coin_id,
            player_index,
            amount_mojos: record.amount,
            height: record.confirmed_height,
        });
    }
    deposits.sort_by_key(|d| (d.height, d.player_index, format!("{}", d.coin_id)));
    Ok((deposits, tip_height))
}

/// Ties `deposit_address`/`DepositRegistry`/`scan_deposits` together into
/// `POST /v1/play/deposit-address` / `GET /v1/play/deposits`'s handlers
/// (`app.rs`).
pub struct DepositService<C: PlayChainClient> {
    pub data_dir: PathBuf,
    pub operator_name: String,
    pub chain: C,
    /// Serializes the registry file's read-modify-write cycle -- same reason
    /// `Publisher::queue_lock`/`WithdrawalPublisher::queue_lock` exist (task
    /// fix precedent, `docs/research/chain_service.md` §10: two independent,
    /// unlocked load-mutate-save cycles can lose an update).
    pub registry_lock: tokio::sync::Mutex<()>,
}

impl<C: PlayChainClient> DepositService<C> {
    pub async fn issue_address(&self, player_index: u32) -> Result<DepositAddressResponse> {
        let master_sk = load_operator_master_sk(&self.data_dir, &self.operator_name)?;
        let play_state = load_play_state(&self.data_dir, &self.operator_name)
            .context("no cached PLAY treasury state for this operator -- run `c2 issue-play` first")?;
        let derived = deposit_address(&master_sk, play_state.asset_id, player_index)?;

        let _guard = self.registry_lock.lock().await;
        let mut registry = DepositRegistry::load(&self.data_dir, &self.operator_name)?;
        if registry.register(player_index, derived.cat_puzzle_hash) {
            registry
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("persisting newly issued deposit address")?;
        }

        Ok(DepositAddressResponse {
            address: derived.address,
            inner_puzzle_hash: format!("0x{}", derived.wallet_key.puzzle_hash),
            cat_puzzle_hash: format!("0x{}", derived.cat_puzzle_hash),
        })
    }

    pub async fn list_deposits(&self, since_height: u32) -> Result<DepositsResponse> {
        let registry = DepositRegistry::load(&self.data_dir, &self.operator_name)?;
        let (deposits, tip_height) = scan_deposits(&self.chain, &registry, since_height).await?;
        Ok(DepositsResponse {
            deposits: deposits
                .into_iter()
                .map(|d| DepositDto {
                    coin_id: format!("0x{}", d.coin_id),
                    player_index: d.player_index,
                    amount_mojos: d.amount_mojos,
                    height: d.height,
                })
                .collect(),
            tip_height,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use c2_cli::keys::{generate_mnemonic, master_sk_from_mnemonic};

    #[test]
    fn derivation_is_deterministic_and_distinct_per_player() {
        let mnemonic = generate_mnemonic().unwrap();
        let master_sk = master_sk_from_mnemonic(&mnemonic);
        let asset_id = Bytes32::from([9u8; 32]);

        let a1 = deposit_address(&master_sk, asset_id, 1).unwrap();
        let a2 = deposit_address(&master_sk, asset_id, 1).unwrap();
        assert_eq!(a1.wallet_key.puzzle_hash, a2.wallet_key.puzzle_hash);
        assert_eq!(a1.cat_puzzle_hash, a2.cat_puzzle_hash);
        assert_eq!(a1.address, a2.address);

        let b = deposit_address(&master_sk, asset_id, 2).unwrap();
        assert_ne!(a1.wallet_key.puzzle_hash, b.wallet_key.puzzle_hash);
        assert_ne!(a1.cat_puzzle_hash, b.cat_puzzle_hash);

        // Never collides with the operator's own index-0 (oracle/treasury) key.
        let operator_key = keys::derive_wallet_key(&master_sk, 0);
        assert_ne!(a1.wallet_key.puzzle_hash, operator_key.puzzle_hash);
    }

    #[test]
    fn different_asset_ids_never_share_a_cat_puzzle_hash() {
        // The "wrong-asset coin ignored" guarantee at its root: two different
        // assets at the very same inner puzzle hash curry to different outer
        // puzzle hashes, so a wrong-asset coin structurally can't match a
        // scan for the real one (see `scan_deposits`'s own doc comment).
        let mnemonic = generate_mnemonic().unwrap();
        let master_sk = master_sk_from_mnemonic(&mnemonic);
        let play_asset_id = Bytes32::from([1u8; 32]);
        let other_asset_id = Bytes32::from([2u8; 32]);

        let play = deposit_address(&master_sk, play_asset_id, 7).unwrap();
        let other = deposit_address(&master_sk, other_asset_id, 7).unwrap();
        assert_eq!(play.wallet_key.puzzle_hash, other.wallet_key.puzzle_hash); // same player, same inner ph
        assert_ne!(play.cat_puzzle_hash, other.cat_puzzle_hash); // different CAT-layer puzzle hash
    }
}
