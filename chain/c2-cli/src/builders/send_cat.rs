//! `send-play`: a plain CAT transfer of a wallet's already-owned CAT coin to
//! an arbitrary `txch1...` address's inner puzzle hash -- the same
//! `CatSpend`/`Cat::spend_all` pattern `chain/sim-spike/tests/cat_play.rs`
//! proves offline (`issue_play_cat_and_transfer_part`'s transfer half), just
//! with the funding CAT coin discovered on chain (`crate::cat_chain::
//! find_cat_coin`) instead of freshly minted in the same test.
//!
//! Carries no fee itself: unlike a plain XCH coin, this CAT coin's amount is
//! the PLAY balance being moved, not spare mojos to reserve a fee out of
//! (same reasoning as `builders::oracle`'s fixed-amount singleton spend, see
//! `builders::fee::build_fee_only`'s doc comment) -- a caller wanting a
//! nonzero fee attaches a separate fee-only XCH coin spend to the same
//! bundle, exactly the way `main.rs`'s `cmd_oracle_publish` already does.

use anyhow::{Result, bail};
use chia_bls::Signature;
use chia_protocol::{Bytes32, CoinSpend};
use chia_sdk_driver::{Cat, CatSpend, SpendContext, SpendWithConditions, StandardLayer};
use chia_sdk_types::Conditions;

use crate::keys::WalletKey;
use crate::sign::sign_transaction;

#[derive(Debug)]
pub struct SendCatResult {
    pub coin_spends: Vec<CoinSpend>,
    pub signature: Signature,
    pub change_amount: u64,
}

/// Builds (and signs) a transfer of `amount` CAT mojos out of `cat` (owned by
/// `sender`) to `to_puzzle_hash`, with any remainder returned to `sender` as
/// CAT change.
pub fn build_send_cat(cat: Cat, sender: &WalletKey, to_puzzle_hash: Bytes32, amount: u64) -> Result<SendCatResult> {
    if cat.info.p2_puzzle_hash != sender.puzzle_hash {
        bail!("CAT coin does not belong to this wallet (p2 puzzle hash mismatch)");
    }
    let Some(change_amount) = cat.coin.amount.checked_sub(amount) else {
        bail!("CAT coin has {} mojos, need at least {amount}", cat.coin.amount);
    };

    let mut ctx = SpendContext::new();
    let sender_p2 = StandardLayer::new(sender.synthetic_pk);

    let to_hint = ctx.hint(to_puzzle_hash)?;
    let mut conditions = Conditions::new().create_coin(to_puzzle_hash, amount, to_hint);
    if change_amount > 0 {
        let change_hint = ctx.hint(sender.puzzle_hash)?;
        conditions = conditions.create_coin(sender.puzzle_hash, change_amount, change_hint);
    }

    let p2_spend = sender_p2.spend_with_conditions(&mut ctx, conditions)?;
    Cat::spend_all(&mut ctx, &[CatSpend::new(cat, p2_spend)])?;

    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, &[sender.synthetic_sk.clone()])?;

    Ok(SendCatResult {
        coin_spends,
        signature,
        change_amount,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
    use chia_protocol::Coin;
    use chia_sdk_driver::CatInfo;

    fn wallet(index: u32) -> WalletKey {
        let mnemonic = generate_mnemonic().unwrap();
        let master_sk = master_sk_from_mnemonic(&mnemonic);
        derive_wallet_key(&master_sk, index)
    }

    #[test]
    fn rejects_wrong_owner() {
        let sender = wallet(0);
        let other = wallet(1);
        let asset_id = Bytes32::from([9u8; 32]);
        let cat_info = CatInfo::new(asset_id, None, other.puzzle_hash);
        let coin = Coin::new(Bytes32::from([0u8; 32]), cat_info.puzzle_hash().into(), 1_000);
        let cat = Cat::new(coin, None, cat_info);
        let err = build_send_cat(cat, &sender, other.puzzle_hash, 100).unwrap_err();
        assert!(err.to_string().contains("does not belong to this wallet"));
    }

    #[test]
    fn rejects_insufficient_funding() {
        let sender = wallet(0);
        let to = wallet(1);
        let asset_id = Bytes32::from([9u8; 32]);
        let cat_info = CatInfo::new(asset_id, None, sender.puzzle_hash);
        let coin = Coin::new(Bytes32::from([0u8; 32]), cat_info.puzzle_hash().into(), 100);
        let cat = Cat::new(coin, None, cat_info);
        let err = build_send_cat(cat, &sender, to.puzzle_hash, 1_000).unwrap_err();
        assert!(err.to_string().contains("need at least"));
    }
}
