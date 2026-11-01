/**
 * Real Sage sign-in over WalletConnect v2 (D-4/D-55, `docs/research/week1_wallet_auth.md`
 * §1.1–§1.2 and its "Реализация" appendix below for the parts that proposal left open).
 *
 * Needs a Reown Cloud `projectId` — Alex's registration is in progress but not usable by this
 * session yet (outbound registrations are only ever made with Alex's own hands/go-ahead,
 * `the project notes` §"Зафиксированные решения"). Until one is configured (`getWalletConnectProjectId`
 * below resolves to `null`), `ConnectWalletModal.tsx` disables the "Connect with Sage" button
 * instead of ever calling into this module — every exported connect function here still throws
 * defensively if called without one, as a second guard.
 *
 * `@walletconnect/sign-client` check (task item 2 — "check licence and size"):
 * - **License**: npm `2.25.0` (checked 2026-09-26) reports `"SEE LICENSE IN LICENSE.md"` — that
 *   file is Reown's own **"WalletConnect Community License Agreement"**
 *   (`github.com/WalletConnect/walletconnect-monorepo`, `packages/sign-client/LICENSE.md`,
 *   "Release Date: 20 August 2025", read via `gh api` at HEAD `f2ec89dd` today) — **not**
 *   Apache-2.0/MIT like this project's own code (D-36) or every other dependency here. That's
 *   fine: it's a dependency used under its own terms, not code of ours. Terms that actually bind
 *   us: (1) royalty-free below **2,500,000 RPCs/month or 500 MAU/month** (§3) — the closed alpha
 *   (D-42: "5–10 человек по ссылке") is nowhere near either, revisit before any wider opening;
 *   (2) must connect to Reown's own relay, never a self-hosted one (§2.b) — exactly what
 *   `RELAY_URL` below does, the same default Sage itself uses (`week1_wallet_auth.md` §1.1); (3)
 *   requires the notice **"Portions © 2025 Reown, Inc. All Rights Reserved"** wherever the
 *   product is distributed (§2.a) — added to `web/README.md`.
 * - **Size**: the package's own `dist/` is ~2.7 MB unpacked (`npm view @walletconnect/sign-client
 *   dist.unpackedSize`) before whatever a bundler tree-shakes, plus its dependency tree (86
 *   packages total when installed, `npm install` output 2026-09-26) — dynamically imported here
 *   (same pattern as `chia-wallet-sdk-wasm` in `wallet/testWallet.ts`) so none of it reaches a
 *   visitor who only ever uses the test wallet.
 *
 * Sage's exact namespace/methods (re-verified today against `xch-dev/sage` HEAD `f2ec89dd` — the
 * same commit `week1_wallet_auth.md` cites, unmoved since): `chia:testnet` — **not**
 * `chia:testnet11` — is the chain id Sage's `requiredNamespaces` check actually accepts
 * (`WalletConnectContext.tsx:174-176`, cited in that doc's §1.1; Sage has no separate
 * testnet11-vs-other-testnet WalletConnect namespace). `chia_getAddress` (`params: {}` →
 * `{ address }`, `confirm: false` — no Sage approval dialog for this one) and
 * `chia_signMessageByAddress` (`params: { message, address }` → `{ publicKey, signature }`,
 * `confirm: true`) both read straight from `src/walletconnect/commands.ts:224-241` at that same
 * commit — `chia_getAddress` specifically was only assumed by `week1_wallet_auth.md` §4's
 * proposed flow, not yet cited to a source line there; confirmed here today.
 */
import type { ServerInfoDto } from "../api/contract";

/** Structural, not `@walletconnect/types`' own re-exported shape — this module only ever reads
 *  these two fields off a session, so it doesn't need to track that package's exact type names
 *  across versions. */
interface MinimalNamespaces {
  [namespace: string]: { chains?: string[] } | undefined;
}
interface MinimalSession {
  topic: string;
  namespaces: MinimalNamespaces;
}
/** The slice of `@walletconnect/sign-client`'s `SignClient` instance this module actually calls
 *  — kept narrow and structural (not `import type SignClient from "@walletconnect/sign-client"`)
 *  so `wallet/walletConnect.test.ts` can substitute a small fake via
 *  `vi.mock("@walletconnect/sign-client", …)` without the real (un-mocked) type shape ever
 *  mattering to the test. */
interface MinimalSignClient {
  connect(params: {
    requiredNamespaces: Record<string, { chains: string[]; methods: string[]; events: string[] }>;
  }): Promise<{ uri?: string; approval: () => Promise<MinimalSession> }>;
  request<T>(params: { topic: string; chainId: string; request: { method: string; params: unknown } }): Promise<T>;
  disconnect(params: { topic: string; reason: { code: number; message: string } }): Promise<void>;
  session: { getAll(): MinimalSession[] };
  on(event: "session_delete", cb: (data: { topic: string }) => void): void;
}

export interface SageConnectResult {
  address: string;
  publicKey: string;
  signature: string;
}

/** NOT `chia:testnet11` — see module doc above (Sage's own WalletConnect namespace check has no
 *  such chain id; `chia:testnet` is what it actually accepts). */
const CHIA_CHAIN = "chia:testnet";
const RELAY_URL = "wss://relay.walletconnect.org"; // Reown's own relay — the license requires it (see module doc).
const LAST_ADDRESS_KEY = "herd_walletconnect_last_address_v1";

function appMetadata() {
  const origin = typeof window !== "undefined" ? window.location.origin : "https://herd.invalid";
  return {
    name: "Herd",
    description: "Herd — call real prices UP or DOWN · Chia testnet11",
    url: origin,
    icons: [`${origin}/icons/icon-192.png`],
  };
}

/** Build-time (`VITE_WALLETCONNECT_PROJECT_ID`, baked in at `npm run build`) with a runtime
 *  override from the server (`ServerInfoDto.walletConnectProjectId`, task item 2 — "ALSO
 *  overridable at runtime… so Alex's registration reaches a Docker image already built without
 *  it"). An empty/whitespace-only value counts as absent either way. */
export function getWalletConnectProjectId(serverInfo?: ServerInfoDto | null): string | null {
  const runtime = serverInfo?.walletConnectProjectId?.trim();
  if (runtime) return runtime;
  const buildTime = (import.meta.env.VITE_WALLETCONNECT_PROJECT_ID as string | undefined)?.trim();
  return buildTime ? buildTime : null;
}

export function isWalletConnectAvailable(serverInfo?: ServerInfoDto | null): boolean {
  return getWalletConnectProjectId(serverInfo) !== null;
}

function loadLastAddress(): string | null {
  try {
    return localStorage.getItem(LAST_ADDRESS_KEY);
  } catch {
    return null; // e.g. some private-browsing configurations — fall back to a fresh pairing.
  }
}

/** Called by `ConnectWalletModal.tsx` once `Api.authVerify` actually confirms this address, not
 *  by this module itself — signing successfully doesn't mean the backend accepted it. */
export function rememberSageAddress(address: string): void {
  try {
    localStorage.setItem(LAST_ADDRESS_KEY, address);
  } catch {
    // best-effort only, see loadLastAddress
  }
}

function clearLastAddress(): void {
  try {
    localStorage.removeItem(LAST_ADDRESS_KEY);
  } catch {
    // best-effort only
  }
}

function hasChiaChain(session: MinimalSession): boolean {
  return Object.values(session.namespaces).some((ns) => ns?.chains?.includes(CHIA_CHAIN));
}

let cachedClient: { projectId: string; client: Promise<MinimalSignClient> } | null = null;

/** Session persistence (task item 2): `SignClient.init` in a browser persists approved sessions
 *  on its own (the same default init Sage itself uses — `week1_wallet_auth.md` §1.2's quoted
 *  snippet passes no `storage` option either) — a page reload reuses this module's cached client
 *  promise for the lifetime of the tab, and the client's OWN storage survives across reloads, so
 *  `ensureSession` below finds the same approved session again without a fresh pairing. */
async function getClient(projectId: string): Promise<MinimalSignClient> {
  if (cachedClient?.projectId === projectId) return cachedClient.client;
  const clientPromise = import("@walletconnect/sign-client").then(async ({ default: SignClient }) => {
    const client = (await SignClient.init({
      projectId,
      relayUrl: RELAY_URL,
      metadata: appMetadata(),
    })) as unknown as MinimalSignClient;
    // Disconnect/expiry handling (task item 2): Sage disconnecting from its own side (or the
    // session simply expiring) fires this event without us ever calling `disconnect` — if that
    // was the last remaining chia session, the persisted "sign in with this address" hint
    // (D-57) is stale and must not silently keep being offered on the next connect attempt.
    client.on("session_delete", () => {
      if (!client.session.getAll().some(hasChiaChain)) clearLastAddress();
    });
    return client;
  });
  cachedClient = { projectId, client: clientPromise };
  clientPromise.catch(() => {
    if (cachedClient?.client === clientPromise) cachedClient = null; // let the next call retry
  });
  return clientPromise;
}

/** Reuses a still-approved session for `chia:testnet` if one exists (session persistence, see
 *  `getClient`'s doc); otherwise opens a fresh pairing, reports its `uri` via `onPairingUri`
 *  (render it as a QR code and/or behind a "Copy connection link" button — same URI works for
 *  both, `ux_spec.md` §10), and resolves once Sage approves it. */
async function ensureSession(client: MinimalSignClient, onPairingUri: (uri: string) => void): Promise<MinimalSession> {
  const existing = client.session.getAll().find(hasChiaChain);
  if (existing) return existing;
  const { uri, approval } = await client.connect({
    requiredNamespaces: {
      chia: { chains: [CHIA_CHAIN], methods: ["chia_getAddress", "chia_signMessageByAddress"], events: [] },
    },
  });
  if (uri) onPairingUri(uri);
  return approval();
}

/**
 * Connects to Sage over WalletConnect (or reuses an already-approved session) and signs
 * `getMessage(address)`'s result with `chia_signMessageByAddress` — mirrors
 * `wallet/testWallet.ts`'s `signTestWalletMessage`'s "caller supplies the exact challenge text"
 * shape, since both feed the same `Api.authVerify` call in `ConnectWalletModal.tsx`.
 *
 * Address identity (D-57): a RETURNING player (this browser has a `lastAddress` persisted from a
 * previous successful sign-in, `rememberSageAddress`) always signs with THAT SAME address —
 * passed straight to `chia_signMessageByAddress`, never re-derived — even if Sage's current
 * receive address has since rotated (Sage addresses rotate; the player's identity must not). A
 * first-time connect has no persisted address yet, so it calls `chia_getAddress()` once, right
 * after the session is approved, to learn one.
 */
export async function connectAndSignWithSage(
  projectId: string,
  getMessage: (address: string) => Promise<string>,
  onPairingUri: (uri: string) => void,
): Promise<SageConnectResult> {
  if (!projectId.trim()) throw new Error("WalletConnect is not configured (no Reown projectId) — see module doc.");
  const client = await getClient(projectId);
  const session = await ensureSession(client, onPairingUri);
  const knownAddress = loadLastAddress();
  const address =
    knownAddress ??
    (
      await client.request<{ address: string }>({
        topic: session.topic,
        chainId: CHIA_CHAIN,
        request: { method: "chia_getAddress", params: {} },
      })
    ).address;
  const message = await getMessage(address);
  const signed = await client.request<{ publicKey: string; signature: string }>({
    topic: session.topic,
    chainId: CHIA_CHAIN,
    request: { method: "chia_signMessageByAddress", params: { message, address } },
  });
  return { address, publicKey: signed.publicKey, signature: signed.signature };
}

/** Best-effort — disconnects every open `chia:testnet` session and forgets the persisted
 *  address, so the NEXT "Connect with Sage" starts a fresh pairing rather than silently reusing
 *  the previous player's identity. Called from `ApiContext.tsx`'s `signOut` (never awaited there
 *  in a way that can block/fail an app sign-out on Sage's relay being unreachable). */
export async function disconnectSage(): Promise<void> {
  clearLastAddress();
  if (!cachedClient) return;
  try {
    const client = await cachedClient.client;
    const { getSdkError } = await import("@walletconnect/utils");
    await Promise.allSettled(
      client.session
        .getAll()
        .filter(hasChiaChain)
        .map((s) => client.disconnect({ topic: s.topic, reason: getSdkError("USER_DISCONNECTED") })),
    );
  } catch {
    // best-effort only, see doc comment
  }
}
