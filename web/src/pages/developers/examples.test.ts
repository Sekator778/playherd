/// <reference types="node" />
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { wire, type WireName } from "../../api/wireSource";
import { CALL_EXAMPLES, ERROR_ROWS, PYTHON_BOT } from "./examples";

/** The repository root: vitest runs in `web/`. */
const ROOT = join(process.cwd(), "..");
const rpcResource = join(ROOT, "server/src/main/java/crowdvsmarket/server/api/RpcResource.java");
const docs = join(ROOT, "docs/api/bots.md");

/** The server's own list of the calls an API key may make (`RpcResource.API_KEY_METHODS`). */
function serverKeyMethods(): string[] {
  const source = readFileSync(rpcResource, "utf8");
  const match = /API_KEY_METHODS\s*=\s*Set\.of\(([^;]*)\);/.exec(source);
  if (!match) throw new Error("RpcResource.API_KEY_METHODS not found - the developers' page cannot be held against the server");
  return [...match[1].matchAll(/"([A-Za-z]+)"/g)].map((m) => m[1]);
}

/** Every key of `excerpt` is in `whole` with the same value (arrays: element by element, the excerpt may be shorter). */
function differences(excerpt: unknown, whole: unknown, path: string): string[] {
  if (excerpt === null || typeof excerpt !== "object") {
    return excerpt === whole ? [] : [`${path}: the page says ${JSON.stringify(excerpt)}, the recorded answer ${JSON.stringify(whole)}`];
  }
  if (whole === null || typeof whole !== "object") return [`${path}: the page has an object, the recorded answer ${JSON.stringify(whole)}`];
  if (Array.isArray(excerpt)) {
    if (!Array.isArray(whole)) return [`${path}: the page has an array, the recorded answer does not`];
    return excerpt.flatMap((item, i) => differences(item, whole[i], `${path}[${i}]`));
  }
  return Object.entries(excerpt).flatMap(([key, value]) => {
    if (!(key in (whole as Record<string, unknown>))) return [`${path}.${key}: not in the recorded answer`];
    return differences(value, (whole as Record<string, unknown>)[key], `${path}.${key}`);
  });
}

function at(value: unknown, path: string | undefined): unknown {
  if (!path) return value;
  return path.split(".").reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], value);
}

describe("the developers' page and the server", () => {
  it("lists exactly the calls the server lets a key make", () => {
    expect(CALL_EXAMPLES.map((c) => c.method).sort()).toEqual([...serverKeyMethods()].sort());
  });

  it("has every call once", () => {
    const methods = CALL_EXAMPLES.map((c) => c.method);
    expect(new Set(methods).size).toBe(methods.length);
  });

  it.each(CALL_EXAMPLES.filter((c) => c.fixture !== undefined))("$method: the response is an excerpt of the recorded answer $fixture", (example) => {
    const recorded = wire(example.fixture as WireName);
    const problems = differences(example.response, at(recorded.response, example.at), example.method);
    expect(problems, problems.join("\n")).toEqual([]);
  });

  it("every call that shows a response says where it comes from", () => {
    for (const example of CALL_EXAMPLES) {
      if (example.response === undefined || example.method === "cancelBet") continue;
      expect(example.fixture, `${example.method} shows a response but names no fixture`).toBeDefined();
    }
  });

  it("every error code is one the server has", () => {
    const enums = readFileSync(join(ROOT, "server/src/main/java/crowdvsmarket/server/dto/Enums.java"), "utf8");
    for (const row of ERROR_ROWS) expect(enums, row.code).toContain(row.code);
  });
});

describe("docs/api/bots.md", () => {
  const text = existsSync(docs) ? readFileSync(docs, "utf8") : "";

  it("is there", () => {
    expect(text.length).toBeGreaterThan(1000);
  });

  it("describes every call, with the request and the excerpt the page shows", () => {
    for (const example of CALL_EXAMPLES) {
      expect(text, example.method).toContain(`### \`${example.method}\``);
      expect(text, `${example.method} request`).toContain(`{"args": ${JSON.stringify(example.args)}}`);
      if (example.response !== undefined) expect(text, `${example.method} response`).toContain(JSON.stringify(example.response, null, 2));
    }
  });

  it("lists every error code and the example bot as the page shows them", () => {
    for (const row of ERROR_ROWS) expect(text, row.code).toContain(`| \`${row.code}\` | ${row.http} | ${row.meaning} |`);
    expect(text).toContain(PYTHON_BOT);
  });

  it("keeps a marked place for the payout modes, and says an absent mode is today's", () => {
    expect(text).toContain("## 8. Payout modes");
    expect(text).toMatch(/WITHOUT `mode`[^\n]*percentage mode/);
  });

  it("calls the game's money PLAY and never describes it as a plaything", () => {
    expect(text.toLowerCase()).not.toMatch(/\btoy\b|play money|play-money|fake money/);
  });
});

describe("the example bot", () => {
  it("is valid Python (compiles), when a python3 is there to say so", () => {
    const run = spawnSync("python3", ["-I", "-c", "import sys; compile(sys.stdin.read(), 'bot.py', 'exec')"], { input: PYTHON_BOT, encoding: "utf8" });
    if (run.error) return; // no python3 on this machine: the check is skipped, not failed
    expect(run.stderr).toBe("");
    expect(run.status).toBe(0);
  });

  it("uses only the standard library", () => {
    const imports = [...PYTHON_BOT.matchAll(/^(?:import|from)\s+([a-z_.]+)/gm)].map((m) => m[1].split(".")[0]);
    expect(imports.sort()).toEqual(["json", "os", "time", "urllib", "urllib"]);
  });

  it("only makes calls a key may make", () => {
    const used = [...PYTHON_BOT.matchAll(/call\("([A-Za-z]+)"/g)].map((m) => m[1]);
    expect(used.length).toBeGreaterThan(0);
    for (const method of used) expect(serverKeyMethods(), method).toContain(method);
  });
});
