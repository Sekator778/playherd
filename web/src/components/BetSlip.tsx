import { useEffect, useId, useRef, useState } from "react";
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
  savedChangeIsShown,
  stakeDisplay,
  stakeForFraction,
  steppedStake,
  type SavedChange,
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

/** What the status line says from the moment the server has the player's change until the page shows it. */
const SAVING_TEXT = "Saved, refreshing…";

/** How long the slip waits for the page to show a change the server has accepted: ten polls of the room. Past it the slip stops
 *  waiting (see where it is used) — the reload and the poll after it have had every chance. */
const SAVED_PATIENCE_MS = 20_000;

type NoticeKind = "placed" | "updated" | "cancelled";

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
  // dial is on the market's default (`defaultLeverage`: x10 as always — x3 where x10 would be the market's cap).
  const leverage = Math.min(leverageChoice ?? defaultLeverage(maxLeverage), maxLeverage);
  const [stake, setStake] = useState<string>(existing ? stakeDisplay(existing.stake) : DEFAULT_STAKE_DISPLAY);
  // What happened to the player's own bet, said in the slip's status line: first that the server has the change and the page has
  // not shown it yet (`saved`), then — once the page does — what it was, for a few seconds (`notice`). First tester pass, D4:
  // an UPDATE left the form open with the same button and not a word of it, and a cancel only made the card vanish.
  const [saved, setSaved] = useState<SavedChange | null>(null);
  const [notice, setNotice] = useState<{ kind: NoticeKind; text: string } | null>(null);

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
  // The page shows what the server was told — the bet as it was sent, or none after a cancel: only now is the change said to have
  // worked, and the form (or the cancel prompt) has done its work. Until then the status line says "Saved, refreshing…": the round is
  // read again at once, and again every two seconds, so a reload that failed or was stale is made up for by itself. A round that
  // has moved on (the window closed) leaves the change behind: the next round's slip starts clean.
  if (saved) {
    if (saved.roundId !== round.roundId) {
      setSaved(null);
    } else if (savedChangeIsShown(saved, existing)) {
      setSaved(null);
      if (saved.kind === "cancelled") {
        setNotice({ kind: "cancelled", text: `Bet cancelled — ${formatPlayAmount(saved.refund)} PLAY is back in your balance.` });
      } else {
        setNotice(saved.kind === "updated" ? { kind: "updated", text: "Bet updated." } : { kind: "placed", text: "Bet placed." });
        setFormOpen(false);
      }
    }
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

  // The line is shown for a few seconds.
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), NOTICE_MS);
    return () => window.clearTimeout(timer);
  }, [notice]);
  // A change that the page has not shown for this long is not going to be (the player placed another bet from a second tab; a
  // server that stores the bet differently from what was sent): the slip stops waiting for it and shows what the page has,
  // instead of holding its buttons off for the rest of the round.
  useEffect(() => {
    if (!saved) return;
    const timer = window.setTimeout(() => setSaved(null), SAVED_PATIENCE_MS);
    return () => window.clearTimeout(timer);
  }, [saved]);

  // FOCUS. A card, the cancel prompt and the form replace one another, and the button the player pressed goes with its view —
  // focus then falls to <body>, and a keyboard or screen-reader user starts over from the top of the page (first tester pass, D4:
  // after UPDATE BET and EDIT BET). So a press that changes the view says where focus goes next (`requestFocus`, in the handler),
  // and once the view has rendered the place is focused — unless the player has meanwhile moved to something else outside the slip.
  // The places are the status line (the result: "Saved, refreshing…", then "Bet updated."), the "Your bet" line of the card, the
  // question of the cancel prompt and the form — each a non-tabbable stop (`tabIndex={-1}`) from which Tab goes on to the controls.
  const slipRef = useRef<HTMLDivElement>(null);
  const statusRef = useRef<HTMLParagraphElement>(null);
  const cardRef = useRef<HTMLParagraphElement>(null);
  const promptRef = useRef<HTMLParagraphElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const focusWanted = useRef<"status" | "card" | "prompt" | "form" | null>(null);
  const requestFocus = (place: "status" | "card" | "prompt" | "form") => {
    focusWanted.current = place;
  };
  useEffect(() => {
    const place = focusWanted.current;
    if (!place) return;
    const target = { status: statusRef, card: cardRef, prompt: promptRef, form: formRef }[place].current;
    if (!target) return; // that view is not on screen yet: after the next render
    focusWanted.current = null;
    const active = document.activeElement;
    if (active && active !== document.body && !slipRef.current?.contains(active)) return; // the player has gone elsewhere: not pulled back
    target.focus();
  });

  // Asks the room to read the round again, and does not wait for the answer: the slip says what it knows (the server has the
  // change) and the page — this reload, or the poll that follows it — shows the rest. A reload that throws changes nothing here.
  const reload = () => {
    try {
      void Promise.resolve(onChanged()).catch(() => {});
    } catch {
      // the poll brings the new state
    }
  };

  // THE STATUS LINE: one element, always in the slip, and only its text changes — a live region that appears already filled is
  // often not read at all, one that is there and changes is. It sits above whichever view the slip is showing, so it survives
  // the card giving way to the form and back.
  const statusText = saved ? SAVING_TEXT : (notice?.text ?? "");
  const statusKind = saved ? "saving" : notice?.kind;
  const statusGlyph = saved ? "↻" : notice?.kind === "cancelled" ? "↩" : "✓";
  const statusLine = (
    <p
      ref={statusRef}
      tabIndex={-1}
      role="status"
      aria-atomic="true"
      className={statusText ? `bet-notice bet-notice--${statusKind}` : "visually-hidden"}
    >
      {statusText && (
        <>
          <span aria-hidden="true">{statusGlyph}</span> {statusText}
        </>
      )}
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
    // While the server's answer is on its way to the page (`saved`) the card is a picture of what is about to change: nothing on it
    // is offered again — an edit or a cancel of a bet that has just been cancelled would only be refused.
    const settling = saved !== null;
    return (
      <div className="card bet-slip" ref={slipRef}>
        {statusLine}
        {showCancelConfirm ? (
          <>
            <p ref={promptRef} tabIndex={-1}>
              Cancel your bet and get {formatPlayAmount(existing.stake)} PLAY back?
            </p>
            <div className="direction-row">
              <button
                type="button"
                className="btn btn--secondary"
                onClick={() => {
                  setShowCancelConfirm(false);
                  requestFocus("card");
                }}
              >
                KEEP BET
              </button>
              <button
                type="button"
                className="btn btn--danger"
                disabled={cancelling || settling}
                onClick={async () => {
                  setCancelling(true);
                  setCancelError(null);
                  try {
                    await api.cancelBet(round.roundId);
                    // The bet is cancelled on the server; the question has been answered. That the player's PLAY is back is said when
                    // the page shows the bet gone (above), and until then the line says "Saved, refreshing…".
                    setSaved({ kind: "cancelled", roundId: round.roundId, refund: existing.stake });
                    setShowCancelConfirm(false);
                    requestFocus("status");
                    reload();
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
            <p ref={cardRef} tabIndex={-1}>
              Your bet: <DirectionGlyph direction={existing.direction} /> {existing.direction} · x{existing.leverage} ·{" "}
              {formatPlayAmount(existing.stake)} PLAY
            </p>
            <div className="direction-row">
              <button
                type="button"
                className="btn btn--secondary"
                disabled={settling}
                onClick={() => {
                  loadBetIntoForm(existing);
                  setNotice(null);
                  setFormOpen(true);
                  requestFocus("form");
                }}
              >
                EDIT BET
              </button>
              <button
                type="button"
                className="btn btn--danger"
                disabled={settling}
                onClick={() => {
                  setShowCancelConfirm(true);
                  requestFocus("prompt");
                }}
              >
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
  // Not again while the server's answer is on its way to the page: that bet is already placed.
  const disabled = submitting || saved !== null || !stake || belowMin || overBalance;

  const submit = async () => {
    setSubmitting(true);
    setErrorMessage(null);
    try {
      const sent = stakeNum.toFixed(3);
      await api.placeBet({ roundId: round.roundId, direction, leverage, stake: sent });
      // The server has the bet. The player is told so now — "Saved, refreshing…" — and what it came to ("Bet updated.") when the
      // page shows it: the card the form gives way to must show the bet as it is now, not the old one (see the block above).
      setSaved({ kind: existing ? "updated" : "placed", roundId: round.roundId, direction, leverage, stake: sent });
      requestFocus("status");
      reload();
    } catch (err) {
      setErrorMessage(mutationErrorText(err, "Something went wrong."));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="card bet-slip" ref={slipRef}>
      {statusLine}
      <form
        ref={formRef}
        tabIndex={-1}
        aria-label={existing ? "Edit your bet" : "Place a bet"}
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
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
              requestFocus("card");
            }}
          >
            BACK TO YOUR BET
          </button>
        )}
      </form>
    </div>
  );
}
