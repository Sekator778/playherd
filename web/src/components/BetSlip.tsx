import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { nextWindow, roundIdFor } from "../api/clock";
import {
  ApiRequestError,
  LEVERAGE_MAX,
  LEVERAGE_MIN,
  MIN_STAKE_PLAY,
  type Direction,
  type LiquidationPreviewDto,
  type RoundBetting,
} from "../api/contract";
import { formatPlayAmount } from "../format";
import { DirectionGlyph } from "./icons";
import { LeverageDial } from "./LeverageDial";
import { LiquidationBar } from "./LiquidationBar";

interface BetSlipProps {
  round: RoundBetting;
  nowMs: number;
  onChanged: () => void;
  /** Round-only reassurance line (`ux_spec.md` §4's "Not signed in" state) — Home doesn't ask
   *  for it, so it stays an opt-in prop rather than baked into the shared "no player" card. */
  noPlayerHint?: string;
  /**
   * Optional external direction control (Pair room arena, `docs/product/design/concepts.md`
   * concept A — "widths reflect the live crowd split... UP/DOWN whose widths reflect the LIVE
   * crowd split"): the arena's own giant UP/DOWN halves double as the direction picker, so the
   * slip embedded below it must not keep its own independent selection. Omitted (Home/Round,
   * unchanged) → the slip manages `direction` itself exactly as before.
   */
  direction?: Direction;
  onDirectionChange?: (direction: Direction) => void;
  /** Pairs with `direction`/`onDirectionChange` above — true when the caller's own UI (the
   *  arena) already IS the direction picker, so the slip's built-in UP/DOWN buttons would just be
   *  a confusing duplicate. Ignored (buttons always shown) unless a controlled `direction` is
   *  also passed. */
  hideDirectionPicker?: boolean;
}

/** Plain-integer default for the stake field's *initial* value — never `MIN_STAKE_PLAY` itself
 *  (contract.ts: `"100.000"`, PLAY's own exact 3-decimal scale) bound straight into the input.
 *  Independent critique, 2026-09-26: a native `<input type="number">` rendered that raw string
 *  locale-dependently — on a comma-decimal system, `"100.000"` reads back as "100,000" (a
 *  thousand-fold misread) even though the interface language is fixed to English (Р-24). Below,
 *  the stake field is a plain text input we format/parse ourselves instead, so this constant only
 *  needs to be a clean starting number, not a value any browser could reformat. */
const DEFAULT_STAKE_DISPLAY = String(Number(MIN_STAKE_PLAY));

/** Only digits and at most one decimal point — typed directly into a plain text field, immune to
 *  the locale-reformatting a native number input applies to its own value. Returns `null` (do
 *  nothing) for a keystroke that isn't a partial number, e.g. a second "." or a stray letter, so
 *  the field can never show something Direction/Number(stake) would silently misparse. */
function sanitizeStakeInput(raw: string): string | null {
  return raw === "" || /^\d*\.?\d*$/.test(raw) ? raw : null;
}

/** Same reasoning as {@link DEFAULT_STAKE_DISPLAY} applied to an existing bet's stake when
 *  loading it back into the editable field — `existing.stake` is contract-precision (e.g.
 *  `"600.000"`); the field shows the plain number a player actually typed. */
function stakeDisplay(amount: string): string {
  return String(Number(amount));
}

/** Bet slip (`ux_spec.md` §11) — used embedded on Home and full-size on the Round page, always
 *  the same component so every state/validation rule lives in exactly one place. */
export function BetSlip({
  round,
  nowMs,
  onChanged,
  noPlayerHint,
  direction,
  onDirectionChange,
  hideDirectionPicker,
}: BetSlipProps) {
  const { player, openConnectModal } = useSession();

  const bettingClosed = nowMs >= round.window.startMs;
  if (bettingClosed) {
    const nextRoundId = roundIdFor(nextWindow(round.window));
    return (
      <div className="card">
        <p>Betting just closed for this round.</p>
        <Link className="btn btn--secondary" to={`/round/${nextRoundId}`}>
          View next round
        </Link>
      </div>
    );
  }

  if (!player) {
    return (
      <BetSlipPreview
        noPlayerHint={noPlayerHint}
        onConnect={openConnectModal}
        direction={direction}
        onDirectionChange={onDirectionChange}
        hideDirectionPicker={hideDirectionPicker}
      />
    );
  }

  return (
    <BetSlipForm
      round={round}
      onChanged={onChanged}
      direction={direction}
      onDirectionChange={onDirectionChange}
      hideDirectionPicker={hideDirectionPicker}
    />
  );
}

/**
 * Logged-out preview (independent critique, 2026-09-26, "Game feel / onboarding" finding): a
 * skeptical newcomer couldn't see what a bet even looks like — direction, leverage, the
 * liquidation price it implies — before connecting a wallet; the slip was replaced outright by a
 * bare "Connect your wallet" card. This reuses the exact same direction/leverage/stake controls
 * and the same `api.previewLiquidation` call `BetSlipForm` uses below, which is why it needs no
 * server or contract change: `RpcResource`'s `previewLiquidation` case has never required a
 * signed-in session (only `placeBet`/`cancelBet` do). There is no balance to check and nothing to
 * submit here — adjusting the controls is real (the liquidation preview genuinely updates), only
 * the final action differs, opening the same connect-wallet modal every other "no player" entry
 * point in the app already uses.
 */
function BetSlipPreview({
  noPlayerHint,
  onConnect,
  direction: controlledDirection,
  onDirectionChange,
  hideDirectionPicker,
}: {
  noPlayerHint?: string;
  onConnect: () => void;
  direction?: Direction;
  onDirectionChange?: (direction: Direction) => void;
  hideDirectionPicker?: boolean;
}) {
  const api = useApi();
  const [internalDirection, setInternalDirection] = useState<Direction>("UP");
  const direction = controlledDirection ?? internalDirection;
  const setDirection = onDirectionChange ?? setInternalDirection;
  const [leverage, setLeverage] = useState<number>(10);
  const [stake, setStake] = useState<string>(DEFAULT_STAKE_DISPLAY);
  const [preview, setPreview] = useState<LiquidationPreviewDto | null>(null);
  const debounceRef = useRef<number | undefined>(undefined);

  useEffect(() => {
    window.clearTimeout(debounceRef.current);
    const stakeNum = Number(stake);
    const validStake = Boolean(stake) && !Number.isNaN(stakeNum) && stakeNum > 0;
    debounceRef.current = window.setTimeout(() => {
      if (!validStake) {
        setPreview(null);
        return;
      }
      api
        .previewLiquidation({ direction, leverage, stake: stakeNum.toFixed(3) })
        .then(setPreview)
        .catch(() => setPreview(null));
    }, 150);
    return () => window.clearTimeout(debounceRef.current);
  }, [api, direction, leverage, stake]);

  return (
    <div className="card bet-slip">
      <p className="test-wallet-tag">PREVIEW — not signed in</p>

      {!hideDirectionPicker && (
        <div className="field">
          <span id="preview-direction-label">Direction</span>
          <div className="direction-row" role="group" aria-labelledby="preview-direction-label">
            <button
              type="button"
              className={`direction-btn direction-btn--up ${direction === "UP" ? "direction-btn--selected" : ""}`}
              aria-pressed={direction === "UP"}
              onClick={() => setDirection("UP")}
            >
              <DirectionGlyph direction="UP" />
              UP
            </button>
            <button
              type="button"
              className={`direction-btn direction-btn--down ${direction === "DOWN" ? "direction-btn--selected" : ""}`}
              aria-pressed={direction === "DOWN"}
              onClick={() => setDirection("DOWN")}
            >
              <DirectionGlyph direction="DOWN" />
              DOWN
            </button>
          </div>
        </div>
      )}

      <div className="field">
        <label htmlFor="preview-leverage-input" className="visually-hidden">
          Leverage
        </label>
        <LeverageDial id="preview-leverage-input" value={leverage} min={LEVERAGE_MIN} max={LEVERAGE_MAX} onChange={setLeverage} />
      </div>

      <div className="field">
        {/* Distinct accessible name from the signed-in slip's "Stake (PLAY)" (`BetSlipForm`
         *  below) — real users only ever see one of the two, but the two components briefly
         *  coexist in the accessibility tree while sign-in state settles, and this is the label
         *  a screen reader (or `findByLabelText` in tests) would otherwise have to disambiguate
         *  by timing alone. */}
        <label htmlFor="preview-stake-input">Preview stake (PLAY)</label>
        <div className="stake-hero num-lg mono" aria-hidden="true">
          {formatPlayAmount(Number(stake) || 0)} PLAY
        </div>
        <input
          id="preview-stake-input"
          className="stake-input-secondary"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          value={stake}
          onChange={(e) => {
            const next = sanitizeStakeInput(e.target.value);
            if (next !== null) setStake(next);
          }}
        />
      </div>

      {preview && (
        <div className="card card--tight liquidation-preview">
          <p className="section-title" style={{ marginBottom: 8 }}>
            Liquidation preview
          </p>
          <LiquidationBar
            entryPrice={preview.entryPriceUsed}
            liquidationPrice={preview.liquidationPrice}
            percentToLiquidation={preview.percentToLiquidation}
          />
        </div>
      )}

      <p className="muted">Connect your wallet to actually place this bet.</p>
      {noPlayerHint && <p className="muted">{noPlayerHint}</p>}
      <button type="button" className="btn btn--primary btn--lg btn--block" onClick={onConnect}>
        CONNECT WALLET
      </button>
    </div>
  );
}

function BetSlipForm({
  round,
  onChanged,
  direction: controlledDirection,
  onDirectionChange,
  hideDirectionPicker,
}: {
  round: RoundBetting;
  onChanged: () => void;
  direction?: Direction;
  onDirectionChange?: (direction: Direction) => void;
  hideDirectionPicker?: boolean;
}) {
  const api = useApi();
  const existing = round.myBet;
  const [formOpen, setFormOpen] = useState(!existing);
  const [showCancelConfirm, setShowCancelConfirm] = useState(false);
  const [internalDirection, setInternalDirection] = useState<Direction>(existing?.direction ?? "UP");
  const direction = controlledDirection ?? internalDirection;
  const setDirection = onDirectionChange ?? setInternalDirection;
  const [leverage, setLeverage] = useState<number>(existing?.leverage ?? 10);
  const [stake, setStake] = useState<string>(existing ? stakeDisplay(existing.stake) : DEFAULT_STAKE_DISPLAY);

  // Reset local form state when the round or the existence of an own bet changes — deliberately
  // keyed on `hasBet` (a boolean), not the `myBet` object reference, which changes on every poll
  // tick and would otherwise kick the player out of "EDIT BET" mode every second. Doing this
  // during render (React's documented "adjusting state when a prop changes" pattern) rather than
  // in an effect also means it never lags a frame behind.
  const hasBet = existing !== null;
  const resetKey = `${round.roundId}:${hasBet}`;
  const [lastResetKey, setLastResetKey] = useState(resetKey);
  if (resetKey !== lastResetKey) {
    setLastResetKey(resetKey);
    setFormOpen(!hasBet);
    setShowCancelConfirm(false);
    setDirection(existing?.direction ?? "UP");
    setLeverage(existing?.leverage ?? 10);
    setStake(existing ? stakeDisplay(existing.stake) : DEFAULT_STAKE_DISPLAY);
  }

  const [balance, setBalance] = useState<number | null>(null);
  const [preview, setPreview] = useState<LiquidationPreviewDto | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const debounceRef = useRef<number | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    api.myAccount().then((acc) => {
      if (!cancelled) setBalance(Number(acc.balance));
    });
    return () => {
      cancelled = true;
    };
  }, [api, round.roundId]);

  useEffect(() => {
    window.clearTimeout(debounceRef.current);
    const stakeNum = Number(stake);
    const validStake = Boolean(stake) && !Number.isNaN(stakeNum) && stakeNum > 0;
    debounceRef.current = window.setTimeout(() => {
      if (!validStake) {
        setPreview(null);
        return;
      }
      api
        .previewLiquidation({ direction, leverage, stake: stakeNum.toFixed(3) })
        .then(setPreview)
        .catch(() => setPreview(null));
    }, 150);
    return () => window.clearTimeout(debounceRef.current);
  }, [api, direction, leverage, stake]);

  if (!formOpen && existing) {
    return (
      <div className="card">
        {showCancelConfirm ? (
          <>
            <p>Cancel your bet and get {formatPlayAmount(existing.stake)} PLAY back?</p>
            <div className="direction-row">
              <button type="button" className="btn btn--secondary" onClick={() => setShowCancelConfirm(false)}>
                KEEP BET
              </button>
              <button
                type="button"
                className="btn btn--danger"
                onClick={async () => {
                  await api.cancelBet(round.roundId);
                  onChanged();
                }}
              >
                CANCEL BET
              </button>
            </div>
          </>
        ) : (
          <>
            <p>
              Your bet: <DirectionGlyph direction={existing.direction} /> {existing.direction} · x{existing.leverage} ·{" "}
              {formatPlayAmount(existing.stake)} PLAY
            </p>
            <div className="direction-row">
              <button type="button" className="btn btn--secondary" onClick={() => setFormOpen(true)}>
                EDIT BET
              </button>
              <button type="button" className="btn btn--danger" onClick={() => setShowCancelConfirm(true)}>
                CANCEL BET
              </button>
            </div>
          </>
        )}
      </div>
    );
  }

  const stakeNum = Number(stake) || 0;
  const belowMin = stakeNum < Number(MIN_STAKE_PLAY);
  const effectiveBalance = (balance ?? 0) + (existing ? Number(existing.stake) : 0);
  const overBalance = stakeNum > effectiveBalance;
  const disabled = submitting || !stake || belowMin || overBalance;

  const setQuickStake = (fraction: number) => {
    setStake(Math.floor(effectiveBalance * fraction).toFixed(0));
  };

  /** Stake chips (direction.md §4 "Chips, not bare inputs") — additive, like casino chips on a
   *  table: +100/+500/+1000 add to whatever's already staked, clamped to the effective balance;
   *  MAX (`setQuickStake(1)`) still goes all-in in one tap. */
  const addStake = (amount: number) => {
    const next = Math.min(effectiveBalance, (Number(stake) || 0) + amount);
    setStake(next.toFixed(0));
  };

  const submit = async () => {
    setSubmitting(true);
    setErrorMessage(null);
    try {
      await api.placeBet({ roundId: round.roundId, direction, leverage, stake: stakeNum.toFixed(3) });
      onChanged();
    } catch (err) {
      setErrorMessage(err instanceof ApiRequestError ? err.message : "Something went wrong.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form
      className="card bet-slip"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      {!hideDirectionPicker && (
        <div className="field">
          <span id="direction-label">Direction</span>
          <div className="direction-row" role="group" aria-labelledby="direction-label">
            <button
              type="button"
              className={`direction-btn direction-btn--up ${direction === "UP" ? "direction-btn--selected" : ""}`}
              aria-pressed={direction === "UP"}
              onClick={() => setDirection("UP")}
            >
              <DirectionGlyph direction="UP" />
              UP
            </button>
            <button
              type="button"
              className={`direction-btn direction-btn--down ${direction === "DOWN" ? "direction-btn--selected" : ""}`}
              aria-pressed={direction === "DOWN"}
              onClick={() => setDirection("DOWN")}
            >
              <DirectionGlyph direction="DOWN" />
              DOWN
            </button>
          </div>
        </div>
      )}

      <div className="field">
        <label htmlFor="leverage-input" className="visually-hidden">
          Leverage
        </label>
        <LeverageDial id="leverage-input" value={leverage} min={LEVERAGE_MIN} max={LEVERAGE_MAX} onChange={setLeverage} />
      </div>

      <div className="field">
        <label htmlFor="stake-input">Stake (PLAY)</label>
        <div className="stake-hero num-lg mono" aria-hidden="true">
          {formatPlayAmount(stakeNum)} PLAY
        </div>
        <input
          id="stake-input"
          className="stake-input-secondary"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          value={stake}
          onChange={(e) => {
            const next = sanitizeStakeInput(e.target.value);
            if (next !== null) setStake(next);
          }}
        />
        <div className="stake-chips">
          <button type="button" className="stake-chip" onClick={() => addStake(100)}>
            +100
          </button>
          <button type="button" className="stake-chip" onClick={() => addStake(500)}>
            +500
          </button>
          <button type="button" className="stake-chip" onClick={() => addStake(1000)}>
            +1000
          </button>
          <button type="button" className="stake-chip stake-chip--max" onClick={() => setQuickStake(1)}>
            MAX
          </button>
        </div>
        {belowMin && <p className="field-error">Minimum stake is 100 PLAY.</p>}
        {!belowMin && overBalance && (
          <p className="field-error">
            Not enough PLAY — you have {formatPlayAmount(effectiveBalance)} PLAY, this bet needs {formatPlayAmount(stakeNum)} PLAY.
          </p>
        )}
      </div>

      {preview && (
        <div className="card card--tight liquidation-preview">
          <p style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 8 }}>
            <span className="section-title" style={{ marginBottom: 0 }}>
              Liquidation preview
            </span>
            {/* New tab: this link would otherwise unmount the form and lose the in-progress bet
             *  (`ux_walkthrough.md` question 4 — leverage/liquidation is unfamiliar to a newcomer
             *  and deserves an explanation in place, without adding a confirmation step). */}
            <Link to="/how-it-works#liquidation" target="_blank" rel="noopener noreferrer">
              What's this?
            </Link>
          </p>
          <LiquidationBar
            entryPrice={preview.entryPriceUsed}
            liquidationPrice={preview.liquidationPrice}
            percentToLiquidation={preview.percentToLiquidation}
          />
          <p className="faint">
            Estimated from the current price — your real entry price is set when the round starts.
          </p>
        </div>
      )}

      {errorMessage && <p className="field-error">{errorMessage}</p>}

      <button type="submit" className="btn btn--primary btn--lg btn--block" disabled={disabled}>
        {existing ? "UPDATE BET" : "PLACE BET"}
      </button>
    </form>
  );
}
