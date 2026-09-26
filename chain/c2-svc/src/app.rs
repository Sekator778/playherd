//! axum router for FIXED INTERFACE (3) (`POST /v1/auth/verify`,
//! `POST /v1/oracle/records`, `GET /v1/oracle/status`, `GET /health`).
//! Generic over `C: ChainClient` so `tests/` can build the exact same
//! router against a fake chain client, and `main.rs` builds it against the
//! real `CoinsetChainClient`.

use std::sync::Arc;

use axum::{
    Json, Router,
    extract::{Path, Query, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::auth::{self, AuthVerifyRequest, AuthVerifyResponse};
use crate::oracle::chain_client::ChainClient;
use crate::oracle::publisher::Publisher;
use crate::oracle::types::{
    OracleRecord, OracleRecordStatusResponse, OracleRecordsRequest, OracleRecordsResponse, OracleStatusResponse,
};
use crate::play::chain_client::PlayChainClient;
use crate::play::deposit::DepositService;
use crate::play::types::{
    DepositAddressRequestDto, DepositAddressResponse, DepositsResponse, WithdrawalAcceptedResponse,
    WithdrawalRequest, WithdrawalRequestDto, WithdrawalStatusResponse,
};
use crate::play::withdrawal_publisher::WithdrawalPublisher;
use crate::service_auth::{SvcTokenState, require_bearer_token};

/// Everything the PLAY withdraw/deposit routes need, bundled behind one
/// `axum::extract::State` (Р-6, decision Р-53 2026-09-26) -- the oracle
/// routes above keep taking `Arc<Publisher<C>>` directly (unchanged), this
/// only adds a second, independent piece of router state alongside it (see
/// `router`'s own doc comment for why `/health` still gets its own separate,
/// unmerged state as before).
struct PlayState<C: PlayChainClient> {
    withdrawals: Arc<WithdrawalPublisher<C>>,
    deposits: Arc<DepositService<C>>,
}

// Manual `Clone` (not `#[derive(Clone)]`): a derived impl would add a
// spurious `C: Clone` bound on the whole struct even though only `Arc<...>`
// is ever cloned here (a known limitation of derive on generic structs).
impl<C: PlayChainClient> Clone for PlayState<C> {
    fn clone(&self) -> Self {
        Self { withdrawals: self.withdrawals.clone(), deposits: self.deposits.clone() }
    }
}

/// `GET /health`'s state (task requirement, decision Р-53 follow-up:
/// "surface stuck state in status/health like the oracle") -- bundles both
/// publishers' `health_check` behind one route, rather than only the
/// oracle's own as before: a wedged PLAY treasury (self-heal found the
/// cached coin spent with no unspent replacement) now degrades `GET /health`
/// exactly the way a wedged oracle singleton spend already did.
struct HealthState<C: PlayChainClient> {
    oracle: Arc<Publisher<C>>,
    withdrawals: Arc<WithdrawalPublisher<C>>,
}

impl<C: PlayChainClient> Clone for HealthState<C> {
    fn clone(&self) -> Self {
        Self { oracle: self.oracle.clone(), withdrawals: self.withdrawals.clone() }
    }
}

/// FIXED INTERFACE (a): every `/v1/*` route requires
/// `Authorization: Bearer <token>` (`service_auth::require_bearer_token`);
/// `GET /health` never does -- it's registered on a separate router merged
/// in *after* the protected one's `.layer()` call, so it never passes
/// through that middleware at all.
pub fn router<C>(
    publisher: Arc<Publisher<C>>,
    withdrawal_publisher: Arc<WithdrawalPublisher<C>>,
    deposit_service: Arc<DepositService<C>>,
    svc_token: Option<String>,
) -> Router
where
    C: PlayChainClient + 'static,
{
    let svc_token_state: SvcTokenState = Arc::new(svc_token);
    let oracle_routes = Router::new()
        .route("/v1/auth/verify", post(auth_verify::<C>))
        .route("/v1/oracle/records", post(oracle_records::<C>))
        .route("/v1/oracle/records/:seq", get(oracle_record_status::<C>))
        .route("/v1/oracle/status", get(oracle_status::<C>))
        .with_state(publisher.clone());
    let play_state = PlayState { withdrawals: withdrawal_publisher.clone(), deposits: deposit_service };
    let play_routes = Router::new()
        .route("/v1/play/withdrawals", post(play_withdrawals::<C>))
        .route("/v1/play/withdrawals/:request_id", get(play_withdrawal_status::<C>))
        .route("/v1/play/deposit-address", post(play_deposit_address::<C>))
        .route("/v1/play/deposits", get(play_deposits::<C>))
        .with_state(play_state);
    let protected = oracle_routes
        .merge(play_routes)
        .layer(axum::middleware::from_fn_with_state(svc_token_state, require_bearer_token));

    // Task fix (`docs/research/chain_service.md` §12, extended by the Р-53
    // follow-up "treasury self-heal", §13): `/health` reads both `Publisher::
    // health_check` (oracle) and `WithdrawalPublisher::health_check` (PLAY
    // treasury) -- local-only reads, never a network call (see either
    // method's doc comment) -- so a wedged oracle singleton spend OR a
    // wedged PLAY treasury (self-heal found the cached coin spent with no
    // unspent replacement) makes this route itself report degraded, not
    // just `GET /v1/oracle/status`. It needs its own `.with_state(...)` --
    // still a completely separate `Router` from `protected`, merged in the
    // same order as before, so it's still never touched by the auth
    // middleware above.
    let health_state = HealthState { oracle: publisher, withdrawals: withdrawal_publisher };
    Router::new().route("/health", get(health::<C>)).with_state(health_state).merge(protected)
}

#[derive(Serialize)]
struct HealthResponse {
    ok: bool,
    /// Only present when `ok` is `false` (task instruction: "HTTP 503 with
    /// {\"ok\":false,\"reason\":...}").
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<String>,
}

async fn health<C: PlayChainClient>(State(state): State<HealthState<C>>) -> Response {
    let oracle_degraded = match state.oracle.health_check().await {
        Ok(reason) => reason,
        // A genuinely unreadable/corrupt local queue file is exactly the
        // kind of unknown state `/health` should never paper over as
        // healthy -- fail toward "unhealthy", same direction `main.rs`'s own
        // startup bootstrap already fails closed in.
        Err(e) => Some(format!("oracle queue file unreadable: {e:#}")),
    };
    let withdrawal_degraded = match state.withdrawals.health_check().await {
        Ok(reason) => reason,
        Err(e) => Some(format!("withdrawal queue file unreadable: {e:#}")),
    };
    // Oracle checked first (same priority precedent `Publisher::
    // degraded_reason` itself uses between its own two signals) -- either
    // one degrades the whole route, task requirement: "like the oracle".
    let degraded = oracle_degraded.or(withdrawal_degraded);
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

// --- PLAY withdraw/deposit (Р-6, decision Р-53 2026-09-26) ------------------

/// `POST /v1/play/withdrawals` -> `202 {accepted: true}` (idempotent by
/// `requestId`, task's FIXED INTERFACE). Validates `toAddress`/`amountMojos`
/// here (400 on failure), same split `oracle_records` uses for its own DTOs.
async fn play_withdrawals<C: PlayChainClient>(
    State(state): State<PlayState<C>>,
    Json(req): Json<WithdrawalRequestDto>,
) -> Result<(StatusCode, Json<WithdrawalAcceptedResponse>), AppError> {
    let request = WithdrawalRequest::from_dto(&req).map_err(|reason| AppError(StatusCode::BAD_REQUEST, reason))?;
    state.withdrawals.enqueue(request).await?;
    Ok((StatusCode::ACCEPTED, Json(WithdrawalAcceptedResponse { accepted: true })))
}

/// `GET /v1/play/withdrawals/{requestId}`.
async fn play_withdrawal_status<C: PlayChainClient>(
    State(state): State<PlayState<C>>,
    Path(request_id): Path<String>,
) -> Result<Json<WithdrawalStatusResponse>, AppError> {
    Ok(Json(state.withdrawals.status(&request_id)?))
}

/// `POST /v1/play/deposit-address` -> `{address, innerPuzzleHash,
/// catPuzzleHash}` (derived deterministically; also durably registers this
/// player index so `GET /v1/play/deposits` knows to scan it).
async fn play_deposit_address<C: PlayChainClient>(
    State(state): State<PlayState<C>>,
    Json(req): Json<DepositAddressRequestDto>,
) -> Result<Json<DepositAddressResponse>, AppError> {
    Ok(Json(state.deposits.issue_address(req.player_index).await?))
}

#[derive(Deserialize)]
struct DepositsQuery {
    #[serde(rename = "sinceHeight", default)]
    since_height: u32,
}

/// `GET /v1/play/deposits?sinceHeight=N` -> `{deposits: [...], tipHeight}`.
async fn play_deposits<C: PlayChainClient>(
    State(state): State<PlayState<C>>,
    Query(query): Query<DepositsQuery>,
) -> Result<Json<DepositsResponse>, AppError> {
    Ok(Json(state.deposits.list_deposits(query.since_height).await?))
}
