/**
 * Who answers Android's hardware Back first (Р-81: the feedback sheet closes on Back, on every screen).
 */
import { render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { handleBackPress, registerBackHandler, useBackHandler } from "./backHandlers";

function Probe({ active, onBack }: { active: boolean; onBack: () => void }) {
  useBackHandler(active, onBack);
  return null;
}

describe("registerBackHandler / handleBackPress", () => {
  it("with nothing registered a press is not handled (the caller navigates, as before)", () => {
    expect(handleBackPress()).toBe(false);
  });

  it("a registered handler takes the press, and says so", () => {
    const handler = vi.fn();
    const off = registerBackHandler(handler);
    expect(handleBackPress()).toBe(true);
    expect(handler).toHaveBeenCalledTimes(1);
    off();
    expect(handleBackPress()).toBe(false);
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it("the sheet opened last answers first, and a press goes to exactly one handler", () => {
    const first = vi.fn();
    const second = vi.fn();
    const offFirst = registerBackHandler(first);
    const offSecond = registerBackHandler(second);
    handleBackPress();
    expect(second).toHaveBeenCalledTimes(1);
    expect(first).not.toHaveBeenCalled();
    offSecond();
    handleBackPress();
    expect(first).toHaveBeenCalledTimes(1);
    offFirst();
  });

  it("taking a handler off twice, or in another order, never takes off somebody else's", () => {
    const a = vi.fn();
    const b = vi.fn();
    const offA = registerBackHandler(a);
    const offB = registerBackHandler(b);
    offA();
    offA();
    handleBackPress();
    expect(b).toHaveBeenCalledTimes(1);
    expect(a).not.toHaveBeenCalled();
    offB();
  });
});

describe("useBackHandler", () => {
  it("answers Back only while it is active", () => {
    const onBack = vi.fn();
    const { rerender, unmount } = render(<Probe active={false} onBack={onBack} />);
    expect(handleBackPress()).toBe(false);
    rerender(<Probe active onBack={onBack} />);
    expect(handleBackPress()).toBe(true);
    expect(onBack).toHaveBeenCalledTimes(1);
    rerender(<Probe active={false} onBack={onBack} />);
    expect(handleBackPress()).toBe(false);
    unmount();
  });

  it("is off again when the component goes away", () => {
    const { unmount } = render(<Probe active onBack={() => {}} />);
    expect(handleBackPress()).toBe(true);
    unmount();
    expect(handleBackPress()).toBe(false);
  });

  it("calls the latest function it was given, not the one of the render it was registered in", () => {
    const stale = vi.fn();
    const fresh = vi.fn();
    const { rerender, unmount } = render(<Probe active onBack={stale} />);
    rerender(<Probe active onBack={fresh} />);
    handleBackPress();
    expect(fresh).toHaveBeenCalledTimes(1);
    expect(stale).not.toHaveBeenCalled();
    unmount();
  });
});
