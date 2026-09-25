//! `offer-make` / `offer-take`: PLAY CAT <-> TXCH offer, the pattern proven
//! in `chain/sim-spike/tests/offer_play_xch.rs` (`chia-sdk-driver`'s
//! action-system `Spends`/`Action` + `Offer` types).

use anyhow::{Result, bail};
use chia_bls::Signature;
use chia_protocol::{Coin, SpendBundle};
use chia_puzzle_types::offer::{NotarizedPayment, Payment};
use chia_puzzles::SETTLEMENT_PAYMENT_HASH;
use chia_sdk_driver::{
    Action, AssetInfo, Cat, Id, Offer, Relation, RequestedPayments, SpendContext, Spends,
};
use indexmap::indexmap;

use crate::keys::WalletKey;
use crate::offer_compress::{decode_offer, encode_offer};
use crate::sign::sign_transaction;

/// Builds and signs the maker's half of a PLAY-for-TXCH offer: `maker` gives
/// up `offer_amount` mojos of `offered_cat` (which may be less than the full
/// coin -- the remainder is automatically returned to `maker` as CAT change,
/// via `Spends`' own delta-based change mechanism, same as a plain-XCH send)
/// and asks for `requested_mojos` plain TXCH paid to `maker`. Returns the
/// bech32 `offer1...` string.
pub fn build_offer_make(
    offered_cat: Cat,
    maker: &WalletKey,
    offer_amount: u64,
    requested_mojos: u64,
) -> Result<String> {
    if offered_cat.info.p2_puzzle_hash != maker.puzzle_hash {
        bail!("offered CAT coin does not belong to this wallet (p2 puzzle hash mismatch)");
    }
    if offer_amount > offered_cat.coin.amount {
        bail!(
            "offer_amount ({offer_amount}) exceeds the cached PLAY coin's amount ({})",
            offered_cat.coin.amount
        );
    }

    let mut ctx = SpendContext::new();
    let maker_hint = ctx.hint(maker.puzzle_hash)?;

    let mut requested_payments = RequestedPayments::new();
    requested_payments.xch.push(NotarizedPayment::new(
        Offer::nonce(vec![offered_cat.coin.coin_id()]),
        vec![Payment::new(maker.puzzle_hash, requested_mojos, maker_hint)],
    ));
    let requested_asset_info = AssetInfo::new();

    let mut spends = Spends::new(maker.puzzle_hash);
    spends.add(offered_cat);

    let deltas = spends.apply(
        &mut ctx,
        &[Action::send(
            Id::Existing(offered_cat.info.asset_id),
            SETTLEMENT_PAYMENT_HASH.into(),
            offer_amount,
            chia_puzzle_types::Memos::None,
        )],
    )?;

    spends.conditions.required = spends
        .conditions
        .required
        .extend(requested_payments.assertions(&mut ctx, &requested_asset_info)?);

    spends.finish_with_keys(
        &mut ctx,
        &deltas,
        Relation::None,
        &indexmap! { maker.puzzle_hash => maker.synthetic_pk },
    )?;

    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, &[maker.synthetic_sk.clone()])?;

    let offer = Offer::from_input_spend_bundle(
        &mut ctx,
        SpendBundle::new(coin_spends, signature),
        requested_payments,
        requested_asset_info,
    )?;

    // `offer.spend_bundle()` only holds the *real* coin spends; the
    // requested-payment side has no real coin, so `Offer::from_spend_bundle`
    // (used by `build_offer_take` to parse this back) only sees it if it's
    // injected as a virtual `parent_coin_info == 0` `CoinSpend`, which is
    // exactly what `to_spend_bundle` does
    // (`chia-sdk-driver-0.36.0/src/offers/offer.rs::to_spend_bundle`).
    let wire_bundle = offer.to_spend_bundle(&mut ctx)?;
    Ok(encode_offer(&wire_bundle)?)
}

pub struct OfferTakeResult {
    pub coin_spends: Vec<chia_protocol::CoinSpend>,
    pub signature: Signature,
    pub received_play_amount: u64,
}

/// Takes an `offer1...` string: `taker` pays the requested TXCH (from
/// `funding_coin`) plus `fee` mojos (bundle-wide, via `Action::fee` -- see
/// its doc comment for why this taker-side spend is the natural place to
/// carry the whole trade's fee, unlike `oracle-publish`'s recreated
/// singleton) and receives the offered PLAY CAT.
pub fn build_offer_take(
    offer_text: &str,
    funding_coin: Coin,
    taker: &WalletKey,
    fee: u64,
) -> Result<OfferTakeResult> {
    if funding_coin.puzzle_hash != taker.puzzle_hash {
        bail!("funding coin does not belong to this wallet (puzzle hash mismatch)");
    }

    let spend_bundle = decode_offer(offer_text)?;
    let mut ctx = SpendContext::new();
    let offer = Offer::from_spend_bundle(&mut ctx, &spend_bundle)?;

    let mut spends = Spends::new(taker.puzzle_hash);
    spends.add(offer.offered_coins().clone());
    spends.add(funding_coin);

    let mut actions = offer.requested_payments().actions();
    if fee > 0 {
        actions.push(Action::fee(fee));
    }
    let deltas = spends.apply(&mut ctx, &actions)?;

    let outputs = spends.finish_with_keys(
        &mut ctx,
        &deltas,
        Relation::None,
        &indexmap! { taker.puzzle_hash => taker.synthetic_pk },
    )?;

    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, &[taker.synthetic_sk.clone()])?;

    let received_play_amount = outputs
        .cats
        .values()
        .flatten()
        .filter(|cat| cat.info.p2_puzzle_hash == taker.puzzle_hash)
        .map(|cat| cat.coin.amount)
        .sum();

    Ok(OfferTakeResult {
        coin_spends,
        signature,
        received_play_amount,
    })
}

/// Assembles the final, pushable spend bundle from a taken offer (this is
/// what `--push` would actually broadcast): the maker's original offer
/// spend bundle plus the taker's completing spend bundle.
pub fn finish_offer(offer_text: &str, taker_coin_spends: Vec<chia_protocol::CoinSpend>, taker_signature: Signature) -> Result<SpendBundle> {
    let maker_bundle = decode_offer(offer_text)?;
    let mut ctx = SpendContext::new();
    let offer = Offer::from_spend_bundle(&mut ctx, &maker_bundle)?;
    Ok(offer.take(SpendBundle::new(taker_coin_spends, taker_signature)))
}
