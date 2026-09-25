//! Cross-checks `c2_cli::offer_compress` against `chia-sdk-driver-0.36.0`'s
//! own test vectors (`chia-sdk-driver-0.36.0/src/offers/compress.rs`'s
//! `test_data/{compressed,decompressed}.offer`, copied verbatim into
//! `tests/test_data/`): proves our zdict port produces byte-identical
//! output to the upstream (feature-gated, libz-sys-requiring) codec, i.e.
//! this CLI's `offer1...` strings really are Sage/reference-wallet
//! compatible, not just self-consistent.

use c2_cli::offer_compress::{compress_offer_bytes, decompress_offer_bytes};

const COMPRESSED_OFFER: &str = include_str!("test_data/compressed.offer");
const DECOMPRESSED_OFFER: &str = include_str!("test_data/decompressed.offer");

#[test]
fn compresses_to_the_same_bytes_as_upstream() {
    let decompressed = hex::decode(DECOMPRESSED_OFFER.trim()).unwrap();
    let output = compress_offer_bytes(&decompressed).unwrap();
    assert_eq!(hex::encode(output), COMPRESSED_OFFER.trim());
}

#[test]
fn decompresses_the_same_as_upstream() {
    let compressed = hex::decode(COMPRESSED_OFFER.trim()).unwrap();
    let output = decompress_offer_bytes(&compressed).unwrap();
    assert_eq!(hex::encode(output), DECOMPRESSED_OFFER.trim());
}
