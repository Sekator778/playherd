//! `c2`: week-1 testnet11 CLI for the PLAY CAT / oracle / offers primitives.
//! See `docs/research/week1_testnet_cli.md` for the full write-up.

use c2_cli::{builders, cat_chain, chain, cost, hyperliquid, keys, keystore, spend_json, state};

use anyhow::{Context, Result, bail};
use bip39::Mnemonic;
use chia_bls::SecretKey;
use chia_puzzle_types::Proof;
use chia_sdk_coinset::{ChiaRpcClient, CoinsetClient};
use chia_sdk_driver::Cat;
use chia_sdk_utils::Address;
use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(name = "c2", about = "C2 testnet11 CLI: PLAY CAT, offers, oracle singleton (week 1)")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Generates a new BIP-39 wallet and stores it under ~/.c2/testnet11/<name>.json.
    Keygen {
        #[arg(long)]
        name: String,
    },
    /// Prints the txch address for a wallet at the given derivation index (default 0).
    Address {
        #[arg(long)]
        name: String,
        #[arg(long, default_value_t = 0)]
        index: u32,
    },
    /// Sums unspent coin amounts across a small gap of derived addresses (coinset.org, read-only).
    Balance {
        #[arg(long)]
        name: String,
    },
    /// Standard XCH transfer from this wallet's coins to a `txch1...` address. Dry-run unless --push.
    Send {
        #[arg(long)]
        name: String,
        #[arg(long)]
        to: String,
        #[arg(long)]
        amount: u64,
        #[arg(long, default_value_t = 0)]
        fee: u64,
        #[arg(long)]
        push: bool,
    },
    /// Sums unspent PLAY (or any other CAT) coin amounts of --asset-id across a small gap of derived addresses (coinset.org, read-only).
    PlayBalance {
        #[arg(long)]
        name: String,
        /// Hex asset id (with or without a `0x` prefix) -- e.g. the PLAY CAT's, `~/.c2/testnet11/operator.play.json`.
        #[arg(long)]
        asset_id: String,
    },
    /// CAT transfer of --asset-id (e.g. PLAY) from this wallet's own on-chain coin to a `txch1...` address. Dry-run unless --push.
    SendPlay {
        #[arg(long)]
        name: String,
        #[arg(long)]
        asset_id: String,
        #[arg(long)]
        to: String,
        /// CAT mojos (1 PLAY = 1000 CAT mojos, same convention as `issue-play`/`offer-make`).
        #[arg(long)]
        amount: u64,
        /// Paid from a second, plain-XCH coin of this wallet (a CAT coin's own amount only backs its color, see `builders::fee`).
        #[arg(long, default_value_t = 0)]
        fee: u64,
        #[arg(long)]
        push: bool,
    },
    /// Mints a new PLAY CAT (single-issuance genesis-by-coin-id TAIL). Dry-run unless --push.
    IssuePlay {
        #[arg(long)]
        name: String,
        #[arg(long)]
        amount: u64,
        #[arg(long, default_value_t = 0)]
        fee: u64,
        #[arg(long)]
        push: bool,
    },
    /// Makes a PLAY-for-TXCH offer from the wallet's cached PLAY CAT coin (see `issue-play`). Never pushes (offers aren't broadcastable alone).
    OfferMake {
        #[arg(long)]
        name: String,
        /// PLAY CAT mojos to offer (1 PLAY = 1000 CAT mojos); may be less
        /// than the cached coin's full amount -- the rest comes back to this
        /// wallet as change in the same offer.
        #[arg(long)]
        amount: u64,
        #[arg(long)]
        requested_mojos: u64,
    },
    /// Takes an offer1... string: pays the requested TXCH, receives the offered PLAY. Dry-run unless --push.
    OfferTake {
        #[arg(long)]
        name: String,
        #[arg(long)]
        offer: String,
        #[arg(long, default_value_t = 0)]
        fee: u64,
        #[arg(long)]
        push: bool,
    },
    /// Launches the minimal oracle singleton (Р-11), manager key = this wallet. Dry-run unless --push.
    OracleLaunch {
        #[arg(long)]
        name: String,
        #[arg(long, default_value_t = 0)]
        fee: u64,
        #[arg(long)]
        push: bool,
    },
    /// Spends the oracle singleton one round forward: recreates itself + announces (price, ts, sequence). Dry-run unless --push.
    OraclePublish {
        #[arg(long)]
        name: String,
        /// Cents. Required unless --from-hyperliquid.
        #[arg(long)]
        price: Option<i64>,
        /// Unix seconds. Required unless --from-hyperliquid.
        #[arg(long)]
        ts: Option<i64>,
        /// Reads BTC markPx (as integer cents) and the current unix time live
        /// from Hyperliquid's public `/info` endpoint instead of --price/--ts.
        #[arg(long)]
        from_hyperliquid: bool,
        /// Mojos, paid from a second (non-singleton) coin of this wallet --
        /// the recreated singleton always carries exactly 1 mojo, so it
        /// can't reserve a fee itself (see `builders::fee`).
        #[arg(long, default_value_t = 0)]
        fee: u64,
        #[arg(long)]
        push: bool,
    },
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    match cli.command {
        Command::Keygen { name } => cmd_keygen(&name),
        Command::Address { name, index } => cmd_address(&name, index),
        Command::Balance { name } => run_async(cmd_balance(name)),
        Command::Send {
            name,
            to,
            amount,
            fee,
            push,
        } => run_async(cmd_send(name, to, amount, fee, push)),
        Command::PlayBalance { name, asset_id } => run_async(cmd_play_balance(name, asset_id)),
        Command::SendPlay {
            name,
            asset_id,
            to,
            amount,
            fee,
            push,
        } => run_async(cmd_send_play(name, asset_id, to, amount, fee, push)),
        Command::IssuePlay {
            name,
            amount,
            fee,
            push,
        } => run_async(cmd_issue_play(name, amount, fee, push)),
        Command::OfferMake {
            name,
            amount,
            requested_mojos,
        } => run_async(cmd_offer_make(name, amount, requested_mojos)),
        Command::OfferTake {
            name,
            offer,
            fee,
            push,
        } => run_async(cmd_offer_take(name, offer, fee, push)),
        Command::OracleLaunch { name, fee, push } => run_async(cmd_oracle_launch(name, fee, push)),
        Command::OraclePublish {
            name,
            price,
            ts,
            from_hyperliquid,
            fee,
            push,
        } => run_async(cmd_oracle_publish(name, price, ts, from_hyperliquid, fee, push)),
    }
}

fn run_async<F: std::future::Future<Output = Result<()>>>(fut: F) -> Result<()> {
    tokio::runtime::Runtime::new()
        .context("starting async runtime")?
        .block_on(fut)
}

fn load_master_sk(name: &str) -> Result<SecretKey> {
    let key_file = keystore::load(name)?;
    let mnemonic = Mnemonic::parse(&key_file.mnemonic).context("parsing stored mnemonic")?;
    Ok(keys::master_sk_from_mnemonic(&mnemonic))
}

// --- keygen / address / balance ------------------------------------------

fn cmd_keygen(name: &str) -> Result<()> {
    if keystore::exists(name)? {
        bail!(
            "key file already exists for '{name}': {} (refusing to overwrite)",
            keystore::key_path(name)?.display()
        );
    }

    let mnemonic = keys::generate_mnemonic()?;
    let key_file = keystore::KeyFile {
        mnemonic: mnemonic.to_string(),
        created_utc: chrono::Utc::now().to_rfc3339(),
        network: "testnet11".to_string(),
    };
    let path = keystore::save_new(name, &key_file)?;

    let master_sk = keys::master_sk_from_mnemonic(&mnemonic);
    let wallet_key = keys::derive_wallet_key(&master_sk, 0);
    let address = keys::address_of(wallet_key.puzzle_hash)?;

    println!("wallet '{name}' created");
    println!("key file: {} (0600, mnemonic never printed above)", path.display());
    println!("address (index 0): {address}");
    Ok(())
}

fn cmd_address(name: &str, index: u32) -> Result<()> {
    let master_sk = load_master_sk(name)?;
    let wallet_key = keys::derive_wallet_key(&master_sk, index);
    println!("{}", keys::address_of(wallet_key.puzzle_hash)?);
    Ok(())
}

async fn cmd_balance(name: String) -> Result<()> {
    let master_sk = load_master_sk(&name)?;
    let client = chain::client();
    let total = chain::balance(&client, &master_sk, chain::ADDRESS_GAP).await?;
    println!(
        "{name}: {total} mojos (unspent, addresses 0..{})",
        chain::ADDRESS_GAP
    );
    Ok(())
}

// --- send --------------------------------------------------------------

async fn cmd_send(name: String, to: String, amount: u64, fee: u64, push: bool) -> Result<()> {
    let master_sk = load_master_sk(&name)?;
    let client = chain::client();
    let to_puzzle_hash = Address::decode(&to)
        .context("decoding --to as a bech32m address")?
        .expect_prefix(keys::TXCH_PREFIX)
        .context("--to must be a txch1... (testnet11) address")?;

    let (sender, funding_coin) =
        chain::find_funding_coin(&client, &master_sk, amount + fee, chain::ADDRESS_GAP).await?;

    let result = builders::send::build_send(funding_coin, &sender, to_puzzle_hash, amount, fee)?;
    print_spend(&result.coin_spends, &result.signature, fee)?;
    println!("sent {amount} mojos to {to}");
    println!("change to self: {} mojos", result.change_amount);

    maybe_push(&client, result.coin_spends, result.signature, push)
        .await
        .map(drop)
}

// --- play-balance / send-play -----------------------------------------------

/// Parses a hex asset id (`--asset-id`), with or without a `0x`/`0X` prefix.
fn parse_asset_id(raw: &str) -> Result<chia_protocol::Bytes32> {
    let stripped = raw.strip_prefix("0x").or_else(|| raw.strip_prefix("0X")).unwrap_or(raw);
    let bytes = hex::decode(stripped).context("--asset-id: invalid hex")?;
    let array: [u8; 32] = bytes
        .as_slice()
        .try_into()
        .map_err(|_| anyhow::anyhow!("--asset-id: expected 32 bytes, got {}", bytes.len()))?;
    Ok(chia_protocol::Bytes32::from(array))
}

async fn cmd_play_balance(name: String, asset_id: String) -> Result<()> {
    let asset_id = parse_asset_id(&asset_id)?;
    let master_sk = load_master_sk(&name)?;
    let client = chain::client();
    let total = cat_chain::cat_balance(&client, &master_sk, asset_id, chain::ADDRESS_GAP).await?;
    println!(
        "{name}: {total} CAT mojos of asset {asset_id} (unspent, addresses 0..{})",
        chain::ADDRESS_GAP
    );
    Ok(())
}

async fn cmd_send_play(name: String, asset_id: String, to: String, amount: u64, fee: u64, push: bool) -> Result<()> {
    let asset_id = parse_asset_id(&asset_id)?;
    let master_sk = load_master_sk(&name)?;
    let client = chain::client();
    let to_puzzle_hash = Address::decode(&to)
        .context("decoding --to as a bech32m address")?
        .expect_prefix(keys::TXCH_PREFIX)
        .context("--to must be a txch1... (testnet11) address")?;

    let (sender, cat) = cat_chain::find_cat_coin(&client, &master_sk, asset_id, amount, chain::ADDRESS_GAP).await?;

    let result = builders::send_cat::build_send_cat(cat, &sender, to_puzzle_hash, amount)?;
    let mut coin_spends = result.coin_spends;
    let mut signature = result.signature;
    if fee > 0 {
        let (payer, fee_funding_coin) = chain::find_funding_coin(&client, &master_sk, fee, chain::ADDRESS_GAP)
            .await
            .context("no XCH coin to pay the send-play fee with")?;
        let fee_result = builders::fee::build_fee_only(fee_funding_coin, &payer, fee)?;
        coin_spends.extend(fee_result.coin_spends);
        signature = signature + &fee_result.signature;
    }

    print_spend(&coin_spends, &signature, fee)?;
    println!("sent {amount} CAT mojos of asset {asset_id} to {to}");
    println!("change to self: {} CAT mojos", result.change_amount);

    let pushed = maybe_push(&client, coin_spends, signature, push).await?;
    // Task fix (`docs/research/chain_service.md` §13): `c2-svc`'s
    // `WithdrawalPublisher` treats `<name>.play.json` as the treasury's
    // current PLAY CAT coin -- an operator running `send-play` on that same
    // wallet (manual top-up/consolidation) used to leave that cache pointing
    // at a now-spent coin (only `issue-play` ever wrote it), relying
    // entirely on the service's own self-heal to notice and adopt the
    // replacement on its next tick. Refreshing it here too (only once the
    // spend actually landed on chain, same "never cache before push
    // succeeds" rule `issue-play` above already follows) means the on-disk
    // cache is never stale in the first place for this operator's own
    // actions -- self-heal remains the backstop for everything else (a
    // crash mid-spend, a restored/stale file, chain reorgs). `cat` is still
    // valid here (`Cat` is `Copy`; it was only ever *copied* into
    // `build_send_cat` above, not moved out from under this binding).
    if pushed {
        if result.change_amount > 0 {
            let next_cat = cat.child(sender.puzzle_hash, result.change_amount);
            let lineage_proof = next_cat
                .lineage_proof
                .context("internal error: send-play change CAT coin has no lineage proof")?;
            let play_state = state::PlayCoinState::new(asset_id, next_cat.coin, lineage_proof);
            let path = state::save_play_coin(&name, &play_state)?;
            println!("refreshed cached PLAY coin: {}", path.display());
        } else {
            println!(
                "send-play fully drained the spent coin (no change) -- {name}.play.json left \
                 unchanged; if this was the treasury wallet, c2-svc's own self-heal will adopt \
                 the correct coin from chain on its next tick"
            );
        }
    } else {
        println!("dry run: PLAY coin cache left unchanged (nothing was broadcast)");
    }
    Ok(())
}

// --- issue-play ------------------------------------------------------------

async fn cmd_issue_play(name: String, amount: u64, fee: u64, push: bool) -> Result<()> {
    let master_sk = load_master_sk(&name)?;
    let client = chain::client();
    let (owner, funding_coin) =
        chain::find_funding_coin(&client, &master_sk, amount + fee, chain::ADDRESS_GAP).await?;

    let result = builders::issue::build_issue_play(funding_coin, &owner, amount, fee)?;

    print_spend(&result.coin_spends, &result.signature, fee)?;
    println!("PLAY asset id: {}", result.cat.info.asset_id);
    println!("PLAY coin id: {}", result.cat.coin.coin_id());
    println!("change to self: {} mojos", result.change_amount);

    // Only cache the minted coin (for `offer-make`) once it's actually been
    // broadcast -- a dry run doesn't create anything on chain, so caching
    // it here would point `offer-make` at a CAT coin that doesn't exist.
    let pushed = maybe_push(&client, result.coin_spends, result.signature, push).await?;
    if pushed {
        let lineage_proof = match result.cat.lineage_proof {
            Some(lp) => lp,
            None => bail!("internal error: minted PLAY coin has no lineage proof"),
        };
        let play_state =
            state::PlayCoinState::new(result.cat.info.asset_id, result.cat.coin, lineage_proof);
        let path = state::save_play_coin(&name, &play_state)?;
        println!("cached PLAY coin for offer-make: {}", path.display());
    } else {
        println!("dry run: PLAY coin cache left unchanged (nothing was broadcast)");
    }
    Ok(())
}

// --- offer-make / offer-take ------------------------------------------------

async fn cmd_offer_make(name: String, amount: u64, requested_mojos: u64) -> Result<()> {
    let master_sk = load_master_sk(&name)?;
    let wallet_key = keys::derive_wallet_key(&master_sk, 0);

    let play_state = state::load_play_coin(&name).context(
        "no cached PLAY coin for this wallet -- run `issue-play` first (offer-make only offers \
         a PLAY coin this CLI itself minted or received, see docs/research/week1_testnet_cli.md)",
    )?;
    let lineage_proof = play_state.lineage_proof()?;
    let cat_info = chia_sdk_driver::CatInfo::new(play_state.asset_id, None, wallet_key.puzzle_hash);
    let cat = Cat::new(play_state.coin, Some(lineage_proof), cat_info);

    let offer_text = builders::offer::build_offer_make(cat, &wallet_key, amount, requested_mojos)?;
    println!(
        "offer (give {amount} PLAY mojos out of {} cached, want {requested_mojos} mojos txch):",
        cat.coin.amount
    );
    println!("{offer_text}");
    Ok(())
}

async fn cmd_offer_take(name: String, offer: String, fee: u64, push: bool) -> Result<()> {
    let master_sk = load_master_sk(&name)?;
    let client = chain::client();

    // The requested-payment amount isn't known until the offer is decoded;
    // conservatively require *some* unspent coin covering at least the fee
    // and let the builder itself fail clearly if it isn't enough overall.
    let (taker, funding_coin) =
        chain::find_funding_coin(&client, &master_sk, 1 + fee, chain::ADDRESS_GAP)
            .await
            .context("no funding coin to pay the offer's requested TXCH (+ fee) with")?;

    let result = builders::offer::build_offer_take(&offer, funding_coin, &taker, fee)?;
    print_spend(&result.coin_spends, &result.signature, fee)?;
    println!("received {} PLAY", result.received_play_amount);

    if push {
        let bundle = builders::offer::finish_offer(&offer, result.coin_spends, result.signature)?;
        push_bundle(&client, bundle).await?;
    } else {
        println!("dry-run only (pass --push to broadcast the completed trade)");
    }
    Ok(())
}

// --- oracle-launch / oracle-publish -----------------------------------------

async fn cmd_oracle_launch(name: String, fee: u64, push: bool) -> Result<()> {
    let master_sk = load_master_sk(&name)?;
    let client = chain::client();
    let (manager, funding_coin) = chain::find_funding_coin(
        &client,
        &master_sk,
        builders::oracle::SINGLETON_AMOUNT + fee,
        chain::ADDRESS_GAP,
    )
    .await?;

    let result = builders::oracle::build_oracle_launch(funding_coin, &manager, fee)?;
    print_spend(&result.coin_spends, &result.signature, fee)?;
    println!("oracle launcher id: {}", result.launcher_id);
    println!("oracle singleton puzzle hash: {}", result.singleton_puzzle_hash);
    println!("change to self: {} mojos", result.change_amount);

    // Only cache the new singleton (for `oracle-publish`) once it's
    // actually been launched on chain -- a dry run's launcher id/eve coin
    // never existed, so caching them would point `oracle-publish` at a
    // singleton that was never created.
    let pushed = maybe_push(&client, result.coin_spends, result.signature, push).await?;
    if pushed {
        let oracle_state = state::OracleState {
            launcher_id: result.launcher_id,
            manager_synthetic_pk: manager.synthetic_pk,
            current_coin: result.eve_coin,
            current_proof_dto: result.eve_proof.into(),
            next_sequence: 1,
        };
        let path = state::save_oracle_state(&name, &oracle_state)?;
        println!("cached oracle state for oracle-publish: {}", path.display());
    } else {
        println!("dry run: oracle state cache left unchanged (nothing was broadcast)");
    }
    Ok(())
}

async fn cmd_oracle_publish(
    name: String,
    price: Option<i64>,
    ts: Option<i64>,
    from_hyperliquid: bool,
    fee: u64,
    push: bool,
) -> Result<()> {
    let (price, ts) = if from_hyperliquid {
        if price.is_some() || ts.is_some() {
            bail!("--from-hyperliquid and --price/--ts are mutually exclusive");
        }
        let http = reqwest::Client::new();
        let mark_px = hyperliquid::fetch_btc_mark_px(&http)
            .await
            .context("reading BTC markPx from Hyperliquid")?;
        let price = hyperliquid::price_str_to_cents(&mark_px)
            .with_context(|| format!("converting Hyperliquid markPx {mark_px:?} to cents"))?;
        let ts = chrono::Utc::now().timestamp();
        println!("hyperliquid: BTC markPx={mark_px} -> price={price} cents, ts={ts}");
        (price, ts)
    } else {
        let (Some(price), Some(ts)) = (price, ts) else {
            bail!("either --from-hyperliquid, or both --price and --ts, are required");
        };
        (price, ts)
    };

    let master_sk = load_master_sk(&name)?;
    let wallet_key = keys::derive_wallet_key(&master_sk, 0);
    let client = chain::client();

    let oracle_state = state::load_oracle_state(&name)
        .context("no cached oracle state for this wallet -- run `oracle-launch` first")?;
    if oracle_state.manager_synthetic_pk != wallet_key.synthetic_pk {
        bail!("this wallet is not the manager key that launched the cached oracle singleton");
    }

    let current_proof: Proof = oracle_state.current_proof();
    let result = builders::oracle::build_oracle_publish(
        oracle_state.launcher_id,
        oracle_state.current_coin,
        current_proof,
        &wallet_key,
        price,
        ts,
        oracle_state.next_sequence,
    )?;

    // The recreated singleton always carries exactly `SINGLETON_AMOUNT` (1
    // mojo) -- it can't also reserve a fee without breaking its own value
    // conservation, so a nonzero fee is paid by a second, ordinary coin of
    // this same wallet, included in the same bundle (see `builders::fee`).
    let mut coin_spends = result.coin_spends;
    let mut signature = result.signature;
    if fee > 0 {
        let (payer, fee_funding_coin) =
            chain::find_funding_coin(&client, &master_sk, fee, chain::ADDRESS_GAP)
                .await
                .context("no second coin to pay the oracle-publish fee with")?;
        let fee_result = builders::fee::build_fee_only(fee_funding_coin, &payer, fee)?;
        coin_spends.extend(fee_result.coin_spends);
        signature = signature + &fee_result.signature;
    }

    print_spend(&coin_spends, &signature, fee)?;
    println!(
        "announced: price={price} ts={ts} sequence={} (singleton execution cost {})",
        oracle_state.next_sequence, result.singleton_execution_cost
    );

    // Only advance the cached generation pointer once this spend has
    // actually been broadcast -- a dry run never recreates the singleton on
    // chain, so advancing the cache here would point the *next*
    // oracle-publish at a coin that doesn't exist yet (this exact bug
    // caused a real testnet11 incident during week 1, day 3/4 -- see
    // `docs/research/week1_testnet_results.md`).
    let pushed = maybe_push(&client, coin_spends, signature, push).await?;
    if pushed {
        let next_state = state::OracleState {
            launcher_id: oracle_state.launcher_id,
            manager_synthetic_pk: oracle_state.manager_synthetic_pk,
            current_coin: result.next_coin,
            current_proof_dto: result.next_proof.into(),
            next_sequence: oracle_state.next_sequence + 1,
        };
        let path = state::save_oracle_state(&name, &next_state)?;
        println!("updated cached oracle state: {}", path.display());
    } else {
        println!("dry run: oracle state cache left unchanged (nothing was broadcast)");
    }
    Ok(())
}

// --- shared: printing + pushing ---------------------------------------------

fn print_spend(
    coin_spends: &[chia_protocol::CoinSpend],
    signature: &chia_bls::Signature,
    fee: u64,
) -> Result<()> {
    let cost = cost::measure_bundle_cost(coin_spends)?;
    let json = spend_json::spend_bundle_json(coin_spends, signature);
    println!("{}", serde_json::to_string_pretty(&json)?);
    println!(
        "clvm cost: total={} byte={} execution={} condition={}",
        cost.total_cost, cost.byte_cost, cost.execution_cost, cost.condition_cost
    );
    println!("fee: {fee} mojos");
    Ok(())
}

/// Returns `Ok(true)` iff a bundle was actually broadcast (and accepted by
/// coinset.org's mempool). Callers use this to decide whether it's safe to
/// update any on-disk cache of the resulting chain state -- a dry run
/// doesn't create anything on chain, so caching its speculative result
/// would desync the cache from reality (see `cmd_issue_play`,
/// `cmd_oracle_launch`, `cmd_oracle_publish`).
async fn maybe_push(
    client: &CoinsetClient,
    coin_spends: Vec<chia_protocol::CoinSpend>,
    signature: chia_bls::Signature,
    push: bool,
) -> Result<bool> {
    if push {
        let bundle = chia_protocol::SpendBundle::new(coin_spends, signature);
        push_bundle(client, bundle).await?;
        Ok(true)
    } else {
        println!("dry-run only (pass --push to broadcast)");
        Ok(false)
    }
}

async fn push_bundle(client: &CoinsetClient, bundle: chia_protocol::SpendBundle) -> Result<()> {
    let response = client.push_tx(bundle).await.context("push_tx")?;
    if !response.success {
        bail!(
            "push_tx failed: {}",
            response.error.unwrap_or_else(|| "(no error message)".to_string())
        );
    }
    println!("pushed: status={:?}", response.status);
    Ok(())
}
