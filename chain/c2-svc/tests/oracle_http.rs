//! `POST /v1/oracle/records` and `GET /v1/oracle/records/{seq}` over the real router (`app::router`, auth
//! middleware included), against a fake chain: what the server actually receives when a record of its collides
//! with one this service holds (`docs/research/chain_service.md` §18.2).
//!
//! The answers are the shared contract fixtures `tests/contract/OracleRecordsResponse.json`,
//! `OracleRecordStatusResponse.json` and `OracleRecordStatusResponse_without_content.json` (written by
//! `tests/contract_fixtures.rs` from the same types the handlers serialize; the Java server reads the same
//! files): the tests compare the handlers' real answers with the committed files, so a fixture cannot drift
//! from what is on the wire.

mod play_common;

use std::sync::Arc;

use anyhow::Result;
use axum::body::{Body, to_bytes};
use axum::http::{Request, StatusCode};
use serde_json::{Value, json};
use tower::ServiceExt;

use c2_svc::app;
use c2_svc::fee_allocator::FeeCoinAllocator;
use c2_svc::oracle::publisher::{Publisher, TickFailureState};
use c2_svc::oracle::queue::{ConfirmedRecordInfo, QueueFile};
use c2_svc::oracle::types::{OracleRecord, OracleRecordsRequest};
use c2_svc::play::deposit::DepositService;
use c2_svc::play::withdrawal_publisher::WithdrawalPublisher;

use play_common::FakePlayChainClient;

const TOKEN: &str = "test-token";

fn router(data_dir: &std::path::Path) -> axum::Router {
    let fee_allocator = Arc::new(FeeCoinAllocator::new());
    let oracle = Arc::new(Publisher {
        data_dir: data_dir.to_path_buf(),
        operator_name: "operator".to_string(),
        push_enabled: true,
        chain: FakePlayChainClient::new(),
        retry_backoff: std::time::Duration::from_secs(45),
        max_retry_attempts: 8,
        max_fee_mojos: 50_000_000,
        fee_target_time_secs: 60,
        mempool_replace_after: std::time::Duration::from_secs(180),
        balance_snapshot: tokio::sync::RwLock::new(None),
        queue_lock: tokio::sync::Mutex::new(()),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: 3,
        fee_allocator: fee_allocator.clone(),
    });
    let withdrawals = Arc::new(WithdrawalPublisher {
        data_dir: data_dir.to_path_buf(),
        operator_name: "operator".to_string(),
        push_enabled: true,
        chain: FakePlayChainClient::new(),
        retry_backoff: std::time::Duration::from_secs(45),
        max_retry_attempts: 8,
        max_fee_mojos: 50_000_000,
        fee_target_time_secs: 60,
        mempool_replace_after: std::time::Duration::from_secs(180),
        queue_lock: tokio::sync::Mutex::new(()),
        treasury_stuck: tokio::sync::RwLock::new(None),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: 3,
        fee_allocator,
    });
    let deposits = Arc::new(DepositService {
        data_dir: data_dir.to_path_buf(),
        operator_name: "operator".to_string(),
        chain: FakePlayChainClient::new(),
        registry_lock: tokio::sync::Mutex::new(()),
    });
    app::router(oracle, withdrawals, deposits, Some(TOKEN.to_string()))
}

fn fixture(name: &str) -> Value {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(format!("tests/contract/{name}.json"));
    serde_json::from_str(&std::fs::read_to_string(path).expect("fixture")).expect("fixture is JSON")
}

async fn send(router: &axum::Router, request: Request<Body>) -> (StatusCode, Value) {
    let response = router.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let bytes = to_bytes(response.into_body(), 1 << 20).await.unwrap();
    (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
}

async fn post_records(router: &axum::Router, body: Value) -> (StatusCode, Value) {
    send(
        router,
        Request::builder()
            .method("POST")
            .uri("/v1/oracle/records")
            .header("authorization", format!("Bearer {TOKEN}"))
            .header("content-type", "application/json")
            .body(Body::from(body.to_string()))
            .unwrap(),
    )
    .await
}

async fn get_record(router: &axum::Router, seq: u64) -> (StatusCode, Value) {
    send(
        router,
        Request::builder()
            .method("GET")
            .uri(format!("/v1/oracle/records/{seq}"))
            .header("authorization", format!("Bearer {TOKEN}"))
            .body(Body::empty())
            .unwrap(),
    )
    .await
}

/// The record of the request fixture (seq 205, asset 3), as the wire carries it.
fn fixture_record() -> Value {
    fixture("OracleRecordsRequest")["records"][0].clone()
}

/// The same record under another `seq`, and with one field changed.
fn with_seq(record: &Value, seq: u64) -> Value {
    let mut copy = record.clone();
    copy["seq"] = json!(seq);
    copy
}

fn with_field(record: &Value, field: &str, value: Value) -> Value {
    let mut copy = record.clone();
    copy[field] = value;
    copy
}

/// The server sends 204 and 205; the service already holds 204 with other content. The answer is 202 with 205
/// accepted and 204 in `conflictSeqs` -- the body of the contract fixture -- and nothing about 204 was queued.
/// A resend of what the service holds is silent; a request without a collision has an empty `conflictSeqs`.
#[tokio::test]
async fn the_http_answer_to_a_colliding_record_names_it_in_conflict_seqs_as_in_the_contract_fixture() -> Result<()> {
    let tmp = tempfile::tempdir()?;
    let router = router(tmp.path());
    let mine_204 = with_seq(&fixture_record(), 204);
    let foreign_204 = with_field(&mine_204, "priceCents", json!(1));

    // 204 is held (queued by an earlier request): the first answer has nothing to report.
    let (status, body) = post_records(&router, json!({"records": [foreign_204]})).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    assert_eq!(body, json!({"queued": 1, "acceptedSeqs": [204], "conflictSeqs": []}), "{body}");

    // The server's own record for 204 is not what the service holds; 205 is new.
    let (status, body) = post_records(&router, json!({"records": [mine_204, fixture_record()]})).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    assert_eq!(body, fixture("OracleRecordsResponse"), "the body on the wire is the committed contract fixture");
    assert_eq!(body["conflictSeqs"], json!([204]));

    // Nothing of the colliding record was queued: 204 still answers with the content that is held.
    let (_, held) = get_record(&router, 204).await;
    assert_eq!((held["state"].as_str(), held["priceCents"].as_i64()), (Some("queued"), Some(1)));
    let queue = QueueFile::load(tmp.path(), "operator")?;
    assert_eq!(queue.pending.iter().map(|r| r.seq).collect::<Vec<_>>(), vec![204, 205]);

    // A repeat of what is held, in a request of its own: 202, nothing accepted, nothing reported.
    let (status, body) = post_records(&router, json!({"records": [fixture_record()]})).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    assert_eq!(body, json!({"queued": 0, "acceptedSeqs": [], "conflictSeqs": []}));

    // The other refusals keep their shape.
    let (status, body) = post_records(&router, json!({"records": []})).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert!(body["error"].is_string() && body.get("conflictSeqs").is_none(), "{body}");
    Ok(())
}

/// `GET /v1/oracle/records/{seq}`: the content of the held record -- the body of the contract fixture for a
/// confirmed record, `null` content (the other fixture) for an entry confirmed before the content was kept, and
/// the content of a queued record too.
#[tokio::test]
async fn the_http_answer_for_a_held_record_carries_its_content_as_in_the_contract_fixtures() -> Result<()> {
    let tmp = tempfile::tempdir()?;
    let router = router(tmp.path());
    let request: OracleRecordsRequest = serde_json::from_value(fixture("OracleRecordsRequest"))?;
    let held = OracleRecord::from_dto(&request.records[0]).map_err(anyhow::Error::msg)?;
    let coin_id = format!("0x{}", "cd".repeat(32));

    // Confirmed, with the content: the first fixture.
    let mut queue = QueueFile::default();
    queue.confirmed.insert(
        205,
        ConfirmedRecordInfo { height: Some(4_737_632), coin_id: coin_id.clone(), record: Some(held) },
    );
    // Confirmed before the content was kept: the second fixture, every content field `null` and present.
    queue.confirmed.insert(
        105,
        ConfirmedRecordInfo { height: Some(4_737_632), coin_id: coin_id.clone(), record: None },
    );
    queue.save_atomic(tmp.path(), "operator")?;

    let (status, body) = get_record(&router, 205).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, fixture("OracleRecordStatusResponse"), "the body on the wire is the committed contract fixture");
    assert_eq!(body["resultsHash"], json!(format!("0x{}", "ab".repeat(32))));
    assert_eq!((body["priceCents"].as_i64(), body["asset"].as_u64(), body["ts"].as_i64()), (Some(8_561_200), Some(3), Some(1_790_400_000)));

    let (status, body) = get_record(&router, 105).await;
    assert_eq!(status, StatusCode::OK);
    let mut expected = fixture("OracleRecordStatusResponse_without_content");
    expected["seq"] = json!(105);
    assert_eq!(body, expected, "an entry without content answers as the committed contract fixture says");
    for field in ["resultsHash", "priceCents", "asset", "ts"] {
        assert_eq!(body.get(field), Some(&Value::Null), "{field} is present and null, not absent and not zero: {body}");
    }

    // A queued record answers with its content; an unknown seq with nothing.
    let (status, _) = post_records(&router, json!({"records": [with_seq(&fixture_record(), 300)]})).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    let (_, queued) = get_record(&router, 300).await;
    assert_eq!(queued["state"], "queued");
    assert_eq!(
        (queued["resultsHash"].clone(), queued["priceCents"].as_i64(), queued["asset"].as_u64(), queued["ts"].as_i64()),
        (json!(format!("0x{}", "ab".repeat(32))), Some(8_561_200), Some(3), Some(1_790_400_000))
    );
    let (_, unknown) = get_record(&router, 999).await;
    assert_eq!(
        unknown,
        json!({"seq": 999, "state": "unknown", "height": null, "coinId": null,
               "resultsHash": null, "priceCents": null, "asset": null, "ts": null})
    );
    Ok(())
}
