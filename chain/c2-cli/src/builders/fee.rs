//! A plain fee-only auxiliary spend: consumes `funding_coin`, reserves
//! `fee` mojos in the bundle-wide fee total, and returns any remainder to
//! its owner as change.
//!
//! Used to attach a fee to a bundle whose "main" coin can't carry
//! `RESERVE_FEE` itself without breaking its own value conservation --
//! `oracle-publish`'s recreated singleton always recreates itself at
//! exactly its fixed `SINGLETON_AMOUNT` (see `builders::oracle`), so a fee
//! there has to come from a second, ordinary coin instead. A spend bundle's
//! `RESERVE_FEE` condition is bundle-wide (consensus sums it across every
//! coin spend), so simply including this coin's spend alongside the main
//! one is enough -- same principle `builders::offer::build_offer_take` uses
//! via `Action::fee` for the taker's own funding coin, just without the
//! action-system machinery (this is a single plain p2 coin, not a set of
//! assets to balance).

use anyhow::{Result, bail};
use chia_bls::Signature;
use chia_protocol::{Coin, CoinSpend};
use chia_puzzle_types::Memos;
use chia_sdk_driver::{SpendContext, StandardLayer};
use chia_sdk_types::Conditions;

use crate::keys::WalletKey;
use crate::sign::sign_transaction;

#[derive(Debug)]
pub struct FeeOnlyResult {
    pub coin_spends: Vec<CoinSpend>,
    pub signature: Signature,
    pub change_amount: u64,
}

/// Builds (and signs) a spend of `funding_coin` (owned by `payer`) that
/// reserves `fee` mojos and returns the rest to `payer` as change.
pub fn build_fee_only(funding_coin: Coin, payer: &WalletKey, fee: u64) -> Result<FeeOnlyResult> {
    if funding_coin.puzzle_hash != payer.puzzle_hash {
        bail!("funding coin does not belong to this wallet (puzzle hash mismatch)");
    }
    if fee == 0 {
        bail!("build_fee_only requires fee > 0 (nothing to reserve)");
    }
    let Some(change_amount) = funding_coin.amount.checked_sub(fee) else {
        bail!(
            "funding coin has {} mojos, need at least {fee} (fee)",
            funding_coin.amount
        );
    };

    let mut ctx = SpendContext::new();
    let payer_p2 = StandardLayer::new(payer.synthetic_pk);

    let mut conditions = Conditions::new().reserve_fee(fee);
    if change_amount > 0 {
        conditions = conditions.create_coin(payer.puzzle_hash, change_amount, Memos::None);
    }

    payer_p2.spend(&mut ctx, funding_coin, conditions)?;

    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, &[payer.synthetic_sk.clone()])?;

    Ok(FeeOnlyResult {
        coin_spends,
        signature,
        change_amount,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
    use chia_protocol::Bytes32;

    fn wallet() -> WalletKey {
        let mnemonic = generate_mnemonic().unwrap();
        let master_sk = master_sk_from_mnemonic(&mnemonic);
        derive_wallet_key(&master_sk, 0)
    }

    #[test]
    fn rejects_zero_fee() {
        let payer = wallet();
        let funding_coin = Coin::new(Bytes32::from([0u8; 32]), payer.puzzle_hash, 1_000);
        let err = build_fee_only(funding_coin, &payer, 0).unwrap_err();
        assert!(err.to_string().contains("fee > 0"));
    }

    #[test]
    fn rejects_insufficient_funding() {
        let payer = wallet();
        let funding_coin = Coin::new(Bytes32::from([0u8; 32]), payer.puzzle_hash, 100);
        let err = build_fee_only(funding_coin, &payer, 1_000).unwrap_err();
        assert!(err.to_string().contains("need at least"));
    }
}
