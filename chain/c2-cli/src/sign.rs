//! Signs a set of coin spends with the given (synthetic) secret keys.
//!
//! Ported from `chia-sdk-test-0.36.0/src/transaction.rs::sign_transaction`.
//! That helper lives in the `chia-sdk-test` crate (a simulator-only test
//! dependency); this is the same logic reached directly from
//! `chia-sdk-signer`, so the production binary doesn't pull in the
//! simulator/test crate.
//!
//! Callers must pass **synthetic** secret keys (see `keys.rs` module docs,
//! point 4) -- a standard-layer spend's `AGG_SIG_ME` is over the synthetic
//! public key the puzzle was curried with, not the plain wallet key.

use std::collections::HashMap;

use anyhow::{Result, bail};
use chia_bls::{PublicKey, SecretKey, Signature, sign};
use chia_protocol::CoinSpend;
use chia_sdk_signer::{AggSigConstants, RequiredSignature};
use chia_sdk_types::TESTNET11_CONSTANTS;
use clvmr::Allocator;

pub fn sign_transaction(
    coin_spends: &[CoinSpend],
    secret_keys: &[SecretKey],
) -> Result<Signature> {
    let mut allocator = Allocator::new();

    let required_signatures = RequiredSignature::from_coin_spends(
        &mut allocator,
        coin_spends,
        &AggSigConstants::new(TESTNET11_CONSTANTS.agg_sig_me_additional_data),
    )?;

    let key_pairs = secret_keys
        .iter()
        .map(|sk| (sk.public_key(), sk))
        .collect::<HashMap<PublicKey, &SecretKey>>();

    let mut aggregated_signature = Signature::default();

    for required in required_signatures {
        let RequiredSignature::Bls(required) = required else {
            continue;
        };
        let pk = required.public_key;
        let Some(sk) = key_pairs.get(&pk) else {
            bail!("missing secret key for a required signature (no matching synthetic pubkey among the keys passed to sign_transaction)");
        };
        aggregated_signature += &sign(sk, required.message());
    }

    Ok(aggregated_signature)
}
