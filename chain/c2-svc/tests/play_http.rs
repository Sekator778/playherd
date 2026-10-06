//! `POST /v1/play/withdrawals` over the real router (`app::router`, auth middleware included), against
//! a fake chain: what the server actually receives when it sends a `requestId` this service already
//! holds (`docs/research/chain_service.md` §17.6).
//!
//! The 409 body is the shared contract fixture `tests/contract/WithdrawalConflictResponse.json` (written
//! by `tests/contract_fixtures.rs` from the same type the handler serializes; the Java server reads the
//! same file): this test compares the handler's real answer with that committed file, so the fixture
//! cannot drift from what is on the wire.

mod play_common;

use std::sync::Arc;

use anyhow::Result;
use axum::body::{Body, to_bytes};
use axum::http::{Request, StatusCode};
use serde_json::{Value, json};
use tower::ServiceExt;

use c2_cli::keys::{TXCH_PREFIX, derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_svc::app;
use c2_svc::fee_allocator::FeeCoinAllocator;
use c2_svc::oracle::publisher::{Publisher, TickFailureState};
use c2_svc::play::deposit::DepositService;
use c2_svc::play::withdrawal_publisher::WithdrawalPublisher;
use chia_sdk_utils::Address;

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
        queue_cache: Default::default(),
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

/// A fresh, valid testnet11 address (a new wallet each call).
fn fresh_address() -> String {
    let wallet = derive_wallet_key(&master_sk_from_mnemonic(&generate_mnemonic().unwrap()), 0);
    Address::new(wallet.puzzle_hash, TXCH_PREFIX.to_string()).encode().unwrap()
}

async fn post(router: &axum::Router, body: Value) -> (StatusCode, Value) {
    let response = router
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/v1/play/withdrawals")
                .header("authorization", format!("Bearer {TOKEN}"))
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = to_bytes(response.into_body(), 1 << 20).await.unwrap();
    (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
}

/// The stand's situation: a request id the service holds (here: queued), submitted again.
#[tokio::test]
async fn the_http_answer_to_a_reused_request_id_is_409_with_the_contract_body() -> Result<()> {
    let tmp = tempfile::tempdir()?;
    let router = router(tmp.path());
    let alice = fresh_address();
    let mallory = fresh_address();

    // The first submission and a repeat of it: 202 {accepted: true}, both.
    let request = json!({"requestId": "w-11", "toAddress": alice, "amountMojos": 500_000});
    assert_eq!(post(&router, request.clone()).await, (StatusCode::ACCEPTED, json!({"accepted": true})));
    assert_eq!(post(&router, request).await, (StatusCode::ACCEPTED, json!({"accepted": true})), "same content is idempotent");

    // A different amount, a different recipient: 409 and the body of the contract fixture.
    let fixture: Value = serde_json::from_str(&std::fs::read_to_string(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/contract/WithdrawalConflictResponse.json"),
    )?)?;
    for changed in [
        json!({"requestId": "w-11", "toAddress": alice, "amountMojos": 500_001}),
        json!({"requestId": "w-11", "toAddress": mallory, "amountMojos": 500_000}),
    ] {
        let (status, body) = post(&router, changed).await;
        assert_eq!(status, StatusCode::CONFLICT);
        assert_eq!(body, fixture, "the body on the wire is the committed contract fixture");
        assert_eq!(body["code"], "request_id_conflict");
        assert_eq!(body["requestId"], "w-11");
        assert!(body["error"].as_str().unwrap().contains("different recipient or amount"));
        assert!(body.get("state").is_none() && body.get("coinId").is_none(), "nothing about the old request: {body}");
    }

    // The other refusals keep their shape: 400 {"error": ...}.
    let (status, body) = post(&router, json!({"requestId": "w-12", "toAddress": "not an address", "amountMojos": 5})).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert!(body["error"].as_str().is_some() && body.get("code").is_none(), "{body}");
    let (status, _) = post(&router, json!({"requestId": "w-12", "toAddress": fresh_address(), "amountMojos": 0})).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    Ok(())
}
