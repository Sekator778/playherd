//! Library half of `c2`: everything the `c2` binary (`main.rs`) uses, split
//! out so integration tests (`tests/`) can exercise the exact same builder
//! functions the CLI calls, against the offline `chia-sdk-test` simulator --
//! "same code path proven offline" instead of a reimplementation.

pub mod builders;
pub mod cat_chain;
pub mod chain;
pub mod cost;
pub mod hyperliquid;
pub mod keys;
pub mod keystore;
pub mod offer_compress;
pub mod sign;
pub mod spend_json;
pub mod state;
