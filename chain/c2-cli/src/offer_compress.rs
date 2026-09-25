//! Offer bech32 encode/decode (`offer1...`).
//!
//! A byte-exact port of `chia-sdk-driver-0.36.0/src/offers/compress.rs`
//! (upstream gates this behind that crate's `offer-compression` feature,
//! which forces `flate2/zlib-ng-compat` -> `libz-sys`, a C library binding).
//! Same zdict, same wire format (2-byte big-endian version prefix + a
//! zlib stream seeded with a dictionary of common puzzle reveals), same
//! bech32 "offer" hrp -- so `offer1...` strings from this CLI decompress
//! with, and are byte-compatible with, the reference wallet / Sage. Only
//! the backend differs: our `flate2` dependency uses the `zlib-rs` feature
//! (a pure-Rust zlib reimplementation with preset-dictionary support, see
//! `Cargo.toml`) instead of a C zlib, and errors are `anyhow` instead of
//! upstream's `DriverError`.

use std::io::Read;
use std::sync::LazyLock;

use anyhow::{Result, bail};
use chia_protocol::SpendBundle;
use chia_puzzles::{
    CAT_PUZZLE, NFT_METADATA_UPDATER_DEFAULT, NFT_OWNERSHIP_LAYER,
    NFT_OWNERSHIP_TRANSFER_PROGRAM_ONE_WAY_CLAIM_WITH_ROYALTIES, NFT_STATE_LAYER,
    P2_DELEGATED_PUZZLE_OR_HIDDEN_PUZZLE, SETTLEMENT_PAYMENT, SINGLETON_TOP_LAYER_V1_1,
};
use chia_sdk_utils::Bech32;
use chia_traits::Streamable;
use flate2::{
    Compress, Compression, Decompress, FlushDecompress,
    read::{ZlibDecoder, ZlibEncoder},
};
use hex_literal::hex;

pub fn encode_offer(spend_bundle: &SpendBundle) -> Result<String> {
    let compressed = compress_offer_bytes(&spend_bundle.to_bytes()?)?;
    Ok(Bech32::new(compressed.into(), "offer".to_string()).encode()?)
}

pub fn decode_offer(text: &str) -> Result<SpendBundle> {
    let bytes = decompress_offer_bytes(&Bech32::decode(text)?.expect_prefix("offer")?)?;
    Ok(SpendBundle::from_bytes(&bytes)?)
}

// --- legacy puzzle reveals used only to seed the zlib dictionary ----------
// (verbatim from chia-sdk-driver-0.36.0/src/offers/compress.rs)

const CAT_PUZZLE_V1: [u8; 1420] = hex!(
    "
    ff02ffff01ff02ff5effff04ff02ffff04ffff04ff05ffff04ffff0bff2cff05
    80ffff04ff0bff80808080ffff04ffff02ff17ff2f80ffff04ff5fffff04ffff
    02ff2effff04ff02ffff04ff17ff80808080ffff04ffff0bff82027fff82057f
    ff820b7f80ffff04ff81bfffff04ff82017fffff04ff8202ffffff04ff8205ff
    ffff04ff820bffff80808080808080808080808080ffff04ffff01ffffffff81
    ca3dff46ff0233ffff3c04ff01ff0181cbffffff02ff02ffff03ff05ffff01ff
    02ff32ffff04ff02ffff04ff0dffff04ffff0bff22ffff0bff2cff3480ffff0b
    ff22ffff0bff22ffff0bff2cff5c80ff0980ffff0bff22ff0bffff0bff2cff80
    80808080ff8080808080ffff010b80ff0180ffff02ffff03ff0bffff01ff02ff
    ff03ffff09ffff02ff2effff04ff02ffff04ff13ff80808080ff820b9f80ffff
    01ff02ff26ffff04ff02ffff04ffff02ff13ffff04ff5fffff04ff17ffff04ff
    2fffff04ff81bfffff04ff82017fffff04ff1bff8080808080808080ffff04ff
    82017fff8080808080ffff01ff088080ff0180ffff01ff02ffff03ff17ffff01
    ff02ffff03ffff20ff81bf80ffff0182017fffff01ff088080ff0180ffff01ff
    088080ff018080ff0180ffff04ffff04ff05ff2780ffff04ffff10ff0bff5780
    ff778080ff02ffff03ff05ffff01ff02ffff03ffff09ffff02ffff03ffff09ff
    11ff7880ffff0159ff8080ff0180ffff01818f80ffff01ff02ff7affff04ff02
    ffff04ff0dffff04ff0bffff04ffff04ff81b9ff82017980ff808080808080ff
    ff01ff02ff5affff04ff02ffff04ffff02ffff03ffff09ff11ff7880ffff01ff
    04ff78ffff04ffff02ff36ffff04ff02ffff04ff13ffff04ff29ffff04ffff0b
    ff2cff5b80ffff04ff2bff80808080808080ff398080ffff01ff02ffff03ffff
    09ff11ff2480ffff01ff04ff24ffff04ffff0bff20ff2980ff398080ffff0109
    80ff018080ff0180ffff04ffff02ffff03ffff09ff11ff7880ffff0159ff8080
    ff0180ffff04ffff02ff7affff04ff02ffff04ff0dffff04ff0bffff04ff17ff
    808080808080ff80808080808080ff0180ffff01ff04ff80ffff04ff80ff1780
    8080ff0180ffffff02ffff03ff05ffff01ff04ff09ffff02ff26ffff04ff02ff
    ff04ff0dffff04ff0bff808080808080ffff010b80ff0180ff0bff22ffff0bff
    2cff5880ffff0bff22ffff0bff22ffff0bff2cff5c80ff0580ffff0bff22ffff
    02ff32ffff04ff02ffff04ff07ffff04ffff0bff2cff2c80ff8080808080ffff
    0bff2cff8080808080ffff02ffff03ffff07ff0580ffff01ff0bffff0102ffff
    02ff2effff04ff02ffff04ff09ff80808080ffff02ff2effff04ff02ffff04ff
    0dff8080808080ffff01ff0bff2cff058080ff0180ffff04ffff04ff28ffff04
    ff5fff808080ffff02ff7effff04ff02ffff04ffff04ffff04ff2fff0580ffff
    04ff5fff82017f8080ffff04ffff02ff7affff04ff02ffff04ff0bffff04ff05
    ffff01ff808080808080ffff04ff17ffff04ff81bfffff04ff82017fffff04ff
    ff0bff8204ffffff02ff36ffff04ff02ffff04ff09ffff04ff820affffff04ff
    ff0bff2cff2d80ffff04ff15ff80808080808080ff8216ff80ffff04ff8205ff
    ffff04ff820bffff808080808080808080808080ff02ff2affff04ff02ffff04
    ff5fffff04ff3bffff04ffff02ffff03ff17ffff01ff09ff2dffff0bff27ffff
    02ff36ffff04ff02ffff04ff29ffff04ff57ffff04ffff0bff2cff81b980ffff
    04ff59ff80808080808080ff81b78080ff8080ff0180ffff04ff17ffff04ff05
    ffff04ff8202ffffff04ffff04ffff04ff24ffff04ffff0bff7cff2fff82017f
    80ff808080ffff04ffff04ff30ffff04ffff0bff81bfffff0bff7cff15ffff10
    ff82017fffff11ff8202dfff2b80ff8202ff808080ff808080ff138080ff8080
    8080808080808080ff018080
    "
);

const SETTLEMENT_PAYMENT_V1: [u8; 267] = hex!(
    "
    ff02ffff01ff02ff0affff04ff02ffff04ff03ff80808080ffff04ffff01ffff
    333effff02ffff03ff05ffff01ff04ffff04ff0cffff04ffff02ff1effff04ff
    02ffff04ff09ff80808080ff808080ffff02ff16ffff04ff02ffff04ff19ffff
    04ffff02ff0affff04ff02ffff04ff0dff80808080ff808080808080ff8080ff
    0180ffff02ffff03ff05ffff01ff04ffff04ff08ff0980ffff02ff16ffff04ff
    02ffff04ff0dffff04ff0bff808080808080ffff010b80ff0180ff02ffff03ff
    ff07ff0580ffff01ff0bffff0102ffff02ff1effff04ff02ffff04ff09ff8080
    8080ffff02ff1effff04ff02ffff04ff0dff8080808080ffff01ff0bffff0101
    ff058080ff0180ff018080
    "
);

static COMPRESSION_ZDICT: LazyLock<Vec<u8>> = LazyLock::new(|| {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(&P2_DELEGATED_PUZZLE_OR_HIDDEN_PUZZLE);
    bytes.extend_from_slice(&CAT_PUZZLE_V1);
    bytes.extend_from_slice(&SETTLEMENT_PAYMENT_V1);
    bytes.extend_from_slice(&SINGLETON_TOP_LAYER_V1_1);
    bytes.extend_from_slice(&NFT_STATE_LAYER);
    bytes.extend_from_slice(&NFT_OWNERSHIP_LAYER);
    bytes.extend_from_slice(&NFT_METADATA_UPDATER_DEFAULT);
    bytes.extend_from_slice(&NFT_OWNERSHIP_TRANSFER_PROGRAM_ONE_WAY_CLAIM_WITH_ROYALTIES);
    bytes.extend_from_slice(&CAT_PUZZLE);
    bytes.extend_from_slice(&SETTLEMENT_PAYMENT);
    bytes
});

pub fn compress_offer_bytes(bytes: &[u8]) -> Result<Vec<u8>> {
    let mut output = 6u16.to_be_bytes().to_vec();
    output.extend(zlib_compress(bytes, &COMPRESSION_ZDICT)?);
    Ok(output)
}

pub fn decompress_offer_bytes(bytes: &[u8]) -> Result<Vec<u8>> {
    let Some(version_bytes) = bytes.get(0..2) else {
        bail!("offer text is missing its 2-byte version prefix");
    };
    let version = u16::from_be_bytes(version_bytes.try_into()?);
    if version > 6 {
        bail!("offer text has an unsupported compression version ({version})");
    }
    zlib_decompress(&bytes[2..], &COMPRESSION_ZDICT)
}

fn zlib_compress(input: &[u8], zdict: &[u8]) -> Result<Vec<u8>> {
    let mut compress = Compress::new(Compression::new(6), true);
    compress.set_dictionary(zdict)?;
    let mut encoder = ZlibEncoder::new_with_compress(input, compress);
    let mut output = Vec::new();
    encoder.read_to_end(&mut output)?;
    Ok(output)
}

// 6 MB matches the limit chia-blockchain's own offer decoder uses
// (chia-blockchain chia/wallet/util/puzzle_compression.py).
const MAX_DECOMPRESSED_SIZE: usize = 6 * 1024 * 1024;

fn zlib_decompress(input: &[u8], zdict: &[u8]) -> Result<Vec<u8>> {
    let mut decompress = Decompress::new(true);

    if decompress
        .decompress(input, &mut [], FlushDecompress::Finish)
        .is_ok()
    {
        bail!("offer text is not zlib-compressed");
    }

    decompress.set_dictionary(zdict)?;
    let i = decompress.total_in();
    let mut decoder = ZlibDecoder::new_with_decompress(&input[usize::try_from(i)?..], decompress);
    let mut output = Vec::new();
    Read::by_ref(&mut decoder)
        .take(MAX_DECOMPRESSED_SIZE as u64)
        .read_to_end(&mut output)?;

    if decoder.read(&mut [0u8])? > 0 {
        bail!("decompressed offer exceeds {MAX_DECOMPRESSED_SIZE} bytes");
    }

    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_arbitrary_bytes_through_the_zdict_codec() {
        let data = b"not a real spend bundle, just exercising zlib_compress/decompress".to_vec();
        let compressed = zlib_compress(&data, &COMPRESSION_ZDICT).unwrap();
        let decompressed = zlib_decompress(&compressed, &COMPRESSION_ZDICT).unwrap();
        assert_eq!(decompressed, data);
    }
}
