//! `POST /v1/auth/verify` (FIXED INTERFACE (3)): verifies a Sage
//! `chia_signMessageByAddress`-shaped signature
//! (`docs/research/week1_wallet_auth.md` §2-4, decision D-4/D-45).
//!
//! ## Scheme (sources: `docs/research/week1_wallet_auth.md` §2, quoting
//! `xch-dev/sage` `crates/sage/src/endpoints/wallet_connect.rs` and
//! `Chia-Network/chia-blockchain` `chia/wallet/util/signing.py`, both read
//! 2026-09-25)
//!
//! 1. `publicKey` is the **synthetic** public key Sage actually signed
//!    with (the same key `StandardLayer`/`StandardArgs::curry_tree_hash`
//!    curries the p2 puzzle with elsewhere in this codebase,
//!    `c2-cli/src/keys.rs` module doc point 4) -- *not* the raw wallet key.
//!    Sage returns it directly from `chia_signMessageByAddress`, so this
//!    service does no further synthetic-key derivation of its own; it only
//!    checks that this exact key's standard p2 puzzle hash equals
//!    `address`'s.
//! 2. The signed message is `sha256tree(("Chia Signed Message" . message))`
//!    (a CLVM cons pair of the fixed prefix atom and the message atom),
//!    verified with the fixed-DST Augmented BLS scheme
//!    (`chia_bls::verify`, `BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_AUG_`).
//! 3. **предложение** (this service's own choice, not from a source): the
//!    `message` field of this HTTP request is always treated as raw UTF-8
//!    bytes, exactly as received over JSON -- never re-interpreted as hex.
//!    This deliberately does *not* replicate Sage's own
//!    hex-vs-utf8 `parse_signature_message` heuristic
//!    (`week1_wallet_auth.md` §2's "ловушка"): the caller that assembles
//!    the login challenge (week 2+ work, not this crate) is responsible for
//!    only ever asking Sage to sign a message that starts with a
//!    non-hex-digit human-readable prefix, so Sage's own heuristic always
//!    takes the UTF-8 branch too, and both sides agree on the bytes. The
//!    `all_hex_message` unit test below documents this: even when `message`
//!    happens to consist only of hex digits, this service still hashes its
//!    UTF-8 bytes, unconditionally -- it is the caller's job to never send
//!    such a message to Sage for signing in the first place.

use chia_bls::{PublicKey, Signature, verify as bls_verify};
use chia_puzzle_types::standard::StandardArgs;
use chia_sdk_utils::Address;
use clvm_utils::{TreeHash, tree_hash_atom, tree_hash_pair};
use serde::{Deserialize, Serialize};

/// Fixed testnet11 address prefix (project constraint: "testnet11 only").
pub const TXCH_PREFIX: &str = "txch";

/// `("Chia Signed Message" . message)` -- CHIP-0002 `chip-0002.md:236`,
/// quoted in `docs/research/week1_wallet_auth.md` §2.
const CHIP_0002_SIGN_MESSAGE_PREFIX: &[u8] = b"Chia Signed Message";

// Serialize is additive (task item 5, contract test Java<->Rust): production code only ever
// deserializes a request off the wire, but tests/contract_fixtures.rs needs to serialize a real,
// constructed instance to write it out as a shared JSON fixture.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthVerifyRequest {
    pub address: String,
    /// 0x-hex, 48 bytes (compressed G1 / BLS public key).
    pub public_key: String,
    /// UTF-8 text (see module doc point 3).
    pub message: String,
    /// 0x-hex, 96 bytes (compressed G2 / BLS signature).
    pub signature: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct AuthVerifyResponse {
    pub valid: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

impl AuthVerifyResponse {
    fn valid() -> Self {
        Self {
            valid: true,
            reason: None,
        }
    }

    fn invalid(reason: impl Into<String>) -> Self {
        Self {
            valid: false,
            reason: Some(reason.into()),
        }
    }
}

/// Strips an optional `0x`/`0X` prefix and decodes exactly `N` bytes of hex.
fn decode_hex_exact<const N: usize>(input: &str, field: &str) -> Result<[u8; N], String> {
    let stripped = input.strip_prefix("0x").or_else(|| input.strip_prefix("0X")).unwrap_or(input);
    let bytes = hex::decode(stripped).map_err(|e| format!("{field}: invalid hex ({e})"))?;
    <[u8; N]>::try_from(bytes.as_slice())
        .map_err(|_| format!("{field}: expected {N} bytes, got {}", bytes.len()))
}

/// The exact tree-hash construction Sage signs (module doc point 2).
pub fn signed_message_tree_hash(message: &[u8]) -> TreeHash {
    tree_hash_pair(tree_hash_atom(CHIP_0002_SIGN_MESSAGE_PREFIX), tree_hash_atom(message))
}

/// Core verification logic, independent of the HTTP layer so it can be unit
/// tested directly against `tests/vectors/auth_vectors.json` (task
/// instruction) without spinning up a server.
pub fn verify(req: &AuthVerifyRequest) -> AuthVerifyResponse {
    let public_key_bytes: [u8; 48] = match decode_hex_exact(&req.public_key, "publicKey") {
        Ok(b) => b,
        Err(reason) => return AuthVerifyResponse::invalid(reason),
    };
    let public_key = match PublicKey::from_bytes(&public_key_bytes) {
        Ok(pk) => pk,
        Err(e) => return AuthVerifyResponse::invalid(format!("publicKey: not a valid BLS point ({e})")),
    };

    let signature_bytes: [u8; 96] = match decode_hex_exact(&req.signature, "signature") {
        Ok(b) => b,
        Err(reason) => return AuthVerifyResponse::invalid(reason),
    };
    let signature = match Signature::from_bytes(&signature_bytes) {
        Ok(sig) => sig,
        Err(e) => return AuthVerifyResponse::invalid(format!("signature: not a valid BLS signature ({e})")),
    };

    let address = match Address::decode(&req.address) {
        Ok(a) => a,
        Err(e) => return AuthVerifyResponse::invalid(format!("address: invalid bech32m ({e})")),
    };
    let address_puzzle_hash = match address.expect_prefix(TXCH_PREFIX) {
        Ok(ph) => ph,
        Err(_) => {
            return AuthVerifyResponse::invalid(format!(
                "address: expected a {TXCH_PREFIX}1... (testnet11) address"
            ));
        }
    };

    let derived_puzzle_hash: chia_protocol::Bytes32 = StandardArgs::curry_tree_hash(public_key).into();
    if derived_puzzle_hash != address_puzzle_hash {
        return AuthVerifyResponse::invalid(
            "publicKey does not map to address via the standard synthetic-key p2 puzzle",
        );
    }

    let message_hash = signed_message_tree_hash(req.message.as_bytes());
    if !bls_verify(&signature, &public_key, message_hash.as_ref()) {
        return AuthVerifyResponse::invalid("signature does not verify against publicKey/message");
    }

    AuthVerifyResponse::valid()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A message consisting entirely of hex digits must still be hashed as
    /// its raw UTF-8 bytes (module doc point 3) -- this pins that behavior
    /// down so a future change can't silently reintroduce Sage's own
    /// hex/utf8 ambiguity on the server side.
    #[test]
    fn all_hex_message_is_hashed_as_utf8_not_decoded() {
        let all_hex = "deadbeef";
        let as_utf8_hash = signed_message_tree_hash(all_hex.as_bytes());
        let decoded = hex::decode(all_hex).unwrap();
        let as_hex_decoded_hash = signed_message_tree_hash(&decoded);
        assert_ne!(
            as_utf8_hash, as_hex_decoded_hash,
            "an all-hex message must hash differently depending on interpretation, \
             otherwise this test can't tell them apart"
        );
        // `verify()` must use the UTF-8 interpretation exclusively; asserted
        // end-to-end via the `all_hex_message` vector in
        // `tests/vectors/auth_vectors.json` (tests/auth_verify.rs).
    }
}
