//! Orchestrates the durable withdrawal queue (`withdrawal_queue.rs`) and the
//! batched CAT spend builder (`withdrawal_spend.rs`) into a state machine
//! that mirrors the oracle publisher's (`oracle/publisher.rs`) tick-based
//! design (task instruction: "same never-double-spend + chain-as-source-of-
//! truth discipline as the oracle publisher (reuse its patterns)"):
//!
//! 1. an in-flight batch just confirmed -> finalize (advance
//!    `operator.play.json`'s treasury coin, record each request's `coinId`/
//!    `height`, clear `in_flight`);
//! 2. an in-flight batch is unconfirmed -> wait, retry, or (on an outright
//!    fee rejection) rebuild with a higher fee;
//! 3. nothing in flight, requests pending -> build, sign, persist durably,
//!    THEN broadcast a new batch (<= `MAX_WITHDRAWALS_PER_SPEND`);
//! 4. nothing pending -> idle.
//!
//! Deliberately does **not** reuse `oracle::publisher::Publisher` itself (a
//! `Publisher<C: oracle::chain_client::ChainClient>` keyed by a singleton's
//! `seq`) -- this one is keyed by `requestId` and tracks an ordinary
//! (non-singleton) CAT coin: close enough in shape to imitate the same
//! design, not close enough to share one generic type without either
//! contorting the oracle one (already hardened by three real incidents,
//! `docs/research/chain_service.md` §9/§11/§12 -- not touched by this task)
//! or losing the "obviously mirrors the proven design" readability the task
//! asks for. There is also deliberately no chain-lineage-walk reconciliation
//! here (`oracle/lineage.rs`'s equivalent): a plain CAT coin isn't a
//! singleton with one linear lineage announced via `CREATE_PUZZLE_
//! ANNOUNCEMENT` the way the oracle is -- reconstructing "every requestId
//! this treasury coin's lineage has ever paid out" from raw CAT spends alone
//! (no `seq`-shaped announcement to decode) is materially harder and is left
//! as an **открытый вопрос** (see `docs/research/chain_service.md` §13);
//! this publisher's own durable queue file is its only source of truth for
//! `requestId` state, same as it was for the oracle queue before §11's fix
//! (a real, understood risk, not an oversight).

use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use chia_bls::Signature;
use chia_protocol::SpendBundle;
use chia_puzzle_types::Proof;
use chia_sdk_driver::{Cat, CatInfo};
use sha2::{Digest, Sha256};

use c2_cli::builders::fee::build_fee_only;
use c2_cli::cost::measure_bundle_cost;
use c2_cli::keys::WalletKey;
use c2_cli::state::PlayCoinState;

use crate::keys_file::{load_operator_master_sk, load_operator_wallet_key};
use crate::play_state_file::{load_play_state, save_play_state_atomic};

use super::chain_client::PlayChainClient;
use super::types::{WithdrawalRequest, WithdrawalStatusResponse};
use super::withdrawal_queue::{InFlightWithdrawalBatch, WithdrawalQueueFile, WithdrawalState};
use super::withdrawal_spend::{MAX_WITHDRAWALS_PER_SPEND, build_play_withdrawal_batch};

/// Same case-insensitive "contains FEE" heuristic as
/// `oracle::publisher::is_fee_rejection`, for the same reason (see that
/// function's doc comment) -- widens which rejections get the chain-verified
/// rebuild-with-higher-fee treatment; safety comes from the unspent/
/// not-in-mempool checks before this is ever consulted, not from this match.
fn is_fee_rejection(error: Option<&str>) -> bool {
    error.is_some_and(|msg| msg.to_ascii_uppercase().contains("FEE"))
}

/// **предложение**: `sha256` of the aggregated signature's bytes, same
/// construction and same caveat as `oracle::publisher::bundle_display_id`
/// (not Chia's canonical `SpendBundle::name()`, which is what
/// `ChainClient::in_mempool` uses instead wherever the real mempool needs to
/// identify this exact bundle).
fn bundle_display_id(signature: &Signature) -> String {
    let mut hasher = Sha256::new();
    hasher.update(signature.to_bytes());
    format!("0x{}", hex::encode(hasher.finalize()))
}

fn now_unix() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WithdrawalTickOutcome {
    Idle,
    DryRun { requests: usize },
    Broadcast { requests: usize, accepted: bool },
    Retried { accepted: bool },
    Rebuilt { requests: usize, accepted: bool, fee_mojos: u64 },
    WaitingForConfirmation,
    /// This batch's requests were moved to `failed` (task's FIXED INTERFACE
    /// names `failed` as a terminal state) -- see module doc.
    Failed { reason: String },
    Confirmed { requests: usize },
}

pub struct WithdrawalPublisher<C: PlayChainClient> {
    pub data_dir: PathBuf,
    pub operator_name: String,
    pub push_enabled: bool,
    pub chain: C,
    pub retry_backoff: Duration,
    pub max_retry_attempts: u32,
    pub max_fee_mojos: u64,
    pub fee_target_time_secs: u32,
    /// Serializes `enqueue()`/`tick()`'s read-modify-write cycle over the
    /// durable queue file -- same root-cause fix as `oracle::publisher::
    /// Publisher::queue_lock` (`docs/research/chain_service.md` §10).
    pub queue_lock: tokio::sync::Mutex<()>,
}

impl<C: PlayChainClient> WithdrawalPublisher<C> {
    /// `POST /v1/play/withdrawals`'s handler support -- `request` is already
    /// validated (`WithdrawalRequest::from_dto`, done by `app.rs` before this
    /// is called, same split as `oracle::app::oracle_records`). `true` iff
    /// newly accepted (idempotent by `requestId`, task's FIXED INTERFACE).
    pub async fn enqueue(&self, request: WithdrawalRequest) -> Result<bool> {
        let _guard = self.queue_lock.lock().await;
        let mut queue = WithdrawalQueueFile::load(&self.data_dir, &self.operator_name)?;
        let accepted = queue.enqueue(request);
        if accepted {
            queue
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("persisting newly queued withdrawal request")?;
        }
        Ok(accepted)
    }

    /// `GET /v1/play/withdrawals/{requestId}`'s handler -- a plain, unlocked
    /// read of the queue's current persisted state (same eventual-
    /// consistency reasoning as `oracle::publisher::Publisher::status`).
    pub fn status(&self, request_id: &str) -> Result<WithdrawalStatusResponse> {
        let queue = WithdrawalQueueFile::load(&self.data_dir, &self.operator_name)?;
        Ok(match queue.state(request_id) {
            WithdrawalState::Confirmed { coin_id, height } => WithdrawalStatusResponse {
                request_id: request_id.to_string(),
                state: "confirmed",
                coin_id: Some(format!("0x{coin_id}")),
                height,
                reason: None,
            },
            WithdrawalState::Failed { reason } => WithdrawalStatusResponse {
                request_id: request_id.to_string(),
                state: "failed",
                coin_id: None,
                height: None,
                reason: Some(reason),
            },
            WithdrawalState::InFlight => WithdrawalStatusResponse {
                request_id: request_id.to_string(),
                state: "in_flight",
                coin_id: None,
                height: None,
                reason: None,
            },
            WithdrawalState::Queued => WithdrawalStatusResponse {
                request_id: request_id.to_string(),
                state: "queued",
                coin_id: None,
                height: None,
                reason: None,
            },
            WithdrawalState::Unknown => WithdrawalStatusResponse {
                request_id: request_id.to_string(),
                state: "unknown",
                coin_id: None,
                height: None,
                reason: None,
            },
        })
    }

    async fn attach_fee_coin(
        &self,
        mut coin_spends: Vec<chia_protocol::CoinSpend>,
        signature: Signature,
        fee_mojos: u64,
    ) -> Result<(Vec<chia_protocol::CoinSpend>, Signature)> {
        if fee_mojos == 0 {
            return Ok((coin_spends, signature));
        }
        let master_sk = load_operator_master_sk(&self.data_dir, &self.operator_name).context("loading operator master key")?;
        let (payer, fee_coin) = self
            .chain
            .find_funding_coin(&master_sk, fee_mojos)
            .await
            .context("no funding coin to pay the withdrawal batch fee with")?;
        let fee_result = build_fee_only(fee_coin, &payer, fee_mojos).context("building fee-only coin spend")?;
        coin_spends.extend(fee_result.coin_spends);
        let signature = signature + &fee_result.signature;
        Ok((coin_spends, signature))
    }

    fn load_treasury_cat(&self) -> Result<(WalletKey, Cat)> {
        let owner = load_operator_wallet_key(&self.data_dir, &self.operator_name).context("loading operator wallet key")?;
        let play_state: PlayCoinState = load_play_state(&self.data_dir, &self.operator_name)
            .context("no cached PLAY treasury state for this operator -- run `c2 issue-play` first")?;
        let lineage_proof = play_state.lineage_proof()?;
        let cat_info = CatInfo::new(play_state.asset_id, None, owner.puzzle_hash);
        let cat = Cat::new(play_state.coin, Some(lineage_proof), cat_info);
        Ok((owner, cat))
    }

    pub async fn tick(&self) -> Result<WithdrawalTickOutcome> {
        let _guard = self.queue_lock.lock().await;
        let mut queue = WithdrawalQueueFile::load(&self.data_dir, &self.operator_name)?;

        if let Some(in_flight) = queue.in_flight.clone() {
            return self.advance_in_flight(&mut queue, in_flight).await;
        }
        if queue.pending.is_empty() {
            return Ok(WithdrawalTickOutcome::Idle);
        }
        self.start_new_batch(&mut queue).await
    }

    async fn advance_in_flight(
        &self,
        queue: &mut WithdrawalQueueFile,
        in_flight: InFlightWithdrawalBatch,
    ) -> Result<WithdrawalTickOutcome> {
        let next_coin_id = in_flight.next_coin.coin_id();
        let record_info = self
            .chain
            .coin_record(next_coin_id)
            .await
            .context("checking in-flight withdrawal batch confirmation")?;

        if record_info.exists {
            let mut play_state = load_play_state(&self.data_dir, &self.operator_name)
                .context("loading PLAY treasury state to finalize a confirmed withdrawal batch")?;
            play_state.coin = in_flight.next_coin;
            play_state.lineage_proof_dto = Proof::Lineage(in_flight.next_lineage_proof.into()).into();
            save_play_state_atomic(&self.data_dir, &self.operator_name, &play_state)
                .context("persisting advanced PLAY treasury state")?;

            for request in &in_flight.requests {
                queue.confirmed.insert(request.request_id.clone(), (next_coin_id, record_info.confirmed_height));
            }
            let confirmed_count = in_flight.requests.len();
            queue.in_flight = None;
            queue
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("clearing in-flight withdrawal batch after confirmation")?;
            tracing::info!(requests = confirmed_count, "play withdrawal batch confirmed");
            return Ok(WithdrawalTickOutcome::Confirmed { requests: confirmed_count });
        }

        let elapsed = now_unix() - in_flight.last_attempt_at_unix;
        if elapsed < self.retry_backoff.as_secs() as i64 {
            return Ok(WithdrawalTickOutcome::WaitingForConfirmation);
        }
        if !self.push_enabled {
            return Ok(WithdrawalTickOutcome::WaitingForConfirmation);
        }

        let spent_record = self
            .chain
            .coin_record(in_flight.spent_coin.coin_id())
            .await
            .context("checking treasury coin before acting on an in-flight withdrawal batch")?;
        if spent_record.spent {
            // Something else (or a race with the `next_coin` check above)
            // already spent it -- the next tick's `next_coin` check resolves
            // it once the recreated coin is visible.
            return Ok(WithdrawalTickOutcome::WaitingForConfirmation);
        }
        let in_mempool = self
            .chain
            .in_mempool(in_flight.bundle.name())
            .await
            .context("checking mempool for the in-flight withdrawal batch's bundle")?;
        if in_mempool {
            return Ok(WithdrawalTickOutcome::WaitingForConfirmation);
        }

        if !in_flight.last_push_accepted
            && is_fee_rejection(in_flight.last_push_error.as_deref())
            && in_flight.fee_mojos < self.max_fee_mojos
        {
            return self.rebuild_in_flight(queue, in_flight).await;
        }

        if in_flight.attempts >= self.max_retry_attempts {
            let reason = format!(
                "bundle {} exceeded {} retry attempts (fee {} mojos, cap {}): {}",
                in_flight.bundle_id,
                self.max_retry_attempts,
                in_flight.fee_mojos,
                self.max_fee_mojos,
                in_flight.last_push_error.as_deref().unwrap_or("(no error message)")
            );
            for request in &in_flight.requests {
                queue.failed.insert(request.request_id.clone(), reason.clone());
            }
            queue.in_flight = None;
            queue
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("persisting failed withdrawal batch")?;
            tracing::warn!(bundle_id = %in_flight.bundle_id, reason = %reason, "in-flight withdrawal batch failed permanently");
            return Ok(WithdrawalTickOutcome::Failed { reason });
        }

        let outcome = self
            .chain
            .push_tx(in_flight.bundle.clone())
            .await
            .context("retrying push_tx for an in-flight withdrawal batch")?;
        let mut in_flight = in_flight;
        in_flight.attempts += 1;
        in_flight.last_attempt_at_unix = now_unix();
        in_flight.last_push_accepted = outcome.accepted;
        in_flight.last_push_error = outcome.error.clone();
        let accepted = outcome.accepted;
        queue.in_flight = Some(in_flight);
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting retried in-flight withdrawal batch")?;
        Ok(WithdrawalTickOutcome::Retried { accepted })
    }

    /// Rebuilds the in-flight batch's requests (plus any newly queued ones,
    /// <= `MAX_WITHDRAWALS_PER_SPEND`) against the *same* treasury coin, with
    /// a re-estimated and multiplied fee -- only ever called after
    /// `advance_in_flight` has confirmed the treasury coin is unspent and the
    /// previous bundle is absent from the mempool (never-double-spend guard),
    /// same shape as `oracle::publisher::Publisher::rebuild_in_flight`.
    async fn rebuild_in_flight(
        &self,
        queue: &mut WithdrawalQueueFile,
        in_flight: InFlightWithdrawalBatch,
    ) -> Result<WithdrawalTickOutcome> {
        let (owner, treasury) = self.load_treasury_cat()?;
        if treasury.coin != in_flight.spent_coin {
            bail!(
                "PLAY treasury coin {} no longer matches the in-flight withdrawal batch's coin {} -- \
                 refusing to rebuild (needs manual reconciliation)",
                treasury.coin.coin_id(),
                in_flight.spent_coin.coin_id()
            );
        }

        let mut requests = in_flight.requests.clone();
        let room = MAX_WITHDRAWALS_PER_SPEND.saturating_sub(requests.len());
        if room > 0 {
            requests.extend(queue.take_batch(room));
        }
        let batch_len = requests.len();

        let draft = build_play_withdrawal_batch(treasury, &owner, &requests)
            .context("rebuilding play withdrawal batch spend with a higher fee")?;
        let draft_cost = measure_bundle_cost(&draft.coin_spends).context("measuring rebuilt draft spend cost")?;

        let shorter_target = (self.fee_target_time_secs / 3).max(1);
        let chain_estimate = match self.chain.fee_estimate_mojos(draft_cost.total_cost, shorter_target).await {
            Ok(fee) => fee,
            Err(e) => {
                tracing::warn!("get_fee_estimate failed while rebuilding a rejected withdrawal batch, falling back to 0: {e:#}");
                0
            }
        };
        let fee_mojos = in_flight.fee_mojos.saturating_mul(2).max(chain_estimate).max(1).min(self.max_fee_mojos);

        let (coin_spends, signature) = self.attach_fee_coin(draft.coin_spends, draft.signature, fee_mojos).await?;
        let bundle_id = bundle_display_id(&signature);
        let bundle = SpendBundle::new(coin_spends, signature);
        let now = now_unix();

        queue.in_flight = Some(InFlightWithdrawalBatch {
            requests,
            spent_coin: treasury.coin,
            next_coin: draft.next_coin,
            next_lineage_proof: draft.next_lineage_proof.into(),
            bundle: bundle.clone(),
            bundle_id,
            fee_mojos,
            broadcast_at_unix: now,
            last_attempt_at_unix: now,
            attempts: 1,
            last_push_accepted: false,
            last_push_error: None,
        });
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting rebuilt in-flight withdrawal batch before broadcasting")?;

        let outcome = self.chain.push_tx(bundle).await.context("push_tx for rebuilt withdrawal batch")?;
        if let Some(in_flight) = queue.in_flight.as_mut() {
            in_flight.last_push_accepted = outcome.accepted;
            in_flight.last_push_error = outcome.error.clone();
        }
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting rebuilt push_tx outcome")?;

        tracing::info!(
            requests = batch_len,
            fee_mojos,
            accepted = outcome.accepted,
            "rebuilt play withdrawal batch with a higher fee after a rejection"
        );
        Ok(WithdrawalTickOutcome::Rebuilt { requests: batch_len, accepted: outcome.accepted, fee_mojos })
    }

    async fn start_new_batch(&self, queue: &mut WithdrawalQueueFile) -> Result<WithdrawalTickOutcome> {
        let (owner, treasury) = self.load_treasury_cat()?;

        let current_record = self
            .chain
            .coin_record(treasury.coin.coin_id())
            .await
            .context("checking PLAY treasury coin before starting a new withdrawal batch")?;
        if current_record.spent {
            bail!(
                "PLAY treasury coin {} is already spent on chain but there is no matching in-flight \
                 withdrawal batch in the local queue -- refusing to build a new batch on stale state \
                 (needs manual reconciliation, see docs/research/chain_service.md §13)",
                treasury.coin.coin_id()
            );
        }

        let batch = queue.take_batch(MAX_WITHDRAWALS_PER_SPEND);
        let batch_len = batch.len();

        let draft = build_play_withdrawal_batch(treasury, &owner, &batch).context("building play withdrawal batch spend")?;
        let draft_cost = measure_bundle_cost(&draft.coin_spends).context("measuring draft spend cost")?;

        let fee_mojos = match self.chain.fee_estimate_mojos(draft_cost.total_cost, self.fee_target_time_secs).await {
            Ok(fee) => fee,
            Err(e) => {
                tracing::warn!("get_fee_estimate failed, falling back to fee=0: {e:#}");
                0
            }
        };

        if !self.push_enabled {
            tracing::info!(
                requests = batch_len,
                cost = draft_cost.total_cost,
                fee_mojos,
                "dry-run (C2_PUSH=0/--dry-run): built play withdrawal batch spend, not broadcasting"
            );
            queue.pending.splice(0..0, batch);
            return Ok(WithdrawalTickOutcome::DryRun { requests: batch_len });
        }

        let (coin_spends, signature) = self.attach_fee_coin(draft.coin_spends, draft.signature, fee_mojos).await?;
        let bundle_id = bundle_display_id(&signature);
        let bundle = SpendBundle::new(coin_spends, signature);
        let now = now_unix();

        queue.in_flight = Some(InFlightWithdrawalBatch {
            requests: batch,
            spent_coin: treasury.coin,
            next_coin: draft.next_coin,
            next_lineage_proof: draft.next_lineage_proof.into(),
            bundle: bundle.clone(),
            bundle_id,
            fee_mojos,
            broadcast_at_unix: now,
            last_attempt_at_unix: now,
            attempts: 1,
            last_push_accepted: false,
            last_push_error: None,
        });
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting new in-flight withdrawal batch before broadcasting")?;

        let outcome = self.chain.push_tx(bundle).await.context("push_tx for new withdrawal batch")?;
        if let Some(in_flight) = queue.in_flight.as_mut() {
            in_flight.last_push_accepted = outcome.accepted;
            in_flight.last_push_error = outcome.error.clone();
        }
        queue.save_atomic(&self.data_dir, &self.operator_name).context("persisting push_tx outcome")?;

        Ok(WithdrawalTickOutcome::Broadcast { requests: batch_len, accepted: outcome.accepted })
    }
}
