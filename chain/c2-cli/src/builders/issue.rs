//! `issue-play`: single-issuance genesis-by-coin-id PLAY CAT, exactly the
//! pattern proven in `chain/sim-spike/tests/cat_play.rs`
//! (`Cat::single_issuance`, `chia-sdk-driver-0.36.0/src/primitives/cat.rs`).
//!
//! The funding coin's *real* mojo amount backs the newly minted CAT 1:1 (a
//! CAT coin's `.amount` still counts as ordinary mojos for consensus's
//! global value-conservation check, it is only the CAT puzzle layer that
//! gives it the PLAY "color") -- so `amount + fee` mojos of the funding coin
//! are locked into the issuance, and anything left over is returned to the
//! owner as plain-XCH change.

use anyhow::{Result, bail};
use chia_bls::Signature;
use chia_protocol::{Coin, CoinSpend};
use chia_sdk_driver::{Cat, SpendContext, StandardLayer};
use chia_sdk_types::Conditions;

use crate::keys::WalletKey;
use crate::sign::sign_transaction;

#[derive(Debug)]
pub struct IssuePlayResult {
    pub coin_spends: Vec<CoinSpend>,
    pub signature: Signature,
    /// The owner's newly minted PLAY CAT coin, lineage proof included --
    /// enough on its own to build a later `offer-make` (see `state.rs`).
    pub cat: Cat,
    pub change_amount: u64,
}

/// Builds (and signs) the issuance spend bundle. Does not push anything.
///
/// `funding_coin` must be an unspent coin owned by `owner` (its puzzle hash
/// must equal `owner.puzzle_hash`) with `amount >= play_amount + fee`.
pub fn build_issue_play(
    funding_coin: Coin,
    owner: &WalletKey,
    play_amount: u64,
    fee: u64,
) -> Result<IssuePlayResult> {
    if funding_coin.puzzle_hash != owner.puzzle_hash {
        bail!("funding coin does not belong to this wallet (puzzle hash mismatch)");
    }
    let Some(change_amount) = funding_coin
        .amount
        .checked_sub(play_amount)
        .and_then(|v| v.checked_sub(fee))
    else {
        bail!(
            "funding coin has {} mojos, need at least {} (play_amount) + {} (fee)",
            funding_coin.amount,
            play_amount,
            fee
        );
    };

    let mut ctx = SpendContext::new();
    let owner_p2 = StandardLayer::new(owner.synthetic_pk);

    let cat_memos = ctx.hint(owner.puzzle_hash)?;
    let (issue_conditions, cats) = Cat::single_issuance(
        &mut ctx,
        funding_coin.coin_id(),
        None, // no hidden/revocation puzzle -- plain single-issuance TAIL
        play_amount,
        Conditions::new().create_coin(owner.puzzle_hash, play_amount, cat_memos),
    )?;

    // `issue_conditions` is just `create_coin(eve CAT coin)`, the one
    // condition the *outer* (plain-XCH) funding-coin spend must emit; add
    // change/fee on top of it for the outer spend.
    let mut outer_conditions = issue_conditions;
    if change_amount > 0 {
        outer_conditions = outer_conditions.create_coin(owner.puzzle_hash, change_amount, chia_puzzle_types::Memos::None);
    }
    if fee > 0 {
        outer_conditions = outer_conditions.reserve_fee(fee);
    }

    owner_p2.spend(&mut ctx, funding_coin, outer_conditions)?;

    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, &[owner.synthetic_sk.clone()])?;

    Ok(IssuePlayResult {
        coin_spends,
        signature,
        cat: cats[0],
        change_amount,
    })
}
