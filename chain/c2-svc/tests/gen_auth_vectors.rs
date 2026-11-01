//! Generates `tests/vectors/auth_vectors.json` from fixed seeds (task
//! instruction). Not run by normal `cargo test` (the vectors are a checked-
//! in fixture `tests/auth_verify.rs` replays, and other components can
//! reuse -- task instruction: "so other components can reuse them"); run
//! explicitly to regenerate:
//!
//!   cargo test -p c2-svc --test gen_auth_vectors -- --ignored
//!
//! Regeneration is deterministic (fixed seeds -> fixed keys/signatures), so
//! re-running it should reproduce byte-identical output.

use chia_bls::{SecretKey, sign};
use serde::Serialize;
use sha2::{Digest, Sha256};

use c2_cli::keys::{address_of, derive_wallet_key};
use c2_svc::auth::signed_message_tree_hash;

#[derive(Serialize)]
struct Vector {
    case: &'static str,
    seed_hex: String,
    address: String,
    public_key: String,
    message: String,
    signature: String,
    valid: bool,
}

/// A fixed, deterministic 32-byte seed for `SecretKey::from_seed`
/// (`chia_bls::SecretKey::from_seed` requires >= 32 bytes, EIP-2333
/// `derive_master_SK`, see `docs/research/week1_wallet_auth.md` §2) --
/// `sha256(label)`, so the vectors are reproducible from the label alone
/// without needing a committed binary seed.
fn fixed_seed(label: &str) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(label.as_bytes());
    hasher.finalize().into()
}

fn keypair(label: &str) -> (String, SecretKey, String) {
    let seed = fixed_seed(label);
    let master_sk = SecretKey::from_seed(&seed);
    let wallet = derive_wallet_key(&master_sk, 0);
    let address = address_of(wallet.puzzle_hash).unwrap();
    (hex::encode(seed), wallet.synthetic_sk, address)
}

fn sign_message(sk: &SecretKey, message: &str) -> String {
    let hash = signed_message_tree_hash(message.as_bytes());
    let signature = sign(sk, hash.as_ref());
    format!("0x{}", hex::encode(signature.to_bytes()))
}

#[test]
#[ignore = "regenerates tests/vectors/auth_vectors.json; run explicitly, see module doc"]
fn generate() {
    let mut vectors = Vec::new();

    // 1. positive: a normal, correctly-matched (address, publicKey,
    //    message, signature) tuple.
    let (seed_a, sk_a, address_a) = keypair("c2-svc auth vector 1: positive");
    let pk_a = sk_a.public_key();
    let message_a = "Crowd vs Market login\nNonce: 1a2b3c4d5e6f\n";
    let sig_a = sign_message(&sk_a, message_a);
    vectors.push(Vector {
        case: "positive",
        seed_hex: seed_a.clone(),
        address: address_a.clone(),
        public_key: format!("0x{}", hex::encode(pk_a.to_bytes())),
        message: message_a.to_string(),
        signature: sig_a.clone(),
        valid: true,
    });

    // 2. wrong address: identical publicKey/message/signature as vector 1,
    //    but `address` swapped for a *different* keypair's address -- the
    //    standard-synthetic-key-puzzle-hash check must reject it.
    let (_, _, address_b) = keypair("c2-svc auth vector 2: a different wallet");
    assert_ne!(address_a, address_b);
    vectors.push(Vector {
        case: "wrong_address",
        seed_hex: seed_a.clone(),
        address: address_b,
        public_key: format!("0x{}", hex::encode(pk_a.to_bytes())),
        message: message_a.to_string(),
        signature: sig_a.clone(),
        valid: false,
    });

    // 3. wrong message: same address/publicKey/signature as vector 1, but
    //    `message` changed after signing -- the tree hash (hence the BLS
    //    message) no longer matches what was actually signed.
    vectors.push(Vector {
        case: "wrong_message",
        seed_hex: seed_a.clone(),
        address: address_a.clone(),
        public_key: format!("0x{}", hex::encode(pk_a.to_bytes())),
        message: "Crowd vs Market login\nNonce: TAMPERED\n".to_string(),
        signature: sig_a.clone(),
        valid: false,
    });

    // 4. tampered signature: same address/publicKey/message as vector 1,
    //    signature bytes flipped -- must fail BLS verification.
    let mut tampered_sig_bytes = hex::decode(sig_a.trim_start_matches("0x")).unwrap();
    *tampered_sig_bytes.last_mut().unwrap() ^= 0xFF;
    vectors.push(Vector {
        case: "tampered_signature",
        seed_hex: seed_a.clone(),
        address: address_a.clone(),
        public_key: format!("0x{}", hex::encode(pk_a.to_bytes())),
        message: message_a.to_string(),
        signature: format!("0x{}", hex::encode(tampered_sig_bytes)),
        valid: false,
    });

    // 5. all-hex message edge case (`docs/research/week1_wallet_auth.md`
    //    §2's "trap"): a message string that consists *entirely* of hex
    //    digits. This service always hashes `message` as raw UTF-8 bytes
    //    (`auth.rs` module doc point 3), so as long as the signer (this
    //    generator) does the same, verification must still succeed.
    let (seed_c, sk_c, address_c) = keypair("c2-svc auth vector 5: all-hex message");
    let pk_c = sk_c.public_key();
    let message_c = "deadbeef1234c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2";
    assert!(
        message_c.chars().all(|c| c.is_ascii_hexdigit()),
        "vector 5 must actually be all-hex to test the edge case"
    );
    let sig_c = sign_message(&sk_c, message_c);
    vectors.push(Vector {
        case: "all_hex_message",
        seed_hex: seed_c,
        address: address_c,
        public_key: format!("0x{}", hex::encode(pk_c.to_bytes())),
        message: message_c.to_string(),
        signature: sig_c,
        valid: true,
    });

    let json = serde_json::to_string_pretty(&vectors).unwrap();
    std::fs::write(
        concat!(env!("CARGO_MANIFEST_DIR"), "/tests/vectors/auth_vectors.json"),
        json + "\n",
    )
    .unwrap();
}
