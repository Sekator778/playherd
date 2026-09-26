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
use chia_protocol::{Bytes32, Coin, CoinSpend};
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
    /// Each `requests[i]`'s own payout coin id, same order as `requests`
    /// (task fix: `GET /v1/play/withdrawals/{requestId}` used to report
    /// every request in a batch as confirmed at the *treasury's change
    /// coin's* id -- wrong for every request but the coincidental case of a
    /// single-request batch. A payout coin is a CAT child of `treasury`
    /// exactly like the change coin is (parent = `treasury.coin.coin_id()`,
    /// puzzle hash = `CAT(PLAY tail, r.to_puzzle_hash)`, amount =
    /// `r.amount_mojos`) -- computed the same way `next_coin` below is
    /// (`Cat::child`), which is guaranteed to agree with what `Cat::
    /// spend_all` actually creates from the identical `create_coin`
    /// condition emitted for this same request in the loop above).
    pub payout_coin_ids: Vec<Bytes32>,
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
    let mut payout_coin_ids = Vec::with_capacity(requests.len());
    for r in requests {
        let hint = ctx.hint(r.to_puzzle_hash)?;
        conditions = conditions.create_coin(r.to_puzzle_hash, r.amount_mojos, hint);
        payout_coin_ids.push(treasury.child(r.to_puzzle_hash, r.amount_mojos).coin.coin_id());
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
        payout_coin_ids,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use c2_cli::builders::issue::build_issue_play;
    use c2_cli::cat_chain::cat_puzzle_hash;
    use c2_cli::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};

    fn request(id: &str, to_puzzle_hash: Bytes32, amount_mojos: u64) -> WithdrawalRequest {
        WithdrawalRequest {
            request_id: id.to_string(),
            to_address: "txch1test".to_string(),
            to_puzzle_hash,
            amount_mojos,
        }
    }

    /// Task fix (`docs/research/chain_service.md` §13): a batch used to
    /// record every request in it as confirmed at the *treasury's change
    /// coin's* id -- correct by coincidence for a single-request batch,
    /// wrong for every other request in a bigger one. A 3-request batch,
    /// asserted three independent ways: distinct ids, none of them the
    /// change coin's id, and each one matching a manual `Coin::new` built
    /// straight from `(parent = treasury coin id, puzzle hash = CAT(asset
    /// id, that request's own puzzle hash), amount = that request's own
    /// amount)` -- computed via `cat_chain::cat_puzzle_hash`, not via
    /// `Cat::child` (the function under test uses `Cat::child` internally),
    /// so this doesn't just check the implementation against itself.
    #[test]
    fn each_request_in_a_batch_gets_its_own_distinct_payout_coin_id_and_the_change_coin_is_never_among_them() {
        let mnemonic = generate_mnemonic().unwrap();
        let master_sk = master_sk_from_mnemonic(&mnemonic);
        let owner = derive_wallet_key(&master_sk, 0);

        const TREASURY_SUPPLY: u64 = 1_000_000;
        let funding_coin = Coin::new(Bytes32::from([9u8; 32]), owner.puzzle_hash, TREASURY_SUPPLY);
        let issued = build_issue_play(funding_coin, &owner, TREASURY_SUPPLY, 0).unwrap();
        let treasury = issued.cat;
        let asset_id = treasury.info.asset_id;

        let alice = Bytes32::from([1u8; 32]);
        let bob = Bytes32::from([2u8; 32]);
        let carol = Bytes32::from([3u8; 32]);
        let requests =
            vec![request("w1", alice, 100_000), request("w2", bob, 50_000), request("w3", carol, 25_000)];

        let draft = build_play_withdrawal_batch(treasury, &owner, &requests).unwrap();
        assert_eq!(draft.payout_coin_ids.len(), 3);

        let mut distinct = draft.payout_coin_ids.clone();
        distinct.sort();
        distinct.dedup();
        assert_eq!(distinct.len(), 3, "each request must get its own distinct payout coin id");

        assert!(
            !draft.payout_coin_ids.contains(&draft.next_coin.coin_id()),
            "the treasury's own change coin must never be reported as a request's payout coin"
        );

        // `payout_coin_ids` is in the same order `requests` was passed in
        // (see the function's own doc comment) -- zip them directly rather
        // than searching.
        let treasury_coin_id = treasury.coin.coin_id();
        let expected_recipients = [alice, bob, carol];
        for ((request, &actual), &recipient) in requests.iter().zip(&draft.payout_coin_ids).zip(&expected_recipients) {
            let expected = Coin::new(treasury_coin_id, cat_puzzle_hash(asset_id, recipient), request.amount_mojos).coin_id();
            assert_eq!(actual, expected, "payout coin id for {} must match an independent computation", request.request_id);
        }
    }
}
