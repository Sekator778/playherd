//! Simulator proof of `offer-make` + `offer-take`'s builders
//! (`c2_cli::builders::offer`), with two independently derived wallets (a
//! maker and a taker), matching the real testnet11 flow: `operator` issues
//! PLAY, offers it for TXCH, `counterparty` takes the offer.

use c2_cli::builders::issue::build_issue_play;
use c2_cli::builders::offer::{build_offer_make, build_offer_take, finish_offer};
use c2_cli::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use chia_sdk_test::Simulator;

#[test]
fn play_cat_for_xch_offer_round_trip() -> anyhow::Result<()> {
    let mut sim = Simulator::new();

    let maker_mnemonic = generate_mnemonic()?;
    let maker_master_sk = master_sk_from_mnemonic(&maker_mnemonic);
    let maker = derive_wallet_key(&maker_master_sk, 0);

    let taker_mnemonic = generate_mnemonic()?;
    let taker_master_sk = master_sk_from_mnemonic(&taker_mnemonic);
    let taker = derive_wallet_key(&taker_master_sk, 0);

    // Matches the real testnet11 trade this CLI runs (week 1, day 2-3):
    // maker mints a much larger PLAY supply than it offers, so the offer
    // must return CAT change -- not just the "offer the whole coin" case.
    const PLAY_SUPPLY: u64 = 100_000_000_000;
    const OFFER_AMOUNT: u64 = 1_000_000;
    const REQUESTED_MOJOS: u64 = 10_000_000_000;

    // Maker issues the whole PLAY supply to themselves (no fee, no change).
    let funding_coin = sim.new_coin(maker.puzzle_hash, PLAY_SUPPLY);
    let issued = build_issue_play(funding_coin, &maker, PLAY_SUPPLY, 0)?;
    sim.spend_coins(issued.coin_spends, &[maker.synthetic_sk.clone()])?;
    let play_cat = issued.cat;

    // Maker offers only part of it (OFFER_AMOUNT) for REQUESTED_MOJOS mojos;
    // the rest must come back as CAT change in the same offer spend.
    let offer_text = build_offer_make(play_cat, &maker, OFFER_AMOUNT, REQUESTED_MOJOS)?;
    assert!(offer_text.starts_with("offer1"));

    // Taker funds the trade and takes the offer.
    let taker_funding_coin = sim.new_coin(taker.puzzle_hash, 1_000_000_000_000);
    let taken = build_offer_take(&offer_text, taker_funding_coin, &taker, 0)?;
    assert_eq!(taken.received_play_amount, OFFER_AMOUNT);

    // `build_offer_take` only produces the *taker's* half; the pushable
    // bundle is the maker's original offer bundle plus this half (exactly
    // what `--push` on `c2 offer-take` broadcasts, see `finish_offer`).
    let bundle = finish_offer(&offer_text, taken.coin_spends, taken.signature)?;
    sim.new_transaction(bundle)?;

    // Taker's new CAT coin lives at `CatArgs::curry_tree_hash(asset_id, taker.puzzle_hash)`.
    let taker_cat_puzzle_hash: chia_protocol::Bytes32 =
        chia_puzzle_types::cat::CatArgs::curry_tree_hash(play_cat.info.asset_id, taker.puzzle_hash.into())
            .into();
    let taker_play_coins = sim.unspent_coins(taker_cat_puzzle_hash, false);
    assert_eq!(
        taker_play_coins.iter().map(|c| c.amount).sum::<u64>(),
        OFFER_AMOUNT
    );

    // Maker keeps the rest of the PLAY supply as CAT change, automatically
    // produced by `Spends`' delta-based change mechanism (see
    // `build_offer_make`'s doc comment).
    let maker_cat_puzzle_hash: chia_protocol::Bytes32 =
        chia_puzzle_types::cat::CatArgs::curry_tree_hash(play_cat.info.asset_id, maker.puzzle_hash.into())
            .into();
    let maker_play_coins = sim.unspent_coins(maker_cat_puzzle_hash, false);
    assert_eq!(
        maker_play_coins.iter().map(|c| c.amount).sum::<u64>(),
        PLAY_SUPPLY - OFFER_AMOUNT
    );

    let maker_xch = sim
        .unspent_coins(maker.puzzle_hash, false)
        .into_iter()
        .find(|c| c.amount == REQUESTED_MOJOS);
    assert!(maker_xch.is_some(), "maker should have received the requested mojos");

    Ok(())
}
