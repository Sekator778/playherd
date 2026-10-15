import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { mutationErrorText } from "../api/apiErrors";
import { nextRoundId } from "../api/clock";
import { useEngineEpoch } from "../api/engineState";
import {
  LEVERAGE_MAX,
  MIN_STAKE_PLAY,
  type Direction,
  type LiquidationPreviewDto,
  type MarketDto,
  type RoundBetting,
} from "../api/contract";
import { useMarket } from "../api/useMarkets";
import { formatDuration, formatPlayAmount } from "../format";
import { DirectionGlyph } from "./icons";
import { LeverageControl } from "./LeverageControl";
import { LiquidationBar } from "./LiquidationBar";
import { DEFAULT_LEVERAGE_LIMIT, effectiveLeverage } from "./leverageScale";
import { useLiquidationPreview } from "./useLiquidationPreview";
import {
  defaultLeverage,
  formatFeePercent,
  maxAffordableStake,
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
import { useT } from "../i18n";
import { Rich } from "../i18n/Rich";

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

/** How long the slip waits for the page to show a change the server has accepted: ten polls of the room. Past it the slip stops
 *  waiting (see where it is used) — the reload and the poll after it have had every chance. */
const SAVED_PATIENCE_MS = 20_000;

type NoticeKind = "placed" | "updated" | "cancelled";

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
  const t = useT();
  // The market's own leverage cap (Т-4) bounds the dial; until the market list arrives the
  // platform-wide cap stands, as it did before markets.
  const { market } = useMarket(round.marketId);
  // Economy stage A/C: the cap belongs to the market AND the round length (up to x4000); the older single cap is the fallback.
  const interval = round.window.interval;
  const maxLeverage = market?.series?.find((s) => s.interval === interval)?.maxLeverage ?? market?.maxLeverage ?? LEVERAGE_MAX;

  const bettingClosed = nowMs >= round.window.startMs;
  if (bettingClosed) {
    return (
      <div className="card">
        <p>{t("betSlip.closed")}</p>
        <Link className="btn btn--secondary" to={`/round/${nextRoundId(round)}`}>
          {t("betSlip.viewNext")}
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
  const t = useT();
  return (
    <div className="field">
      <span id={id}>{t("betSlip.direction")}</span>
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
  const t = useT();
  const against: Direction | null = herd === null ? null : herd === "UP" ? "DOWN" : "UP";
  const button = (kind: "with" | "against", side: Direction | null) => (
    <button
      type="button"
      className={`herd-btn herd-btn--${kind}`}
      disabled={side === null}
      aria-pressed={side !== null && direction === side}
      onClick={() => side && onPick(side)}
    >
      <span className="herd-btn__what">{kind === "with" ? t("betSlip.withHerd") : t("betSlip.againstHerd")}</span>
      {side && (
        <span className={`herd-btn__side herd-btn__side--${side.toLowerCase()}`}>
          <DirectionGlyph direction={side} /> {side}
        </span>
      )}
    </button>
  );
  return (
    <div className="herd-picker" role="group" aria-label={t("betSlip.pickSideAria")}>
      <div className="herd-picker__row">
        {button("with", herd)}
        {button("against", against)}
      </div>
      <p className="herd-picker__hint faint">
        {herd === null
          ? t("betSlip.noHerd")
          : t("betSlip.herdHint")}
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
  maxStake,
}: {
  inputId: string;
  label: string;
  value: string;
  onChange: (next: string) => void;
  /** What the player holds (free PLAY); `undefined` (a signed-out preview) hides the available line and the chips. */
  balance?: number;
  /** The biggest stake whose fees still fit in `balance` (the MAX chip); omitted → the whole balance. */
  maxStake?: number;
}) {
  const t = useT();
  const current = Number(value) || 0;
  const step = (delta: number) => onChange(steppedStake(current, delta, balance));
  return (
    <div className="field stake-field">
      <label htmlFor={inputId}>{label}</label>
      <div className="stake-row">
        <button type="button" className="stake-step" aria-label={t("betSlip.decrease", { step: STAKE_STEP })} onClick={() => step(-STAKE_STEP)}>
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
        <button type="button" className="stake-step" aria-label={t("betSlip.increase", { step: STAKE_STEP })} onClick={() => step(STAKE_STEP)}>
          <span aria-hidden="true">+</span>
        </button>
      </div>
      {balance !== undefined && (
        <>
          <p className="stake-available">
            <Rich text={t("betSlip.available", { amount: formatPlayAmount(balance) })} />
          </p>
          <div className="stake-chips">
            {STAKE_CHIPS.map((chip) => {
              const chipValue = stakeForFraction(chip.fraction >= 1 ? Math.min(balance, maxStake ?? balance) : balance, chip.fraction);
              return (
                <button
                  key={chip.label}
                  type="button"
                  className={`stake-chip ${chip.fraction === 1 ? "stake-chip--max" : ""}`}
                  aria-pressed={value === chipValue}
                  aria-label={chip.fraction === 1 ? t("betSlip.maxAria") : t("betSlip.chipAria", { label: chip.label })}
                  onClick={() => onChange(chipValue)}
                >
                  {chip.fraction === 1 ? t("betSlip.max") : chip.label}
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
 * to /how-it-works#liquidation that opened in a new tab (so as not to lose a half-made bet). That is all the first tester pass says of
 * it (D12: "opens /how-it-works#liquidation in a new tab"): it records what the link did and asks for nothing. Showing the explanation
 * in place instead is this slip's own choice. What a new tab does inside the INSTALLED app — whether it leaves the app for the system
 * browser — is UNVERIFIED: the pass was a browser, and no installed-app run has looked. The open state belongs to the slip, not to this
 * heading: the card around it disappears while the stake is not a number, and the explanation must not close under the player's
 * thumb when it does.
 */
function LiquidationPreviewHead({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  const t = useT();
  const explainerId = useId();
  return (
    <>
      <p style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 8 }}>
        <span className="section-title" style={{ marginBottom: 0 }}>
          {t("betSlip.liqPreview")}
        </span>
        <button type="button" className="text-button" aria-expanded={open} aria-controls={explainerId} onClick={onToggle}>
          {t("betSlip.whatsThis")}
        </button>
      </p>
      {open && (
        <div id={explainerId} className="liquidation-explainer">
          <p>
            {t("betSlip.explain1")}
          </p>
          <p>{t("betSlip.explain2")}</p>
        </div>
      )}
    </>
  );
}

/**
 * What the bet costs, before it is confirmed (economy stage C, C1; Р-92): the Herd fee and the network fee, each in PLAY and as a share
 * of the stake, the total that leaves the balance (stake + both fees), and the highest leverage the stake allows. Shown only when the
 * server's preview names them (it does once the preview was asked for the round's length); a cancelled bet gives both fees back.
 */
function FeeBreakdown({ preview, stake }: { preview: LiquidationPreviewDto; stake: number }) {
  const t = useT();
  if (preview.feeHerd == null || preview.feeNetwork == null || preview.totalDebit == null) return null;
  const herd = Number(preview.feeHerd);
  const net = Number(preview.feeNetwork);
  return (
    <div className="card card--tight bet-fees" aria-label={t("betSlip.feeTitle")}>
      <p className="section-title" style={{ marginBottom: 6 }}>
        {t("betSlip.feeTitle")}
      </p>
      <dl className="bet-fees__rows">
        <div>
          <dt>{t("betSlip.feeHerd")}</dt>
          <dd className="mono">
            {formatPlayAmount(herd, { decimals: 3 })} PLAY <span className="faint">({formatFeePercent(herd, stake)})</span>
          </dd>
        </div>
        <div>
          <dt>{t("betSlip.feeNetwork")}</dt>
          <dd className="mono">
            {formatPlayAmount(net, { decimals: 3 })} PLAY <span className="faint">({formatFeePercent(net, stake)})</span>
          </dd>
        </div>
        <div className="bet-fees__total">
          <dt>{t("betSlip.feeTotal")}</dt>
          <dd className="mono">
            <strong>{formatPlayAmount(preview.totalDebit, { decimals: 3 })} PLAY</strong>
          </dd>
        </div>
      </dl>
      {preview.maxLeverageForStake != null && (
        <p className="bet-fees__max">
          <Rich text={t("betSlip.maxForStake", { max: preview.maxLeverageForStake })} />
        </p>
      )}
      <p className="faint bet-fees__note">{t("betSlip.feeRefund")}</p>
    </div>
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
  const t = useT();
  const length = formatDuration((round.window.endMs - round.window.startMs) / 1000, t);
  return (
    <p className="bet-summary">
      <strong className="mono">{formatPlayAmount(stake)} PLAY</strong> · <span className={`bet-summary__side bet-summary__side--${direction.toLowerCase()}`}>
        <DirectionGlyph direction={direction} /> {direction}
      </span>{" "}
      · <strong className="mono">x{leverage}</strong>{" "}
      {t(market?.kind === "ODDS" ? "betSlip.summaryTailOdds" : "betSlip.summaryTail", { asset: round.asset, length })}
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
  const t = useT();
  const [internalDirection, setInternalDirection] = useState<Direction>("UP");
  const direction = controlledDirection ?? internalDirection;
  const setDirection = onDirectionChange ?? setInternalDirection;
  // `null`: the dial has not been touched, so it sits on the market's default — which follows the cap once the market
  // list has arrived (`defaultLeverage`), instead of being fixed by whatever cap was assumed before it did.
  const [leverageChoice, setLeverage] = useState<number | null>(null);
  const [high, setHigh] = useState(false);
  const [stake, setStake] = useState<string>(DEFAULT_STAKE_DISPLAY);
  const wantedLeverage = effectiveLeverage(leverageChoice, defaultLeverage(maxLeverage), maxLeverage, high, null);
  const { preview, stakeMax } = useLiquidationPreview(round.marketId, direction, wantedLeverage, stake, round.window.interval);
  const leverage = effectiveLeverage(leverageChoice, defaultLeverage(maxLeverage), maxLeverage, high, stakeMax);
  const [explainOpen, setExplainOpen] = useState(false);

  return (
    <div className="card bet-slip">
      <p className="test-wallet-tag">{t("betSlip.previewTag")}</p>

      {!hideDirectionPicker && <DirectionPicker id="preview-direction-label" direction={direction} onChange={setDirection} />}
      <HerdPicker herd={herdSideOf(round)} direction={direction} onPick={setDirection} />

      <div className="field">
        <label htmlFor="preview-leverage-input" className="visually-hidden">
          {t("betSlip.leverage")}
        </label>
        <LeverageControl
          id="preview-leverage-input"
          value={leverage}
          onChange={setLeverage}
          cap={maxLeverage}
          stakeMax={stakeMax}
          high={high}
          onHighChange={setHigh}
          marketId={round.marketId}
          direction={direction}
          stake={stake}
          interval={round.window.interval}
        />
      </div>

      {/* Distinct accessible name from the signed-in slip's "Stake (PLAY)" (`BetSlipForm` below) —
       *  real users only ever see one of the two, but the two components briefly coexist in the
       *  accessibility tree while sign-in state settles, and this is the label a screen reader (or
       *  `findByLabelText` in tests) would otherwise have to disambiguate by timing alone. */}
      <StakeField inputId="preview-stake-input" label={t("betSlip.previewStake")} value={stake} onChange={setStake} />

      {preview && <FeeBreakdown preview={preview} stake={Number(stake) || 0} />}

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

      <p className="muted">{t("betSlip.connectToPlace")}</p>
      {noPlayerHint && <p className="muted">{noPlayerHint}</p>}
      <button type="button" className="btn btn--primary btn--lg btn--block" onClick={onConnect}>
        {t("common.connectWallet")}
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
  const t = useT();
  const existing = round.myBet;
  const [formOpen, setFormOpen] = useState(!existing);
  const [showCancelConfirm, setShowCancelConfirm] = useState(false);
  const [internalDirection, setInternalDirection] = useState<Direction>(existing?.direction ?? "UP");
  const direction = controlledDirection ?? internalDirection;
  const setDirection = onDirectionChange ?? setInternalDirection;
  const [leverageChoice, setLeverage] = useState<number | null>(existing?.leverage ?? null);
  // Never above this market's own cap, even if the market list arrives after the player touched the dial; an untouched
  // dial is on the market's default (`defaultLeverage`: x10 as always — x3 where x10 would be the market's cap).
  // "High leverage" (above x100) is on from the start when the bet being edited already is above it.
  const [high, setHigh] = useState((existing?.leverage ?? 0) > DEFAULT_LEVERAGE_LIMIT);
  const [stake, setStake] = useState<string>(existing ? stakeDisplay(existing.stake) : DEFAULT_STAKE_DISPLAY);
  // The preview names the economy's numbers (fees, the total debit, the highest leverage for this stake), and the dial stays within
  // that highest leverage: the leverage in use is the choice within the cap, x100 unless "High leverage" is on, and the stake's tier.
  const wantedLeverage = effectiveLeverage(leverageChoice, defaultLeverage(maxLeverage), maxLeverage, high, null);
  const { preview, stakeMax } = useLiquidationPreview(round.marketId, direction, wantedLeverage, stake, round.window.interval);
  const leverage = effectiveLeverage(leverageChoice, defaultLeverage(maxLeverage), maxLeverage, high, stakeMax);
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
    setHigh((existing?.leverage ?? 0) > DEFAULT_LEVERAGE_LIMIT);
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
        setNotice({ kind: "cancelled", text: t("betSlip.cancelledNotice", { amount: formatPlayAmount(saved.refund) }) });
      } else {
        setNotice(saved.kind === "updated" ? { kind: "updated", text: t("betSlip.updated") } : { kind: "placed", text: t("betSlip.placed") });
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
  const statusText = saved ? t("betSlip.saving") : (notice?.text ?? "");
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
    setHigh(bet.leverage > DEFAULT_LEVERAGE_LIMIT);
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
              {t("betSlip.cancelConfirm", { amount: formatPlayAmount(existing.stake) })}
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
                {t("betSlip.keepBet")}
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
                    setCancelError(mutationErrorText(err, t("betSlip.cancelFail"), t));
                  } finally {
                    setCancelling(false);
                  }
                }}
              >
                {cancelling ? t("betSlip.cancelling") : t("betSlip.cancelBet")}
              </button>
            </div>
            {cancelError && <p className="field-error">{cancelError}</p>}
          </>
        ) : (
          <>
            <p ref={cardRef} tabIndex={-1}>
              {t("betSlip.yourBet")} <DirectionGlyph direction={existing.direction} /> {existing.direction} · x{existing.leverage} ·{" "}
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
                {t("betSlip.editBet")}
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
                {t("betSlip.cancelBet")}
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
  // An edit refunds the old bet's stake AND its fees (a cancelled bet gives both back), and the new bet costs its stake plus its
  // fees (economy stage C, C1): what has to be free is the preview's total debit, the bare stake while there is no preview.
  const existingCost = existing ? Number(existing.stake) + Number(existing.feeHerd ?? 0) + Number(existing.feeNetwork ?? 0) : 0;
  const effectiveBalance = (balance ?? 0) + existingCost;
  const feeHerd = preview?.feeHerd != null ? Number(preview.feeHerd) : null;
  const feeNetwork = preview?.feeNetwork != null ? Number(preview.feeNetwork) : null;
  const totalDebit = preview?.totalDebit != null ? Number(preview.totalDebit) : null;
  const neededPlay = totalDebit ?? stakeNum;
  const overBalance = balance !== null && neededPlay > effectiveBalance;
  // The MAX chip leaves room for the fees of the bet it makes.
  const maxStake = maxAffordableStake(effectiveBalance, totalDebit === null ? null : totalDebit - (feeHerd ?? 0) - (feeNetwork ?? 0), feeHerd, feeNetwork);
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
      setErrorMessage(mutationErrorText(err, t("common.somethingWrong"), t));
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
        aria-label={existing ? t("betSlip.formEdit") : t("betSlip.formPlace")}
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        {!hideDirectionPicker && <DirectionPicker id="direction-label" direction={direction} onChange={setDirection} />}
        <HerdPicker herd={herdSideOf(round)} direction={direction} onPick={setDirection} />

        <div className="field">
          <label htmlFor="leverage-input" className="visually-hidden">
            {t("betSlip.leverage")}
          </label>
          <LeverageControl
            id="leverage-input"
            value={leverage}
            onChange={setLeverage}
            cap={maxLeverage}
            stakeMax={stakeMax}
            high={high}
            onHighChange={setHigh}
            marketId={round.marketId}
            direction={direction}
            stake={stake}
            interval={round.window.interval}
          />
        </div>

        <StakeField
          inputId="stake-input"
          label={t("betSlip.stake")}
          value={stake}
          onChange={setStake}
          balance={balance === null ? undefined : effectiveBalance}
          maxStake={maxStake}
        />
        {belowMin && <p className="field-error">{t("betSlip.minStake")}</p>}
        {balance === null && balanceFailed && (
          <div className="stack" style={{ gap: 8 }} role="status">
            <p className="field-hint" style={{ margin: 0 }}>
              {t("betSlip.balanceFail")}
            </p>
            <button type="button" className="btn btn--secondary" onClick={() => setBalanceAttempt((n) => n + 1)}>
              {t("betSlip.tryNow")}
            </button>
          </div>
        )}
        {!belowMin && overBalance && (
          <p className="field-error">
            {t("betSlip.notEnough", { have: formatPlayAmount(effectiveBalance), need: formatPlayAmount(neededPlay) })}
          </p>
        )}

        {preview && <FeeBreakdown preview={preview} stake={stakeNum} />}

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
              {t("betSlip.estimated")}
            </p>
          </div>
        )}

        {errorMessage && <p className="field-error">{errorMessage}</p>}

        <BetSummary round={round} market={market} direction={direction} leverage={leverage} stake={stakeNum} />
        <button type="submit" className="btn btn--primary btn--lg btn--block" disabled={disabled}>
          {existing ? t("betSlip.updateBet") : t("betSlip.placeBet")}
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
            {t("betSlip.backToBet")}
          </button>
        )}
      </form>
    </div>
  );
}
