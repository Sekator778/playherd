//! Batched PLAY treasury withdrawal spend: transfers up to
//! `MAX_WITHDRAWALS_PER_SPEND` player payouts (task instruction: "many
//! outputs per treasury CAT spend") out of the treasury's single current
//! PLAY CAT coin in one spend, plus one change output back to the treasury --
//! the same `CatSpend`/`Cat::spend_all` primitive
//! `chain/sim-spike/tests/cat_play.rs` proves offline and `c2_cli::builders::
//! send_cat` uses for a single transfer, just with N outputs instead of one.
//!
//! The change coin is computed directly (`Cat::child`), the same way
//! `oracle/spend.rs::build_oracle_publish_batch` derives its own singleton's
//! next generation directly rather than searching `Cat::spend_all`'s
//! returned children -- simpler, and unambiguous even if a withdrawal
//! happened to target the treasury's own address with the same amount as the
//! change (two identical `(puzzle_hash, amount)` outputs from the same
//! parent would otherwise be indistinguishable by that search).

use anyhow::{Context, Result, bail};
use chia_bls::Signature;
use chia_protocol::{Coin, CoinSpend};
use chia_puzzle_types::LineageProof;
use chia_sdk_driver::{Cat, CatSpend, SpendContext, SpendWithConditions, StandardLayer};
use chia_sdk_types::Conditions;

use c2_cli::keys::WalletKey;
use c2_cli::sign::sign_transaction;

use super::withdrawal_queue::WithdrawalRequest;

/// Task instruction: "many outputs per treasury CAT spend" -- capped the
/// same way the oracle publisher caps its own batch
/// (`oracle::spend::MAX_RECORDS_PER_SPEND`), **предложение** at the same
/// value (20): bounds CLVM cost per spend, no source fixes an exact number
/// for withdrawals.
pub const MAX_WITHDRAWALS_PER_SPEND: usize = 20;

pub struct WithdrawalBatchResult {
    pub coin_spends: Vec<CoinSpend>,
    pub signature: Signature,
    /// The treasury's next PLAY CAT coin (this batch's change output) --
    /// what the next batch will spend from, mirroring `OracleState::
    /// current_coin`'s self-recreation role.
    pub next_coin: Coin,
    pub next_lineage_proof: LineageProof,
    pub change_amount: u64,
}

/// Spends the treasury's current PLAY CAT coin (`treasury`, owned by
/// `owner`) into one `create_coin` per `requests` (1..=20 of them) plus one
/// change coin back to `owner`.
pub fn build_play_withdrawal_batch(
    treasury: Cat,
    owner: &WalletKey,
    requests: &[WithdrawalRequest],
) -> Result<WithdrawalBatchResult> {
    if requests.is_empty() {
        bail!("build_play_withdrawal_batch requires at least one request");
    }
    if requests.len() > MAX_WITHDRAWALS_PER_SPEND {
        bail!(
            "build_play_withdrawal_batch got {} requests, max {MAX_WITHDRAWALS_PER_SPEND} per spend",
            requests.len()
        );
    }
    if treasury.info.p2_puzzle_hash != owner.puzzle_hash {
        bail!("treasury PLAY CAT coin does not belong to this wallet (p2 puzzle hash mismatch)");
    }

    let mut total_out: u64 = 0;
    for r in requests {
        total_out = total_out
            .checked_add(r.amount_mojos)
            .context("withdrawal batch total overflows u64")?;
    }
    let Some(change_amount) = treasury.coin.amount.checked_sub(total_out) else {
        bail!(
            "treasury PLAY CAT coin has {} mojos, batch needs {total_out}",
            treasury.coin.amount
        );
    };
    // A change coin is what lets the next batch find "the" treasury coin
    // again (same self-recreation principle `builders::oracle`'s singleton
    // uses, see `docs/research/chain_service.md` §13) -- refuse to fully
    // drain it in one spend rather than leave the treasury with no coin left
    // to continue from. **предложение**: the treasury is expected to always
    // hold far more PLAY than one batch pays out; genuine exhaustion needs an
    // operator to top it up (`c2 send-play`/a fresh `issue-play`), not a
    // silent dead end here.
    if change_amount == 0 {
        bail!(
            "withdrawal batch would fully drain the treasury PLAY CAT coin ({} mojos) -- refusing \
             (there would be no change coin left to continue from); reduce the batch or top up the treasury",
            treasury.coin.amount
        );
    }

    let mut ctx = SpendContext::new();
    let owner_p2 = StandardLayer::new(owner.synthetic_pk);

    let mut conditions = Conditions::new();
    for r in requests {
        let hint = ctx.hint(r.to_puzzle_hash)?;
        conditions = conditions.create_coin(r.to_puzzle_hash, r.amount_mojos, hint);
    }
    let change_hint = ctx.hint(owner.puzzle_hash)?;
    conditions = conditions.create_coin(owner.puzzle_hash, change_amount, change_hint);

    let p2_spend = owner_p2.spend_with_conditions(&mut ctx, conditions)?;
    Cat::spend_all(&mut ctx, &[CatSpend::new(treasury, p2_spend)])?;

    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, &[owner.synthetic_sk.clone()])?;

    let next_cat = treasury.child(owner.puzzle_hash, change_amount);
    let next_lineage_proof = next_cat
        .lineage_proof
        .context("internal error: treasury change CAT coin has no lineage proof")?;

    Ok(WithdrawalBatchResult {
        coin_spends,
        signature,
        next_coin: next_cat.coin,
        next_lineage_proof,
        change_amount,
    })
}
