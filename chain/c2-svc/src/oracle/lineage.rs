//! Startup/periodic "walk the chain, don't trust the local file" bootstrap
//! (task fix, incident writeup `docs/research/chain_service.md` §11):
//! reconstructs the oracle singleton's *authoritative* confirmed-record map
//! directly from testnet11 by walking every generation of the singleton
//! from the launcher (or from a persisted checkpoint coin, forward) and
//! decoding each spend's `CREATE_PUZZLE_ANNOUNCEMENT` conditions with the
//! fixed record format (`(seq ts priceCents resultsHash sourceFlag
//! sourceTs)` plus, since record v2, an optional seventh `asset`, §3.1/§16)
//! -- instead of ever trusting `QueueFile::pending`
//! blindly. That local file is exactly what a stale/race-corrupted restart
//! (§11.1: redeploying onto an old queue file) got wrong: 76 records the
//! chain already had, byte-identical, got rebuilt and rebroadcast because
//! nothing ever checked the chain itself before publishing.
//!
//! Each generation's coin id is computed the same deterministic way
//! `advance_in_flight`/`build_oracle_publish_batch` already do (parent coin
//! id + the singleton's own, unchanging puzzle hash + `SINGLETON_AMOUNT`) --
//! no `get_coin_records_by_parent_ids` needed, just one `coin_record` call
//! per generation (two if it turns out spent, to fetch and decode its
//! puzzle+solution).

use std::collections::BTreeMap;

use anyhow::{Context, Result};
use chia_bls::PublicKey;
use chia_protocol::{Bytes, Bytes32, Coin, CoinSpend};
use chia_puzzle_types::singleton::SingletonArgs;
use chia_puzzle_types::standard::StandardArgs;
use chia_puzzle_types::{EveProof, LineageProof, Proof};
use chia_sdk_types::{Condition, run_puzzle_with_cost};
use clvm_traits::FromClvm;
use clvmr::{Allocator, NodePtr, serde::node_from_bytes};

use c2_cli::builders::oracle::SINGLETON_AMOUNT;
use c2_cli::cost::SIMULATOR_MAX_COST;

use super::chain_client::ChainClient;
use super::types::OracleRecord;

/// Hard ceiling on how many generations one `walk_lineage` call advances --
/// bounds a single `Publisher::tick()`'s worst case (checkpointed resumes
/// only ever re-walk what's advanced since the last scan, so this is only
/// ever exercised in full on a first-ever bootstrap with no checkpoint yet,
/// or after an operator wipes one). **предложение**: generously above any
/// generation count this project's testnet11 singleton has reached so far
/// (91, `lineage_result_wave2.json`), cheap even at 10x that.
const MAX_GENERATIONS_PER_WALK: u64 = 10_000;

/// One record as it actually exists on chain: `height`/`coin_id` are the
/// confirming spend's outcome (the singleton generation it created), same
/// convention `queue.rs::ConfirmedRecordInfo` already uses. Kept separate
/// from `ConfirmedRecordInfo` (rather than reusing it directly) because
/// reconciliation needs the actual record *content* to compare against a
/// locally queued record with the same `seq` -- `ConfirmedRecordInfo` never
/// stores that (queue.rs's own doc comment: it only needs `record_state` to
/// answer "confirmed or not", never content comparison, until now).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChainRecord {
    pub height: Option<u32>,
    pub coin_id: String,
    pub record: OracleRecord,
}

/// One generation of the singleton the walk passed through: the coin that was spent, the coin its
/// spend recreated, when, and what it announced. Kept (rather than only the merged `confirmed`
/// map) because the publisher has to answer "was the spend that consumed my in-flight bundle's
/// singleton coin *my* bundle, and which of my records did it carry" -- which the first-occurrence
/// `confirmed` map cannot say (a record announced earlier hides a later duplicate) and which a
/// replaced bundle makes a real question (`docs/research/chain_service.md` §17.4): two bundles
/// for one coin, and the chain says which of them won.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WalkedGeneration {
    /// The singleton coin this generation consumed.
    pub spent_coin: Coin,
    /// The coin the spend recreated -- computed, not read: the id of the successor of a singleton
    /// spend is the same whichever bundle spent it (parent id, puzzle hash and amount all fixed).
    pub next_coin: Coin,
    /// The block the spend confirmed in.
    pub height: Option<u32>,
    /// The `seq` of every announcement the spend made, duplicates of earlier generations
    /// included. `None` when the spend could not be read completely -- coinset had no
    /// puzzle/solution for it, its puzzle did not run, or at least one announcement did not decode
    /// as an oracle record: nothing may be concluded from a partial list, so a caller treats
    /// `None` as "unknown", never as "announced nothing".
    pub announced: Option<Vec<u64>>,
}

#[derive(Debug, Clone)]
pub struct LineageWalkOutcome {
    /// `seq -> ChainRecord`, first on-chain occurrence only (see module doc
    /// "duplicates: first occurrence wins" -- guaranteed for free by
    /// walking generations in ascending order and only ever inserting a
    /// `seq` the first time it's seen).
    pub confirmed: BTreeMap<u64, ChainRecord>,
    /// The first coin in this walk found still unspent -- the new
    /// checkpoint (`QueueFile::chain_scan_checkpoint`) to resume from next
    /// time, so a restart (or the next periodic scan) only ever re-checks
    /// forward from here instead of re-walking the whole lineage from the
    /// launcher.
    pub tip_coin: Coin,
    /// How many generations were actually advanced this call (0 if the
    /// starting coin was already the tip, or doesn't exist yet on chain --
    /// e.g. before `c2 oracle-launch` has run).
    pub generations_advanced: u64,
    /// Every generation this call advanced through, oldest first (`generations_advanced` of them).
    pub generations: Vec<WalkedGeneration>,
    /// An on-chain announcement that didn't decode as the fixed 6/7-field
    /// record format (e.g. this exact testnet11 launcher's very first-ever
    /// spend, made by the old single-record `c2-cli` publisher's 3-field
    /// format before `c2-svc` existed -- see `docs/research/
    /// chain_service.md` §1/§11) -- not an error, just skipped and reported
    /// for visibility (never silently, per the task fix's own principle).
    pub decode_warnings: Vec<String>,
}

impl LineageWalkOutcome {
    /// The generation that consumed `coin`, if this walk passed through it.
    pub fn generation_spending(&self, coin: &Coin) -> Option<&WalkedGeneration> {
        self.generations.iter().find(|generation| generation.spent_coin == *coin)
    }
}

/// The singleton's very first coin (the "eve" coin, created by the
/// launcher's own spend) -- same formula `c2_cli::builders::oracle::
/// build_oracle_launch` uses to produce it, and `spend::singleton_puzzle_hash`
/// uses to recompute it, rederived here from only the *public* half of the
/// manager key (`OracleState::manager_synthetic_pk`) since the lineage walk
/// never needs to sign or build anything, only recognize coins.
pub fn genesis_coin(launcher_id: Bytes32, manager_synthetic_pk: PublicKey) -> Coin {
    let inner_puzzle_hash: Bytes32 = StandardArgs::curry_tree_hash(manager_synthetic_pk).into();
    let singleton_puzzle_hash: Bytes32 =
        SingletonArgs::curry_tree_hash(launcher_id, inner_puzzle_hash.into()).into();
    Coin::new(launcher_id, singleton_puzzle_hash, SINGLETON_AMOUNT)
}

/// CLVM shape of one announcement message (task instruction, `spend.rs`
/// module doc, exact field order): `(seq ts priceCents resultsHash
/// sourceFlag sourceTs [asset])`, a proper list. `#[clvm(list)]` decodes the
/// first six list elements into the required fields, an optional seventh into
/// `asset` (`#[clvm(default = 0)]`: absent = BTC, the 6-element form every
/// announcement had before record v2), and ignores anything past them (matches
/// `clvm-traits`' own documented behavior, `clvm_traits::lib.rs`'s
/// `test_solution_struct`). A message with *fewer* than six elements (e.g.
/// the old 3-field pre-`c2-svc` format, `docs/research/chain_service.md`
/// §1/§11) fails to decode instead of silently misreading fields, which is
/// exactly the "not our format, skip it" signal `walk_lineage` needs.
#[derive(Debug, Clone, clvm_traits::FromClvm)]
#[clvm(list)]
struct AnnouncementMessage {
    seq: i64,
    ts: i64,
    price_cents: i64,
    results_hash: Bytes32,
    source_flag: i64,
    source_ts: i64,
    /// Decoded as a *signed* integer like every other field, then range-checked
    /// into a `u32` by `decode_oracle_record`: clvm-traits' own unsigned
    /// decoding reads the single byte `ff` (the signed encoding of -1) as 255,
    /// which would make this decoder disagree with the public Python verifier
    /// (`atom_to_int`, signed) about what an out-of-range asset atom means.
    #[clvm(default = 0)]
    asset: i64,
}

/// Decodes one announcement message (the CLVM bytes of a
/// `CREATE_PUZZLE_ANNOUNCEMENT`) into an [`OracleRecord`]. Accepts the
/// 6-element form (asset 0) and the 7-element form of record v2. Public so
/// tests can decode a message straight out of the builder.
pub fn decode_oracle_record(message: &[u8]) -> Result<OracleRecord> {
    let mut allocator = Allocator::new();
    let ptr = node_from_bytes(&mut allocator, message).context("parsing announcement message as CLVM")?;
    let decoded = AnnouncementMessage::from_clvm(&allocator, ptr)
        .context("decoding announcement as (seq ts priceCents resultsHash sourceFlag sourceTs [asset])")?;
    let seq = u64::try_from(decoded.seq).with_context(|| format!("announcement seq {} is negative", decoded.seq))?;
    let asset = u32::try_from(decoded.asset)
        .with_context(|| format!("announcement asset {} is outside 0..=u32::MAX", decoded.asset))?;
    Ok(OracleRecord {
        seq,
        ts: decoded.ts,
        price_cents: decoded.price_cents,
        results_hash: decoded.results_hash,
        source_flag: decoded.source_flag,
        source_ts: decoded.source_ts,
        asset,
    })
}

/// Runs `coin_spend`'s puzzle against its solution and returns every
/// `CREATE_PUZZLE_ANNOUNCEMENT` message it emits, raw (undecoded) -- mirrors
/// `spend.rs::build_oracle_publish_batch`'s own `run_puzzle_with_cost` call
/// (same function, same cost ceiling), just run in reverse: there it's used
/// to measure a spend this service is *building*; here, to read back the
/// conditions of a spend that already happened on chain.
fn announcement_messages(coin_spend: &CoinSpend) -> Result<Vec<Bytes>> {
    let mut allocator = Allocator::new();
    let puzzle_ptr =
        node_from_bytes(&mut allocator, coin_spend.puzzle_reveal.as_ref()).context("parsing puzzle_reveal as CLVM")?;
    let solution_ptr =
        node_from_bytes(&mut allocator, coin_spend.solution.as_ref()).context("parsing solution as CLVM")?;
    let reduction = run_puzzle_with_cost(&mut allocator, puzzle_ptr, solution_ptr, SIMULATOR_MAX_COST, false)
        .map_err(|e| anyhow::anyhow!("running on-chain puzzle_reveal against its solution: {e}"))?;
    let conditions: Vec<Condition<NodePtr>> =
        FromClvm::from_clvm(&allocator, reduction.1).context("decoding conditions from puzzle output")?;
    Ok(conditions
        .into_iter()
        .filter_map(|c| match c {
            Condition::CreatePuzzleAnnouncement(ann) => Some(ann.message),
            _ => None,
        })
        .collect())
}

/// What one spend of a singleton coin says, read from its puzzle and solution: the generation
/// (`WalkedGeneration`), every announcement that decoded as an oracle record (`ChainRecord`, in
/// announcement order, duplicates kept -- the caller decides which occurrence counts), and the
/// warnings for whatever did not decode. `coin_spend == None` is "the coin is spent but its
/// puzzle/solution could not be fetched": a generation with `announced == None` and a warning.
pub struct ReadGeneration {
    pub generation: WalkedGeneration,
    pub records: Vec<ChainRecord>,
    pub warnings: Vec<String>,
}

/// Reads one generation: the spend `coin_spend` of `spent_coin`, confirmed at `height`. Shared by
/// `walk_lineage` (a spend the coin index knows) and `walk_from_spend` (a spend found in the
/// blocks themselves while the index still shows the coin unspent, `docs/research/chain_service.md` §20).
pub fn read_generation(spent_coin: Coin, coin_spend: Option<&CoinSpend>, height: Option<u32>) -> ReadGeneration {
    let next = Coin::new(spent_coin.coin_id(), spent_coin.puzzle_hash, spent_coin.amount);
    let mut records = Vec::new();
    let mut warnings = Vec::new();
    // `Some` only when this spend was read completely (see `WalkedGeneration::announced`).
    let mut announced: Option<Vec<u64>> = None;
    match coin_spend {
        Some(coin_spend) => match announcement_messages(coin_spend) {
            Ok(messages) => {
                let mut seqs = Vec::with_capacity(messages.len());
                let mut complete = true;
                for message in messages {
                    match decode_oracle_record(message.as_ref()) {
                        Ok(record) => {
                            seqs.push(record.seq);
                            records.push(ChainRecord { height, coin_id: format!("{}", next.coin_id()), record });
                        }
                        Err(e) => {
                            complete = false;
                            warnings.push(format!(
                                "coin {} spend: announcement did not decode as an oracle record: {e:#}",
                                spent_coin.coin_id()
                            ));
                        }
                    }
                }
                if complete {
                    announced = Some(seqs);
                }
            }
            Err(e) => warnings.push(format!(
                "coin {} spend: could not decode conditions from its puzzle+solution: {e:#}",
                spent_coin.coin_id()
            )),
        },
        None => warnings.push(format!(
            "coin {} is marked spent but coinset returned no puzzle/solution for it",
            spent_coin.coin_id()
        )),
    }
    ReadGeneration {
        generation: WalkedGeneration { spent_coin, next_coin: next, height, announced },
        records,
        warnings,
    }
}

/// A walk of exactly one generation, from a spend of `spent_coin` that was found in block `height`
/// itself (`get_puzzle_and_solution` with an explicit height) while the coin index still showed
/// the coin unspent (`docs/research/chain_service.md` §20). The outcome has the shape
/// `walk_lineage` would have produced had the index known the spend, so `QueueFile::
/// reconcile_with_chain` settles the in-flight bundle from it the same way: the records the spend
/// announced are confirmed, the ones it did not announce go back to `pending`, and the tip is the
/// coin the spend recreated.
pub fn walk_from_spend(spent_coin: Coin, coin_spend: &CoinSpend, height: u32) -> LineageWalkOutcome {
    let read = read_generation(spent_coin, Some(coin_spend), Some(height));
    let mut confirmed = BTreeMap::new();
    for chain_record in read.records {
        confirmed.entry(chain_record.record.seq).or_insert(chain_record);
    }
    LineageWalkOutcome {
        confirmed,
        tip_coin: read.generation.next_coin,
        generations_advanced: 1,
        generations: vec![read.generation],
        decode_warnings: read.warnings,
    }
}

/// Walks the singleton lineage forward from `start` (either the genesis
/// coin, or a persisted checkpoint -- caller's choice, see
/// `Publisher::reconcile_queue_with_chain`), decoding every confirmed
/// spend's announcements, until it reaches a coin that's still unspent (the
/// current tip) or `MAX_GENERATIONS_PER_WALK` is hit. Pure with respect to
/// local state -- takes no `QueueFile`, returns what it found, entirely
/// generic over `C: ChainClient` so it's exercised in tests against
/// `FakeChainClient` exactly like every other publisher-side chain
/// interaction (`tests/oracle_queue.rs`'s own module doc).
pub async fn walk_lineage<C: ChainClient>(chain: &C, start: Coin) -> Result<LineageWalkOutcome> {
    let mut confirmed = BTreeMap::new();
    let mut decode_warnings = Vec::new();
    let mut generations = Vec::new();
    let mut current = start;
    let mut generations_advanced = 0u64;

    loop {
        let record_info = chain
            .coin_record(current.coin_id())
            .await
            .with_context(|| format!("coin_record({}) while walking oracle singleton lineage", current.coin_id()))?;
        if !record_info.exists {
            // Either the singleton hasn't been launched on chain yet (no
            // checkpoint, genesis coin not found -- nothing to reconcile
            // against yet), or -- if this ever happens starting from a
            // *persisted* checkpoint -- something has gone wrong upstream
            // (a reorg deeper than this chain's own finality assumptions).
            // Either way there is nothing more this walk can learn: stop
            // where we are rather than erroring the whole reconciliation
            // out, and let the checkpoint stay where it was (unchanged).
            break;
        }
        if !record_info.spent {
            // This is the current tip -- nothing more to walk yet.
            break;
        }
        if generations_advanced >= MAX_GENERATIONS_PER_WALK {
            decode_warnings.push(format!(
                "lineage walk stopped at {MAX_GENERATIONS_PER_WALK} generations (coin {}) -- resuming next scan",
                current.coin_id()
            ));
            break;
        }

        let coin_spend = chain
            .puzzle_and_solution(current.coin_id(), record_info.spent_height)
            .await
            .with_context(|| format!("puzzle_and_solution({}) while walking oracle singleton lineage", current.coin_id()))?;

        let read = read_generation(current, coin_spend.as_ref(), record_info.spent_height);
        for chain_record in read.records {
            // First occurrence wins (module doc): ascending generation order + `entry(..).
            // or_insert(..)` means a later duplicate (exactly what caused this incident, see
            // §11.1) can never overwrite the original.
            confirmed.entry(chain_record.record.seq).or_insert(chain_record);
        }
        decode_warnings.extend(read.warnings);
        let next = read.generation.next_coin;
        generations.push(read.generation);
        current = next;
        generations_advanced += 1;
    }

    Ok(LineageWalkOutcome {
        confirmed,
        tip_coin: current,
        generations_advanced,
        generations,
        decode_warnings,
    })
}

/// Computes the `Proof` needed to spend `tip` (`walk_lineage`'s
/// `tip_coin` -- the chain's actual current, unspent singleton generation).
/// Task fix (`docs/research/chain_service.md` §12, incident: `reconcile_
/// with_chain`'s `all_confirmed` branch cleared `in_flight` without ever
/// advancing `OracleState`, since only `advance_in_flight` used to do that
/// -- and `tick()`'s reconciliation pass runs *first*, so `advance_in_flight`
/// never got the chance): "the lineage walk already reaches the current
/// unspent tip -- have it return the tip coin + lineage proof (and the
/// parent spend needed for the next proof)".
///
/// One extra `coin_record` call on `tip.parent_coin_info` is all this needs:
/// `tip`'s own fields only name its parent's *coin id*, but the singleton
/// layer's lineage proof needs that parent's own `parent_coin_info`/`amount`
/// (one generation further back) -- and coinset's `get_coin_record_by_name`
/// already returns the full `coin` object for whatever id you ask about
/// (`CoinRecordInfo::coin`, see `chain_client.rs`), so no second round-trip
/// to decode a puzzle/solution is needed.
///
/// Distinguishes the one case that needs an `EveProof` instead of a
/// `LineageProof`: `tip` is the eve/genesis coin itself (`tip.parent_coin_info
/// == launcher_id`, true only for the singleton's very first coin, created by
/// spending the launcher) -- vanishingly unlikely on this project's actual
/// testnet11 singleton (already dozens of generations deep, `docs/research/
/// chain_service.md` §11.1), but handled correctly rather than assumed away.
///
/// `Ok(None)` only when `tip`'s parent has no coin record on chain at all --
/// shouldn't happen for a `tip` that `walk_lineage` itself just found
/// confirmed, but kept as a defensive "decline to adopt" rather than an
/// error: a caller can simply leave `OracleState` untouched this round and
/// let the existing stale-state guard (`Publisher::start_new_batch`) surface
/// the disagreement via a repeated tick failure instead.
pub async fn tip_lineage_proof<C: ChainClient>(
    chain: &C,
    launcher_id: Bytes32,
    manager_synthetic_pk: PublicKey,
    tip: Coin,
) -> Result<Option<Proof>> {
    let parent_record = chain
        .coin_record(tip.parent_coin_info)
        .await
        .with_context(|| format!("coin_record({}) to derive the chain tip's lineage proof", tip.parent_coin_info))?;
    let Some(parent_coin) = parent_record.coin else {
        return Ok(None);
    };

    if tip.parent_coin_info == launcher_id {
        // `tip` is the eve coin: its "parent" for `EveProof` purposes is the
        // launcher coin, so `parent_coin` here already *is* the launcher
        // coin's own record -- same construction as `c2_cli::builders::
        // oracle::build_oracle_launch`'s own `eve_proof` (its `parent_amount`
        // is always `SINGLETON_AMOUNT`, never the launcher's queried amount,
        // by the same convention -- they're equal in practice, this just
        // documents which one is authoritative).
        return Ok(Some(Proof::Eve(EveProof {
            parent_parent_coin_info: parent_coin.parent_coin_info,
            parent_amount: SINGLETON_AMOUNT,
        })));
    }

    // `tip`'s parent is itself a prior singleton generation -- same manager
    // key/inner puzzle every generation (`spend.rs::build_oracle_publish_
    // batch`'s own doc comment), so the inner puzzle hash is always this
    // constant, recomputed here rather than threaded through as a parameter.
    let inner_puzzle_hash: Bytes32 = StandardArgs::curry_tree_hash(manager_synthetic_pk).into();
    Ok(Some(Proof::Lineage(LineageProof {
        parent_parent_coin_info: parent_coin.parent_coin_info,
        parent_inner_puzzle_hash: inner_puzzle_hash,
        parent_amount: parent_coin.amount,
    })))
}
