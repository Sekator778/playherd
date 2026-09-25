//! Bearer-token auth for every `/v1/*` endpoint except `GET /health`
//! (FIXED INTERFACE (a), week-3 chainsvc/server auth contract): header
//! `Authorization: Bearer <token>` where `<token>` = env `C2_SVC_TOKEN`
//! (this service) = env `HERD_CHAINSVC_TOKEN` (the server, the other side of
//! this contract -- the two env var names differ because each side names
//! the shared secret from its own perspective) -- missing/wrong header =>
//! `401`. `main.rs` refuses to start with no `C2_SVC_TOKEN` configured
//! unless `C2_SVC_SVC_ALLOW_NO_AUTH=1` (see `config.rs::check_startup_auth_config`);
//! this module only implements the per-request check, layered over the
//! protected routes in `app.rs::router` (never `/health`).
//!
//! Compared via a fixed-length digest (SHA-256) in constant time: both sides
//! are first hashed to a 32-byte digest (so timing never depends on either
//! input's *length*), then every byte pair is XORed and OR-accumulated with
//! no early return (so timing never depends on *where* the first
//! mismatching byte is either) -- task instruction: "constant-time compare
//! over fixed-length digests".

use std::sync::Arc;

use axum::body::Body;
use axum::extract::{Request, State};
use axum::http::{StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use serde_json::json;
use sha2::{Digest, Sha256};

/// `axum::middleware::from_fn_with_state`'s state: the expected token, or
/// `None` when the service was started with `C2_SVC_ALLOW_NO_AUTH=1` and no
/// `C2_SVC_TOKEN` configured -- in that case every request is let through
/// unchecked (task instruction: "for tests" only; `main.rs`'s
/// `check_startup_auth_config` refuses to start in this configuration
/// otherwise, see `config.rs`).
pub type SvcTokenState = Arc<Option<String>>;

/// Constant-time equality over two SHA-256 digests of `expected`/`provided`
/// (module doc: neither the length nor the position of the first
/// mismatching byte of the *raw* strings can leak through timing, only the
/// two fixed-size digests are ever compared, unconditionally, byte by byte).
pub fn constant_time_token_eq(expected: &str, provided: &str) -> bool {
    let expected_digest = Sha256::digest(expected.as_bytes());
    let provided_digest = Sha256::digest(provided.as_bytes());
    let mut diff: u8 = 0;
    for (a, b) in expected_digest.iter().zip(provided_digest.iter()) {
        diff |= a ^ b;
    }
    diff == 0
}

fn unauthorized(reason: &str) -> Response {
    (StatusCode::UNAUTHORIZED, axum::Json(json!({ "error": reason }))).into_response()
}

/// `axum::middleware::from_fn_with_state` handler, layered over every
/// `/v1/*` route (never `/health`, see `app.rs::router`).
pub async fn require_bearer_token(State(expected_token): State<SvcTokenState>, request: Request<Body>, next: Next) -> Response {
    let Some(expected) = expected_token.as_deref() else {
        // `C2_SVC_ALLOW_NO_AUTH=1` with no token configured: auth disabled
        // (tests only -- `main.rs` never reaches this with
        // `expected_token == None` outside that flag).
        return next.run(request).await;
    };
    let provided = request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));
    match provided {
        Some(token) if constant_time_token_eq(expected, token) => next.run(request).await,
        Some(_) => unauthorized("invalid bearer token"),
        None => unauthorized("missing Authorization: Bearer <token> header"),
    }
}

#[cfg(test)]
mod tests {
    use axum::Router;
    use axum::routing::get;
    use tower::ServiceExt;

    use super::*;

    #[test]
    fn same_token_matches() {
        assert!(constant_time_token_eq("secret-token", "secret-token"));
    }

    #[test]
    fn different_token_does_not_match() {
        assert!(!constant_time_token_eq("secret-token", "wrong-token"));
    }

    #[test]
    fn different_length_does_not_match() {
        assert!(!constant_time_token_eq("secret-token", "secret-token-but-longer"));
        assert!(!constant_time_token_eq("secret-token", "short"));
    }

    #[test]
    fn empty_provided_does_not_match_nonempty_expected() {
        assert!(!constant_time_token_eq("secret-token", ""));
    }

    fn protected_router(token: Option<&str>) -> Router {
        let state: SvcTokenState = Arc::new(token.map(str::to_string));
        Router::new()
            .route("/v1/thing", get(|| async { "ok" }))
            .layer(axum::middleware::from_fn_with_state(state, require_bearer_token))
    }

    fn request(auth_header: Option<&str>) -> Request<Body> {
        let mut builder = axum::http::Request::builder().uri("/v1/thing").method("GET");
        if let Some(h) = auth_header {
            builder = builder.header(header::AUTHORIZATION, h);
        }
        builder.body(Body::empty()).unwrap()
    }

    #[tokio::test]
    async fn missing_header_is_401() {
        let response = protected_router(Some("expected")).oneshot(request(None)).await.unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn wrong_token_is_401() {
        let response = protected_router(Some("expected"))
            .oneshot(request(Some("Bearer wrong")))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn malformed_header_without_bearer_prefix_is_401() {
        let response = protected_router(Some("expected"))
            .oneshot(request(Some("expected")))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn correct_token_is_200() {
        let response = protected_router(Some("expected"))
            .oneshot(request(Some("Bearer expected")))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn no_configured_token_lets_every_request_through() {
        // `C2_SVC_ALLOW_NO_AUTH=1` case (`config.rs::check_startup_auth_config`
        // is what gates *starting* the service this way; this only proves the
        // middleware itself honors `None` by passing every request through).
        let response = protected_router(None).oneshot(request(None)).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn health_route_outside_the_layer_is_never_protected() {
        // Mirrors `app.rs::router`'s shape: `/health` is a separate router
        // merged in *after* the protected one, so it never passes through
        // this middleware at all, regardless of `C2_SVC_TOKEN`.
        let health = Router::new().route("/health", get(|| async { "ok" }));
        let router = health.merge(protected_router(Some("expected")));

        let response = router
            .clone()
            .oneshot(axum::http::Request::builder().uri("/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK, "/health must never require a token");

        let response = router
            .oneshot(axum::http::Request::builder().uri("/v1/thing").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED, "/v1/* must still require a token");
    }
}
