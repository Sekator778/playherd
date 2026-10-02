import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { impact, notification, vibrate } from "./haptics";
import { setHapticsEnabled } from "./fxSettings";

function stubNavigator(userActivation: { hasBeenActive: boolean } | undefined) {
  const vibrateSpy = vi.fn(() => true);
  Object.defineProperty(navigator, "vibrate", { value: vibrateSpy, configurable: true });
  Object.defineProperty(navigator, "userActivation", { value: userActivation, configurable: true });
  return vibrateSpy;
}

beforeEach(() => setHapticsEnabled(true));
afterEach(() => {
  Reflect.deleteProperty(navigator, "vibrate");
  Reflect.deleteProperty(navigator, "userActivation");
});

describe("haptics — navigator.vibrate", () => {
  it("vibrates once the player has touched the page", () => {
    const spy = stubNavigator({ hasBeenActive: true });
    impact("medium");
    notification("success");
    vibrate([10, 20]);
    expect(spy.mock.calls).toEqual([[30], [[10, 40, 15]], [[10, 20]]]);
  });

  it("does NOT call it before the first touch — the browser would refuse it with an error-level console line", () => {
    const spy = stubNavigator({ hasBeenActive: false });
    impact("heavy");
    expect(spy).not.toHaveBeenCalled();
  });

  it("a browser that cannot say (no userActivation) is called as before", () => {
    const spy = stubNavigator(undefined);
    impact("light");
    expect(spy).toHaveBeenCalledWith(15);
  });

  it("the player's own switch still wins", () => {
    const spy = stubNavigator({ hasBeenActive: true });
    setHapticsEnabled(false);
    impact("heavy");
    expect(spy).not.toHaveBeenCalled();
  });

  it("an unsupported or throwing vibrate is a silent no-op", () => {
    Object.defineProperty(navigator, "userActivation", { value: { hasBeenActive: true }, configurable: true });
    Object.defineProperty(navigator, "vibrate", {
      value: () => {
        throw new Error("blocked");
      },
      configurable: true,
    });
    expect(() => impact("light")).not.toThrow();
  });
});
