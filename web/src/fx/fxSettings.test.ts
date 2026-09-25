import { beforeEach, describe, expect, it } from "vitest";
import { getHapticsEnabled, getSfxEnabled, setHapticsEnabled, setPreviewOverrides, setSfxEnabled } from "./fxSettings";

describe("fxSettings", () => {
  beforeEach(() => {
    localStorage.clear();
    setPreviewOverrides({ sfx: null, haptics: null });
  });

  it("defaults: sfx off, haptics on (direction.md §6)", () => {
    expect(getSfxEnabled()).toBe(false);
    expect(getHapticsEnabled()).toBe(true);
  });

  it("round-trips through localStorage", () => {
    setSfxEnabled(true);
    expect(getSfxEnabled()).toBe(true);
    expect(localStorage.getItem("herd:sfxEnabled")).toBe("true");

    setHapticsEnabled(false);
    expect(getHapticsEnabled()).toBe(false);
    expect(localStorage.getItem("herd:hapticsEnabled")).toBe("false");
  });

  it("persists across a fresh read (simulating a reload)", () => {
    setSfxEnabled(true);
    setHapticsEnabled(false);
    // No in-memory state here — every getter re-reads localStorage each call.
    expect(getSfxEnabled()).toBe(true);
    expect(getHapticsEnabled()).toBe(false);
  });

  it("a /lab preview override wins over the persisted value without changing it", () => {
    setSfxEnabled(false);
    setPreviewOverrides({ sfx: true });
    expect(getSfxEnabled()).toBe(true);
    expect(localStorage.getItem("herd:sfxEnabled")).toBe("false"); // untouched

    setPreviewOverrides({ sfx: null });
    expect(getSfxEnabled()).toBe(false); // falls back to the real, persisted value
  });
});
