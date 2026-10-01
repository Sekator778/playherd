/**
 * The PLAY token's asset id (the CAT's TAIL hash) — what a player pastes into Sage to name the token it
 * shows as "Unknown CAT" (`docs/research/sage_real_e2e.md` «Трение» п. 2).
 *
 * The default is the testnet11 issuance: `0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70`
 * (`docs/research/week1_testnet_results.md:60`, block 4 735 648). It is a property of the DEPLOYMENT (the
 * chain service reads its tail from its own state), not of the web build — a stand or a later network with
 * another issuance sets `VITE_PLAY_ASSET_ID` when building. The contract has no field to carry it (the
 * server should add one to `ServerInfoDto`; noted in `docs/product/design/markets-lobby.md`).
 */
const TESTNET11_PLAY_ASSET_ID = "0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70";

export const PLAY_ASSET_ID: string = (import.meta.env.VITE_PLAY_ASSET_ID as string | undefined)?.trim() || TESTNET11_PLAY_ASSET_ID;

/** The network fee that worked for a PLAY transfer from Sage on a busy testnet11: 0.0005 TXCH
 *  (500 000 000 mojos; `docs/research/sage_real_e2e.md` — a transfer with fee 0 did not go through). */
export const RECOMMENDED_DEPOSIT_FEE = "0.0005 TXCH";
