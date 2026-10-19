/** API bots in the mock (Р-106): same contract as the server's `createBot` / `myBots` / `regenerateBotKey` / `revokeBotKey` / `botRatings`. */
import { describe, expect, it } from "vitest";
import { ApiRequestError } from "./contract";
import { MockApi } from "./mockApi";

async function expectApiError(promise: Promise<unknown>, code: string): Promise<void> {
  const err = await promise.then(
    () => null,
    (e: unknown) => e,
  );
  expect(err).toBeInstanceOf(ApiRequestError);
  expect((err as ApiRequestError).code).toBe(code);
}

async function signedInApi(address: string): Promise<MockApi> {
  const api = new MockApi();
  const challenge = await api.authChallenge({});
  await api.authVerify({ nonce: challenge.nonce, address, publicKey: "pk", signature: "sig" });
  return api;
}

describe("API bots in the mock (same contract as the server, Р-106)", () => {
  it("creates a bot with a key shown once; the list carries only the hint", async () => {
    const api = await signedInApi("txch1botmock");
    const created = await api.createBot(" Alpha ");
    expect(created.apiKey).toMatch(/^herd_[A-Za-z0-9_-]{43}$/);
    expect(created.bot).toMatchObject({ nickname: "Alpha", keyActive: true });
    expect(created.bot.keyHint).toBe(`herd_...${created.apiKey.slice(-4)}`);
    const listed = await api.myBots();
    expect(listed).toHaveLength(1);
    expect(JSON.stringify(listed)).not.toContain(created.apiKey);
  });

  it("at most 3 bots, a bad name is INVALID_NICKNAME", async () => {
    const api = await signedInApi("txch1botmock2");
    await api.createBot("Bot one");
    await api.createBot("Bot two");
    await api.createBot("Bot three");
    await expectApiError(api.createBot("Bot four"), "BOT_LIMIT_REACHED");
    await expectApiError(api.createBot("x"), "INVALID_NICKNAME");
  });

  it("regenerating replaces the key, revoking leaves the bot without one", async () => {
    const api = await signedInApi("txch1botmock3");
    const first = await api.createBot("Alpha");
    const second = await api.regenerateBotKey(first.bot.playerId);
    expect(second.apiKey).not.toBe(first.apiKey);
    expect(second.bot.keyHint).not.toBe(first.bot.keyHint);
    const revoked = await api.revokeBotKey(first.bot.playerId);
    expect(revoked).toMatchObject({ keyActive: false, keyHint: null });
    expect((await api.myBots())[0]).toMatchObject({ keyActive: false, keyHint: null });
    await expectApiError(api.revokeBotKey("api-nope"), "NOT_FOUND");
  });

  it("needs a signed-in player, and forgets the bots at sign-out", async () => {
    const api = new MockApi();
    await expectApiError(api.myBots(), "NOT_SIGNED_IN");
    await expectApiError(api.createBot("Alpha"), "NOT_SIGNED_IN");
    const signed = await signedInApi("txch1botmock4");
    await signed.createBot("Alpha");
    await signed.signOut();
    const challenge = await signed.authChallenge({});
    await signed.authVerify({ nonce: challenge.nonce, address: "txch1botmock4", publicKey: "pk", signature: "sig" });
    expect(await signed.myBots()).toEqual([]);
  });

  it("the bots' board is empty in the mock, with the people's threshold", async () => {
    const boards = await new MockApi().botRatings();
    expect(boards).toMatchObject({ roundsThreshold: 10, rich: [], prophets: [] });
  });
});
