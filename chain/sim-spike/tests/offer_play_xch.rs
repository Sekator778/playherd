//! (d) PLAY CAT <-> XCH offer, made and taken entirely inside the offline
//! simulator, using `chia-sdk-driver`'s action-system (`Spends`/`Action`)
//! and `Offer` types.
//!
//! Pattern taken from `chia-sdk-driver-0.36.0/src/offers/offer.rs`
//! (`mod tests::test_offer_nft_for_nft`), substituting the offered NFT with
//! the whole PLAY CAT balance and the requested NFT with a plain XCH payment
//! (so no `AssetInfo` entry is needed for the requested side).

use chia_protocol::SpendBundle;
use chia_puzzle_types::offer::{NotarizedPayment, Payment};
use chia_puzzles::SETTLEMENT_PAYMENT_HASH;
use chia_sdk_driver::{
    Action, AssetInfo, Cat, Id, Offer, Relation, RequestedPayments, SpendContext, Spends,
    StandardLayer,
};
use chia_sdk_test::{Simulator, sign_transaction};
use chia_sdk_types::Conditions;
use indexmap::indexmap;

#[test]
fn play_cat_for_xch_offer_round_trip() -> anyhow::Result<()> {
    let mut sim = Simulator::new();
    let mut ctx = SpendContext::new();

    const PLAY_SUPPLY: u64 = 1_000;
    const REQUESTED_MOJOS: u64 = 100;

    let alice = sim.bls(PLAY_SUPPLY); // funds PLAY CAT issuance (1:1, becomes the eve CAT coin)
    let bob = sim.bls(1_000_000_000); // pays REQUESTED_MOJOS to take the offer

    let alice_p2 = StandardLayer::new(alice.pk);
    let alice_hint = ctx.hint(alice.puzzle_hash)?;

    let (issue_cat, cats) = Cat::single_issuance(
        &mut ctx,
        alice.coin.coin_id(),
        None,
        PLAY_SUPPLY,
        Conditions::new().create_coin(alice.puzzle_hash, PLAY_SUPPLY, alice_hint),
    )?;
    alice_p2.spend(&mut ctx, alice.coin, issue_cat)?;
    sim.spend_coins(ctx.take(), &[alice.sk.clone()])?;

    let play_cat = cats[0];
    let asset_id = play_cat.info.asset_id;

    // --- Alice makes the offer: all of her PLAY for REQUESTED_MOJOS mojos.
    let mut requested_payments = RequestedPayments::new();
    requested_payments.xch.push(NotarizedPayment::new(
        Offer::nonce(vec![play_cat.coin.coin_id()]),
        vec![Payment::new(alice.puzzle_hash, REQUESTED_MOJOS, alice_hint)],
    ));
    let requested_asset_info = AssetInfo::new();

    let mut spends = Spends::new(alice.puzzle_hash);
    spends.add(play_cat);

    let deltas = spends.apply(
        &mut ctx,
        &[Action::send(
            Id::Existing(asset_id),
            SETTLEMENT_PAYMENT_HASH.into(),
            PLAY_SUPPLY,
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
        &indexmap! { alice.puzzle_hash => alice.pk },
    )?;

    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, &[alice.sk])?;

    let offer = Offer::from_input_spend_bundle(
        &mut ctx,
        SpendBundle::new(coin_spends, signature),
        requested_payments,
        requested_asset_info,
    )?;

    // --- Bob takes the offer.
    let mut spends = Spends::new(bob.puzzle_hash);
    spends.add(offer.offered_coins().clone());
    spends.add(bob.coin);

    let deltas = spends.apply(&mut ctx, &offer.requested_payments().actions())?;

    let outputs = spends.finish_with_keys(
        &mut ctx,
        &deltas,
        Relation::None,
        &indexmap! { bob.puzzle_hash => bob.pk },
    )?;

    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, &[bob.sk])?;

    let spend_bundle = offer.take(SpendBundle::new(coin_spends, signature));
    sim.new_transaction(spend_bundle)?;

    let bob_play = &outputs.cats[&Id::Existing(asset_id)][0];
    assert_eq!(bob_play.info.p2_puzzle_hash, bob.puzzle_hash);
    assert_eq!(bob_play.coin.amount, PLAY_SUPPLY);
    assert!(sim.coin_state(bob_play.coin.coin_id()).is_some());

    let alice_xch = outputs
        .xch
        .iter()
        .find(|c| c.puzzle_hash == alice.puzzle_hash && c.amount == REQUESTED_MOJOS)
        .expect("alice's XCH payment coin");
    assert!(sim.coin_state(alice_xch.coin_id()).is_some());

    println!("PLAY asset id: {asset_id}");
    println!(
        "offer taken: {} PLAY -> bob, {} mojo -> alice",
        PLAY_SUPPLY, REQUESTED_MOJOS
    );

    Ok(())
}
