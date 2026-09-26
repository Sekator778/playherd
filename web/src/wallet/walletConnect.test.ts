/**
 * `wallet/walletConnect.ts` against a mocked `@walletconnect/sign-client` (task item 2 — "unit
 * tests with a mocked SignClient") — no real relay/network/Sage instance is reachable from this
 * environment (`docs/research/week1_wallet_auth.md`'s own research note), so every assertion here
 * is about THIS module's own orchestration (namespace/method names, address identity per Р-57,
 * session reuse, disconnect handling), not about round-tripping with a live Sage.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";

const LAST_ADDRESS_KEY = "herd_walletconnect_last_address_v1";
const CHIA_SESSION = { topic: "topic-1", namespaces: { chia: { chains: ["chia:testnet"] } } };

const connectMock = vi.fn();
const requestMock = vi.fn();
const disconnectMock = vi.fn();
const sessionGetAllMock = vi.fn();
let sessionDeleteHandler: ((data: { topic: string }) => void) | undefined;

const initMock = vi.fn(async () => ({
  connect: connectMock,
  request: requestMock,
  disconnect: disconnectMock,
  session: { getAll: sessionGetAllMock },
  on: (event: string, cb: (data: { topic: string }) => void) => {
    if (event === "session_delete") sessionDeleteHandler = cb;
  },
}));

vi.mock("@walletconnect/sign-client", () => ({
  default: { init: () => initMock() },
}));

vi.mock("@walletconnect/utils", () => ({
  getSdkError: vi.fn(() => ({ code: 6000, message: "User disconnected." })),
}));

beforeEach(() => {
  vi.resetModules();
  vi.unstubAllEnvs();
  localStorage.clear();
  connectMock.mockReset();
  requestMock.mockReset();
  disconnectMock.mockReset();
  sessionGetAllMock.mockReset().mockReturnValue([]);
  initMock.mockClear();
  sessionDeleteHandler = undefined;
});

describe("getWalletConnectProjectId / isWalletConnectAvailable", () => {
  it("falls back to the build-time env var when no runtime override is given", async () => {
    vi.stubEnv("VITE_WALLETCONNECT_PROJECT_ID", "build-time-id");
    const { getWalletConnectProjectId, isWalletConnectAvailable } = await import("./walletConnect");
    expect(getWalletConnectProjectId()).toBe("build-time-id");
    expect(isWalletConnectAvailable()).toBe(true);
  });

  it("prefers the runtime serverInfo override over the build-time env var", async () => {
    vi.stubEnv("VITE_WALLETCONNECT_PROJECT_ID", "build-time-id");
    const { getWalletConnectProjectId } = await import("./walletConnect");
    expect(getWalletConnectProjectId({ walletConnectProjectId: "runtime-id" } as never)).toBe("runtime-id");
  });

  it("treats an empty/whitespace project id (either source) as absent", async () => {
    vi.stubEnv("VITE_WALLETCONNECT_PROJECT_ID", "   ");
    const { getWalletConnectProjectId, isWalletConnectAvailable } = await import("./walletConnect");
    expect(getWalletConnectProjectId({ walletConnectProjectId: "  " } as never)).toBeNull();
    expect(isWalletConnectAvailable(null)).toBe(false);
  });

  it("is unavailable with neither a build-time nor a runtime project id", async () => {
    const { isWalletConnectAvailable } = await import("./walletConnect");
    expect(isWalletConnectAvailable()).toBe(false);
  });
});

describe("connectAndSignWithSage", () => {
  it("refuses to even try without a projectId", async () => {
    const { connectAndSignWithSage } = await import("./walletConnect");
    await expect(
      connectAndSignWithSage("", async () => "msg", () => {}),
    ).rejects.toThrow(/not configured/);
    expect(initMock).not.toHaveBeenCalled();
  });

  it("first-time connect: pairs, reports the URI, learns the address via chia_getAddress, then signs", async () => {
    connectMock.mockResolvedValue({
      uri: "wc:abc@2?relay-protocol=irn&symKey=deadbeef",
      approval: vi.fn().mockResolvedValue(CHIA_SESSION),
    });
    requestMock
      .mockResolvedValueOnce({ address: "txch1newaddr" }) // chia_getAddress
      .mockResolvedValueOnce({ publicKey: "0xpub", signature: "0xsig" }); // chia_signMessageByAddress

    const { connectAndSignWithSage } = await import("./walletConnect");
    const onUri = vi.fn();
    const getMessage = vi.fn().mockResolvedValue("Crowd vs Market login challenge");

    const result = await connectAndSignWithSage("project-123", getMessage, onUri);

    expect(onUri).toHaveBeenCalledWith("wc:abc@2?relay-protocol=irn&symKey=deadbeef");
    expect(connectMock).toHaveBeenCalledWith({
      requiredNamespaces: {
        chia: { chains: ["chia:testnet"], methods: ["chia_getAddress", "chia_signMessageByAddress"], events: [] },
      },
    });
    expect(getMessage).toHaveBeenCalledWith("txch1newaddr");
    expect(requestMock).toHaveBeenNthCalledWith(1, {
      topic: "topic-1",
      chainId: "chia:testnet",
      request: { method: "chia_getAddress", params: {} },
    });
    expect(requestMock).toHaveBeenNthCalledWith(2, {
      topic: "topic-1",
      chainId: "chia:testnet",
      request: {
        method: "chia_signMessageByAddress",
        params: { message: "Crowd vs Market login challenge", address: "txch1newaddr" },
      },
    });
    expect(result).toEqual({ address: "txch1newaddr", publicKey: "0xpub", signature: "0xsig" });
  });

  it("returning player (Р-57): skips pairing and chia_getAddress, signs with the persisted address", async () => {
    localStorage.setItem(LAST_ADDRESS_KEY, "txch1knownaddr");
    sessionGetAllMock.mockReturnValue([CHIA_SESSION]);
    requestMock.mockResolvedValueOnce({ publicKey: "0xpub2", signature: "0xsig2" });

    const { connectAndSignWithSage } = await import("./walletConnect");
    const onUri = vi.fn();
    const getMessage = vi.fn().mockResolvedValue("challenge text");

    const result = await connectAndSignWithSage("project-123", getMessage, onUri);

    expect(connectMock).not.toHaveBeenCalled();
    expect(onUri).not.toHaveBeenCalled();
    expect(getMessage).toHaveBeenCalledWith("txch1knownaddr");
    expect(requestMock).toHaveBeenCalledTimes(1);
    expect(requestMock).toHaveBeenCalledWith({
      topic: "topic-1",
      chainId: "chia:testnet",
      request: { method: "chia_signMessageByAddress", params: { message: "challenge text", address: "txch1knownaddr" } },
    });
    expect(result).toEqual({ address: "txch1knownaddr", publicKey: "0xpub2", signature: "0xsig2" });
  });

  it("a returning player whose persisted session has expired falls back to a fresh pairing, still signing with the same address", async () => {
    localStorage.setItem(LAST_ADDRESS_KEY, "txch1knownaddr");
    sessionGetAllMock.mockReturnValue([]); // no live session anymore
    connectMock.mockResolvedValue({ uri: "wc:fresh", approval: vi.fn().mockResolvedValue(CHIA_SESSION) });
    requestMock.mockResolvedValueOnce({ publicKey: "pk", signature: "sig" });

    const { connectAndSignWithSage } = await import("./walletConnect");
    const onUri = vi.fn();
    await connectAndSignWithSage("project-123", async () => "msg", onUri);

    expect(connectMock).toHaveBeenCalledTimes(1);
    expect(onUri).toHaveBeenCalledWith("wc:fresh");
    // Still the persisted address, not a fresh chia_getAddress round trip.
    expect(requestMock).toHaveBeenCalledTimes(1);
    expect(requestMock).toHaveBeenCalledWith(
      expect.objectContaining({ request: expect.objectContaining({ method: "chia_signMessageByAddress" }) }),
    );
  });
});

describe("session_delete handling and disconnectSage", () => {
  it("clears the persisted address once the last chia session is remotely deleted", async () => {
    sessionGetAllMock.mockReturnValueOnce([CHIA_SESSION]).mockReturnValue([]);
    requestMock.mockResolvedValueOnce({ address: "txch1a" }).mockResolvedValueOnce({ publicKey: "pk", signature: "sig" });
    connectMock.mockResolvedValue({ uri: "wc:x", approval: vi.fn().mockResolvedValue(CHIA_SESSION) });

    const mod = await import("./walletConnect");
    await mod.connectAndSignWithSage("proj", async () => "msg", () => {});
    expect(localStorage.getItem(LAST_ADDRESS_KEY)).toBeNull(); // connecting alone never persists it

    mod.rememberSageAddress("txch1a");
    expect(localStorage.getItem(LAST_ADDRESS_KEY)).toBe("txch1a");

    expect(sessionDeleteHandler).toBeTypeOf("function");
    sessionDeleteHandler?.({ topic: "topic-1" }); // no chia sessions remain (mocked above)

    expect(localStorage.getItem(LAST_ADDRESS_KEY)).toBeNull();
  });

  it("keeps the persisted address if another chia session is still open after one is deleted", async () => {
    const otherSession = { topic: "topic-2", namespaces: { chia: { chains: ["chia:testnet"] } } };
    sessionGetAllMock.mockReturnValueOnce([CHIA_SESSION]).mockReturnValue([otherSession]);
    requestMock.mockResolvedValueOnce({ address: "txch1a" }).mockResolvedValueOnce({ publicKey: "pk", signature: "sig" });
    connectMock.mockResolvedValue({ uri: "wc:x", approval: vi.fn().mockResolvedValue(CHIA_SESSION) });

    const mod = await import("./walletConnect");
    await mod.connectAndSignWithSage("proj", async () => "msg", () => {});
    mod.rememberSageAddress("txch1a");

    sessionDeleteHandler?.({ topic: "topic-1" });

    expect(localStorage.getItem(LAST_ADDRESS_KEY)).toBe("txch1a");
  });

  it("disconnectSage disconnects every open chia session and forgets the persisted address", async () => {
    sessionGetAllMock.mockReturnValueOnce([CHIA_SESSION]).mockReturnValue([CHIA_SESSION]);
    requestMock.mockResolvedValueOnce({ address: "txch1a" }).mockResolvedValueOnce({ publicKey: "pk", signature: "sig" });
    connectMock.mockResolvedValue({ uri: "wc:x", approval: vi.fn().mockResolvedValue(CHIA_SESSION) });
    disconnectMock.mockResolvedValue(undefined);

    const mod = await import("./walletConnect");
    await mod.connectAndSignWithSage("proj", async () => "msg", () => {}); // creates the cached client
    mod.rememberSageAddress("txch1a");

    await mod.disconnectSage();

    expect(disconnectMock).toHaveBeenCalledWith({ topic: "topic-1", reason: { code: 6000, message: "User disconnected." } });
    expect(localStorage.getItem(LAST_ADDRESS_KEY)).toBeNull();
  });

  it("disconnectSage is a harmless no-op if Sage was never connected to in this session", async () => {
    const { disconnectSage } = await import("./walletConnect");
    await expect(disconnectSage()).resolves.toBeUndefined();
    expect(disconnectMock).not.toHaveBeenCalled();
  });
});
