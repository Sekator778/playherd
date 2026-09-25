/**
 * Hidden "/lab" design lab (`implementation-plan.md` §5) — lets Alex trigger every result-moment
 * kind × tier, and preview the leverage dial / liquidation bar across their full range, without
 * playing real rounds. Not in `NAV_LINKS` (`Layout.tsx`) — reachable only by URL, no auth-gate
 * needed (nothing here touches real money or a real session).
 */
import { useState } from "react";
import type { Interval } from "../api/contract";
import { publish } from "../fx/resultEventBus";
import type { ResultEvent, ResultKind, Tier } from "../fx/resultEvent";
import { setForcedReducedMotion } from "../fx/reducedMotion";
import { setPreviewOverrides } from "../fx/fxSettings";
import { LeverageDial } from "../components/LeverageDial";
import { LiquidationBar } from "../components/LiquidationBar";

let labSeq = 0;

function fireEvent(kind: ResultKind, tier: Tier, opts: { stake: number; net: number; leverage: number; interval?: Interval }): void {
  labSeq += 1;
  const balanceAfter = 10_000;
  const event: ResultEvent = {
    id: `lab-${kind}-${tier}-${labSeq}`,
    kind,
    roundId: "1h-lab-preview",
    interval: opts.interval ?? "HOURLY",
    netPlay: opts.net,
    stakePlay: opts.stake,
    leverage: opts.leverage,
    balanceBefore: balanceAfter - opts.net,
    balanceAfter,
    atMs: Date.now(),
    tier,
  };
  publish(event);
}

// Representative fake amounts per tier — result-moment.md §2's multiplier/stakeShare bands, with
// a stake of 100 PLAY and a leverage low enough that the kicker doesn't also fire (kept separate
// below as its own button).
const WIN_MULTIPLIER: Record<Tier, number> = { 1: 1.2, 2: 2, 3: 5, 4: 12 };
const LOSS_NET: Record<Tier, number> = { 1: -3, 2: -12, 3: -30, 4: -60 };
const LIQ_LEVERAGE: Record<Tier, number> = { 1: 5, 2: 15, 3: 25, 4: 38 };

const TIERS: Tier[] = [1, 2, 3, 4];

export function LabPage() {
  const [leverage, setLeverage] = useState(10);
  const [fakePrice, setFakePrice] = useState(0.3); // fraction of SAFE_REFERENCE distance, 0..0.4
  const [forceReduced, setForceReduced] = useState<"" | "on" | "off">("");
  const [forceSound, setForceSound] = useState(false);
  const [forceHaptics, setForceHaptics] = useState(false);

  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="card">
        <h1>Design lab</h1>
        <p className="muted">
          Hidden page (not in the main nav) for previewing every result-moment kind × tier, and the leverage
          dial / liquidation bar across their full range, without playing real rounds.
        </p>
      </div>

      <div className="lab-section">
        <h2>Result moments</h2>
        <h3>WIN</h3>
        <div className="lab-grid">
          {TIERS.map((tier) => (
            <button
              key={`win-${tier}`}
              type="button"
              className="btn btn--secondary"
              onClick={() => {
                const stake = 100;
                const net = stake * (WIN_MULTIPLIER[tier] - 1);
                fireEvent("WIN", tier, { stake, net, leverage: 10 + tier * 3 });
              }}
            >
              WIN T{tier} (×{WIN_MULTIPLIER[tier]})
            </button>
          ))}
          <button
            type="button"
            className="btn btn--secondary"
            onClick={() => fireEvent("WIN", 4, { stake: 100, net: 250, leverage: 38 })}
          >
            WIN + x38 kicker
          </button>
        </div>
        <h3>LOSS</h3>
        <div className="lab-grid">
          {TIERS.map((tier) => (
            <button
              key={`loss-${tier}`}
              type="button"
              className="btn btn--secondary"
              onClick={() => fireEvent("LOSS", tier, { stake: 100, net: LOSS_NET[tier], leverage: 8 + tier * 2 })}
            >
              LOSS T{tier}
            </button>
          ))}
        </div>
        <h3>LIQUIDATION (representative leverage x5 / x15 / x25 / x38)</h3>
        <div className="lab-grid">
          {TIERS.map((tier) => (
            <button
              key={`liq-${tier}`}
              type="button"
              className="btn btn--danger"
              onClick={() => fireEvent("LIQUIDATION", tier, { stake: 100, net: -100, leverage: LIQ_LEVERAGE[tier] })}
            >
              LIQUIDATED T{tier} (x{LIQ_LEVERAGE[tier]})
            </button>
          ))}
        </div>
        <h3>EVEN</h3>
        <div className="lab-grid">
          <button type="button" className="btn btn--secondary" onClick={() => fireEvent("EVEN", 1, { stake: 100, net: 0, leverage: 10 })}>
            EVEN
          </button>
        </div>
        <h3>Overflow (3 events at once)</h3>
        <div className="lab-grid">
          <button
            type="button"
            className="btn btn--secondary"
            onClick={() => {
              fireEvent("WIN", 2, { stake: 100, net: 100, leverage: 10 });
              fireEvent("LOSS", 1, { stake: 100, net: -10, leverage: 10 });
              fireEvent("WIN", 1, { stake: 100, net: 15, leverage: 10 });
            }}
          >
            Fire 3 at once
          </button>
        </div>
      </div>

      <div className="lab-section">
        <h2>Leverage dial (x2–x40)</h2>
        <LeverageDial id="lab-leverage" value={leverage} min={2} max={40} onChange={setLeverage} />
      </div>

      <div className="lab-section">
        <h2>Liquidation bar (all zones)</h2>
        <div className="lab-row">
          <label htmlFor="lab-price">Distance to liquidation</label>
          <input
            id="lab-price"
            type="range"
            min={0}
            max={0.4}
            step={0.005}
            value={fakePrice}
            onChange={(e) => setFakePrice(Number(e.target.value))}
          />
          <button type="button" className="btn btn--secondary" onClick={() => setFakePrice(0.35)}>
            Safe
          </button>
          <button type="button" className="btn btn--secondary" onClick={() => setFakePrice(0.18)}>
            Watch
          </button>
          <button type="button" className="btn btn--secondary" onClick={() => setFakePrice(0.09)}>
            Close
          </button>
          <button type="button" className="btn btn--secondary" onClick={() => setFakePrice(0.03)}>
            Critical
          </button>
        </div>
        <LiquidationBar entryPrice="83451.00" liquidationPrice="79528.10" percentToLiquidation={fakePrice} />
      </div>

      <div className="lab-section">
        <h2>Overrides (preview only — never touch real settings)</h2>
        <div className="lab-row">
          <label>
            <input
              type="checkbox"
              checked={forceReduced === "on"}
              onChange={(e) => {
                const on = e.target.checked;
                setForceReduced(on ? "on" : "");
                setForcedReducedMotion(on ? true : null);
              }}
            />{" "}
            Force reduced motion
          </label>
          <label>
            <input
              type="checkbox"
              checked={forceSound}
              onChange={(e) => {
                setForceSound(e.target.checked);
                setPreviewOverrides({ sfx: e.target.checked ? true : null });
              }}
            />{" "}
            Force sound on
          </label>
          <label>
            <input
              type="checkbox"
              checked={forceHaptics}
              onChange={(e) => {
                setForceHaptics(e.target.checked);
                setPreviewOverrides({ haptics: e.target.checked ? true : null });
              }}
            />{" "}
            Force haptics on
          </label>
        </div>
      </div>
    </div>
  );
}
