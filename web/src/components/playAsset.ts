/**
 * The PLAY token's asset id (the CAT's TAIL hash) — what a player pastes into Sage to name the token it
 * shows as "Unknown CAT" (`docs/research/sage_real_e2e.md` «Трение» п. 2).
 *
 * The server knows it: `ServerInfoDto.playAssetId` (the chain service reads its tail from its own state), and
 * {@link playAssetIdOf} prefers that. The build-time {@link PLAY_ASSET_ID} is the fallback for a server that does
 * not send one (it has no chain service configured, or predates the field): by default the testnet11 issuance
 * `0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70` (`docs/research/week1_testnet_results.md:60`,
 * block 4 735 648); a stand or a later network with another issuance sets `VITE_PLAY_ASSET_ID` when building.
 */
const TESTNET11_PLAY_ASSET_ID = "0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70";

export const PLAY_ASSET_ID: string = (import.meta.env.VITE_PLAY_ASSET_ID as string | undefined)?.trim() || TESTNET11_PLAY_ASSET_ID;

/** The network fee that worked for a PLAY transfer from Sage on a busy testnet11: 0.0005 TXCH
 *  (500 000 000 mojos; `docs/research/sage_real_e2e.md` — a transfer with fee 0 did not go through). */
export const RECOMMENDED_DEPOSIT_FEE = "0.0005 TXCH";

const ASSET_ID = /^(0x)?[0-9a-f]{64}$/i;

/**
 * The PLAY asset id to show a player: the one the server sends (`ServerInfoDto.playAssetId`, "0x" + 64 hex) when it
 * is a well-formed asset id, normalised to lower-case with the "0x"; otherwise the build-time fallback. A value
 * that is not an asset id is never shown — a player would paste it into Sage and add the wrong token.
 */
export function playAssetIdOf(info: { playAssetId?: string } | null | undefined): string {
  const sent = info?.playAssetId?.trim();
  if (sent && ASSET_ID.test(sent)) return `0x${sent.replace(/^0x/i, "").toLowerCase()}`;
  return PLAY_ASSET_ID;
}
