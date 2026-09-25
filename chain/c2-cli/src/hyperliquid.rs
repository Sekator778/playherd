//! Read-only BTC mark price from Hyperliquid's public `/info` endpoint, for
//! `oracle-publish --from-hyperliquid`.
//!
//! Route and field choice: `docs/research/hl_liquidation_math.md` §4 --
//! `POST https://api.hyperliquid.xyz/info {"type":"metaAndAssetCtxs"}`
//! returns `[meta, assetCtxs]`; `meta.universe[i]`/`assetCtxs[i]` are the
//! same coin at the same index `i`. The index must be found **by name**
//! (`meta.universe[i].name == "BTC"`), never hardcoded, because `universe`'s
//! order isn't a documented stable contract (same source, confirmed live
//! 2026-09-25: BTC was index 0, but that's not guaranteed to stay true).
//! `markPx` (not `oraclePx`) is the field this project's engine models
//! liquidations against (`hl_liquidation_math.md` §4), so it's the one this
//! oracle publishes.

use anyhow::{Context, Result, bail};
use serde::Deserialize;

pub const HYPERLIQUID_INFO_URL: &str = "https://api.hyperliquid.xyz/info";

#[derive(Debug, Deserialize)]
pub struct Meta {
    universe: Vec<UniverseAsset>,
}

#[derive(Debug, Deserialize)]
struct UniverseAsset {
    name: String,
}

#[derive(Debug, Deserialize)]
pub struct AssetCtx {
    #[serde(rename = "markPx")]
    mark_px: String,
}

/// Parses a `metaAndAssetCtxs` response body (the raw `[meta, assetCtxs]`
/// JSON array) without touching the network -- split out from
/// [`fetch_btc_mark_px`] so the parsing/lookup logic has an offline test.
pub fn parse_meta_and_asset_ctxs(body: &str) -> Result<(Meta, Vec<AssetCtx>)> {
    serde_json::from_str(body).context("parsing Hyperliquid metaAndAssetCtxs response as [meta, assetCtxs]")
}

/// Finds BTC's `markPx` (Hyperliquid's own decimal string, e.g. `"83468.0"`)
/// by name in `meta.universe`, not by a hardcoded index.
pub fn find_btc_mark_px<'a>(meta: &Meta, asset_ctxs: &'a [AssetCtx]) -> Result<&'a str> {
    let index = meta
        .universe
        .iter()
        .position(|asset| asset.name == "BTC")
        .context("BTC not found in Hyperliquid meta.universe")?;
    let ctx = asset_ctxs
        .get(index)
        .with_context(|| format!("assetCtxs has no element at BTC's universe index {index}"))?;
    Ok(&ctx.mark_px)
}

/// Live read: POSTs `{"type":"metaAndAssetCtxs"}` to Hyperliquid's public
/// `/info` endpoint (no account/keys needed) and returns BTC's `markPx` as
/// Hyperliquid's own decimal string.
pub async fn fetch_btc_mark_px(client: &reqwest::Client) -> Result<String> {
    let response = client
        .post(HYPERLIQUID_INFO_URL)
        .json(&serde_json::json!({"type": "metaAndAssetCtxs"}))
        .send()
        .await
        .with_context(|| format!("POST {HYPERLIQUID_INFO_URL}"))?
        .error_for_status()
        .context("Hyperliquid /info returned an error status")?;
    let body = response
        .text()
        .await
        .context("reading Hyperliquid /info response body")?;
    let (meta, asset_ctxs) = parse_meta_and_asset_ctxs(&body)?;
    find_btc_mark_px(&meta, &asset_ctxs).map(str::to_string)
}

/// Converts a Hyperliquid decimal price string (e.g. `"83468.0"`) into
/// integer cents, rounded to the nearest cent. `f64` has ~15-17 significant
/// decimal digits, far more than BTC's price magnitude (~10^5) plus two
/// cent digits needs, so this is exact for every price Hyperliquid actually
/// returns.
pub fn price_str_to_cents(price: &str) -> Result<i64> {
    let value: f64 = price
        .trim()
        .parse()
        .with_context(|| format!("parsing price {price:?} as a decimal number"))?;
    if !value.is_finite() {
        bail!("price {price:?} is not a finite number");
    }
    let cents = (value * 100.0).round();
    if cents < i64::MIN as f64 || cents > i64::MAX as f64 {
        bail!("price {price:?} converts to a cents value out of i64 range");
    }
    Ok(cents as i64)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Real shape from `docs/research/hl_liquidation_math.md` §1.10 (live
    /// fetch, 2026-09-25 ~14:10 UTC), trimmed to a handful of assets plus the
    /// fields this module reads -- BTC deliberately placed at index 1, not
    /// 0, so the test would fail if the lookup ever regressed to a
    /// hardcoded index.
    const SAMPLE_RESPONSE: &str = r#"[
        {"universe": [
            {"szDecimals": 4, "name": "ETH", "maxLeverage": 25},
            {"szDecimals": 5, "name": "BTC", "maxLeverage": 40, "marginTableId": 56},
            {"szDecimals": 2, "name": "SOL", "maxLeverage": 20}
        ]},
        [
            {"funding": "0.0000100", "markPx": "3200.5", "oraclePx": "3200.1"},
            {"funding": "0.0000125", "openInterest": "38634.58326", "prevDayPx": "84215.0",
             "oraclePx": "83492.0", "markPx": "83468.0", "midPx": "83474.5"},
            {"funding": "0.0000050", "markPx": "140.2", "oraclePx": "140.1"}
        ]
    ]"#;

    #[test]
    fn finds_btc_by_name_not_index() {
        let (meta, asset_ctxs) = parse_meta_and_asset_ctxs(SAMPLE_RESPONSE).unwrap();
        assert_eq!(find_btc_mark_px(&meta, &asset_ctxs).unwrap(), "83468.0");
    }

    #[test]
    fn missing_btc_is_an_error() {
        let body = r#"[{"universe":[{"name":"ETH"}]}, [{"markPx":"3200.5"}]]"#;
        let (meta, asset_ctxs) = parse_meta_and_asset_ctxs(body).unwrap();
        assert!(find_btc_mark_px(&meta, &asset_ctxs).is_err());
    }

    #[test]
    fn cents_rounding() {
        assert_eq!(price_str_to_cents("83468.0").unwrap(), 8_346_800);
        assert_eq!(price_str_to_cents("83468.25").unwrap(), 8_346_825);
        assert_eq!(price_str_to_cents("83468.256").unwrap(), 8_346_826);
        assert_eq!(price_str_to_cents("0.004").unwrap(), 0);
    }

    #[test]
    fn rejects_garbage_price() {
        assert!(price_str_to_cents("not-a-number").is_err());
    }
}
