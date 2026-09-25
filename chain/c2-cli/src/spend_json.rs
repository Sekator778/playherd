//! Renders a spend bundle (coin spends + aggregated signature) as JSON, in
//! exactly the shape `chia-sdk-coinset`'s `push_tx` sends to coinset.org
//! (`chia-sdk-coinset-0.36.0/src/chia_rpc_client.rs::push_tx`) -- so the
//! dry-run JSON printed by every spending command is literally the request
//! body `--push` would send.

use chia_bls::Signature;
use chia_protocol::CoinSpend;
use serde_json::{Value, json};

pub fn spend_bundle_json(coin_spends: &[CoinSpend], signature: &Signature) -> Value {
    json!({
        "coin_spends": coin_spends.iter().map(|coin_spend| json!({
            "coin": {
                "parent_coin_info": format!("0x{}", hex::encode(coin_spend.coin.parent_coin_info.to_bytes())),
                "puzzle_hash": format!("0x{}", hex::encode(coin_spend.coin.puzzle_hash.to_bytes())),
                "amount": coin_spend.coin.amount,
            },
            "puzzle_reveal": format!("0x{}", hex::encode(coin_spend.puzzle_reveal.to_vec())),
            "solution": format!("0x{}", hex::encode(coin_spend.solution.to_vec())),
        })).collect::<Vec<Value>>(),
        "aggregated_signature": format!("0x{}", hex::encode(signature.to_bytes())),
    })
}
