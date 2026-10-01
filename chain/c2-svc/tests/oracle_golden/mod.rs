//! Frozen reference data for the oracle announcement format, shared by
//! `tests/oracle_record_format.rs` and `tests/oracle_queue.rs` (Cargo treats
//! `tests/<dir>/mod.rs` as a module of the including test crate, not as a test
//! of its own -- same convention as `tests/play_common/mod.rs`).
//!
//! `SIX_ELEMENT_MESSAGE_HEX` is the serialized CLVM message of
//! `fixed_record()` exactly as the encoder produced it BEFORE record v2
//! (`asset`) existed, recorded on 2026-10-01 from the then-current
//! `spend.rs::build_oracle_publish_batch` and cross-checked with an
//! independent encoder (`chia_rs.Program.to([...])` in Python, same 59
//! bytes). It must never change: every announcement of a BTC record (asset 0)
//! ever published is byte-identical to this shape, and the public verifier
//! (`tools/verify-oracle`) as well as consumers asserting
//! `announcement_id(singleton_puzzle_hash, message)` rely on that.

#![allow(dead_code)]

use chia_protocol::{Bytes, Bytes32, Coin, CoinSpend};
use chia_sdk_driver::SpendContext;

use c2_svc::oracle::types::OracleRecord;

/// `(1145 1790400000 8561200 0xab..ab 0 1790400001)`.
pub const SIX_ELEMENT_MESSAGE_HEX: &str = "ff820479ff846ab75600ff840082a230ffa0abababababababababababababababababababababababababababababababab\
ff80ff846ab7560180";

/// `fixed_record()` with `asset = 3` (record v2): the same list with a trailing
/// `03`, 61 bytes. Independent encoder: `chia_rs.Program.to([..., 3])`.
pub const SEVEN_ELEMENT_ASSET_3_MESSAGE_HEX: &str = "ff820479ff846ab75600ff840082a230ffa0abababababababababababababababababababababababababababababababab\
ff80ff846ab75601ff0380";

/// `fixed_record()` with `asset = 200`: 200 does not fit a signed byte, so the
/// seventh element is the two-byte atom `00c8` (`82 00c8`), 63 bytes.
pub const SEVEN_ELEMENT_ASSET_200_MESSAGE_HEX: &str = "ff820479ff846ab75600ff840082a230ffa0abababababababababababababababababababababababababababababababab\
ff80ff846ab75601ff8200c880";

/// The record `SIX_ELEMENT_MESSAGE_HEX` encodes (a live-source BTC record).
pub fn fixed_record() -> OracleRecord {
    OracleRecord {
        seq: 1145,
        ts: 1_790_400_000,
        price_cents: 8_561_200,
        results_hash: Bytes32::from([0xab; 32]),
        source_flag: 0,
        source_ts: 1_790_400_001,
        asset: 0,
    }
}

/// A standalone puzzle `(q . ((62 <message>)))` and its (nil) solution: the
/// smallest spend that announces exactly `message` via
/// `CREATE_PUZZLE_ANNOUNCEMENT`. `lineage::walk_lineage` runs a spent coin's
/// puzzle against its solution and decodes the announcements it makes -- it
/// never checks the puzzle against the coin's puzzle hash -- so a spend like
/// this lets a test feed the lineage decoder an announcement of arbitrary
/// bytes without going through the production encoder.
pub fn announcing_coin_spend(coin: Coin, message: &[u8]) -> CoinSpend {
    let mut ctx = SpendContext::new();
    let puzzle = ctx.alloc(&(1u8, vec![(62u8, (Bytes::from(message.to_vec()), ()))])).expect("alloc puzzle");
    let solution = ctx.alloc(&()).expect("alloc solution");
    let puzzle_reveal = ctx.serialize(&puzzle).expect("serialize puzzle");
    let solution = ctx.serialize(&solution).expect("serialize solution");
    CoinSpend::new(coin, puzzle_reveal, solution)
}
