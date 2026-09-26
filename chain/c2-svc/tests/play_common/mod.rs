//! Shared fake `ChainClient` + `PlayChainClient` for the PLAY withdraw/
//! deposit tests (`tests/play_withdrawals.rs`, `tests/play_deposits.rs`) --
//! no network, no simulator, same "unit the queueing/publishing logic
//! against a fake chain client" approach `tests/oracle_queue.rs::
//! FakeChainClient` already uses for the oracle publisher. `tests/*/mod.rs`
//! (this file's path) is Cargo's own convention for code shared between
//! integration-test binaries without itself becoming one.

#![allow(dead_code)]

use std::collections::HashMap;
use std::sync::Mutex;

use anyhow::Result;
use chia_bls::SecretKey;
use chia_protocol::{Bytes32, Coin, CoinSpend, SpendBundle};

use c2_cli::keys::{WalletKey, derive_wallet_key};
use c2_svc::oracle::chain_client::{ChainClient, CoinRecordInfo, PushOutcome};
use c2_svc::play::chain_client::{PlayChainClient, PuzzleHashCoinRecord};

struct Inner {
    coin_records: HashMap<Bytes32, CoinRecordInfo>,
    puzzle_hash_records: Vec<PuzzleHashCoinRecord>,
    peak_height: u32,
    fee_mojos: u64,
    funding_amount: u64,
    push_calls: u32,
    next_push_outcome: PushOutcome,
    in_mempool: HashMap<Bytes32, bool>,
}

pub struct FakePlayChainClient(Mutex<Inner>);

impl FakePlayChainClient {
    pub fn new() -> Self {
        Self(Mutex::new(Inner {
            coin_records: HashMap::new(),
            puzzle_hash_records: Vec::new(),
            peak_height: 1_000,
            fee_mojos: 0,
            funding_amount: 1_000_000,
            push_calls: 0,
            next_push_outcome: PushOutcome { accepted: true, status: Some("SUCCESS".to_string()), error: None },
            in_mempool: HashMap::new(),
        }))
    }

    pub fn set_fee_estimate(&self, fee_mojos: u64) {
        self.0.lock().unwrap().fee_mojos = fee_mojos;
    }

    pub fn set_in_mempool(&self, tx_id: Bytes32, present: bool) {
        self.0.lock().unwrap().in_mempool.insert(tx_id, present);
    }

    pub fn set_confirmed(&self, coin_id: Bytes32, height: u32) {
        self.0.lock().unwrap().coin_records.insert(
            coin_id,
            CoinRecordInfo { exists: true, confirmed_height: Some(height), spent: false, spent_height: None, coin: None },
        );
    }

    pub fn set_spent(&self, coin_id: Bytes32, confirmed_height: u32, spent_height: u32) {
        self.0.lock().unwrap().coin_records.insert(
            coin_id,
            CoinRecordInfo {
                exists: true,
                confirmed_height: Some(confirmed_height),
                spent: true,
                spent_height: Some(spent_height),
                coin: None,
            },
        );
    }

    pub fn set_push_outcome(&self, outcome: PushOutcome) {
        self.0.lock().unwrap().next_push_outcome = outcome;
    }

    pub fn push_call_count(&self) -> u32 {
        self.0.lock().unwrap().push_calls
    }

    pub fn set_peak_height(&self, height: u32) {
        self.0.lock().unwrap().peak_height = height;
    }

    /// Registers a coin as visible to a `coin_records_by_puzzle_hashes` scan
    /// (deposit detection).
    pub fn add_puzzle_hash_record(&self, record: PuzzleHashCoinRecord) {
        self.0.lock().unwrap().puzzle_hash_records.push(record);
    }
}

impl ChainClient for FakePlayChainClient {
    async fn coin_record(&self, coin_id: Bytes32) -> Result<CoinRecordInfo> {
        Ok(self.0.lock().unwrap().coin_records.get(&coin_id).copied().unwrap_or(CoinRecordInfo {
            exists: false,
            confirmed_height: None,
            spent: false,
            spent_height: None,
            coin: None,
        }))
    }

    async fn fee_estimate_mojos(&self, _cost: u64, _target_time_secs: u32) -> Result<u64> {
        Ok(self.0.lock().unwrap().fee_mojos)
    }

    async fn find_funding_coin(&self, master_sk: &SecretKey, min_amount: u64) -> Result<(WalletKey, Coin)> {
        let amount = self.0.lock().unwrap().funding_amount;
        let wallet_key = derive_wallet_key(master_sk, 0);
        if amount < min_amount {
            anyhow::bail!("fake funding coin has {amount} mojos, need >= {min_amount}");
        }
        let coin = Coin::new(Bytes32::from([7u8; 32]), wallet_key.puzzle_hash, amount);
        Ok((wallet_key, coin))
    }

    async fn balance(&self, _master_sk: &SecretKey) -> Result<u64> {
        Ok(self.0.lock().unwrap().funding_amount)
    }

    async fn push_tx(&self, _bundle: SpendBundle) -> Result<PushOutcome> {
        let mut inner = self.0.lock().unwrap();
        inner.push_calls += 1;
        Ok(inner.next_push_outcome.clone())
    }

    async fn in_mempool(&self, tx_id: Bytes32) -> Result<bool> {
        Ok(self.0.lock().unwrap().in_mempool.get(&tx_id).copied().unwrap_or(false))
    }

    async fn puzzle_and_solution(&self, _coin_id: Bytes32, _height: Option<u32>) -> Result<Option<CoinSpend>> {
        Ok(None)
    }
}

impl PlayChainClient for FakePlayChainClient {
    async fn coin_records_by_puzzle_hashes(
        &self,
        puzzle_hashes: Vec<Bytes32>,
        start_height: Option<u32>,
    ) -> Result<Vec<PuzzleHashCoinRecord>> {
        let inner = self.0.lock().unwrap();
        Ok(inner
            .puzzle_hash_records
            .iter()
            .copied()
            .filter(|r| puzzle_hashes.contains(&r.puzzle_hash))
            .filter(|r| start_height.is_none_or(|h| r.confirmed_height >= h))
            .collect())
    }

    async fn peak_height(&self) -> Result<u32> {
        Ok(self.0.lock().unwrap().peak_height)
    }
}
