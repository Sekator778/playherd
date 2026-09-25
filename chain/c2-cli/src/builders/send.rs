//! `send`: a plain standard-coin (p2) XCH transfer from one wallet's address
//! to an arbitrary `txch1...` address. Same `StandardLayer::spend` pattern
//! `chain/sim-spike/tests/standard_coin.rs` proves offline, just to a
//! different puzzle hash than the sender's own instead of back to itself,
//! with optional change and fee.

use anyhow::{Result, bail};
use chia_bls::Signature;
use chia_protocol::{Bytes32, Coin, CoinSpend};
use chia_puzzle_types::Memos;
use chia_sdk_driver::{SpendContext, StandardLayer};
use chia_sdk_types::Conditions;

use crate::keys::WalletKey;
use crate::sign::sign_transaction;

#[derive(Debug)]
pub struct SendResult {
    pub coin_spends: Vec<CoinSpend>,
    pub signature: Signature,
    pub change_amount: u64,
}

/// Builds (and signs) a transfer of `amount` mojos from `funding_coin`
/// (owned by `sender`) to `to_puzzle_hash`, reserving `fee` mojos and
/// returning any remainder to `sender` as change.
pub fn build_send(
    funding_coin: Coin,
    sender: &WalletKey,
    to_puzzle_hash: Bytes32,
    amount: u64,
    fee: u64,
) -> Result<SendResult> {
    if funding_coin.puzzle_hash != sender.puzzle_hash {
        bail!("funding coin does not belong to this wallet (puzzle hash mismatch)");
    }
    let Some(change_amount) = funding_coin
        .amount
        .checked_sub(amount)
        .and_then(|v| v.checked_sub(fee))
    else {
        bail!(
            "funding coin has {} mojos, need at least {} (amount) + {} (fee)",
            funding_coin.amount,
            amount,
            fee
        );
    };

    let mut ctx = SpendContext::new();
    let sender_p2 = StandardLayer::new(sender.synthetic_pk);

    let mut conditions = Conditions::new().create_coin(to_puzzle_hash, amount, Memos::None);
    if change_amount > 0 {
        conditions =
            conditions.create_coin(sender.puzzle_hash, change_amount, Memos::None);
    }
    if fee > 0 {
        conditions = conditions.reserve_fee(fee);
    }

    sender_p2.spend(&mut ctx, funding_coin, conditions)?;

    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, &[sender.synthetic_sk.clone()])?;

    Ok(SendResult {
        coin_spends,
        signature,
        change_amount,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};

    fn wallet(index: u32) -> WalletKey {
        let mnemonic = generate_mnemonic().unwrap();
        let master_sk = master_sk_from_mnemonic(&mnemonic);
        derive_wallet_key(&master_sk, index)
    }

    #[test]
    fn rejects_wrong_owner() {
        let sender = wallet(0);
        let other = wallet(1);
        let funding_coin = Coin::new(Bytes32::from([0u8; 32]), other.puzzle_hash, 1_000);
        let err = build_send(funding_coin, &sender, other.puzzle_hash, 100, 0).unwrap_err();
        assert!(err.to_string().contains("does not belong to this wallet"));
    }

    #[test]
    fn rejects_insufficient_funding() {
        let sender = wallet(0);
        let to = wallet(1);
        let funding_coin = Coin::new(Bytes32::from([0u8; 32]), sender.puzzle_hash, 100);
        let err = build_send(funding_coin, &sender, to.puzzle_hash, 1_000, 0).unwrap_err();
        assert!(err.to_string().contains("need at least"));
    }
}
