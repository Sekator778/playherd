//! Local caches of chain state this CLI itself produced: the PLAY CAT coin a
//! wallet last received from `issue-play` (so `offer-make` doesn't need a
//! general-purpose CAT-coin chain scanner -- see
//! `docs/research/week1_testnet_cli.md` for why), and an oracle singleton's
//! current generation (so `oracle-publish` can recreate it in the next
//! process). Stored next to the key file, `~/.c2/testnet11/`, but unlike the
//! key file these are just a cache of public chain data and are freely
//! overwritten.
//!
//! `Coin`/`Bytes32`/`PublicKey` already have `serde` impls (`chia-protocol`'s
//! `serde` feature, transitively required by `chia-sdk-coinset`, itself
//! enables `chia-bls/serde`). `chia_puzzle_types::Proof`/`LineageProof` have
//! none (that crate never derives `serde` at all), so `ProofDto` below
//! mirrors them by hand.

use std::fs;
use std::path::PathBuf;

use anyhow::{Context, Result, bail};
use chia_bls::PublicKey;
use chia_protocol::{Bytes32, Coin};
use chia_puzzle_types::{EveProof, LineageProof, Proof};
use serde::{Deserialize, Serialize};

use crate::keystore::keys_dir;

fn state_path(name: &str, suffix: &str) -> Result<PathBuf> {
    Ok(keys_dir()?.join(format!("{name}.{suffix}.json")))
}

fn save<T: Serialize>(path: &PathBuf, value: &T) -> Result<()> {
    crate::keystore::ensure_key_dirs()?;
    let json = serde_json::to_string_pretty(value)?;
    fs::write(path, json).with_context(|| format!("writing {}", path.display()))
}

fn load<T: for<'de> Deserialize<'de>>(path: &PathBuf) -> Result<T> {
    let data = fs::read_to_string(path).with_context(|| format!("reading {}", path.display()))?;
    serde_json::from_str(&data).with_context(|| format!("parsing {}", path.display()))
}

/// Hand-written serde mirror of `chia_puzzle_types::Proof` (see module doc).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind")]
pub enum ProofDto {
    Eve {
        parent_parent_coin_info: Bytes32,
        parent_amount: u64,
    },
    Lineage {
        parent_parent_coin_info: Bytes32,
        parent_inner_puzzle_hash: Bytes32,
        parent_amount: u64,
    },
}

impl From<Proof> for ProofDto {
    fn from(proof: Proof) -> Self {
        match proof {
            Proof::Eve(EveProof {
                parent_parent_coin_info,
                parent_amount,
            }) => ProofDto::Eve {
                parent_parent_coin_info,
                parent_amount,
            },
            Proof::Lineage(LineageProof {
                parent_parent_coin_info,
                parent_inner_puzzle_hash,
                parent_amount,
            }) => ProofDto::Lineage {
                parent_parent_coin_info,
                parent_inner_puzzle_hash,
                parent_amount,
            },
        }
    }
}

impl From<ProofDto> for Proof {
    fn from(dto: ProofDto) -> Self {
        match dto {
            ProofDto::Eve {
                parent_parent_coin_info,
                parent_amount,
            } => Proof::Eve(EveProof {
                parent_parent_coin_info,
                parent_amount,
            }),
            ProofDto::Lineage {
                parent_parent_coin_info,
                parent_inner_puzzle_hash,
                parent_amount,
            } => Proof::Lineage(LineageProof {
                parent_parent_coin_info,
                parent_inner_puzzle_hash,
                parent_amount,
            }),
        }
    }
}

// --- PLAY CAT coin cache -----------------------------------------------

#[derive(Debug, Serialize, Deserialize)]
pub struct PlayCoinState {
    pub asset_id: Bytes32,
    pub coin: Coin,
    pub lineage_proof_dto: ProofDto,
}

impl PlayCoinState {
    pub fn new(asset_id: Bytes32, coin: Coin, lineage_proof: LineageProof) -> Self {
        Self {
            asset_id,
            coin,
            lineage_proof_dto: Proof::Lineage(lineage_proof).into(),
        }
    }

    pub fn lineage_proof(&self) -> Result<LineageProof> {
        match Proof::from(self.lineage_proof_dto.clone()) {
            Proof::Lineage(lp) => Ok(lp),
            Proof::Eve(_) => bail!("PLAY coin state has an Eve proof, expected Lineage"),
        }
    }
}

pub fn save_play_coin(name: &str, state: &PlayCoinState) -> Result<PathBuf> {
    let path = state_path(name, "play")?;
    save(&path, state)?;
    Ok(path)
}

pub fn load_play_coin(name: &str) -> Result<PlayCoinState> {
    load(&state_path(name, "play")?)
}

// --- Oracle singleton state ----------------------------------------------

#[derive(Debug, Serialize, Deserialize)]
pub struct OracleState {
    pub launcher_id: Bytes32,
    pub manager_synthetic_pk: PublicKey,
    pub current_coin: Coin,
    pub current_proof_dto: ProofDto,
    pub next_sequence: u64,
}

impl OracleState {
    pub fn current_proof(&self) -> Proof {
        self.current_proof_dto.clone().into()
    }
}

pub fn save_oracle_state(name: &str, state: &OracleState) -> Result<PathBuf> {
    let path = state_path(name, "oracle")?;
    save(&path, state)?;
    Ok(path)
}

pub fn load_oracle_state(name: &str) -> Result<OracleState> {
    load(&state_path(name, "oracle")?)
}
