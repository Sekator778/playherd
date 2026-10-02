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
//! asks for. There is also deliberately no chain-lineage-*walk*
//! reconciliation here (`oracle/lineage.rs`'s equivalent): a plain CAT coin
//! isn't a singleton with one linear lineage announced via `CREATE_PUZZLE_
//! ANNOUNCEMENT` the way the oracle is -- reconstructing "every requestId
//! this treasury coin's lineage has ever paid out" from raw CAT spends alone
//! (no `seq`-shaped announcement to decode) is materially harder and is left
//! as an **открытый вопрос** (see `docs/research/chain_service.md` §13);
//! this publisher's own durable queue file is its only source of truth for
//! `requestId` state, same as it was for the oracle queue before §11's fix
//! (a real, understood risk, not an oversight).
//!
//! **A batch that waits in the mempool is replaced, and the chain says which version won**
//! (`docs/research/chain_service.md` §17.8): after `mempool_replace_after` a batch the node holds
//! but does not confirm is replaced by one for the same treasury coin that the node's replacement
//! rule accepts (`mempool_rule.rs`); earlier versions (replaced, rebuilt, or a replacement the node
//! did not take) are kept on the batch (`SupersededWithdrawalBatch`) because any of them can still be
//! the one the chain takes -- the treasury coin is spent once -- and a payout must be recorded exactly
//! as the chain made it: requests the winning version paid are paid (each with its own payout coin),
//! requests it did not pay go back to the queue.
//!
//! What *is* reused here (decision Р-53 follow-up, "treasury self-heal",
//! `docs/research/chain_service.md` §13): the oracle's §12 principle "make
//! the chain the source of truth for our own position" -- `tick()` (and
//! `bootstrap_treasury_from_chain` at startup) reconciles the cached
//! treasury coin (`operator.play.json`) against the chain every cycle
//! (`reconcile_treasury_with_chain`) and, if it's spent with no in-flight
//! batch of ours explaining it, finds and adopts the largest unspent PLAY
//! CAT coin the treasury key actually owns (puzzle-hash lookup +
//! `Cat::parse_children` lineage reconstruction) rather than ever building a
//! new batch on a coin the chain already shows spent. Unlike the oracle's
//! own reconciliation this is a single current-coin check, not a
//! generation-by-generation walk -- see the paragraph above for why a walk
//! isn't the right tool here.

use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use chia_bls::{SecretKey, Signature};
use chia_protocol::{Bytes32, Coin, SpendBundle};
use chia_puzzle_types::Proof;
use chia_sdk_driver::{Cat, CatInfo, Puzzle};
use clvmr::{Allocator, serde::node_from_bytes};
use sha2::{Digest, Sha256};

use c2_cli::builders::fee::build_fee_only;
use c2_cli::cat_chain::cat_puzzle_hash;
use c2_cli::cost::measure_bundle_cost;
use c2_cli::keys::WalletKey;
use c2_cli::state::PlayCoinState;

use crate::fee_allocator::{FeeCoinAllocator, NoFeeCoin, fee_coin_of, is_coin_spent_rejection};
use crate::fee_reserve::FeeCoinsBusy;
use crate::keys_file::{load_operator_master_sk, load_operator_wallet_key};
use crate::mempool_rule::{MEMPOOL_MIN_FEE_INCREASE, required_replacement_fee};
use crate::oracle::publisher::TickFailureState;
use crate::play_state_file::{load_play_state, save_play_state_atomic};

use super::chain_client::PlayChainClient;
use super::types::{WithdrawalRequest, WithdrawalStatusResponse};
use super::withdrawal_queue::{
    InFlightWithdrawalBatch, SupersededWithdrawalBatch, WithdrawalQueueFile, WithdrawalState,
};
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

/// A replacement bundle `replace_in_mempool` is ready to make durable and push.
struct ReplacementBatch {
    bundle: SpendBundle,
    bundle_id: String,
    fee_mojos: u64,
    next_coin: Coin,
    next_lineage_proof: super::withdrawal_queue::LineageProofDto,
    payout_coin_ids: Vec<Bytes32>,
    /// The fee coin reserved for this replacement alone (the old batch had none).
    newly_reserved: Option<Coin>,
}

/// What `prepare_replacement` came to.
enum Prepared {
    Ready(Box<ReplacementBatch>),
    /// No valid replacement can be made now; the reason goes to `GET /health`.
    Blocked(String),
    /// The wallet's only coin big enough is held by the fee-coin split: wait.
    FeeCoinsBusy,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WithdrawalTickOutcome {
    Idle,
    DryRun { requests: usize },
    Broadcast { requests: usize, accepted: bool },
    Retried { accepted: bool },
    Rebuilt { requests: usize, accepted: bool, fee_mojos: u64 },
    /// The in-flight batch sat in the node's mempool past `mempool_replace_after` and was replaced by a
    /// batch for the same treasury coin that pays `fee_mojos` instead of `replaced_fee_mojos` and
    /// carries `requests` (the old ones plus any newly pending, at most `MAX_WITHDRAWALS_PER_SPEND`).
    Replaced { requests: usize, fee_mojos: u64, replaced_fee_mojos: u64 },
    /// The in-flight batch sat in the mempool past `mempool_replace_after` but no valid replacement
    /// could be made or the node did not take it; the old batch stays in flight (`GET /health` says why).
    ReplacementBlocked { reason: String },
    /// An earlier version of the in-flight batch (replaced or rebuilt) was the one the chain took:
    /// its `confirmed` requests are paid, the `requeued` ones are back in `pending`.
    ConfirmedEarlierVersion { confirmed: usize, requeued: usize },
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
    /// How long a batch may sit in the node's mempool unconfirmed before `replace_in_mempool`
    /// replaces it (`C2_MEMPOOL_REPLACE_AFTER_SECS`, the same setting as the oracle publisher's);
    /// zero turns the replacement off.
    pub mempool_replace_after: Duration,
    /// Serializes `enqueue()`/`tick()`'s read-modify-write cycle over the
    /// durable queue file -- same root-cause fix as `oracle::publisher::
    /// Publisher::queue_lock` (`docs/research/chain_service.md` §10).
    pub queue_lock: tokio::sync::Mutex<()>,
    /// Set by `reconcile_treasury_with_chain` when the cached PLAY treasury
    /// coin is spent on chain, no in-flight batch of ours explains it, AND
    /// no unspent replacement PLAY CAT coin was found for the treasury key
    /// -- needs manual operator reconciliation. Cleared the moment
    /// reconciliation next finds the cached coin fine or successfully
    /// adopts a replacement. Read by `health_check` (task requirement:
    /// "surface stuck state in status/health like the oracle").
    pub treasury_stuck: tokio::sync::RwLock<Option<String>>,
    /// Reused from `oracle::publisher` (same shape, no oracle-specific
    /// fields) rather than redeclared -- `tick()`'s public wrapper records
    /// every `tick_once()` outcome into this exactly the way `oracle::
    /// publisher::Publisher::tick` does into its own (`docs/research/
    /// chain_service.md` §12: "any repeated tick failure (N consecutive)
    /// must set stuckReason ... and /health must report degraded"), so a
    /// hard failure *before* `reconcile_treasury_with_chain` ever gets to
    /// classify a spent coin as healed-or-stuck (e.g. the chain itself being
    /// unreachable) is still surfaced via `health_check`, not silently
    /// swallowed the way it would be if only `treasury_stuck` above existed.
    pub tick_failures: tokio::sync::RwLock<TickFailureState>,
    /// N consecutive `tick()` failures before `health_check` reports
    /// degraded -- same knob (`Config::tick_failure_threshold`,
    /// `C2_TICK_FAILURE_THRESHOLD`) `main.rs` already passes the oracle
    /// `Publisher`, reused rather than adding a second, parallel env var for
    /// what is the exact same kind of signal.
    pub tick_failure_threshold: u32,
    /// Same `Arc` instance `main.rs` also gives the oracle `Publisher` --
    /// task fix, `docs/research/chain_service.md` §15: both publishers fund
    /// spends from the same operator wallet, so they must share one
    /// reservation set or they can (and, on this stand, did) pick the same
    /// not-yet-confirmed fee coin. See `fee_allocator`'s module doc.
    pub fee_allocator: std::sync::Arc<FeeCoinAllocator>,
}

impl<C: PlayChainClient> WithdrawalPublisher<C> {
    /// `POST /v1/play/withdrawals`'s handler support -- `request` is already
    /// validated (`WithdrawalRequest::from_dto`, done by `app.rs` before this
    /// is called, same split as `oracle::app::oracle_records`). `true` iff
    /// newly accepted (idempotent by `requestId`, task's FIXED INTERFACE: a repeat with the same
    /// recipient and amount is `Ok(false)`).
    ///
    /// A `requestId` this service already holds with a different recipient or amount fails with a
    /// `RequestIdConflict` (the root cause of the `anyhow::Error`; `app.rs` answers HTTP 409) --
    /// never with the old request's status (`WithdrawalQueueFile::enqueue`, §17.6).
    pub async fn enqueue(&self, request: WithdrawalRequest) -> Result<bool> {
        let _guard = self.queue_lock.lock().await;
        let mut queue = WithdrawalQueueFile::load(&self.data_dir, &self.operator_name)?;
        let accepted = queue.enqueue(request).map_err(anyhow::Error::new)?;
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
            .fee_allocator
            .reserve(&self.chain, &master_sk, fee_mojos)
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

    /// Startup bootstrap (task requirement: "at startup and every tick",
    /// `docs/research/chain_service.md` §13) -- `main.rs` calls this once,
    /// synchronously, before opening any listener or spawning the periodic
    /// `tick()` loop, same "reconcile with the chain before accepting or
    /// publishing anything" precedent the oracle publisher's own
    /// `bootstrap_from_chain` sets (`docs/research/chain_service.md` §11).
    /// Safe to call again later too -- it's the exact same reconciliation
    /// `tick()` also runs on every regular cycle.
    pub async fn bootstrap_treasury_from_chain(&self) -> Result<()> {
        let _guard = self.queue_lock.lock().await;
        let queue = WithdrawalQueueFile::load(&self.data_dir, &self.operator_name)?;
        self.reconcile_treasury_with_chain(&queue).await
    }

    /// The fee coin id a persisted in-flight batch (if any) currently
    /// depends on -- used once at startup (`main.rs`, right after
    /// `bootstrap_treasury_from_chain`) to seed the shared
    /// `FeeCoinAllocator` before any tick runs, same reasoning as
    /// `oracle::publisher::Publisher::in_flight_fee_coin_id` (task fix,
    /// `docs/research/chain_service.md` §15).
    pub fn in_flight_fee_coin_id(&self) -> Result<Option<Bytes32>> {
        let queue = WithdrawalQueueFile::load(&self.data_dir, &self.operator_name)?;
        Ok(queue.in_flight.as_ref().and_then(|f| fee_coin_of(&f.bundle, f.spent_coin)).map(|c| c.coin_id()))
    }

    /// `GET /health`'s handler support, alongside the oracle's own
    /// `Publisher::health_check` (task requirement: "surface stuck state in
    /// status/health like the oracle") -- `Ok(None)` = healthy, `Ok(Some(
    /// reason))` = either `tick()` itself has failed `tick_failure_threshold`
    /// times in a row (checked first, same priority order as the oracle's
    /// own `degraded_reason`), or `reconcile_treasury_with_chain` found the
    /// cached PLAY treasury coin spent with no in-flight batch explaining it
    /// and no unspent replacement either. A plain in-memory read, never a
    /// network call -- same reasoning as the oracle's own `health_check` doc
    /// comment, so a slow/stuck coinset can never make `GET /health` itself
    /// hang.
    pub async fn health_check(&self) -> Result<Option<String>> {
        let failures = self.tick_failures.read().await;
        if failures.consecutive_failures >= self.tick_failure_threshold {
            return Ok(Some(format!(
                "play withdrawal publisher tick has failed {} consecutive times (threshold {}): {}",
                failures.consecutive_failures,
                self.tick_failure_threshold,
                failures.last_error.as_deref().unwrap_or("(no error message)")
            )));
        }
        drop(failures);
        if let Some(reason) = self.treasury_stuck.read().await.clone() {
            return Ok(Some(reason));
        }
        // A batch that waited in the mempool and could not be replaced (`replace_in_mempool`): a plain
        // read of the queue file, like the oracle's `degraded_reason`.
        let queue = WithdrawalQueueFile::load(&self.data_dir, &self.operator_name)?;
        Ok(queue.in_flight.as_ref().and_then(|batch| {
            batch.replace_blocked.as_ref().map(|reason| {
                format!(
                    "withdrawal batch {} has waited in the mempool without confirming and could not be replaced: {reason}",
                    batch.bundle_id
                )
            })
        }))
    }

    /// Reconciles the cached treasury PLAY CAT coin (`operator.play.json`)
    /// with the chain, at startup (`bootstrap_treasury_from_chain`) and on
    /// every regular `tick()` -- task requirement: "like the oracle
    /// publisher does for its singleton" (`oracle::publisher::Publisher::
    /// reconcile_queue_with_chain`, `docs/research/chain_service.md` §12's
    /// "make the chain the source of truth for our own position"), just
    /// without a lineage *walk*: see this module's own doc comment for why.
    ///
    /// Gated on `queue.in_flight.is_none()`, same reasoning as the oracle's
    /// own gate: whenever a batch of ours is in flight, `in_flight.
    /// spent_coin` is by construction exactly `play_state.coin`
    /// (`start_new_batch`/`rebuild_in_flight` always build the next batch
    /// from the currently cached coin) -- so a spent cached coin while a
    /// batch is in flight is already explained (task requirement: "no
    /// in-flight batch of ours explains it"), and `advance_in_flight`'s own
    /// confirmation/retry logic is what should run next, not this. Only once
    /// nothing is in flight can an unexplained spend mean genuine
    /// staleness: an external `c2 send-play` consolidation, a restored/stale
    /// `operator.play.json`, or a manual top-up that moved the treasury to a
    /// coin this cache never saw.
    async fn reconcile_treasury_with_chain(&self, queue: &WithdrawalQueueFile) -> Result<()> {
        if queue.in_flight.is_some() {
            return Ok(());
        }
        let Ok(owner) = load_operator_wallet_key(&self.data_dir, &self.operator_name) else {
            return Ok(()); // no operator key yet -- nothing to reconcile
        };
        let Ok(play_state) = load_play_state(&self.data_dir, &self.operator_name) else {
            return Ok(()); // no treasury minted yet (`c2 issue-play` not run) -- nothing to reconcile
        };

        let record = self
            .chain
            .coin_record(play_state.coin.coin_id())
            .await
            .context("checking cached PLAY treasury coin against the chain")?;
        if !record.spent {
            // Still current (or simply not confirmed on chain yet, e.g.
            // right after a fresh `issue-play`) -- nothing to heal. Clear
            // any previous stuck flag either way: either this cache was
            // never actually broken, or a previous self-heal already fixed
            // it and this is just reconfirming -- same "success resets it"
            // convention `TickFailureState` uses.
            *self.treasury_stuck.write().await = None;
            return Ok(());
        }

        let candidates = self
            .find_unspent_treasury_cats(play_state.asset_id, owner.puzzle_hash)
            .await
            .context("scanning for a replacement PLAY treasury coin after finding the cached one spent")?;

        match candidates.into_iter().max_by_key(|c| c.coin.amount) {
            Some(largest) => {
                let lineage_proof = largest
                    .lineage_proof
                    .context("internal error: adopted PLAY treasury CAT coin has no lineage proof")?;
                let new_state = PlayCoinState::new(largest.info.asset_id, largest.coin, lineage_proof);
                save_play_state_atomic(&self.data_dir, &self.operator_name, &new_state)
                    .context("persisting self-healed PLAY treasury state")?;
                tracing::warn!(
                    stale_coin = %play_state.coin.coin_id(),
                    adopted_coin = %largest.coin.coin_id(),
                    amount = largest.coin.amount,
                    "cached PLAY treasury coin was spent on chain with no in-flight batch of ours \
                     explaining it -- adopted the largest unspent PLAY CAT coin found for the \
                     treasury key (docs/research/chain_service.md §13)"
                );
                *self.treasury_stuck.write().await = None;
            }
            None => {
                let reason = format!(
                    "cached PLAY treasury coin {} is spent on chain, no in-flight withdrawal batch \
                     explains it, and no unspent PLAY CAT coin was found for the treasury key -- \
                     needs manual operator reconciliation (docs/research/chain_service.md §13)",
                    play_state.coin.coin_id()
                );
                tracing::error!(%reason, "PLAY treasury self-heal failed");
                *self.treasury_stuck.write().await = Some(reason);
            }
        }
        Ok(())
    }

    /// Finds every currently unspent PLAY CAT coin the treasury key
    /// (`owner_puzzle_hash`, always `load_operator_wallet_key`'s index-0
    /// key -- the treasury only ever has one key, never a gap of them) owns
    /// and reconstructs each as a spendable `Cat` (lineage proof included).
    /// Task requirement: "hint/puzzle-hash lookup + Cat::parse_children
    /// lineage" -- the same technique `c2_cli::cat_chain::find_cat_coin`/
    /// `cat_from_coin_id` use for a wallet's own CAT sync, ported onto the
    /// generic `ChainClient`/`PlayChainClient` traits instead of a concrete
    /// `CoinsetClient` (task requirement: unit-testable against the fake
    /// chain client, see `tests/play_withdrawals.rs`).
    async fn find_unspent_treasury_cats(&self, asset_id: Bytes32, owner_puzzle_hash: Bytes32) -> Result<Vec<Cat>> {
        let treasury_cat_puzzle_hash = cat_puzzle_hash(asset_id, owner_puzzle_hash);
        let records = self
            .chain
            .coin_records_by_puzzle_hashes(vec![treasury_cat_puzzle_hash], None)
            .await
            .context("scanning for the treasury's current PLAY CAT coin(s)")?;

        let mut cats = Vec::new();
        for record in records.into_iter().filter(|r| !r.spent) {
            let cat = self
                .reconstruct_treasury_cat(asset_id, owner_puzzle_hash, record.coin_id)
                .await
                .with_context(|| format!("reconstructing lineage for treasury CAT candidate {}", record.coin_id))?;
            cats.push(cat);
        }
        Ok(cats)
    }

    /// Reconstructs `coin_id` (expected: an unspent CAT coin of `asset_id`,
    /// owned by `owner_puzzle_hash`) as a spendable `Cat` by decoding its
    /// parent's already-confirmed spend -- same technique `c2_cli::
    /// cat_chain::cat_from_coin_id` uses (that one takes a concrete
    /// `CoinsetClient` directly; this is the same logic ported onto
    /// `ChainClient`'s generic `coin_record`/`puzzle_and_solution` so it
    /// works against the fake chain client in tests too).
    async fn reconstruct_treasury_cat(&self, asset_id: Bytes32, owner_puzzle_hash: Bytes32, coin_id: Bytes32) -> Result<Cat> {
        let record = self.chain.coin_record(coin_id).await.context("coin_record")?;
        let coin = record.coin.with_context(|| format!("coin {coin_id} has no coin record"))?;

        let parent_record =
            self.chain.coin_record(coin.parent_coin_info).await.context("coin_record (parent)")?;
        if !parent_record.spent {
            bail!("parent coin {} of candidate treasury CAT {coin_id} is not spent yet", coin.parent_coin_info);
        }
        let parent_spend = self
            .chain
            .puzzle_and_solution(coin.parent_coin_info, parent_record.spent_height)
            .await
            .context("puzzle_and_solution (parent)")?
            .with_context(|| format!("no puzzle/solution for parent coin {}", coin.parent_coin_info))?;

        let mut allocator = Allocator::new();
        let puzzle_ptr = node_from_bytes(&mut allocator, parent_spend.puzzle_reveal.as_ref())
            .context("parsing parent puzzle_reveal as CLVM")?;
        let solution_ptr =
            node_from_bytes(&mut allocator, parent_spend.solution.as_ref()).context("parsing parent solution as CLVM")?;
        let puzzle = Puzzle::parse(&allocator, puzzle_ptr);

        let children = Cat::parse_children(&mut allocator, parent_spend.coin, puzzle, solution_ptr)
            .context("parsing CAT children from parent spend")?
            .with_context(|| format!("parent coin {} spend is not a CAT spend", coin.parent_coin_info))?;

        let cat = children
            .into_iter()
            .find(|c| c.coin.coin_id() == coin_id)
            .with_context(|| format!("coin {coin_id} not found among its parent's CAT children"))?;

        if cat.info.asset_id != asset_id {
            bail!("coin {coin_id} is a CAT of asset id {}, expected {asset_id}", cat.info.asset_id);
        }
        if cat.info.p2_puzzle_hash != owner_puzzle_hash {
            bail!("coin {coin_id}'s CAT p2 puzzle hash does not match the treasury key");
        }
        Ok(cat)
    }

    /// Runs one iteration of the state machine (`tick_once`), then records
    /// the outcome into `tick_failures` -- same wrapper `oracle::publisher::
    /// Publisher::tick` uses around its own `tick_once` (see `tick_failures`'
    /// doc comment).
    pub async fn tick(&self) -> Result<WithdrawalTickOutcome> {
        let result = self.tick_once().await;
        {
            let mut failures = self.tick_failures.write().await;
            match &result {
                Ok(_) => *failures = TickFailureState::default(),
                Err(e) => {
                    failures.consecutive_failures += 1;
                    failures.last_error = Some(format!("{e:#}"));
                }
            }
        }
        result
    }

    /// The actual publisher state machine -- see module doc. `tick()` above
    /// adds failure tracking around it without changing its own logic.
    async fn tick_once(&self) -> Result<WithdrawalTickOutcome> {
        let _guard = self.queue_lock.lock().await;
        let mut queue = WithdrawalQueueFile::load(&self.data_dir, &self.operator_name)?;

        // Task requirement: "at startup and every tick" -- see
        // `reconcile_treasury_with_chain`'s own doc comment.
        self.reconcile_treasury_with_chain(&queue).await?;

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

            // Task fix (`docs/research/chain_service.md` §13): each request's
            // OWN payout coin id, not `next_coin_id` (the treasury's change
            // coin -- confirming it just proves the whole batch landed,
            // nothing more) for every request in the batch. `payout_coin_ids`
            // is always the same length as `requests` (built together, see
            // `withdrawal_spend::build_play_withdrawal_batch`), so `zip`
            // covers every request with no fallback needed.
            for (request, &coin_id) in in_flight.requests.iter().zip(&in_flight.payout_coin_ids) {
                queue.record_confirmed(request, coin_id, record_info.confirmed_height);
            }
            let confirmed_count = in_flight.requests.len();
            // The fee coin this confirmed batch consumed is now genuinely,
            // permanently spent -- release its reservation (task fix,
            // `docs/research/chain_service.md` §15), same reasoning as
            // `oracle::publisher::advance_in_flight`'s own confirm branch.
            for coin_id in in_flight.every_fee_coin() {
                self.fee_allocator.release(coin_id).await;
            }
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
            // The treasury coin is consumed but this batch's own change coin is not on chain (checked
            // above). Either the next tick's `next_coin` check resolves it once the recreated coin is
            // visible, or an EARLIER version of this batch (replaced in the mempool, or rebuilt) is what
            // the chain took: its change coin is the one that exists. Look for it (`settle_earlier_version`).
            if let Some((version, height)) = self.find_confirmed_earlier_version(&in_flight).await? {
                return self.settle_earlier_version(queue, in_flight, version, height).await;
            }
            return Ok(WithdrawalTickOutcome::WaitingForConfirmation);
        }
        let in_mempool = self
            .chain
            .in_mempool(in_flight.bundle.name())
            .await
            .context("checking mempool for the in-flight withdrawal batch's bundle")?;
        if in_mempool {
            // Waiting -- for `mempool_replace_after`; past that the batch is replaced
            // (`replace_in_mempool`, `docs/research/chain_service.md` §17.8), like the oracle's bundle.
            return self.replace_in_mempool_if_due(queue, in_flight).await;
        }

        // Not in the mempool any more (or never): whatever kept it from being replaced is over.
        let mut in_flight = in_flight;
        if in_flight.replace_blocked.take().is_some() {
            queue.in_flight = Some(in_flight.clone());
            queue
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("clearing a stale 'replacement blocked' note from the in-flight batch")?;
        }

        // Task fix (`docs/research/chain_service.md` §15): mirrors
        // `oracle::publisher::advance_in_flight`'s own double-spend
        // handling exactly -- the treasury coin was just confirmed unspent
        // above, so a double-spend rejection can only be this batch's *fee*
        // coin. Verify on-chain, then drop it and rebuild immediately
        // rather than burning a retry attempt on a failure that will keep
        // repeating identically forever otherwise.
        if !in_flight.last_push_accepted
            && is_coin_spent_rejection(in_flight.last_push_error.as_deref())
            && let Some(fee_coin) = fee_coin_of(&in_flight.bundle, in_flight.spent_coin)
        {
            let fee_coin_record = self
                .chain
                .coin_record(fee_coin.coin_id())
                .await
                .context("checking the fee coin before treating a rejection as a spent fee coin")?;
            if fee_coin_record.spent {
                tracing::warn!(
                    bundle_id = %in_flight.bundle_id,
                    fee_coin = %fee_coin.coin_id(),
                    "in-flight withdrawal batch's fee coin was spent elsewhere (not the treasury) \
                     -- dropping it and rebuilding with a fresh fee coin, no retry attempt spent"
                );
                return self.rebuild_in_flight(queue, in_flight).await;
            }
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
            // This batch never actually landed on chain -- its fee coin
            // (if any) is genuinely still free (task fix, `docs/research/
            // chain_service.md` §15): release it so a future batch can use
            // it instead of leaking the reservation forever.
            for coin_id in in_flight.every_fee_coin() {
                self.fee_allocator.release(coin_id).await;
            }
            for request in &in_flight.requests {
                queue.record_failed(request, reason.clone());
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

    /// The treasury coin of the in-flight batch is consumed and the batch's own change coin is not on
    /// chain: is the change coin of an EARLIER version of the batch? A version that pays other requests
    /// leaves another change coin (its amount is the treasury's minus what that version pays), so the
    /// coin that exists names the version the chain took. Newest version first; `None` when no version's
    /// change coin exists (someone else spent the treasury coin, or the chain has not shown it yet).
    async fn find_confirmed_earlier_version(
        &self,
        in_flight: &InFlightWithdrawalBatch,
    ) -> Result<Option<(SupersededWithdrawalBatch, Option<u32>)>> {
        for version in in_flight.superseded.iter().rev() {
            let record = self
                .chain
                .coin_record(version.next_coin.coin_id())
                .await
                .context("checking whether an earlier version of the in-flight withdrawal batch confirmed")?;
            if record.exists {
                return Ok(Some((version.clone(), record.confirmed_height)));
            }
        }
        Ok(None)
    }

    /// An earlier version of the in-flight batch is what the chain took (its change coin exists, the
    /// treasury coin is consumed): the latest version can never confirm. The requests that version paid
    /// are paid -- each with its own payout coin, proof that the payout happened -- and the requests only
    /// the latest version carried (merged from `pending` by a rebuild or a replacement) go back to the
    /// front of `pending`: they were not paid, a later batch pays them, once. The treasury moves on to
    /// that version's change coin (the lineage proof of the next treasury coin is the same for every
    /// version: it describes the consumed coin, not the outputs) and every fee coin reserved for the
    /// batch is released.
    async fn settle_earlier_version(
        &self,
        queue: &mut WithdrawalQueueFile,
        in_flight: InFlightWithdrawalBatch,
        version: SupersededWithdrawalBatch,
        height: Option<u32>,
    ) -> Result<WithdrawalTickOutcome> {
        let mut play_state = load_play_state(&self.data_dir, &self.operator_name)
            .context("loading PLAY treasury state to settle an earlier version of a withdrawal batch")?;
        play_state.coin = version.next_coin;
        play_state.lineage_proof_dto = Proof::Lineage(in_flight.next_lineage_proof.into()).into();
        save_play_state_atomic(&self.data_dir, &self.operator_name, &play_state)
            .context("persisting the PLAY treasury advanced by an earlier version of the batch")?;

        // Every request that version paid is paid -- wherever the queue has it now: in the latest batch,
        // or back in `pending` (a replacement that the node did not take returned its merged requests
        // there, and that replacement is exactly what the chain may still take).
        let paid_ids: std::collections::HashSet<&str> = version.requests.iter().map(|r| r.request_id.as_str()).collect();
        for (request, &payout_coin_id) in version.requests.iter().zip(&version.payout_coin_ids) {
            queue.record_confirmed(request, payout_coin_id, height);
        }
        let confirmed = version.requests.len();
        queue.pending.retain(|r| !paid_ids.contains(r.request_id.as_str()));
        // The requests only the latest batch carried were not paid: they go back to the front of `pending`.
        let requeued: Vec<WithdrawalRequest> = in_flight
            .requests
            .iter()
            .filter(|r| !paid_ids.contains(r.request_id.as_str()) && !queue.pending.iter().any(|p| p.request_id == r.request_id))
            .cloned()
            .collect();
        let requeued_count = requeued.len();
        queue.pending.splice(0..0, requeued);
        for coin_id in in_flight.every_fee_coin() {
            self.fee_allocator.release(coin_id).await;
        }
        queue.in_flight = None;
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("clearing the in-flight withdrawal batch after an earlier version of it confirmed")?;
        tracing::info!(
            requests = confirmed,
            requeued = requeued_count,
            height = ?height,
            fee_mojos = version.fee_mojos,
            "play withdrawal batch confirmed (an earlier version of the in-flight batch was the one the chain took: \
             the requests it did not carry are back in the queue and a later batch pays them, docs/research/chain_service.md §17.8)"
        );
        Ok(WithdrawalTickOutcome::ConfirmedEarlierVersion { confirmed, requeued: requeued_count })
    }

    /// The in-flight batch is in the node's mempool: replace it once it has waited
    /// `mempool_replace_after` since this publisher last acted on it (`last_attempt_at_unix`).
    async fn replace_in_mempool_if_due(
        &self,
        queue: &mut WithdrawalQueueFile,
        in_flight: InFlightWithdrawalBatch,
    ) -> Result<WithdrawalTickOutcome> {
        if self.mempool_replace_after.is_zero() {
            return Ok(WithdrawalTickOutcome::WaitingForConfirmation);
        }
        let waited = now_unix() - in_flight.last_attempt_at_unix;
        if waited < i64::try_from(self.mempool_replace_after.as_secs()).unwrap_or(i64::MAX) {
            return Ok(WithdrawalTickOutcome::WaitingForConfirmation);
        }
        self.replace_in_mempool(queue, in_flight).await
    }

    /// The replacement could not be made: say so on the batch that stays in flight and restart the wait.
    fn block_replacement(
        &self,
        queue: &mut WithdrawalQueueFile,
        mut in_flight: InFlightWithdrawalBatch,
        reason: String,
    ) -> Result<WithdrawalTickOutcome> {
        tracing::warn!(
            bundle_id = %in_flight.bundle_id,
            fee_mojos = in_flight.fee_mojos,
            %reason,
            "in-flight withdrawal batch has waited in the mempool past C2_MEMPOOL_REPLACE_AFTER_SECS but cannot be \
             replaced -- it keeps waiting"
        );
        in_flight.replace_blocked = Some(reason.clone());
        in_flight.last_attempt_at_unix = now_unix();
        queue.in_flight = Some(in_flight);
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting the 'replacement blocked' note on the in-flight batch")?;
        Ok(WithdrawalTickOutcome::ReplacementBlocked { reason })
    }

    /// Replaces the in-flight batch, which the node holds in its mempool but does not confirm, with a
    /// batch that the node's replacement rule accepts (`mempool_rule.rs`, §17.3) -- the withdrawal
    /// counterpart of `oracle::publisher::Publisher::replace_in_mempool`, with the same persistence and
    /// failure handling (made durable before the push; a refused or parked replacement is undone: the
    /// old batch is the in-flight one again, the merged requests are back in `pending`, a fee coin
    /// reserved for the replacement alone is released).
    ///
    /// **Why this cannot pay anyone twice or lose a payout** (§17.8): both batches spend the same
    /// treasury CAT coin, so the chain confirms at most one. Which one is read off the chain -- each
    /// version's treasury change coin is unique to what it pays -- never assumed: the latest version
    /// confirmed when ITS change coin exists (`advance_in_flight`); an earlier one when its change coin
    /// exists (`settle_earlier_version`). Requests the winning version paid are confirmed with their own
    /// payout coin; requests it did not pay return to `pending`.
    async fn replace_in_mempool(
        &self,
        queue: &mut WithdrawalQueueFile,
        in_flight: InFlightWithdrawalBatch,
    ) -> Result<WithdrawalTickOutcome> {
        let (owner, treasury) = self.load_treasury_cat()?;
        if treasury.coin != in_flight.spent_coin {
            bail!(
                "PLAY treasury coin {} no longer matches the in-flight withdrawal batch's coin {} -- \
                 refusing to replace (needs manual reconciliation)",
                treasury.coin.coin_id(),
                in_flight.spent_coin.coin_id()
            );
        }
        let master_sk = load_operator_master_sk(&self.data_dir, &self.operator_name).context("loading operator master key")?;

        let old_fee = in_flight.fee_mojos;
        let old_cost = measure_bundle_cost(&in_flight.bundle.coin_spends)
            .context("measuring the in-mempool withdrawal batch's cost")?
            .total_cost;
        let minimum_by_increase = old_fee.saturating_add(MEMPOOL_MIN_FEE_INCREASE);
        if minimum_by_increase > self.max_fee_mojos {
            return self.block_replacement(
                queue,
                in_flight,
                format!(
                    "the node accepts a replacement only if it pays at least {MEMPOOL_MIN_FEE_INCREASE} mojos more than the \
                     bundle it replaces ({old_fee} mojos): {minimum_by_increase} mojos, and C2_MAX_FEE_MOJOS is {} mojos",
                    self.max_fee_mojos
                ),
            );
        }

        // The newly pending requests ride along, like in `rebuild_in_flight` ...
        let mut requests = in_flight.requests.clone();
        let room = MAX_WITHDRAWALS_PER_SPEND.saturating_sub(requests.len());
        let mut merged: Vec<WithdrawalRequest> = if room > 0 { queue.take_batch(room) } else { Vec::new() };
        requests.extend(merged.iter().cloned());

        // ... unless the merged batch cannot be built (more than the treasury coin holds, say): then the
        // same requests are replaced, and the merged ones wait for a later batch.
        let mut prepared = self
            .prepare_replacement(&in_flight, &requests, treasury, &owner, &master_sk, old_cost)
            .await;
        if prepared.is_err() && !merged.is_empty() {
            tracing::warn!("the merged replacement withdrawal batch could not be prepared -- replacing without the newly pending requests");
            queue.pending.splice(0..0, std::mem::take(&mut merged));
            requests = in_flight.requests.clone();
            prepared = self.prepare_replacement(&in_flight, &requests, treasury, &owner, &master_sk, old_cost).await;
        }
        let ready = match prepared {
            Ok(Prepared::Ready(ready)) => *ready,
            Ok(Prepared::Blocked(reason)) => {
                queue.pending.splice(0..0, merged);
                return self.block_replacement(queue, in_flight, reason);
            }
            Ok(Prepared::FeeCoinsBusy) => {
                queue.pending.splice(0..0, merged);
                tracing::info!("not replacing the withdrawal batch in the mempool yet: the fee-coin split is holding the wallet's only fee coin");
                return Ok(WithdrawalTickOutcome::WaitingForConfirmation);
            }
            Err(e) => {
                queue.pending.splice(0..0, merged);
                return Err(e);
            }
        };

        let now = now_unix();
        let mut superseded = in_flight.superseded.clone();
        superseded.push(in_flight.as_superseded("replaced in the mempool", now));
        let replacement = InFlightWithdrawalBatch {
            requests,
            payout_coin_ids: ready.payout_coin_ids,
            spent_coin: treasury.coin,
            next_coin: ready.next_coin,
            next_lineage_proof: ready.next_lineage_proof,
            bundle: ready.bundle.clone(),
            bundle_id: ready.bundle_id,
            fee_mojos: ready.fee_mojos,
            broadcast_at_unix: now,
            last_attempt_at_unix: now,
            attempts: 1,
            last_push_accepted: false,
            last_push_error: None,
            superseded,
            replace_blocked: None,
        };
        let batch_len = replacement.requests.len();
        let replacement_fee = replacement.fee_mojos;
        queue.in_flight = Some(replacement);
        // Durability first, same invariant as `start_new_batch`.
        if let Err(e) = queue.save_atomic(&self.data_dir, &self.operator_name) {
            if let Some(coin) = ready.newly_reserved {
                self.fee_allocator.release(coin.coin_id()).await;
            }
            return Err(e.context("persisting the replacement in-flight withdrawal batch before broadcasting"));
        }

        let bundle_name = ready.bundle.name();
        let pushed = self.chain.push_tx(ready.bundle).await.context("push_tx for the replacement withdrawal batch")?;
        // `push_tx` answers success with `status: "PENDING"` for a bundle it parked in its conflict cache
        // instead of replacing the old one (`mempool_rule.rs`): it has to be a `SUCCESS`, or the mempool
        // has to say it holds the bundle.
        let taken = pushed.accepted
            && (pushed.status.as_deref() == Some("SUCCESS")
                || self.chain.in_mempool(bundle_name).await.unwrap_or(false));

        if taken {
            if let Some(current) = queue.in_flight.as_mut() {
                current.last_push_accepted = true;
                current.last_push_error = None;
            }
            queue
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("persisting the replacement's push outcome")?;
            tracing::info!(
                requests = batch_len,
                fee_mojos = replacement_fee,
                replaced_fee_mojos = old_fee,
                waited_secs = now - in_flight.last_attempt_at_unix,
                "replaced the play withdrawal batch that sat in the mempool"
            );
            return Ok(WithdrawalTickOutcome::Replaced {
                requests: batch_len,
                fee_mojos: replacement_fee,
                replaced_fee_mojos: old_fee,
            });
        }

        let detail = match (&pushed.error, &pushed.status) {
            (Some(error), _) => error.clone(),
            (None, Some(status)) => format!("status {status}"),
            (None, None) => "no answer".to_string(),
        };
        if let Some(coin) = ready.newly_reserved {
            self.fee_allocator.release(coin.coin_id()).await;
        }
        queue.pending.splice(0..0, merged);
        // The replacement is not forgotten: the node may still hold it (parked in its conflict cache,
        // retried at every new block) and another node may include it. Kept as a version of the batch that
        // goes back in flight, so that if the chain takes it, the payouts it made are recognised.
        let mut restored = in_flight;
        if let Some(refused) = queue.in_flight.as_ref() {
            restored.superseded.push(refused.as_superseded("replacement not taken by the node", now));
        }
        self.block_replacement(
            queue,
            restored,
            format!("the node did not take the replacement bundle (fee {replacement_fee} mojos): {detail}"),
        )
    }

    /// Builds the replacement bundle for `replace_in_mempool` (see the oracle publisher's
    /// `prepare_replacement`, which this mirrors for a CAT spend): the draft treasury spend for
    /// `requests` and a fee spend that satisfies the node's replacement rule -- the old batch's fee coin
    /// kept, or a new one when it had none -- or the reason it cannot be made.
    async fn prepare_replacement(
        &self,
        in_flight: &InFlightWithdrawalBatch,
        requests: &[WithdrawalRequest],
        treasury: chia_sdk_driver::Cat,
        owner: &WalletKey,
        master_sk: &SecretKey,
        old_cost: u64,
    ) -> Result<Prepared> {
        let old_fee = in_flight.fee_mojos;
        let cap = self.max_fee_mojos;
        let minimum_by_increase = old_fee.saturating_add(MEMPOOL_MIN_FEE_INCREASE);

        let draft = build_play_withdrawal_batch(treasury, owner, requests)
            .context("building the replacement play withdrawal batch spend")?;
        let draft_cost = measure_bundle_cost(&draft.coin_spends)
            .context("measuring the replacement draft's cost")?
            .total_cost;

        let shorter_target = (self.fee_target_time_secs / 3).max(1);
        let estimate = self
            .fee_allocator
            .fees()
            .estimate(&self.chain, draft_cost, shorter_target, u64::MAX)
            .await
            .fee_mojos;
        let wanted = old_fee.saturating_mul(2).max(estimate).max(minimum_by_increase);

        let (payer, fee_coin, newly_reserved, ceiling) = match fee_coin_of(&in_flight.bundle, in_flight.spent_coin) {
            Some(coin) => {
                let Some(payer) = FeeCoinAllocator::owner_key_of(master_sk, &coin) else {
                    return Ok(Prepared::Blocked(format!(
                        "the old batch's fee coin {} is not one of this wallet's first {} addresses, so it cannot be \
                         spent again, and the node only accepts a replacement that spends every coin the old bundle spends",
                        coin.coin_id(),
                        c2_cli::chain::ADDRESS_GAP
                    )));
                };
                let record = self
                    .chain
                    .coin_record(coin.coin_id())
                    .await
                    .context("checking the old batch's fee coin before replacing the batch")?;
                if record.spent {
                    return Ok(Prepared::Blocked(format!(
                        "the old batch's fee coin {} is already spent on chain: that batch cannot confirm and will \
                         leave the mempool, a replacement that must spend the same coin is impossible",
                        coin.coin_id()
                    )));
                }
                let ceiling = cap.min(coin.amount);
                if minimum_by_increase > ceiling {
                    return Ok(Prepared::Blocked(format!(
                        "the replacement must spend the old batch's fee coin ({} mojos) and pay at least \
                         {minimum_by_increase} mojos, C2_MAX_FEE_MOJOS is {cap} mojos",
                        coin.amount
                    )));
                }
                (payer, coin, None, ceiling)
            }
            None => {
                let (payer, coin) = match self.fee_allocator.reserve(&self.chain, master_sk, wanted.min(cap)).await {
                    Ok(reserved) => reserved,
                    Err(e) if e.downcast_ref::<FeeCoinsBusy>().is_some() => return Ok(Prepared::FeeCoinsBusy),
                    Err(e) if e.downcast_ref::<NoFeeCoin>().is_some() => {
                        return Ok(Prepared::Blocked(format!(
                            "the old batch paid no fee coin and the wallet has no unspent, unreserved coin that can pay the \
                             replacement's fee of up to {} mojos: {e:#}",
                            wanted.min(cap)
                        )));
                    }
                    Err(e) => return Err(e.context("no funding coin to pay the replacement withdrawal batch's fee with")),
                };
                let ceiling = cap.min(coin.amount);
                (payer, coin, Some(coin), ceiling)
            }
        };

        let release_on_exit = async |reserved: Option<Coin>| {
            if let Some(coin) = reserved {
                self.fee_allocator.release(coin.coin_id()).await;
            }
        };

        let mut fee = wanted.min(ceiling);
        for _ in 0..3 {
            let fee_spend = match build_fee_only(fee_coin, &payer, fee) {
                Ok(spend) => spend,
                Err(e) => {
                    release_on_exit(newly_reserved).await;
                    return Err(e.context("building the replacement's fee-only coin spend"));
                }
            };
            let mut coin_spends = draft.coin_spends.clone();
            coin_spends.extend(fee_spend.coin_spends);
            let new_cost = match measure_bundle_cost(&coin_spends) {
                Ok(cost) => cost.total_cost,
                Err(e) => {
                    release_on_exit(newly_reserved).await;
                    return Err(e.context("measuring the replacement bundle's cost"));
                }
            };
            let required = required_replacement_fee(old_fee, old_cost, new_cost);
            if fee >= required {
                let signature = draft.signature.clone() + &fee_spend.signature;
                let bundle_id = bundle_display_id(&signature);
                return Ok(Prepared::Ready(Box::new(ReplacementBatch {
                    bundle: SpendBundle::new(coin_spends, signature),
                    bundle_id,
                    fee_mojos: fee,
                    next_coin: draft.next_coin,
                    next_lineage_proof: draft.next_lineage_proof.into(),
                    payout_coin_ids: draft.payout_coin_ids,
                    newly_reserved,
                })));
            }
            if required > ceiling {
                release_on_exit(newly_reserved).await;
                return Ok(Prepared::Blocked(format!(
                    "the node's replacement rule needs this bundle (cost {new_cost}) to pay at least {required} mojos -- more than \
                     the {old_fee} mojos of the bundle it replaces (cost {old_cost}) plus {MEMPOOL_MIN_FEE_INCREASE}, and a higher \
                     fee per cost -- but it may pay at most {ceiling} mojos (C2_MAX_FEE_MOJOS {cap}, fee coin {} mojos)",
                    fee_coin.amount
                )));
            }
            fee = required;
        }
        release_on_exit(newly_reserved).await;
        Ok(Prepared::Blocked("the replacement's fee did not settle after three rounds".to_string()))
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

        // The bundle being rebuilt is dropped in its entirety -- release
        // its fee coin (task fix, `docs/research/chain_service.md` §15)
        // *before* `attach_fee_coin` below reserves a new one, same
        // reasoning as `oracle::publisher::rebuild_in_flight`.
        if let Some(old_fee_coin) = fee_coin_of(&in_flight.bundle, in_flight.spent_coin) {
            self.fee_allocator.release(old_fee_coin.coin_id()).await;
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
        // A failed or zero estimate is the fallback rate (`fee_estimate.rs`, §17.2), never 0 -- same
        // rule as the oracle publisher's rebuild.
        let chain_estimate = self
            .fee_allocator
            .fees()
            .estimate(&self.chain, draft_cost.total_cost, shorter_target, u64::MAX)
            .await
            .fee_mojos;
        let fee_mojos = in_flight.fee_mojos.saturating_mul(2).max(chain_estimate).max(1).min(self.max_fee_mojos);

        let (coin_spends, signature) = self.attach_fee_coin(draft.coin_spends, draft.signature, fee_mojos).await?;
        let bundle_id = bundle_display_id(&signature);
        let bundle = SpendBundle::new(coin_spends, signature);
        let now = now_unix();

        queue.in_flight = Some(InFlightWithdrawalBatch {
            requests,
            payout_coin_ids: draft.payout_coin_ids,
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
            // The batch this one replaces stays on record: the chain decides which version confirms.
            superseded: {
                let mut versions = in_flight.superseded.clone();
                versions.push(in_flight.as_superseded("rebuilt after a rejection", now));
                versions
            },
            replace_blocked: None,
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

        // The estimate; when `get_fee_estimate` fails or answers 0 the fallback rate -- never a
        // withdrawal batch for nothing because the estimate was unavailable (`fee_estimate.rs`,
        // `docs/research/chain_service.md` §17.2). Capped at `C2_MAX_FEE_MOJOS` from the first
        // attempt on, like every rebuild (the oracle publisher does the same).
        let fee_mojos = self
            .fee_allocator
            .fees()
            .estimate(&self.chain, draft_cost.total_cost, self.fee_target_time_secs, self.max_fee_mojos)
            .await
            .fee_mojos;

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

        let (coin_spends, signature) = match self.attach_fee_coin(draft.coin_spends, draft.signature, fee_mojos).await {
            Ok(attached) => attached,
            // The wallet's only free fee coin is held by the fee-coin split waiting for confirmation
            // (`fee_reserve.rs`): wait a tick, the requests go back to `pending`, unsaved.
            Err(e) if e.downcast_ref::<FeeCoinsBusy>().is_some() => {
                tracing::info!("not starting a withdrawal batch yet: the fee-coin split is holding the wallet's only fee coin");
                queue.pending.splice(0..0, batch);
                return Ok(WithdrawalTickOutcome::WaitingForConfirmation);
            }
            Err(e) => return Err(e),
        };
        let bundle_id = bundle_display_id(&signature);
        let bundle = SpendBundle::new(coin_spends, signature);
        let now = now_unix();

        queue.in_flight = Some(InFlightWithdrawalBatch {
            requests: batch,
            payout_coin_ids: draft.payout_coin_ids,
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
            superseded: Vec::new(),
            replace_blocked: None,
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
