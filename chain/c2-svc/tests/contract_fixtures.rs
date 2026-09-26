//! Task item 5 (contract test Java<->Rust, docs/reviews/2026-09-26-independent-critique.md): the
//! only Java-side tests of the server<->chainsvc boundary used to mock `ChainSvcClient` directly
//! rather than exercising the real HTTP/JSON contract against `c2-svc`'s actual request/response
//! shapes — a silent field-name/type drift on either side would not have been caught by any
//! automated test. This writes one JSON fixture per FIXED INTERFACE (3) DTO, built from c2-svc's
//! own real types (not hand-copied JSON) — where possible via the real production code path
//! (`auth::verify`, replaying `tests/vectors/auth_vectors.json`'s already-committed real BLS
//! material, exactly like `tests/auth_verify.rs` does) rather than a bare struct literal, so the
//! fixture is a genuine wire example, not a guess at one.
//!
//! `server/src/test/java/crowdvsmarket/server/chain/ChainSvcContractFixturesTest.java`
//! deserializes/reserializes these same committed files with the server's own DTOs
//! (`ChainSvcRestClient`'s nested records) — the same "shared golden fixture across two languages"
//! pattern `LiquidationCalculatorGoldenVectorTest`/`liquidation.test.ts` already use for the
//! liquidation math.
//!
//! Run `cargo test -p c2-svc --test contract_fixtures` to (re)write them; commit the result
//! whenever a wire type's shape changes, so the Java side's test catches the drift instead of it
//! only ever being observed live.

use c2_svc::auth::{AuthVerifyRequest, AuthVerifyResponse, verify};
use c2_svc::oracle::types::{
    OracleRecordDto, OracleRecordStatusResponse, OracleRecordsRequest, OracleRecordsResponse, OracleStatusResponse,
    Source,
};
use serde::Deserialize;
use std::fs;
use std::path::PathBuf;

fn contract_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/contract")
}

fn write_fixture(name: &str, value: &impl serde::Serialize) {
    let dir = contract_dir();
    fs::create_dir_all(&dir).expect("create tests/contract dir");
    let json = serde_json::to_string_pretty(value).expect("serialize fixture") + "\n";
    fs::write(dir.join(format!("{name}.json")), json).expect("write fixture file");
}

/// Same vector shape `tests/auth_verify.rs` already deserializes `tests/vectors/auth_vectors.json`
/// into — reused here rather than redeclared differently, so both tests agree on what the file
/// contains.
#[derive(Deserialize)]
struct Vector {
    case: String,
    #[serde(rename = "seed_hex")]
    _seed_hex: String,
    address: String,
    public_key: String,
    message: String,
    signature: String,
    #[serde(rename = "valid")]
    _expected_valid: bool,
}

fn load_vector(case: &str) -> Vector {
    let json = include_str!("vectors/auth_vectors.json");
    let vectors: Vec<Vector> = serde_json::from_str(json).expect("tests/vectors/auth_vectors.json must be valid JSON");
    vectors.into_iter().find(|v| v.case == case).unwrap_or_else(|| panic!("no vector named '{case}'"))
}

#[test]
fn write_contract_fixtures() {
    // --- POST /v1/auth/verify ---
    // "positive": real BLS material, a genuinely valid signature -- verify()'s real production
    // code path, not a hand-built AuthVerifyResponse -- exercises the `reason` field ABSENT
    // (#[serde(skip_serializing_if = "Option::is_none")]) on the Java side.
    let positive = load_vector("positive");
    let positive_request = AuthVerifyRequest {
        address: positive.address,
        public_key: positive.public_key,
        message: positive.message,
        signature: positive.signature,
    };
    let positive_response: AuthVerifyResponse = verify(&positive_request);
    assert!(positive_response.valid, "fixture generation assumes the 'positive' vector is valid");
    write_fixture("AuthVerifyRequest", &positive_request);
    write_fixture("AuthVerifyResponse_valid", &positive_response);

    // "wrong_address": same real production code path, a genuinely invalid case -- exercises the
    // `reason` field PRESENT on the Java side.
    let wrong_address = load_vector("wrong_address");
    let invalid_request = AuthVerifyRequest {
        address: wrong_address.address,
        public_key: wrong_address.public_key,
        message: wrong_address.message,
        signature: wrong_address.signature,
    };
    let invalid_response: AuthVerifyResponse = verify(&invalid_request);
    assert!(!invalid_response.valid, "fixture generation assumes the 'wrong_address' vector is invalid");
    assert!(invalid_response.reason.is_some());
    write_fixture("AuthVerifyResponse_invalid", &invalid_response);

    // --- POST /v1/oracle/records ---
    let record = OracleRecordDto {
        seq: 205,
        ts: 1_790_400_000,
        price_cents: 8_561_200,
        results_hash: format!("0x{}", "ab".repeat(32)),
        source: Source::Live,
        source_ts: 1_790_400_001,
    };
    let records_request = OracleRecordsRequest { records: vec![record] };
    write_fixture("OracleRecordsRequest", &records_request);

    let records_response = OracleRecordsResponse { queued: 1, accepted_seqs: vec![205] };
    write_fixture("OracleRecordsResponse", &records_response);

    // --- GET /v1/oracle/records/{seq} ---
    let record_status = OracleRecordStatusResponse {
        seq: 205,
        state: "confirmed",
        height: Some(4_737_632),
        coin_id: Some(format!("0x{}", "cd".repeat(32))),
    };
    write_fixture("OracleRecordStatusResponse", &record_status);

    // --- GET /v1/oracle/status ---
    let status = OracleStatusResponse {
        launcher_id: "c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20".to_string(),
        network: "testnet11",
        last_confirmed_seq: Some(205),
        last_confirmed_height: Some(4_737_632),
        pending_records: 4,
        in_flight_bundle_id: None,
        operator_balance_mojos: Some("999999999".to_string()),
        stuck_reason: None,
        conflicts: vec![],
    };
    write_fixture("OracleStatusResponse", &status);
}
