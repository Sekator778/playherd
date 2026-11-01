//! Deposit-address derivation and deposit-scanning tests for the PLAY
//! withdraw/deposit endpoints (D-6, decision D-53 2026-09-26) -- no network,
//! `DepositService` against `FakePlayChainClient` (`tests/play_common/
//! mod.rs`) and a temp data dir. Exercises the task's explicit requirements:
//! "deposit detection for 2 players", "wrong-asset coin at a deposit address
//! ignored", "deposit-address derivation deterministic".

mod play_common;

use anyhow::Result;
use chia_protocol::{Bytes32, Coin};
use chia_puzzle_types::LineageProof;

use c2_cli::cat_chain::cat_puzzle_hash;
use c2_cli::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_cli::keystore::KeyFile;
use c2_cli::state::PlayCoinState;
use c2_svc::play::chain_client::PuzzleHashCoinRecord;
use c2_svc::play::deposit::{DepositRegistry, DepositService};

use play_common::FakePlayChainClient;

const PLAY_ASSET_ID: [u8; 32] = [3u8; 32];
const OTHER_ASSET_ID: [u8; 32] = [4u8; 32];

fn set_up() -> Result<(tempfile::TempDir, DepositService<FakePlayChainClient>)> {
    let tmp = tempfile::tempdir()?;
    let data_dir = tmp.path().to_path_buf();

    let mnemonic = generate_mnemonic()?;
    let master_sk = master_sk_from_mnemonic(&mnemonic);
    let _operator = derive_wallet_key(&master_sk, 0); // exercised only indirectly, via the key file below

    std::fs::write(
        data_dir.join("operator.json"),
        serde_json::to_string_pretty(&KeyFile {
            mnemonic: mnemonic.to_string(),
            created_utc: "2026-09-26T00:00:00Z".to_string(),
            network: "testnet11".to_string(),
        })?,
    )?;

    // Deposit derivation only ever needs `PlayCoinState::asset_id` -- the
    // coin/lineage proof fields are irrelevant here (nothing spends this
    // state in these tests), so they're filled with structurally valid but
    // otherwise meaningless placeholders.
    let play_state = PlayCoinState::new(
        Bytes32::from(PLAY_ASSET_ID),
        Coin::new(Bytes32::from([9u8; 32]), Bytes32::from([8u8; 32]), 1),
        LineageProof {
            parent_parent_coin_info: Bytes32::from([0u8; 32]),
            parent_inner_puzzle_hash: Bytes32::from([0u8; 32]),
            parent_amount: 1,
        },
    );
    c2_svc::play_state_file::save_play_state_atomic(&data_dir, "operator", &play_state)?;

    let service = DepositService {
        data_dir,
        operator_name: "operator".to_string(),
        chain: FakePlayChainClient::new(),
        registry_lock: tokio::sync::Mutex::new(()),
    };
    Ok((tmp, service))
}

#[tokio::test]
async fn deposit_address_derivation_is_deterministic() -> Result<()> {
    let (_tmp, service) = set_up()?;

    let first = service.issue_address(5).await?;
    let second = service.issue_address(5).await?;
    assert_eq!(first, second, "re-issuing the same player index must re-derive byte-identical values");

    let different = service.issue_address(6).await?;
    assert_ne!(first.address, different.address);
    assert_ne!(first.cat_puzzle_hash, different.cat_puzzle_hash);

    // Issuing the same player twice must not duplicate the registry entry.
    let registry = DepositRegistry::load(&service.data_dir, &service.operator_name)?;
    assert_eq!(registry.players.len(), 2);

    Ok(())
}

#[tokio::test]
async fn deposit_detection_for_two_players() -> Result<()> {
    let (_tmp, service) = set_up()?;

    let player1 = service.issue_address(1).await?;
    let player2 = service.issue_address(2).await?;

    let coin1 = Bytes32::from([10u8; 32]);
    let coin2 = Bytes32::from([11u8; 32]);
    service.chain.add_puzzle_hash_record(PuzzleHashCoinRecord {
        coin_id: coin1,
        puzzle_hash: Bytes32::try_from(hex::decode(player1.cat_puzzle_hash.trim_start_matches("0x"))?.as_slice())?,
        amount: 5_000,
        confirmed_height: 100,
        spent: false,
    });
    service.chain.add_puzzle_hash_record(PuzzleHashCoinRecord {
        coin_id: coin2,
        puzzle_hash: Bytes32::try_from(hex::decode(player2.cat_puzzle_hash.trim_start_matches("0x"))?.as_slice())?,
        amount: 7_500,
        confirmed_height: 101,
        spent: false,
    });
    service.chain.set_peak_height(200);

    let response = service.list_deposits(0).await?;
    assert_eq!(response.tip_height, 200);
    assert_eq!(response.deposits.len(), 2);

    let d1 = response.deposits.iter().find(|d| d.player_index == 1).expect("player 1's deposit");
    assert_eq!(d1.amount_mojos, 5_000);
    assert_eq!(d1.height, 100);
    assert_eq!(d1.coin_id, format!("0x{coin1}"));

    let d2 = response.deposits.iter().find(|d| d.player_index == 2).expect("player 2's deposit");
    assert_eq!(d2.amount_mojos, 7_500);
    assert_eq!(d2.height, 101);

    Ok(())
}

#[tokio::test]
async fn wrong_asset_coin_at_a_deposit_address_is_ignored() -> Result<()> {
    let (_tmp, service) = set_up()?;

    let player = service.issue_address(1).await?;
    let inner_puzzle_hash =
        Bytes32::try_from(hex::decode(player.inner_puzzle_hash.trim_start_matches("0x"))?.as_slice())?;

    // A coin of a *different* asset id, sitting at the very same inner
    // puzzle hash -- its CAT-layer puzzle hash is completely different from
    // the registered PLAY deposit puzzle hash (`cat_puzzle_hash` curries the
    // asset id in), so it must never be picked up as a deposit.
    let wrong_asset_ph = cat_puzzle_hash(Bytes32::from(OTHER_ASSET_ID), inner_puzzle_hash);
    service.chain.add_puzzle_hash_record(PuzzleHashCoinRecord {
        coin_id: Bytes32::from([12u8; 32]),
        puzzle_hash: wrong_asset_ph,
        amount: 999_999,
        confirmed_height: 50,
        spent: false,
    });

    let response = service.list_deposits(0).await?;
    assert!(response.deposits.is_empty(), "a wrong-asset coin at a deposit address must be ignored");

    Ok(())
}
