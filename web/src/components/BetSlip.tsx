import { useEffect, useId, useState } from "react";
import { Link } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { mutationErrorText } from "../api/apiErrors";
import { nextRoundId } from "../api/clock";
import { useEngineEpoch } from "../api/engineState";
import {
  LEVERAGE_MAX,
  LEVERAGE_MIN,
  MIN_STAKE_PLAY,
  type Direction,
  type LiquidationPreviewDto,
  type MarketDto,
  type MarketId,
  type RoundBetting,
} from "../api/contract";
import { useMarket } from "../api/useMarkets";
import { formatDuration, formatPlayAmount } from "../format";
import { DirectionGlyph } from "./icons";
import { LeverageDial } from "./LeverageDial";
import { LiquidationBar } from "./LiquidationBar";
import {
  defaultLeverage,
  DEFAULT_STAKE_DISPLAY,
  STAKE_CHIPS,
  STAKE_STEP,
  sanitizeStakeInput,
  stakeDisplay,
  stakeForFraction,
  steppedStake,
} from "./betSlipModel";
import { herdSideOf } from "./marketModel";

interface BetSlipProps {
  round: RoundBetting;
  nowMs: number;
  /** The player's own bet changed (placed, updated, cancelled): reload the round. May answer with a promise — the slip
   *  then waits for the reloaded round before it says what happened, so the "Your bet" card never shows the old bet. */
  onChanged: () => void | Promise<void>;
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

/** How long the line that says what just happened to the player's bet stays ("Bet updated."). */
const NOTICE_MS = 5000;

/** The liquidation preview of the bet being composed (Р-7, Р-33: shown BEFORE the bet) — debounced, and
 *  an answer that arrives after a newer question was asked is dropped, not shown. */
function useLiquidationPreview(marketId: MarketId, direction: Direction, leverage: number, stake: string): LiquidationPreviewDto | null {
  const api = useApi();
  const [preview, setPreview] = useState<LiquidationPreviewDto | null>(null);
  useEffect(() => {
    let cancelled = false;
    const stakeNum = Number(stake);
    const validStake = stake !== "" && Number.isFinite(stakeNum) && stakeNum > 0;
    const timer = window.setTimeout(() => {
      if (!validStake) {
        setPreview(null);
        return;
      }
      api
        .previewLiquidation({ marketId, direction, leverage, stake: stakeNum.toFixed(3) })
        .then((p) => {
          if (!cancelled) setPreview(p);
        })
        .catch(() => {
          if (!cancelled) setPreview(null);
        });
    }, 150);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [api, marketId, direction, leverage, stake]);
  return preview;
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
  // The market's own leverage cap (Т-4) bounds the dial; until the market list arrives the
  // platform-wide cap stands, as it did before markets.
  const { market } = useMarket(round.marketId);
  const maxLeverage = market?.maxLeverage ?? LEVERAGE_MAX;

  const bettingClosed = nowMs >= round.window.startMs;
  if (bettingClosed) {
    return (
      <div className="card">
        <p>Betting just closed for this round.</p>
        <Link className="btn btn--secondary" to={`/round/${nextRoundId(round)}`}>
          View next round
        </Link>
      </div>
    );
  }

  if (!player) {
    return (
      <BetSlipPreview
        round={round}
        market={market}
        maxLeverage={maxLeverage}
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
      market={market}
      maxLeverage={maxLeverage}
      onChanged={onChanged}
      direction={direction}
      onDirectionChange={onDirectionChange}
      hideDirectionPicker={hideDirectionPicker}
    />
  );
}

// ---------------------------------------------------------------------------
// Parts the preview and the real form share
// ---------------------------------------------------------------------------

function DirectionPicker({
  id,
  direction,
  onChange,
}: {
  id: string;
  direction: Direction;
  onChange: (direction: Direction) => void;
}) {
  return (
    <div className="field">
      <span id={id}>Direction</span>
      <div className="direction-row" role="group" aria-labelledby={id}>
        <button
          type="button"
          className={`direction-btn direction-btn--up ${direction === "UP" ? "direction-btn--selected" : ""}`}
          aria-pressed={direction === "UP"}
          onClick={() => onChange("UP")}
        >
          <DirectionGlyph direction="UP" />
          UP
        </button>
        <button
          type="button"
          className={`direction-btn direction-btn--down ${direction === "DOWN" ? "direction-btn--selected" : ""}`}
          aria-pressed={direction === "DOWN"}
          onClick={() => onChange("DOWN")}
        >
          <DirectionGlyph direction="DOWN" />
          DOWN
        </button>
      </div>
    </div>
  );
}

/**
 * «With the herd» / «Against the herd» (Р-71, Herd's own): one tap sets the direction to the side with
 * more PLAY on it right now, or to the opposite one. Both are always offered with equal weight and say
 * which side they will pick — they are shortcuts, not advice (nothing here claims the herd is right or
 * wrong). With no majority yet (an empty round, or an exact tie) both are off and a line says why.
 */
export function HerdPicker({
  herd,
  direction,
  onPick,
}: {
  /** The side the herd is on, `null` when there is no majority. */
  herd: Direction | null;
  direction: Direction;
  onPick: (direction: Direction) => void;
}) {
  const against: Direction | null = herd === null ? null : herd === "UP" ? "DOWN" : "UP";
  const button = (kind: "with" | "against", side: Direction | null) => (
    <button
      type="button"
      className={`herd-btn herd-btn--${kind}`}
      disabled={side === null}
      aria-pressed={side !== null && direction === side}
      onClick={() => side && onPick(side)}
    >
      <span className="herd-btn__what">{kind === "with" ? "With the herd" : "Against the herd"}</span>
      {side && (
        <span className={`herd-btn__side herd-btn__side--${side.toLowerCase()}`}>
          <DirectionGlyph direction={side} /> {side}
        </span>
      )}
    </button>
  );
  return (
    <div className="herd-picker" role="group" aria-label="Pick a side relative to the herd">
      <div className="herd-picker__row">
        {button("with", herd)}
        {button("against", against)}
      </div>
      <p className="herd-picker__hint faint">
        {herd === null
          ? "No herd yet — the crowd is split evenly, or nobody has bet."
          : "The herd is the side with more PLAY on it right now — players and test bots together."}
      </p>
    </div>
  );
}

function StakeField({
  inputId,
  label,
  value,
  onChange,
  balance,
}: {
  inputId: string;
  label: string;
  value: string;
  onChange: (next: string) => void;
  /** The most the player could stake; `undefined` (a signed-out preview) hides the available line and the chips. */
  balance?: number;
}) {
  const current = Number(value) || 0;
  const step = (delta: number) => onChange(steppedStake(current, delta, balance));
  return (
    <div className="field stake-field">
      <label htmlFor={inputId}>{label}</label>
      <div className="stake-row">
        <button type="button" className="stake-step" aria-label={`Decrease stake by ${STAKE_STEP} PLAY`} onClick={() => step(-STAKE_STEP)}>
          <span aria-hidden="true">−</span>
        </button>
        <div className="stake-amount">
          <input
            id={inputId}
            className="stake-input mono"
            type="text"
            inputMode="decimal"
            autoComplete="off"
            value={value}
            onChange={(e) => {
              const next = sanitizeStakeInput(e.target.value);
              if (next !== null) onChange(next);
            }}
          />
          <span className="stake-unit" aria-hidden="true">
            PLAY
          </span>
        </div>
        <button type="button" className="stake-step" aria-label={`Increase stake by ${STAKE_STEP} PLAY`} onClick={() => step(STAKE_STEP)}>
          <span aria-hidden="true">+</span>
        </button>
      </div>
      {balance !== undefined && (
        <>
          <p className="stake-available">
            Available: <strong className="mono">{formatPlayAmount(balance)}</strong> PLAY
          </p>
          <div className="stake-chips">
            {STAKE_CHIPS.map((chip) => {
              const chipValue = stakeForFraction(balance, chip.fraction);
              return (
                <button
                  key={chip.label}
                  type="button"
                  className={`stake-chip ${chip.fraction === 1 ? "stake-chip--max" : ""}`}
                  aria-pressed={value === chipValue}
                  aria-label={chip.fraction === 1 ? "MAX — your whole balance" : `${chip.label} of your balance`}
                  onClick={() => onChange(chipValue)}
                >
                  {chip.label}
                </button>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}

/**
 * The heading of the liquidation preview card, with "What's this?" — which opens the explanation RIGHT THERE. It used to be a link
 * to /how-it-works in a new tab, so as not to lose a half-made bet; the tester asked for it to open in the same app instead (first
 * tester pass, D12 — what a new tab does inside the installed Android app is UNVERIFIED). The open state belongs to the slip, not to
 * this heading: the card around it disappears while the stake is not a number, and the explanation must not close under the
 * player's thumb when it does.
 */
function LiquidationPreviewHead({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  const explainerId = useId();
  return (
    <>
      <p style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 8 }}>
        <span className="section-title" style={{ marginBottom: 0 }}>
          Liquidation preview
        </span>
        <button type="button" className="text-button" aria-expanded={open} aria-controls={explainerId} onClick={onToggle}>
          What's this?
        </button>
      </p>
      {open && (
        <div id={explainerId} className="liquidation-explainer">
          <p>
            If the price reaches the liquidation price at any moment of the round — not only at its close — your bet is liquidated and the whole
            stake is lost.
          </p>
          <p>The higher the leverage, the closer that price sits to the entry price.</p>
        </div>
      )}
    </>
  );
}

/** One line that says what pressing the button will do — with eighteen markets and a leverage dial, a
 *  player should be able to read "which market, which side, how much, how hot" once more before betting. */
function BetSummary({
  round,
  market,
  direction,
  leverage,
  stake,
}: {
  round: RoundBetting;
  market: MarketDto | undefined;
  direction: Direction;
  leverage: number;
  stake: number;
}) {
  const length = formatDuration((round.window.endMs - round.window.startMs) / 1000);
  return (
    <p className="bet-summary">
      <strong className="mono">{formatPlayAmount(stake)} PLAY</strong> · <span className={`bet-summary__side bet-summary__side--${direction.toLowerCase()}`}>
        <DirectionGlyph direction={direction} /> {direction}
      </span>{" "}
      · <strong className="mono">x{leverage}</strong> on {round.asset}
      {market?.kind === "ODDS" ? " odds" : ""} · {length} round
    </p>
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
  round,
  market,
  maxLeverage,
  noPlayerHint,
  onConnect,
  direction: controlledDirection,
  onDirectionChange,
  hideDirectionPicker,
}: {
  round: RoundBetting;
  market: MarketDto | undefined;
  maxLeverage: number;
  noPlayerHint?: string;
  onConnect: () => void;
  direction?: Direction;
  onDirectionChange?: (direction: Direction) => void;
  hideDirectionPicker?: boolean;
}) {
  const [internalDirection, setInternalDirection] = useState<Direction>("UP");
  const direction = controlledDirection ?? internalDirection;
  const setDirection = onDirectionChange ?? setInternalDirection;
  // `null`: the dial has not been touched, so it sits on the market's default — which follows the cap once the market
  // list has arrived (`defaultLeverage`), instead of being fixed by whatever cap was assumed before it did.
  const [leverageChoice, setLeverage] = useState<number | null>(null);
  const leverage = Math.min(leverageChoice ?? defaultLeverage(maxLeverage), maxLeverage);
  const [stake, setStake] = useState<string>(DEFAULT_STAKE_DISPLAY);
  const preview = useLiquidationPreview(round.marketId, direction, leverage, stake);
  const [explainOpen, setExplainOpen] = useState(false);

  return (
    <div className="card bet-slip">
      <p className="test-wallet-tag">PREVIEW — not signed in</p>

      {!hideDirectionPicker && <DirectionPicker id="preview-direction-label" direction={direction} onChange={setDirection} />}
      <HerdPicker herd={herdSideOf(round)} direction={direction} onPick={setDirection} />

      <div className="field">
        <label htmlFor="preview-leverage-input" className="visually-hidden">
          Leverage
        </label>
        <LeverageDial id="preview-leverage-input" value={leverage} min={LEVERAGE_MIN} max={maxLeverage} onChange={setLeverage} />
      </div>

      {/* Distinct accessible name from the signed-in slip's "Stake (PLAY)" (`BetSlipForm` below) —
       *  real users only ever see one of the two, but the two components briefly coexist in the
       *  accessibility tree while sign-in state settles, and this is the label a screen reader (or
       *  `findByLabelText` in tests) would otherwise have to disambiguate by timing alone. */}
      <StakeField inputId="preview-stake-input" label="Preview stake (PLAY)" value={stake} onChange={setStake} />

      {preview && (
        <div className="card card--tight liquidation-preview">
          <LiquidationPreviewHead open={explainOpen} onToggle={() => setExplainOpen((v) => !v)} />
          <LiquidationBar
            entryPrice={preview.entryPriceUsed}
            liquidationPrice={preview.liquidationPrice}
            percentToLiquidation={preview.percentToLiquidation}
            market={market}
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
  market,
  maxLeverage,
  onChanged,
  direction: controlledDirection,
  onDirectionChange,
  hideDirectionPicker,
}: {
  round: RoundBetting;
  market: MarketDto | undefined;
  maxLeverage: number;
  onChanged: () => void | Promise<void>;
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
  const [leverageChoice, setLeverage] = useState<number | null>(existing?.leverage ?? null);
  // Never above this market's own cap, even if the market list arrives after the player touched the dial; an untouched
  // dial is on the market's default (`defaultLeverage`: a quarter of the cap, never the cap itself).
  const leverage = Math.min(leverageChoice ?? defaultLeverage(maxLeverage), maxLeverage);
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
    setInternalDirection(existing?.direction ?? "UP");
    setLeverage(existing?.leverage ?? null);
    setStake(existing ? stakeDisplay(existing.stake) : DEFAULT_STAKE_DISPLAY);
  }
  // A controlled direction belongs to the parent (the room's arena): the slip may not set the parent's
  // state while it renders (React: "cannot update a component while rendering a different one"), and a
  // fresh round must not throw away a side the player already picked. The one thing it does tell the
  // parent is the side of a bet that already exists, so the arena shows what the player is holding.
  const existingDirection = existing?.direction;
  useEffect(() => {
    if (existingDirection && onDirectionChange) onDirectionChange(existingDirection);
  }, [round.roundId, existingDirection, onDirectionChange]);

  // The balance the slip checks the stake against: `null` until it is KNOWN. Unknown is not zero — while it loads (or
  // after it failed) no "not enough PLAY" is ever shown, and a failed load is retried by itself and says so.
  const [balance, setBalance] = useState<number | null>(null);
  const [balanceFailed, setBalanceFailed] = useState(false);
  const [balanceAttempt, setBalanceAttempt] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const preview = useLiquidationPreview(round.marketId, direction, leverage, stake);
  const [explainOpen, setExplainOpen] = useState(false);

  // Reads the balance on every new round, after the player's own bet changes (placing or editing one moves it) and
  // when the server comes back from a restart; a failed read tries again in 3 s, until it works.
  const engineEpoch = useEngineEpoch();
  const existingStake = existing?.stake;
  useEffect(() => {
    let cancelled = false;
    let retry: number | undefined;
    api
      .myAccount()
      .then((acc) => {
        if (cancelled) return;
        setBalance(Number(acc.balance));
        setBalanceFailed(false);
      })
      .catch(() => {
        if (cancelled) return;
        setBalanceFailed(true);
        retry = window.setTimeout(() => setBalanceAttempt((n) => n + 1), 3000);
      });
    return () => {
      cancelled = true;
      window.clearTimeout(retry);
    };
  }, [api, round.roundId, existingStake, engineEpoch, balanceAttempt]);

  // One line that says what just happened to the player's bet, for a few seconds. Placing a bet brings the "Your bet" card
  // up at once, but an UPDATE used to leave the form open, its button unchanged and not a word of it — the header balance
  // was the only sign it had worked — and a cancel only made the card vanish (first tester pass, D4).
  const [notice, setNotice] = useState<{ kind: "placed" | "updated" | "cancelled"; text: string } | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), NOTICE_MS);
    return () => window.clearTimeout(timer);
  }, [notice]);
  const noticeLine = notice && (
    <p className={`bet-notice bet-notice--${notice.kind}`} role="status">
      <span aria-hidden="true">{notice.kind === "cancelled" ? "↩" : "✓"}</span> {notice.text}
    </p>
  );

  // Editing starts from the bet as it is, not from whatever was typed and abandoned before.
  const loadBetIntoForm = (bet: NonNullable<typeof existing>) => {
    setErrorMessage(null);
    setDirection(bet.direction);
    setLeverage(bet.leverage);
    setStake(stakeDisplay(bet.stake));
  };

  if (!formOpen && existing) {
    return (
      <div className="card">
        {noticeLine}
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
                disabled={cancelling}
                onClick={async () => {
                  setCancelling(true);
                  setCancelError(null);
                  try {
                    await api.cancelBet(round.roundId);
                    await onChanged();
                    setNotice({ kind: "cancelled", text: `Bet cancelled — ${formatPlayAmount(existing.stake)} PLAY is back in your balance.` });
                  } catch (err) {
                    setCancelError(mutationErrorText(err, "Couldn't cancel the bet — try again."));
                  } finally {
                    setCancelling(false);
                  }
                }}
              >
                {cancelling ? "CANCELLING…" : "CANCEL BET"}
              </button>
            </div>
            {cancelError && <p className="field-error">{cancelError}</p>}
          </>
        ) : (
          <>
            <p>
              Your bet: <DirectionGlyph direction={existing.direction} /> {existing.direction} · x{existing.leverage} ·{" "}
              {formatPlayAmount(existing.stake)} PLAY
            </p>
            <div className="direction-row">
              <button
                type="button"
                className="btn btn--secondary"
                onClick={() => {
                  loadBetIntoForm(existing);
                  setNotice(null);
                  setFormOpen(true);
                }}
              >
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
  // The slip may spend what is free plus what it already has on this round (an edit refunds it first). Only a KNOWN
  // balance can say "not enough": while it is unknown the server stays the judge of the bet.
  const effectiveBalance = (balance ?? 0) + (existing ? Number(existing.stake) : 0);
  const overBalance = balance !== null && stakeNum > effectiveBalance;
  const disabled = submitting || !stake || belowMin || overBalance;

  const submit = async () => {
    setSubmitting(true);
    setErrorMessage(null);
    try {
      await api.placeBet({ roundId: round.roundId, direction, leverage, stake: stakeNum.toFixed(3) });
      // The round is reloaded first, so the card the form gives way to shows the bet as it is now, not the old one.
      await onChanged();
      setNotice(existing ? { kind: "updated", text: "Bet updated." } : { kind: "placed", text: "Bet placed." });
      setFormOpen(false);
    } catch (err) {
      setErrorMessage(mutationErrorText(err, "Something went wrong."));
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
      {noticeLine}
      {!hideDirectionPicker && <DirectionPicker id="direction-label" direction={direction} onChange={setDirection} />}
      <HerdPicker herd={herdSideOf(round)} direction={direction} onPick={setDirection} />

      <div className="field">
        <label htmlFor="leverage-input" className="visually-hidden">
          Leverage
        </label>
        <LeverageDial id="leverage-input" value={leverage} min={LEVERAGE_MIN} max={maxLeverage} onChange={setLeverage} />
      </div>

      <StakeField inputId="stake-input" label="Stake (PLAY)" value={stake} onChange={setStake} balance={balance === null ? undefined : effectiveBalance} />
      {belowMin && <p className="field-error">Minimum stake is 100 PLAY.</p>}
      {balance === null && balanceFailed && (
        <div className="stack" style={{ gap: 8 }} role="status">
          <p className="field-hint" style={{ margin: 0 }}>
            Couldn't load your balance yet — trying again.
          </p>
          <button type="button" className="btn btn--secondary" onClick={() => setBalanceAttempt((n) => n + 1)}>
            Try now
          </button>
        </div>
      )}
      {!belowMin && overBalance && (
        <p className="field-error">
          Not enough PLAY — you have {formatPlayAmount(effectiveBalance)} PLAY, this bet needs {formatPlayAmount(stakeNum)} PLAY.
        </p>
      )}

      {preview && (
        <div className="card card--tight liquidation-preview">
          <LiquidationPreviewHead open={explainOpen} onToggle={() => setExplainOpen((v) => !v)} />
          <LiquidationBar
            entryPrice={preview.entryPriceUsed}
            liquidationPrice={preview.liquidationPrice}
            percentToLiquidation={preview.percentToLiquidation}
            market={market}
          />
          <p className="faint">
            Estimated from the current price — your real entry price is set when the round starts.
          </p>
        </div>
      )}

      {errorMessage && <p className="field-error">{errorMessage}</p>}

      <BetSummary round={round} market={market} direction={direction} leverage={leverage} stake={stakeNum} />
      <button type="submit" className="btn btn--primary btn--lg btn--block" disabled={disabled}>
        {existing ? "UPDATE BET" : "PLACE BET"}
      </button>
      {existing && (
        // The way out of edit mode that does not send anything — and back to the card with CANCEL BET on it.
        <button
          type="button"
          className="btn btn--secondary btn--block"
          onClick={() => {
            loadBetIntoForm(existing);
            setFormOpen(false);
          }}
        >
          BACK TO YOUR BET
        </button>
      )}
    </form>
  );
}
