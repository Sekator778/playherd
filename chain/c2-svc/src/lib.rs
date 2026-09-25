//! Library half of `c2-svc`: everything the `c2-svc` binary (`main.rs`)
//! wires together, split out so integration tests (`tests/`) can exercise
//! the exact same handler/builder functions the server runs, and so a fake
//! `ChainClient` can be substituted for the real coinset one in tests
//! without touching the network (task instruction: "queueing logic (unit
//! with a fake chain client)").
//!
//! Implements FIXED INTERFACE (3) from the week-2 chain-service task:
//! `POST /v1/auth/verify`, `POST /v1/oracle/records`, `GET /v1/oracle/status`,
//! `GET /health`. See `docs/research/chain_service.md` for the full write-up.

pub mod app;
pub mod atomic_file;
pub mod auth;
pub mod config;
pub mod keys_file;
pub mod oracle;
pub mod state_file;
