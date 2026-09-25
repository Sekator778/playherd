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
import { formatPercent, formatPlayAmount, formatUsd } from "../format";
import { DirectionGlyph } from "./icons";

interface BetSlipProps {
  round: RoundBetting;
  nowMs: number;
  onChanged: () => void;
  /** Round-only reassurance line (`ux_spec.md` §4's "Not signed in" state) — Home doesn't ask
   *  for it, so it stays an opt-in prop rather than baked into the shared "no player" card. */
  noPlayerHint?: string;
}

/** Bet slip (`ux_spec.md` §11) — used embedded on Home and full-size on the Round page, always
 *  the same component so every state/validation rule lives in exactly one place. */
export function BetSlip({ round, nowMs, onChanged, noPlayerHint }: BetSlipProps) {
  const { player, openConnectModal } = useSession();

  if (!player) {
    return (
      <div className="card">
        <h3>Connect your wallet to place a bet</h3>
        {noPlayerHint && <p className="muted">{noPlayerHint}</p>}
        <button type="button" className="btn btn--primary btn--lg btn--block" onClick={openConnectModal}>
          CONNECT WALLET
        </button>
      </div>
    );
  }

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

  return <BetSlipForm round={round} onChanged={onChanged} />;
}

function BetSlipForm({ round, onChanged }: { round: RoundBetting; onChanged: () => void }) {
  const api = useApi();
  const existing = round.myBet;
  const [formOpen, setFormOpen] = useState(!existing);
  const [showCancelConfirm, setShowCancelConfirm] = useState(false);
  const [direction, setDirection] = useState<Direction>(existing?.direction ?? "UP");
  const [leverage, setLeverage] = useState<number>(existing?.leverage ?? 10);
  const [stake, setStake] = useState<string>(existing ? existing.stake : MIN_STAKE_PLAY);

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
    setStake(existing ? existing.stake : MIN_STAKE_PLAY);
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
            <p>Cancel your bet and get {formatPlayAmount(existing.stake, { decimals: 0 })} PLAY back?</p>
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
              {formatPlayAmount(existing.stake, { decimals: 0 })} PLAY
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

  const setQuickStake = (fraction: number | "min") => {
    if (fraction === "min") {
      setStake(Number(MIN_STAKE_PLAY).toFixed(0));
      return;
    }
    setStake(Math.floor(effectiveBalance * fraction).toFixed(0));
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

      <div className="field">
        <label htmlFor="leverage-input">Leverage: x{leverage}</label>
        <input
          id="leverage-input"
          type="range"
          min={LEVERAGE_MIN}
          max={LEVERAGE_MAX}
          step={1}
          value={leverage}
          onChange={(e) => setLeverage(Number(e.target.value))}
        />
      </div>

      <div className="field">
        <label htmlFor="stake-input">Stake (PLAY)</label>
        <input
          id="stake-input"
          type="number"
          inputMode="decimal"
          min={0}
          step={1}
          value={stake}
          onChange={(e) => setStake(e.target.value)}
        />
        <div className="quick-amounts">
          <button type="button" onClick={() => setQuickStake("min")}>
            MIN
          </button>
          <button type="button" onClick={() => setQuickStake(0.25)}>
            25%
          </button>
          <button type="button" onClick={() => setQuickStake(0.5)}>
            50%
          </button>
          <button type="button" onClick={() => setQuickStake(1)}>
            MAX
          </button>
        </div>
        {belowMin && <p className="field-error">Minimum stake is 100 PLAY.</p>}
        {!belowMin && overBalance && (
          <p className="field-error">
            Not enough PLAY — you have {effectiveBalance.toFixed(3)} PLAY, this bet needs {stakeNum.toFixed(3)} PLAY.
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
          <p className="mono">Liquidation price: {formatUsd(preview.liquidationPrice)}</p>
          <p className="mono">{formatPercent(preview.percentToLiquidation)} move to liquidation</p>
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
