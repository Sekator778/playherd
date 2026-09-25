//! `POST /v1/oracle/records` / `GET /v1/oracle/status` (FIXED INTERFACE
//! (3)): the multi-record oracle singleton publisher. See
//! `docs/research/chain_service.md` §2-5 for the full write-up.

pub mod chain_client;
pub mod publisher;
pub mod queue;
pub mod spend;
pub mod types;
