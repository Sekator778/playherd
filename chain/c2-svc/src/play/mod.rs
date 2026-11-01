//! PLAY on-chain withdraw/deposit (D-6, decision D-53 2026-09-26): real
//! testnet11 CAT withdrawals (batched treasury spends) and deposits
//! (confirmed-coin scanning at derived per-player deposit addresses). See
//! `docs/research/chain_service.md` §13 for the full write-up.

pub mod chain_client;
pub mod deposit;
pub mod types;
pub mod withdrawal_publisher;
pub mod withdrawal_queue;
pub mod withdrawal_spend;
