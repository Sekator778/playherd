//! Shared fee-coin allocation across every publisher that funds its spends
//! from the operator's plain-XCH wallet (the oracle singleton publisher,
//! `oracle::publisher::Publisher`, and the PLAY withdrawal publisher,
//! `play::withdrawal_publisher::WithdrawalPublisher`).
//!
//! **Incident** (`docs/research/chain_service.md` §15): both publishers
//! picked a fee coin the same way -- `ChainClient::find_funding_coin`, a
//! plain gap-scan for the first unspent coin >= the fee amount, with zero
//! coordination between them. Because both publishers spend from the same
//! operator wallet and that scan is deterministic (first match by
//! address-index order), they reliably picked the *same* coin whenever both
//! needed a fee at once -- whichever spend confirmed first left the other's
//! already-signed, already-broadcast bundle permanently double-spending a
//! now-spent input. The old retry loop had no way to distinguish "this
//! bundle's fee input is now spent by something else, entirely and
//! permanently" from any other rejection, so it just kept re-pushing the
//! identical (permanently doomed) bytes until it exhausted its retry budget
//! and gave up forever -- see `docs/research/chain_service.md` §15.1 for the
//! live evidence this stand produced.
//!
//! The fix has two halves:
//!
//! 1. **Prevention** -- `FeeCoinAllocator` below: a single, in-process
//!    reservation set, shared (one `Arc` instance, constructed once by
//!    `main.rs`) between every publisher that funds spends this way.
//!    `reserve` never hands out a coin id another not-yet-confirmed bundle
//!    already holds, and hands out the *smallest* coin that is big enough
//!    (best fit, `docs/research/chain_service.md` §16 -- it used to be the first
//!    one in scan order, which kept chewing through the big coins and left the
//!    small ones idle); `release` gives one back once its bundle either
//!    confirms (the coin really is spent now, nothing left to protect) or
//!    is abandoned before ever landing on chain (a rebuild dropping a dead
//!    fee coin before picking a replacement, or a terminal `Failed` state).
//!    Not itself durable -- `reserve_specific` re-seeds it from each
//!    publisher's own already-durable queue file once at startup (see
//!    `main.rs`), so a restart never forgets a reservation a surviving
//!    in-flight bundle still depends on. The same allocator also owns the
//!    **fee-coin reserve** (`fee_reserve.rs`): the maintenance step that keeps
//!    the wallet holding enough free coins for both publishers to have one at the
//!    same time.
//! 2. **Detection + recovery** -- `fee_coin_of` and
//!    `is_coin_spent_rejection` below, called from both publishers'
//!    `advance_in_flight`: recognize a rejection as "some input is already
//!    spent", confirm via a fresh `coin_record` lookup that it's genuinely
//!    the *fee* coin (never the singleton/treasury coin -- that case is
//!    already handled by the existing "adopt the chain tip"
//!    reconciliation, `docs/research/chain_service.md` §12), and rebuild
//!    immediately with a freshly allocated fee coin instead of burning a
//!    retry attempt on a failure that is deterministic and will never
//!    resolve itself.

use std::collections::HashSet;

use anyhow::{Result, anyhow};
use chia_bls::SecretKey;
use chia_protocol::{Bytes32, Coin, SpendBundle};

use c2_cli::keys::WalletKey;

use crate::fee_reserve::{FeeCoinPolicy, FeeCoinView, FeeCoinsBusy, ReserveState};
use crate::oracle::chain_client::ChainClient;

/// In-process (not persisted -- see module doc) set of fee coin ids
/// currently backing some publisher's not-yet-confirmed in-flight bundle,
/// plus the fee-coin reserve's own in-memory state (`fee_reserve.rs`).
#[derive(Debug, Default)]
pub struct FeeCoinAllocator {
    pub(crate) reserved: tokio::sync::Mutex<HashSet<Bytes32>>,
    pub(crate) policy: FeeCoinPolicy,
    pub(crate) reserve_state: std::sync::Mutex<ReserveState>,
}

impl FeeCoinAllocator {
    /// An allocator with the default reserve policy (`FeeCoinPolicy::default`:
    /// 4 coins of 200,000,000 mojos).
    pub fn new() -> Self {
        Self::default()
    }

    /// An allocator that keeps the wallet to `policy` (`main.rs`, from
    /// `C2_FEE_COINS_MIN` / `C2_FEE_COIN_MOJOS`).
    pub fn with_policy(policy: FeeCoinPolicy) -> Self {
        Self { policy, ..Self::default() }
    }

    /// Picks and reserves the unspent coin that fits best: the smallest one that
    /// is >= `min_amount` and not already reserved (ties: first in scan order).
    /// Atomic with respect to every other `reserve` call on this same allocator
    /// (whichever publisher, same process): the lock is held across the whole
    /// chain round-trip, so two concurrent callers can never both observe the
    /// same coin as available and both reserve it.
    ///
    /// Best fit, not first fit: a fee bundle consumes its coin whole and
    /// returns the change as a new coin, so taking the smallest sufficient one
    /// keeps the large coins -- the ones the reserve is made of, and the ones
    /// a split can still be cut from -- for when they are needed.
    ///
    /// When nothing fits only because the fee-coin split holds the one big coin,
    /// the error is `FeeCoinsBusy` (a publisher waits a tick); otherwise the wallet
    /// really has no free coin that big.
    pub async fn reserve<C: ChainClient>(
        &self,
        chain: &C,
        master_sk: &SecretKey,
        min_amount: u64,
    ) -> Result<(WalletKey, Coin)> {
        let mut reserved = self.reserved.lock().await;
        let mut coins = chain.unspent_coins(master_sk).await?;
        let snapshot: Vec<(Bytes32, u64)> = coins.iter().map(|(_, coin)| (coin.coin_id(), coin.amount)).collect();
        let best = (0..coins.len())
            .filter(|&i| coins[i].1.amount >= min_amount && !reserved.contains(&snapshot[i].0))
            .min_by_key(|&i| (coins[i].1.amount, i));
        let Some(best) = best else {
            self.state().view = Some(FeeCoinView::of(&snapshot, &reserved, &self.policy));
            if self.split_in_flight() {
                return Err(FeeCoinsBusy.into());
            }
            return Err(anyhow!(
                "no unspent, unreserved coin with amount >= {min_amount} mojos found in addresses 0..{} \
                 (excluding {} coin(s) already reserved by an in-flight spend elsewhere in this process); \
                 fund the wallet from https://testnet11-faucet.chia.net/ first, or wait for the in-flight \
                 spend(s) to confirm",
                c2_cli::chain::ADDRESS_GAP,
                reserved.len()
            ));
        };
        let (wallet_key, coin) = coins.swap_remove(best);
        reserved.insert(coin.coin_id());
        self.state().view = Some(FeeCoinView::of(&snapshot, &reserved, &self.policy));
        Ok((wallet_key, coin))
    }

    /// Gives a coin id back to the pool. Call once the bundle that reserved
    /// it either confirms (the coin is now genuinely, permanently spent --
    /// releasing it is just housekeeping, a gap-scan of unspent coins will
    /// never offer it again) or is abandoned without ever landing on chain
    /// (a rebuild dropping a dead fee coin before picking a replacement, or
    /// a terminal failure that never actually broadcast successfully).
    pub async fn release(&self, coin_id: Bytes32) {
        self.reserved.lock().await.remove(&coin_id);
    }

    /// Seeds the reservation set from a coin id a durable queue file already
    /// committed to an in-flight bundle before this process started --
    /// called once at startup (`main.rs`, right after each publisher's own
    /// chain-reconciliation bootstrap) for every publisher that has a
    /// persisted `in_flight` spend, so a fresh process's in-memory
    /// reservation set doesn't forget a fee coin a still-pending bundle from
    /// before the restart depends on.
    pub async fn reserve_specific(&self, coin_id: Bytes32) {
        self.reserved.lock().await.insert(coin_id);
    }

    /// Whether `coin_id` is currently reserved -- mainly for integration
    /// tests (`tests/oracle_queue.rs`, `tests/fee_allocator.rs`) to assert
    /// on; not `#[cfg(test)]`-gated because those live in separate
    /// integration-test crates that only ever see this crate's normal,
    /// non-test-cfg public API.
    pub async fn is_reserved(&self, coin_id: Bytes32) -> bool {
        self.reserved.lock().await.contains(&coin_id)
    }
}

/// The fee-only coin a bundle attached alongside its "main" spend (the
/// oracle singleton, or the PLAY treasury CAT coin). Both publishers'
/// bundles are always exactly `[main coin's own spend(s)..., fee-only
/// spend]` (each publisher's own `attach_fee_coin`), so the fee coin is
/// simply whichever `CoinSpend` in the bundle does *not* consume
/// `main_coin`. `None` only if the fee was `0` (`attach_fee_coin` skips
/// attaching anything in that case -- no fee coin to find) or for a bundle
/// that somehow doesn't fit this two-part shape; callers treat that as
/// "can't tell, don't guess" and fall back to the pre-existing generic
/// retry/stuck handling rather than acting on a guess.
pub fn fee_coin_of(bundle: &SpendBundle, main_coin: Coin) -> Option<Coin> {
    bundle.coin_spends.iter().map(|cs| cs.coin).find(|&coin| coin != main_coin)
}

/// A rejection meaning "this bundle's spend conflicts with something
/// already confirmed on chain". Chia's mempool manager rejects a spend of
/// an already-spent coin with `Err.DOUBLE_SPEND` -- verified live on this
/// stand, see `docs/research/chain_service.md` §15.1's exact `push_tx`
/// error text. **предложение**: the broader case-insensitive "contains
/// ALREADY SPENT" match widens this for any other phrasing of the same
/// underlying condition; this classifier only ever decides whether to
/// *look closer* -- safety never depends on it being exhaustive, because
/// every caller confirms the specific coin it suspects is spent via a fresh
/// `coin_record` lookup before acting on it (`oracle::publisher::
/// advance_in_flight`, `play::withdrawal_publisher::advance_in_flight`).
pub fn is_coin_spent_rejection(error: Option<&str>) -> bool {
    error.is_some_and(|msg| {
        let upper = msg.to_ascii_uppercase();
        upper.contains("DOUBLE_SPEND") || upper.contains("ALREADY SPENT")
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use c2_cli::builders::fee::build_fee_only;
    use c2_cli::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
    use chia_protocol::Bytes32;

    fn wallet() -> WalletKey {
        let mnemonic = generate_mnemonic().unwrap();
        let master_sk = master_sk_from_mnemonic(&mnemonic);
        derive_wallet_key(&master_sk, 0)
    }

    #[test]
    fn is_coin_spent_rejection_matches_double_spend_case_insensitively() {
        assert!(is_coin_spent_rejection(Some("Failed to include transaction ..., error DOUBLE_SPEND")));
        assert!(is_coin_spent_rejection(Some("double_spend")));
        assert!(is_coin_spent_rejection(Some("Coin already spent")));
        assert!(!is_coin_spent_rejection(Some("INVALID_FEE_TOO_CLOSE_TO_ZERO")));
        assert!(!is_coin_spent_rejection(None));
    }

    /// Builds two genuine, independently-signed `build_fee_only` spends (the
    /// real builder every publisher's `attach_fee_coin` uses) and packs them
    /// into one bundle -- exactly the two-part shape (`[main coin's own
    /// spend, fee-only spend]`) every real oracle/withdrawal bundle has, so
    /// this test proves `fee_coin_of` against genuine `CoinSpend`s, not
    /// hand-rolled ones.
    #[test]
    fn fee_coin_of_finds_the_one_spend_that_is_not_the_main_coin() {
        let payer = wallet();
        let main_coin = Coin::new(Bytes32::from([9u8; 32]), payer.puzzle_hash, 1_000);
        let fee_funding_coin = Coin::new(Bytes32::from([8u8; 32]), payer.puzzle_hash, 2_000);

        // Stands in for "the main coin's own spend" -- its actual contents
        // don't matter to `fee_coin_of`, only its `.coin`.
        let main_result = build_fee_only(main_coin, &payer, 500).unwrap();
        let fee_result = build_fee_only(fee_funding_coin, &payer, 100).unwrap();

        let mut coin_spends = main_result.coin_spends;
        coin_spends.extend(fee_result.coin_spends);
        let bundle = SpendBundle::new(coin_spends, main_result.signature + &fee_result.signature);

        assert_eq!(fee_coin_of(&bundle, main_coin), Some(fee_funding_coin));
    }

    #[test]
    fn fee_coin_of_is_none_without_a_second_coin() {
        let payer = wallet();
        let main_coin = Coin::new(Bytes32::from([9u8; 32]), payer.puzzle_hash, 1_000);
        let result = build_fee_only(main_coin, &payer, 500).unwrap();
        let bundle = SpendBundle::new(result.coin_spends, result.signature);
        assert_eq!(fee_coin_of(&bundle, main_coin), None);
    }

    #[tokio::test]
    async fn reserve_specific_and_release_round_trip() {
        let allocator = FeeCoinAllocator::new();
        let id = Bytes32::from([5u8; 32]);
        assert!(!allocator.is_reserved(id).await);
        allocator.reserve_specific(id).await;
        assert!(allocator.is_reserved(id).await);
        allocator.release(id).await;
        assert!(!allocator.is_reserved(id).await);
    }
}
