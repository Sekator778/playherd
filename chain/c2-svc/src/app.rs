//! axum router for FIXED INTERFACE (3) (`POST /v1/auth/verify`,
//! `POST /v1/oracle/records`, `GET /v1/oracle/status`, `GET /health`).
//! Generic over `C: ChainClient` so `tests/` can build the exact same
//! router against a fake chain client, and `main.rs` builds it against the
//! real `CoinsetChainClient`.

use std::sync::Arc;

use axum::{
    Json, Router,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::Serialize;
use serde_json::json;

use crate::auth::{self, AuthVerifyRequest, AuthVerifyResponse};
use crate::oracle::chain_client::ChainClient;
use crate::oracle::publisher::Publisher;
use crate::oracle::types::{
    OracleRecord, OracleRecordStatusResponse, OracleRecordsRequest, OracleRecordsResponse, OracleStatusResponse,
};
use crate::service_auth::{SvcTokenState, require_bearer_token};

/// FIXED INTERFACE (a): every `/v1/*` route requires
/// `Authorization: Bearer <token>` (`service_auth::require_bearer_token`);
/// `GET /health` never does -- it's registered on a separate router merged
/// in *after* the protected one's `.layer()` call, so it never passes
/// through that middleware at all.
pub fn router<C>(publisher: Arc<Publisher<C>>, svc_token: Option<String>) -> Router
where
    C: ChainClient + 'static,
{
    let svc_token_state: SvcTokenState = Arc::new(svc_token);
    let protected = Router::new()
        .route("/v1/auth/verify", post(auth_verify::<C>))
        .route("/v1/oracle/records", post(oracle_records::<C>))
        .route("/v1/oracle/records/:seq", get(oracle_record_status::<C>))
        .route("/v1/oracle/status", get(oracle_status::<C>))
        .with_state(publisher.clone())
        .layer(axum::middleware::from_fn_with_state(svc_token_state, require_bearer_token));

    // Task fix (`docs/research/chain_service.md` §12): `/health` now reads
    // `Publisher::health_check` (a local-only read, never a network call --
    // see that method's doc comment) so a wedged publisher (repeated tick
    // failures, or a specific in-flight spend stuck past its retry budget)
    // makes this route itself report degraded, not just `GET
    // /v1/oracle/status`. It needs its own `.with_state(...)` (a plain
    // `Json<HealthResponse>` health used to need none) -- still a completely
    // separate `Router` from `protected`, merged in the same order as
    // before, so it's still never touched by the auth middleware above.
    Router::new().route("/health", get(health::<C>)).with_state(publisher).merge(protected)
}

#[derive(Serialize)]
struct HealthResponse {
    ok: bool,
    /// Only present when `ok` is `false` (task instruction: "HTTP 503 with
    /// {\"ok\":false,\"reason\":...}").
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<String>,
}

async fn health<C: ChainClient>(State(publisher): State<Arc<Publisher<C>>>) -> Response {
    let degraded = match publisher.health_check().await {
        Ok(reason) => reason,
        // A genuinely unreadable/corrupt local queue file is exactly the
        // kind of unknown state `/health` should never paper over as
        // healthy -- fail toward "unhealthy", same direction `main.rs`'s own
        // startup bootstrap already fails closed in.
        Err(e) => Some(format!("queue file unreadable: {e:#}")),
    };
    match degraded {
        None => (StatusCode::OK, Json(HealthResponse { ok: true, reason: None })).into_response(),
        Some(reason) => {
            (StatusCode::SERVICE_UNAVAILABLE, Json(HealthResponse { ok: false, reason: Some(reason) })).into_response()
        }
    }
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
    let accepted_seqs = publisher.enqueue(records).await?;
    Ok((StatusCode::ACCEPTED, Json(OracleRecordsResponse { queued: accepted_seqs.len(), accepted_seqs })))
}

/// `GET /v1/oracle/records/{seq}` (FIXED INTERFACE (b)).
async fn oracle_record_status<C: ChainClient>(
    State(publisher): State<Arc<Publisher<C>>>,
    Path(seq): Path<u64>,
) -> Result<Json<OracleRecordStatusResponse>, AppError> {
    Ok(Json(publisher.record_status(seq)?))
}

async fn oracle_status<C: ChainClient>(
    State(publisher): State<Arc<Publisher<C>>>,
) -> Result<Json<OracleStatusResponse>, AppError> {
    Ok(Json(publisher.status().await?))
}
