//! Orchestrates the durable queue (`queue.rs`), the multi-record spend
//! builder (`spend.rs`), and a `ChainClient` (`chain_client.rs`) into the
//! oracle publisher's state machine. Generic over `C: ChainClient` so
//! `tests/oracle_queue.rs` can drive it against an in-memory fake (task
//! instruction: "queueing logic (unit with a fake chain client)") without
//! any real network access; `main.rs` instantiates it with
//! `CoinsetChainClient`.
//!
//! One `tick()` call does exactly one of, in priority order:
//! 1. an in-flight spend just confirmed -> finalize (advance
//!    `operator.oracle.json`, clear `in_flight`);
//! 2. an in-flight spend is still unconfirmed -> maybe retry `push_tx`
//!    (backoff, task instruction "retries with backoff");
//! 3. no in-flight spend and records are pending -> build, sign, persist
//!    (durably, before broadcasting), and broadcast a new batch (<=20
//!    records, task instruction);
//! 4. nothing pending -> idle.
//!
//! "Never double-spend the singleton" (task instruction) is enforced two
//! ways: only one `in_flight` spend is ever persisted at a time (case 3
//! only runs when `in_flight` is `None`), and case 3 additionally checks
//! coinset that `OracleState::current_coin` isn't already spent before
//! building on top of it (guards against a previous crash having left
//! stale local state -- see `docs/research/chain_service.md` §5).

use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use chia_protocol::SpendBundle;
use chia_puzzle_types::Proof;
use sha2::{Digest, Sha256};

use c2_cli::builders::fee::build_fee_only;
use c2_cli::cost::measure_bundle_cost;
use c2_cli::state::ProofDto;

use crate::keys_file::{load_operator_master_sk, load_operator_wallet_key};
use crate::state_file::{load_oracle_state, save_oracle_state_atomic};

use super::chain_client::ChainClient;
use super::queue::{InFlightSpend, QueueFile};
use super::spend::{MAX_RECORDS_PER_SPEND, build_oracle_publish_batch};
use super::types::{OracleRecord, OracleStatusResponse};

fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TickOutcome {
    /// Nothing in flight, nothing pending.
    Idle,
    /// A batch was built and signed but not broadcast (`push_enabled ==
    /// false`); the queue is unchanged (records stay `pending`).
    DryRun { records: usize },
    /// A new batch was persisted `in_flight` and `push_tx` was called.
    Broadcast { records: usize, accepted: bool },
    /// An already-in-flight spend's `push_tx` was retried this tick.
    Retried { accepted: bool },
    /// An in-flight spend exists but isn't due for a retry yet.
    WaitingForConfirmation,
    /// An in-flight spend hit `max_retry_attempts` -- needs operator
    /// attention (surfaced via `GET /v1/oracle/status`).
    StuckInFlight,
    /// The in-flight spend's `next_coin` was found confirmed on chain;
    /// `operator.oracle.json` was advanced and the queue's `in_flight` was
    /// cleared.
    Confirmed { records: usize },
}

pub struct Publisher<C: ChainClient> {
    pub data_dir: PathBuf,
    pub operator_name: String,
    pub push_enabled: bool,
    pub chain: C,
    pub retry_backoff: Duration,
    pub max_retry_attempts: u32,
    /// `target_times` seconds passed to `get_fee_estimate` (task
    /// instruction: "fees from coinset get_fee_estimate with the dry-run
    /// cost"). **предложение**: 60s, see `Config::retry_backoff` doc.
    pub fee_target_time_secs: u32,
}

impl<C: ChainClient> Publisher<C> {
    /// `POST /v1/oracle/records`'s handler: durably enqueues newly
    /// submitted records, deduped against records already seen (see
    /// `QueueFile::enqueue`). Returns how many were newly accepted.
    pub fn enqueue(&self, records: Vec<OracleRecord>) -> Result<usize> {
        let mut queue = QueueFile::load(&self.data_dir, &self.operator_name)?;
        let accepted = queue.enqueue(records);
        if accepted > 0 {
            queue
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("persisting newly queued oracle records")?;
        }
        Ok(accepted)
    }

    /// `GET /v1/oracle/status`'s handler.
    pub async fn status(&self) -> Result<OracleStatusResponse> {
        let queue = QueueFile::load(&self.data_dir, &self.operator_name)?;
        let oracle_state = load_oracle_state(&self.data_dir, &self.operator_name).ok();

        let operator_balance_mojos = match load_operator_master_sk(&self.data_dir, &self.operator_name) {
            Ok(master_sk) => self.chain.balance(&master_sk).await.ok().map(|mojos| mojos.to_string()),
            Err(_) => None,
        };

        let in_flight_records = queue.in_flight.as_ref().map(|f| f.records.len()).unwrap_or(0);

        Ok(OracleStatusResponse {
            launcher_id: oracle_state
                .as_ref()
                .map(|s| format!("0x{}", s.launcher_id))
                .unwrap_or_default(),
            network: "testnet11",
            last_confirmed_seq: queue.last_confirmed_seq,
            last_confirmed_height: queue.last_confirmed_height,
            pending_records: queue.pending_count() + in_flight_records,
            in_flight_bundle_id: queue.in_flight.as_ref().map(|f| f.bundle_id.clone()),
            operator_balance_mojos,
        })
    }

    /// Runs one iteration of the publisher state machine (see module doc).
    pub async fn tick(&self) -> Result<TickOutcome> {
        let mut queue = QueueFile::load(&self.data_dir, &self.operator_name)?;

        if let Some(in_flight) = queue.in_flight.clone() {
            return self.advance_in_flight(&mut queue, in_flight).await;
        }

        if queue.pending.is_empty() {
            return Ok(TickOutcome::Idle);
        }

        self.start_new_batch(&mut queue).await
    }

    async fn advance_in_flight(&self, queue: &mut QueueFile, in_flight: InFlightSpend) -> Result<TickOutcome> {
        let next_coin_id = in_flight.next_coin.coin_id();
        let record_info = self
            .chain
            .coin_record(next_coin_id)
            .await
            .context("checking in-flight spend confirmation")?;

        if record_info.exists {
            let mut oracle_state = load_oracle_state(&self.data_dir, &self.operator_name)
                .context("loading oracle state to finalize a confirmed spend")?;
            oracle_state.current_coin = in_flight.next_coin;
            oracle_state.current_proof_dto = in_flight.next_proof.clone();
            oracle_state.next_sequence += 1;
            save_oracle_state_atomic(&self.data_dir, &self.operator_name, &oracle_state)
                .context("persisting advanced oracle state")?;

            let confirmed_count = in_flight.records.len();
            queue.last_confirmed_seq = in_flight.records.last().map(|r| r.seq).or(queue.last_confirmed_seq);
            queue.last_confirmed_height = record_info.confirmed_height.or(queue.last_confirmed_height);
            queue.in_flight = None;
            queue
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("clearing in-flight spend after confirmation")?;

            tracing::info!(records = confirmed_count, "oracle publish batch confirmed");
            return Ok(TickOutcome::Confirmed { records: confirmed_count });
        }

        if in_flight.attempts >= self.max_retry_attempts {
            tracing::warn!(
                bundle_id = %in_flight.bundle_id,
                attempts = in_flight.attempts,
                "in-flight oracle publish spend exceeded max retry attempts, needs operator attention"
            );
            return Ok(TickOutcome::StuckInFlight);
        }

        let elapsed = now_unix() - in_flight.last_attempt_at_unix;
        if elapsed < self.retry_backoff.as_secs() as i64 {
            return Ok(TickOutcome::WaitingForConfirmation);
        }
        if !self.push_enabled {
            return Ok(TickOutcome::WaitingForConfirmation);
        }

        let outcome = self
            .chain
            .push_tx(in_flight.bundle.clone())
            .await
            .context("retrying push_tx for an in-flight spend")?;

        let mut in_flight = in_flight;
        in_flight.attempts += 1;
        in_flight.last_attempt_at_unix = now_unix();
        in_flight.last_push_accepted = outcome.accepted;
        in_flight.last_push_error = outcome.error.clone();
        let accepted = outcome.accepted;
        queue.in_flight = Some(in_flight);
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting retried in-flight spend")?;

        Ok(TickOutcome::Retried { accepted })
    }

    async fn start_new_batch(&self, queue: &mut QueueFile) -> Result<TickOutcome> {
        let manager = load_operator_wallet_key(&self.data_dir, &self.operator_name)
            .context("loading operator wallet key")?;
        let oracle_state =
            load_oracle_state(&self.data_dir, &self.operator_name).context("loading oracle state")?;

        // Never double-spend the singleton: refuse to build on stale local
        // state (see module doc + `docs/research/chain_service.md` §5).
        let current_record = self
            .chain
            .coin_record(oracle_state.current_coin.coin_id())
            .await
            .context("checking oracle singleton current_coin before starting a new batch")?;
        if current_record.spent {
            bail!(
                "oracle singleton current_coin {} is already spent on chain but there is no \
                 matching in-flight spend in the local queue -- refusing to build a new spend \
                 on stale state (needs manual reconciliation, see \
                 docs/research/chain_service.md §5)",
                oracle_state.current_coin.coin_id()
            );
        }

        let batch = queue.take_batch(MAX_RECORDS_PER_SPEND);
        let batch_len = batch.len();
        let current_proof: Proof = oracle_state.current_proof();

        let draft = build_oracle_publish_batch(
            oracle_state.launcher_id,
            oracle_state.current_coin,
            current_proof,
            &manager,
            &batch,
        )
        .context("building oracle-publish batch spend")?;
        let draft_cost = measure_bundle_cost(&draft.coin_spends).context("measuring draft spend cost")?;

        let fee_mojos = match self
            .chain
            .fee_estimate_mojos(draft_cost.total_cost, self.fee_target_time_secs)
            .await
        {
            Ok(fee) => fee,
            Err(e) => {
                tracing::warn!("get_fee_estimate failed, falling back to fee=0: {e:#}");
                0
            }
        };

        let mut coin_spends = draft.coin_spends;
        let mut signature = draft.signature;
        if fee_mojos > 0 {
            let master_sk =
                load_operator_master_sk(&self.data_dir, &self.operator_name).context("loading operator master key")?;
            let (payer, fee_coin) = self
                .chain
                .find_funding_coin(&master_sk, fee_mojos)
                .await
                .context("no funding coin to pay the oracle-publish batch fee with")?;
            let fee_result = build_fee_only(fee_coin, &payer, fee_mojos).context("building fee-only coin spend")?;
            coin_spends.extend(fee_result.coin_spends);
            signature = signature + &fee_result.signature;
        }

        if !self.push_enabled {
            tracing::info!(
                records = batch_len,
                cost = draft_cost.total_cost,
                fee_mojos,
                "dry-run (C2_PUSH=0/--dry-run): built oracle-publish batch spend, not broadcasting"
            );
            // Put the drained batch back -- dry-run must not change any
            // persisted state (mirrors `c2-cli`'s own `--push` convention).
            queue.pending.splice(0..0, batch);
            return Ok(TickOutcome::DryRun { records: batch_len });
        }

        let bundle_id = {
            let mut hasher = Sha256::new();
            hasher.update(signature.to_bytes());
            format!("0x{}", hex::encode(hasher.finalize()))
        };
        let bundle = SpendBundle::new(coin_spends, signature);
        let next_proof_dto: ProofDto = draft.next_proof.into();

        let now = now_unix();
        queue.in_flight = Some(InFlightSpend {
            records: batch,
            spent_coin: oracle_state.current_coin,
            next_coin: draft.next_coin,
            next_proof: next_proof_dto,
            bundle: bundle.clone(),
            bundle_id,
            fee_mojos,
            broadcast_at_unix: now,
            last_attempt_at_unix: now,
            attempts: 1,
            last_push_accepted: false,
            last_push_error: None,
        });
        // Durability first (task instruction: "queue records durably ...
        // while a spend is in flight"): this atomic write is what removes
        // the batch from `pending`. A crash right after this line and
        // before `push_tx` below just means the next tick's retry path
        // (`advance_in_flight`) broadcasts this exact persisted bundle.
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting new in-flight spend before broadcasting")?;

        let outcome = self.chain.push_tx(bundle).await.context("push_tx for new batch")?;
        if let Some(in_flight) = queue.in_flight.as_mut() {
            in_flight.last_push_accepted = outcome.accepted;
            in_flight.last_push_error = outcome.error.clone();
        }
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting push_tx outcome")?;

        Ok(TickOutcome::Broadcast {
            records: batch_len,
            accepted: outcome.accepted,
        })
    }
}
