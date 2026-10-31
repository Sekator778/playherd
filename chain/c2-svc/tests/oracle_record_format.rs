//! The oracle record's on-wire, on-disk and on-chain formats. Written as
//! characterization tests FIRST (2026-10-01, before record v2 added the
//! `asset` field): they pin down what today's code does so the v2 change can
//! be proven additive -- a record with asset 0 must stay byte-identical to
//! what was published before.

mod oracle_golden;

use anyhow::Result;
use chia_protocol::{Bytes32, Coin};
use clvm_traits::clvm_list;
use clvmr::{Allocator, SExp, serde::node_from_bytes};

use c2_cli::builders::oracle::build_oracle_launch;
use c2_cli::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_svc::oracle::lineage::decode_oracle_record;
use c2_svc::oracle::queue::QueueFile;
use c2_svc::oracle::spend::build_oracle_publish_batch;
use c2_svc::oracle::types::{OracleRecord, OracleRecordDto, Source};

use oracle_golden::{
    SEVEN_ELEMENT_ASSET_3_MESSAGE_HEX, SEVEN_ELEMENT_ASSET_200_MESSAGE_HEX, SIX_ELEMENT_MESSAGE_HEX, fixed_record,
};

/// Announcement messages (hex) the real builder produces for `records`, inside
/// a real singleton spend built against a freshly launched (offline) oracle.
fn announced_messages(records: &[OracleRecord]) -> Result<Vec<String>> {
    let manager = derive_wallet_key(&master_sk_from_mnemonic(&generate_mnemonic()?), 0);
    let funding_coin = Coin::new([9u8; 32].into(), manager.puzzle_hash, 1);
    let launch = build_oracle_launch(funding_coin, &manager, 0)?;
    let result = build_oracle_publish_batch(launch.launcher_id, launch.eve_coin, launch.eve_proof, &manager, records)?;
    Ok(result.announcement_messages.iter().map(|m| hex::encode(m.as_ref())).collect())
}

fn with_asset(asset: u32) -> OracleRecord {
    OracleRecord { asset, ..fixed_record() }
}

/// Serialized CLVM bytes of an arbitrary list shape -- for feeding the decoder
/// messages the production encoder never produces.
fn clvm_bytes(value: &impl clvm_traits::ToClvm<Allocator>) -> Vec<u8> {
    let mut allocator = Allocator::new();
    let ptr = value.to_clvm(&mut allocator).expect("to_clvm");
    clvmr::serde::node_to_bytes(&allocator, ptr).expect("serialize")
}

fn element_count(message: &[u8]) -> usize {
    let mut allocator = Allocator::new();
    let mut node = node_from_bytes(&mut allocator, message).expect("valid CLVM");
    let mut count = 0;
    while let SExp::Pair(_, rest) = allocator.sexp(node) {
        count += 1;
        node = rest;
    }
    count
}

/// (a) Golden bytes: the announcement message of a fixed record, produced by
/// the real builder inside a real singleton spend, is exactly the 6-element
/// CLVM list the chain history already contains.
#[test]
fn announcement_message_of_a_fixed_record_is_byte_identical_to_the_pre_v2_encoding() -> Result<()> {
    let manager = derive_wallet_key(&master_sk_from_mnemonic(&generate_mnemonic()?), 0);
    let funding_coin = Coin::new([9u8; 32].into(), manager.puzzle_hash, 1);
    let launch = build_oracle_launch(funding_coin, &manager, 0)?;

    let result =
        build_oracle_publish_batch(launch.launcher_id, launch.eve_coin, launch.eve_proof, &manager, &[fixed_record()])?;

    assert_eq!(result.announcement_messages.len(), 1);
    assert_eq!(hex::encode(result.announcement_messages[0].as_ref()), SIX_ELEMENT_MESSAGE_HEX);
    assert_eq!(result.announcement_messages[0].as_ref().len(), 59, "59 bytes at seq 1145 (oracle_path.md §1.2)");
    Ok(())
}

/// (b) A queue file written in today's format -- records carry no `asset`
/// field anywhere: `pending`, the in-flight spend's `records` and both sides
/// of a `conflicts` entry -- must keep loading, or every `enqueue`, `tick`,
/// `status` and `health_check` would fail right after the upgrade
/// (`QueueFile::load` is all-or-nothing, `atomic_file::read_json_or_default`).
/// `tests/fixtures/oracle_queue_pre_asset.json` was written by the
/// pre-v2 code itself (`QueueFile::save_atomic`, real spend bundle inside), not
/// hand-edited.
#[test]
fn queue_file_written_before_the_asset_field_still_loads() -> Result<()> {
    let dir = tempfile::tempdir()?;
    std::fs::copy(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/oracle_queue_pre_asset.json"),
        QueueFile::path(dir.path(), "operator"),
    )?;

    let queue = QueueFile::load(dir.path(), "operator")?;

    assert_eq!(queue.pending.iter().map(|r| r.seq).collect::<Vec<_>>(), vec![13, 14]);
    let in_flight = queue.in_flight.as_ref().expect("fixture has an in-flight spend");
    assert_eq!(in_flight.records.iter().map(|r| r.seq).collect::<Vec<_>>(), vec![11, 12]);
    assert_eq!(in_flight.bundle.coin_spends.len(), 2, "singleton spend + fee-only spend");
    assert_eq!(queue.confirmed.keys().copied().collect::<Vec<_>>(), vec![9, 10]);
    let conflict = queue.conflicts.get(&8).expect("fixture has a conflict");
    assert_ne!(conflict.local, conflict.chain);
    assert_eq!(queue.last_accepted_seq, Some(14));
    assert_eq!(queue.last_confirmed_seq, Some(10));
    assert!(queue.chain_scan_checkpoint.is_some());
    Ok(())
}

// --- record v2 (`asset`) --------------------------------------------------------

/// The core compatibility promise of v2: asset 0 (BTC) gains no trailing
/// element -- the 6-element bytes of the characterization test above -- while a
/// non-zero asset appends exactly one element. 200 does not fit a signed byte
/// and must use the two-byte form (`00c8`), the way every CLVM integer does.
#[test]
fn a_record_with_asset_zero_announces_six_elements_and_a_nonzero_asset_announces_seven() -> Result<()> {
    let messages = announced_messages(&[with_asset(0), with_asset(3), with_asset(200)])?;

    assert_eq!(messages[0], SIX_ELEMENT_MESSAGE_HEX, "asset 0 must stay byte-identical to the pre-v2 encoding");
    assert_eq!(messages[1], SEVEN_ELEMENT_ASSET_3_MESSAGE_HEX);
    assert_eq!(messages[2], SEVEN_ELEMENT_ASSET_200_MESSAGE_HEX);
    for (message, expected) in messages.iter().zip([6, 7, 7]) {
        assert_eq!(element_count(&hex::decode(message)?), expected);
    }
    Ok(())
}

/// Lineage decoder: both shapes, `asset` defaulting to 0 for the old one.
#[test]
fn the_lineage_decoder_reads_both_the_six_and_the_seven_element_shapes() -> Result<()> {
    assert_eq!(decode_oracle_record(&hex::decode(SIX_ELEMENT_MESSAGE_HEX)?)?, with_asset(0));
    assert_eq!(decode_oracle_record(&hex::decode(SEVEN_ELEMENT_ASSET_3_MESSAGE_HEX)?)?, with_asset(3));
    assert_eq!(decode_oracle_record(&hex::decode(SEVEN_ELEMENT_ASSET_200_MESSAGE_HEX)?)?, with_asset(200));

    // An explicit asset 0 in seventh position (never produced by this encoder,
    // but a legal reading) is the same record as the 6-element form.
    let explicit_zero = clvm_bytes(&clvm_list!(1145i64, 1_790_400_000i64, 8_561_200i64, Bytes32::from([0xab; 32]), 0i64, 1_790_400_001i64, 0i64));
    assert_eq!(decode_oracle_record(&explicit_zero)?, with_asset(0));
    Ok(())
}

/// What the decoder must NOT accept as a record. A short list is not ours (the
/// week-1 3-element message); an asset that is not a valid u32 must be an error,
/// never a wrapped value that silently names another market.
#[test]
fn the_lineage_decoder_rejects_what_is_not_a_record() {
    let hash = Bytes32::from([0xab; 32]);
    let three_field = clvm_bytes(&clvm_list!(8_396_500i64, 1_790_353_493i64, 1i64));
    let five_field = clvm_bytes(&clvm_list!(1145i64, 1_790_400_000i64, 8_561_200i64, hash, 0i64));
    let negative_asset = clvm_bytes(&clvm_list!(1145i64, 1_790_400_000i64, 8_561_200i64, hash, 0i64, 1_790_400_001i64, -1i64));
    let oversized_asset =
        clvm_bytes(&clvm_list!(1145i64, 1_790_400_000i64, 8_561_200i64, hash, 0i64, 1_790_400_001i64, 4_294_967_296i64));
    let negative_seq = clvm_bytes(&clvm_list!(-5i64, 1_790_400_000i64, 8_561_200i64, hash, 0i64, 1_790_400_001i64));

    for (name, message) in [
        ("3 elements", three_field),
        ("5 elements", five_field),
        ("negative asset", negative_asset),
        ("asset above u32::MAX", oversized_asset),
        ("negative seq", negative_seq),
    ] {
        assert!(decode_oracle_record(&message).is_err(), "{name} must not decode as a record");
    }
}

/// Pins the derive's documented "extras are ignored" behavior: a future format
/// that appends an eighth element stays readable by v2 decoders (they read the
/// first seven). Not a feature of v2 itself -- a tripwire for a clvm-derive
/// upgrade that changes it.
#[test]
fn an_eighth_element_is_ignored_by_the_lineage_decoder() -> Result<()> {
    let eight = clvm_bytes(&clvm_list!(
        1145i64,
        1_790_400_000i64,
        8_561_200i64,
        Bytes32::from([0xab; 32]),
        0i64,
        1_790_400_001i64,
        3i64,
        99i64
    ));
    assert_eq!(decode_oracle_record(&eight)?, with_asset(3));
    Ok(())
}

/// Wire: a record from an older server has no `asset` key (-> 0 = BTC), a v2
/// record carries it through `from_dto` into the queue record, and the DTO
/// always writes the key back out (the contract fixture shows it).
#[test]
fn the_wire_dto_defaults_a_missing_asset_to_zero_and_carries_a_present_one() -> Result<()> {
    let without = r#"{"seq":7,"ts":1,"priceCents":2,"resultsHash":"0x0707070707070707070707070707070707070707070707070707070707070707","source":"live","sourceTs":3}"#;
    let dto: OracleRecordDto = serde_json::from_str(without)?;
    assert_eq!(dto.asset, 0);
    assert_eq!(OracleRecord::from_dto(&dto).map_err(anyhow::Error::msg)?.asset, 0);

    let with = without.replace(r#""sourceTs":3"#, r#""sourceTs":3,"asset":9"#);
    let dto: OracleRecordDto = serde_json::from_str(&with)?;
    assert_eq!(dto.asset, 9);
    assert_eq!(OracleRecord::from_dto(&dto).map_err(anyhow::Error::msg)?.asset, 9);
    assert_eq!(serde_json::to_value(&dto)?["asset"], 9);
    assert_eq!(dto.source, Source::Live);
    Ok(())
}

/// The queue file keeps `asset` across a save/load (a non-zero asset record
/// must never come back as BTC after a restart), and a pre-v2 file loads with
/// every record at asset 0.
#[test]
fn the_queue_file_keeps_asset_and_old_files_load_as_asset_zero() -> Result<()> {
    let dir = tempfile::tempdir()?;
    let queue = QueueFile {
        pending: vec![with_asset(0), OracleRecord { seq: 1146, ..with_asset(3) }],
        ..QueueFile::default()
    };
    queue.save_atomic(dir.path(), "operator")?;
    let loaded = QueueFile::load(dir.path(), "operator")?;
    assert_eq!(loaded.pending.iter().map(|r| r.asset).collect::<Vec<_>>(), vec![0, 3]);

    let old = tempfile::tempdir()?;
    std::fs::copy(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/oracle_queue_pre_asset.json"),
        QueueFile::path(old.path(), "operator"),
    )?;
    let old = QueueFile::load(old.path(), "operator")?;
    let every_record = old
        .pending
        .iter()
        .chain(old.in_flight.iter().flat_map(|f| f.records.iter()))
        .chain(old.conflicts.values().flat_map(|c| [&c.local, &c.chain]));
    assert!(every_record.clone().count() >= 6, "fixture must exercise pending, in_flight and conflicts");
    assert!(every_record.into_iter().all(|r| r.asset == 0), "a pre-v2 queue file reads as all-BTC");
    Ok(())
}
