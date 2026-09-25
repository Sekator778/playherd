//! axum router for FIXED INTERFACE (3) (`POST /v1/auth/verify`,
//! `POST /v1/oracle/records`, `GET /v1/oracle/status`, `GET /health`).
//! Generic over `C: ChainClient` so `tests/` can build the exact same
//! router against a fake chain client, and `main.rs` builds it against the
//! real `CoinsetChainClient`.

use std::sync::Arc;

use axum::{
    Json, Router,
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::Serialize;
use serde_json::json;

use crate::auth::{self, AuthVerifyRequest, AuthVerifyResponse};
use crate::oracle::chain_client::ChainClient;
use crate::oracle::publisher::Publisher;
use crate::oracle::types::{OracleRecord, OracleRecordsRequest, OracleRecordsResponse, OracleStatusResponse};

pub fn router<C>(publisher: Arc<Publisher<C>>) -> Router
where
    C: ChainClient + 'static,
{
    Router::new()
        .route("/health", get(health))
        .route("/v1/auth/verify", post(auth_verify::<C>))
        .route("/v1/oracle/records", post(oracle_records::<C>))
        .route("/v1/oracle/status", get(oracle_status::<C>))
        .with_state(publisher)
}

#[derive(Serialize)]
struct HealthResponse {
    ok: bool,
}

async fn health() -> Json<HealthResponse> {
    Json(HealthResponse { ok: true })
}

async fn auth_verify<C: ChainClient>(
    State(_publisher): State<Arc<Publisher<C>>>,
    Json(req): Json<AuthVerifyRequest>,
) -> Json<AuthVerifyResponse> {
    Json(auth::verify(&req))
}

/// Server error shape for this internal service (not FIXED INTERFACE (1)'s
/// public-facing `{"error":{"code":...,"message":...}}` envelope -- that one
/// is the web<->server RPC contract; `chainsvc` is only ever called by that
/// server, never by the browser, so a plain `{"error": "..."}` is enough
/// here, **предложение**).
struct AppError(StatusCode, String);

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({ "error": self.1 }))).into_response()
    }
}

impl From<anyhow::Error> for AppError {
    fn from(e: anyhow::Error) -> Self {
        AppError(StatusCode::INTERNAL_SERVER_ERROR, format!("{e:#}"))
    }
}

async fn oracle_records<C: ChainClient>(
    State(publisher): State<Arc<Publisher<C>>>,
    Json(req): Json<OracleRecordsRequest>,
) -> Result<(StatusCode, Json<OracleRecordsResponse>), AppError> {
    if req.records.is_empty() {
        return Err(AppError(StatusCode::BAD_REQUEST, "records must not be empty".to_string()));
    }
    let mut records = Vec::with_capacity(req.records.len());
    for dto in &req.records {
        let record: OracleRecord =
            OracleRecord::from_dto(dto).map_err(|reason| AppError(StatusCode::BAD_REQUEST, reason))?;
        records.push(record);
    }
    let queued = publisher.enqueue(records)?;
    Ok((StatusCode::ACCEPTED, Json(OracleRecordsResponse { queued })))
}

async fn oracle_status<C: ChainClient>(
    State(publisher): State<Arc<Publisher<C>>>,
) -> Result<Json<OracleStatusResponse>, AppError> {
    Ok(Json(publisher.status().await?))
}
