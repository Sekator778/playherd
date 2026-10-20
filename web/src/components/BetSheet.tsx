import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { mutationErrorText } from "../api/apiErrors";
import { placeBetWithRetryId } from "../api/betAttempt";
import { LEVERAGE_MAX, LEVERAGE_MIN, type BetMode, type Direction, type MarketDto, type RoundBetting } from "../api/contract";
import { useMarket, useMarkets } from "../api/useMarkets";
import { effectiveBetMode, fixedModeAvailable, fixedQuote, isFixedMode, loadBetMode, saveBetMode } from "./betModeModel";
import { useMyPositions } from "../api/useMyPositions";
import { usePoll } from "../api/usePoll";
import { useServerInfo } from "../api/useServerInfo";
import { formatCountdown, formatPercent, formatPlayAmount, formatPrice } from "../format";
import { notification } from "../fx/haptics";
import { useT } from "../i18n";
import { FeeBreakdown } from "./BetSlip";
import { announceDraftPlaced, betDraftFor, clearBetDraft, onDraftPlaced, saveBetDraft, revealBetSlip } from "./betDraft";
import { announceCustomize, closeBetSheet, consumeAutoTestWallet, requestAutoTestWallet, useBetSheetRequest, type BetSheetRequest } from "./betSheetState";
import { defaultRiskId, exampleFor, optionFor, riskOptions, RISK_WANTED, SHEET_STAKES, signedMove, stakeIsValid, type RiskId } from "./betSheetModel";
import { defaultLeverage, formatFeePercent, formatMovePercent, sanitizeStakeInput } from "./betSlipModel";
import { DirectionGlyph } from "./icons";
import { dialCeiling, effectiveLeverage } from "./leverageScale";
import { setProMode } from "./proMode";
import { useLiquidationPreview } from "./useLiquidationPreview";
import { useServerNow } from "./useServerNow";
import "./BetSheet.css";

/** Mounted once, in the layout: whatever opened the sheet (a lobby card, the room's arena) is a request in `betSheet.ts`. */
export function BetSheetHost() {
  const request = useBetSheetRequest();
  if (!request) return null;
  // Keyed by the market and series: another card's tap starts the sheet from scratch.
  return <BetSheet key={`${request.marketId}:${request.interval}`} request={request} />;
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';

function BetSheet({ request }: { request: BetSheetRequest }) {
  const t = useT();
  const api = useApi();
  const navigate = useNavigate();
  const { player, openConnectModal } = useSession();
  const { refresh: refreshPositions } = useMyPositions();
  const nowMs = useServerNow(1000);
  const { market } = useMarket(request.marketId);
  const { direction, interval, marketId } = request;
  const symbol = market?.symbol ?? marketId;

  // --- the round this bet is for: read now and every two seconds, so the sheet follows the conveyor (a new round opens as one starts) ---
  const [round, setRound] = useState<RoundBetting | null>(null);
  const [roundClosed, setRoundClosed] = useState(false);
  usePoll(() => {
    api
      .rounds(interval, marketId)
      .then((pair) => {
        if (pair.betting.phase === "BETTING") {
          setRound(pair.betting);
          setRoundClosed(false);
        } else {
          setRound(null);
          setRoundClosed(true);
        }
      })
      .catch(() => {});
  }, 2000, [api, interval, marketId], [`market:${marketId}`]);

  // --- the choice: a stake (a chip, or typed under "other") and a risk level ---
  const [stake, setStake] = useState(String(SHEET_STAKES[0]));
  const [otherOpen, setOtherOpen] = useState(false);
  const [riskChoice, setRiskChoice] = useState<RiskId | null>(null);
  const [feesOpen, setFeesOpen] = useState(false);
  // "Win or lose" (Р-106, fixed payout) or "%" (the boost): the first sheet a new player sees is "Win or lose"; the last choice is kept.
  // Odds markets take the percentage mode only, and the switch is not shown there.
  const { economy } = useMarkets();
  const [chosenMode, setChosenMode] = useState<BetMode>(() => loadBetMode());
  const modeOffered = fixedModeAvailable(market?.kind);
  const mode = effectiveBetMode(chosenMode, market?.kind);
  const fixed = mode === "FIXED";
  const pickMode = (next: BetMode) => {
    setChosenMode(next);
    saveBetMode(next);
  };

  const cap = market?.series?.find((s) => s.interval === interval)?.maxLeverage ?? market?.maxLeverage ?? LEVERAGE_MAX;
  const riskId = riskChoice ?? defaultRiskId(cap);
  // What the level asks for within the market's cap; the stake's tier (`stakeMax`, from the preview) lowers it further.
  const wanted = optionFor(riskOptions(cap, null), riskId).leverage;
  const asked = useLiquidationPreview(marketId, direction, wanted, stake, interval, stakeIsValid(stake) && !fixed);
  const preview = fixed ? null : asked.preview;
  const stakeMax = fixed ? null : asked.stakeMax;
  const options = riskOptions(cap, stakeMax);
  const selected = optionFor(options, riskId);
  // a fixed-payout bet has no leverage: the lowest value is the placeholder the API and the journal carry
  const leverage = fixed ? LEVERAGE_MIN : effectiveLeverage(selected.leverage, defaultLeverage(cap), cap, false, stakeMax);
  const stakeNum = Number(stake) || 0;
  const quote = fixed ? fixedQuote(stakeNum, economy) : null;
  const ceiling = dialCeiling(cap, false, stakeMax);
  const clamped = ceiling < RISK_WANTED[RISK_WANTED.length - 1].wanted;

  // --- the balance, once signed in ---
  const [balance, setBalance] = useState<number | null>(null);
  const existing = round?.myBet ?? null;
  useEffect(() => {
    if (!player) return;
    let cancelled = false;
    api
      .myAccount()
      .then((acc) => {
        if (!cancelled) setBalance(Number(acc.balance));
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [api, player, existing?.stake]);
  // An edit refunds the old bet and its fees first (the slip's rule): that money counts as free.
  const existingCost = existing ? Number(existing.stake) + Number(existing.feeHerd ?? 0) + Number(existing.feeNetwork ?? 0) : 0;
  const free = balance === null ? null : balance + existingCost;
  const needed = fixed ? (quote?.totalDebit ?? stakeNum) : preview?.totalDebit != null ? Number(preview.totalDebit) : stakeNum;
  const overBalance = free !== null && needed > free;

  // --- the choice survives the sign-in: while signed out the draft follows every change (and the round, when it rolls over) ---
  const roundId = round?.roundId ?? null;
  const totalDebit = fixed ? (quote ? quote.totalDebit.toFixed(3) : null) : (preview?.totalDebit ?? null);
  const draftMode: BetMode | undefined = fixed ? "FIXED" : undefined;
  const saveDraft = () => {
    if (roundId === null) return;
    saveBetDraft({ roundId, marketId, direction, leverageChoice: leverage, high: false, stake, leverage, totalDebit, fromSheet: true, mode: draftMode });
  };
  useEffect(() => {
    if (player || roundId === null) return;
    saveBetDraft({ roundId, marketId, direction, leverageChoice: leverage, high: false, stake, leverage, totalDebit, fromSheet: true, mode: draftMode });
  }, [player, roundId, marketId, direction, leverage, stake, totalDebit, draftMode]);

  // --- closing ---
  const dialogRef = useRef<HTMLDivElement>(null);
  const dismiss = () => {
    if (roundId !== null && betDraftFor(roundId)?.fromSheet) clearBetDraft(roundId);
    closeBetSheet();
  };
  // The sign-in dialog placed the drafted bet itself: the sheet has done its work.
  useEffect(() => onDraftPlaced(() => closeBetSheet()), []);
  // A request for the test wallet that the sign-in dialog never took (it was already open, the sheet was closed) must not wait for the next one.
  useEffect(
    () => () => {
      consumeAutoTestWallet();
    },
    [],
  );
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    dialogRef.current?.focus();
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = overflow;
      previous?.focus?.();
    };
  }, []);
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") {
      // The sign-in dialog sits over the sheet and takes the Escape for itself.
      if (document.querySelector('[aria-labelledby="connect-modal-title"]')) return;
      dismiss();
      return;
    }
    if (e.key !== "Tab" || !dialogRef.current) return;
    if (document.querySelector('[aria-labelledby="connect-modal-title"]')) return;
    const items = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (items.length === 0) return;
    const first = items[0];
    const last = items[items.length - 1];
    if (e.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current)) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  // --- the actions ---
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const valid = stakeIsValid(stake);
  const bettingOpen = round !== null && nowMs < round.window.startMs;
  const canBet = player !== null && bettingOpen && valid && !overBalance && !submitting;

  const place = async () => {
    if (!round || !canBet) return;
    setSubmitting(true);
    setError(null);
    try {
      const sent = stakeNum.toFixed(3);
      await placeBetWithRetryId(api, { roundId: round.roundId, direction, leverage, stake: sent, ...(fixed ? { mode: "FIXED" as const } : {}) });
      notification("success");
      // The room (if it is the one open) reads its round again at once and says the bet is placed; the dock finds the bet on its next read.
      announceDraftPlaced({ roundId: round.roundId, direction, leverage, stake: sent });
      refreshPositions();
      closeBetSheet();
    } catch (err) {
      setError(mutationErrorText(err, t("common.somethingWrong"), t));
    } finally {
      setSubmitting(false);
    }
  };

  const { status: serverStatus, info: serverInfo } = useServerInfo();
  const testWalletOffered = serverStatus === "unsupported" ? true : serverInfo?.testWallet !== false;
  const signInTest = () => {
    saveDraft();
    requestAutoTestWallet();
    openConnectModal();
  };
  const signInSage = () => {
    saveDraft();
    openConnectModal();
  };

  /** "Customize (Pro)": the same choice, in today's full slip — the Pro view, the room of this market, the slip scrolled into sight. */
  const customize = () => {
    saveDraft();
    setProMode(true);
    announceCustomize(direction);
    closeBetSheet();
    navigate(`/play/${encodeURIComponent(marketId)}?side=${direction.toLowerCase()}&series=${interval === "DAILY" ? "daily" : "hourly"}`);
    window.setTimeout(revealBetSlip, 400);
  };

  const otherStakeValue = !SHEET_STAKES.some((s) => String(s) === stake);
  const showOtherInput = otherOpen || otherStakeValue;
  const closesIn = round ? formatCountdown(round.window.startMs - nowMs) : null;
  const fees = fixed ? null : preview?.feeHerd != null && preview.feeNetwork != null ? Number(preview.feeHerd) + Number(preview.feeNetwork) : null;

  return (
    <div
      className="bet-sheet-overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) dismiss();
      }}
    >
      <div
        ref={dialogRef}
        className={`bet-sheet bet-sheet--${direction.toLowerCase()}`}
        role="dialog"
        aria-modal="true"
        aria-label={t("sheet.aria")}
        tabIndex={-1}
        onKeyDown={onKeyDown}
      >
        <header className="bet-sheet__head">
          <h2 className={`bet-sheet__title bet-sheet__title--${direction.toLowerCase()}`}>
            <DirectionGlyph direction={direction} /> {t("sheet.title", { side: direction, symbol })}
          </h2>
          {closesIn && bettingOpen && <span className="bet-sheet__timer mono">{t("sheet.closesIn", { time: closesIn })}</span>}
          <button type="button" className="bet-sheet__close" aria-label={t("sheet.close")} onClick={dismiss}>
            <span aria-hidden="true">×</span>
          </button>
        </header>

        {roundClosed || (round && !bettingOpen) ? (
          <div className="bet-sheet__body">
            <p>{t("sheet.closed")}</p>
            <button type="button" className="btn btn--secondary btn--block" onClick={dismiss}>
              {t("sheet.close")}
            </button>
          </div>
        ) : !round ? (
          <div className="bet-sheet__body">
            <p className="muted" role="status">
              {t("sheet.loading")}
            </p>
          </div>
        ) : (
          <div className="bet-sheet__body">
            {existing && (
              <p className="bet-sheet__existing">
                {isFixedMode(existing)
                  ? t("mode.existing", { side: existing.direction, stake: formatPlayAmount(existing.stake) })
                  : t("sheet.existing", { side: existing.direction, leverage: existing.leverage, stake: formatPlayAmount(existing.stake) })}
              </p>
            )}

            {modeOffered && (
              <fieldset className="bet-sheet__group bet-sheet__mode">
                <legend className="visually-hidden">{t("mode.aria")}</legend>
                <div className="bet-sheet__chips" role="group" aria-label={t("mode.aria")}>
                  <button type="button" className="bet-sheet__chip" aria-pressed={fixed} onClick={() => pickMode("FIXED")}>
                    {t("mode.winOrLose")}
                  </button>
                  <button type="button" className="bet-sheet__chip mono" aria-pressed={!fixed} onClick={() => pickMode("PERCENTAGE")}>
                    {t("mode.percent")}
                  </button>
                </div>
                <p className="faint bet-sheet__mode-hint">{t(fixed ? "mode.winOrLoseHint" : "mode.percentHint")}</p>
              </fieldset>
            )}

            <fieldset className="bet-sheet__group">
              <legend>{t("sheet.betLabel")}</legend>
              <div className="bet-sheet__chips">
                {SHEET_STAKES.map((s) => (
                  <button
                    key={s}
                    type="button"
                    className="bet-sheet__chip mono"
                    aria-pressed={!showOtherInput && stake === String(s)}
                    disabled={free !== null && s > free}
                    onClick={() => {
                      setStake(String(s));
                      setOtherOpen(false);
                    }}
                  >
                    {formatPlayAmount(s)}
                  </button>
                ))}
                <button type="button" className="bet-sheet__chip" aria-pressed={showOtherInput} onClick={() => setOtherOpen(true)}>
                  {t("sheet.other")}
                </button>
              </div>
              {showOtherInput && (
                <div className="bet-sheet__other">
                  <label htmlFor="bet-sheet-other" className="visually-hidden">
                    {t("sheet.otherLabel")}
                  </label>
                  <input
                    id="bet-sheet-other"
                    className="stake-input mono"
                    type="text"
                    inputMode="decimal"
                    autoComplete="off"
                    placeholder={t("sheet.otherLabel")}
                    value={stake}
                    onChange={(e) => {
                      const next = sanitizeStakeInput(e.target.value);
                      if (next !== null) setStake(next);
                    }}
                  />
                </div>
              )}
              {!valid && <p className="field-error">{t("betSlip.minStake")}</p>}
              {free !== null && <p className="bet-sheet__balance faint">{t("sheet.balance", { amount: formatPlayAmount(free) })}</p>}
              {overBalance && valid && <p className="field-error">{t("sheet.notEnough", { have: formatPlayAmount(free ?? 0) })}</p>}
            </fieldset>

            {!fixed && (
            <fieldset className="bet-sheet__group">
              <legend>{t("sheet.boostLabel")}</legend>
              <div className="bet-sheet__chips">
                {options.map((o) => (
                  <button
                    key={o.id}
                    type="button"
                    className={`bet-sheet__chip bet-sheet__risk bet-sheet__risk--${o.id}`}
                    aria-pressed={selected.id === o.id}
                    onClick={() => setRiskChoice(o.id)}
                  >
                    <span>{t(o.id === "safer" ? "sheet.safer" : o.id === "normal" ? "sheet.normal" : "sheet.bold")}</span>
                    <span className="mono">x{o.leverage}</span>
                  </button>
                ))}
              </div>
              {clamped && <p className="faint bet-sheet__cap">{t("sheet.capNote", { max: ceiling })}</p>}
            </fieldset>
            )}

            {fixed ? (
              <div className="bet-sheet__example" aria-live="polite">
                {quote && valid ? (
                  <>
                    <p className="bet-sheet__quote mono">
                      {t("mode.quote", {
                        win: formatPlayAmount(quote.win),
                        lose: formatPlayAmount(-quote.lose),
                        fee: formatPlayAmount(quote.fee, { decimals: 3 }),
                      })}
                    </p>
                    <p className="faint bet-sheet__quote-note">
                      {t("mode.feeNote", { platform: formatPlayAmount(quote.platformFee, { decimals: 3 }), network: formatPlayAmount(quote.networkFee, { decimals: 3 }) })}{" "}
                      <Link to="/fees" onClick={() => closeBetSheet()}>
                        {t("sheet.feesAll")}
                      </Link>
                    </p>
                  </>
                ) : (
                  <p className="faint">{t("sheet.exLoading")}</p>
                )}
              </div>
            ) : (
            <div className="bet-sheet__example" aria-live="polite">
              {preview && valid ? <Example market={market} symbol={symbol} direction={direction} stake={stakeNum} leverage={leverage} preview={preview} fees={fees} /> : <p className="faint">{t("sheet.exLoading")}</p>}
            </div>
            )}

            {fees !== null && preview && (
              <div className="bet-sheet__fees">
                <button type="button" className="bet-sheet__fees-toggle" aria-expanded={feesOpen} title={t("sheet.feesAria")} onClick={() => setFeesOpen((v) => !v)}>
                  {t("sheet.fees", { amount: formatPlayAmount(fees, { decimals: 3 }), percent: formatFeePercent(fees, stakeNum) })}{" "}
                  <span aria-hidden="true">{feesOpen ? "▴" : "▾"}</span>
                </button>
                <Link to="/fees" className="bet-sheet__fees-all" onClick={() => closeBetSheet()}>
                  {t("sheet.feesAll")}
                </Link>
                {feesOpen && <FeeBreakdown preview={preview} stake={stakeNum} />}
              </div>
            )}

            {error && (
              <p className="field-error" role="alert">
                {error}
              </p>
            )}

            {player ? (
              <button type="button" className={`btn btn--lg btn--block bet-sheet__go bet-sheet__go--${direction.toLowerCase()}`} disabled={!canBet} onClick={() => void place()}>
                {submitting ? t("sheet.placing") : t(existing ? "sheet.update" : "sheet.button", { amount: formatPlayAmount(stakeNum), side: direction })}
              </button>
            ) : (
              <div className="bet-sheet__signin">
                <p className="muted">{t("sheet.signInFirst")}</p>
                {testWalletOffered && (
                  <button type="button" className="btn btn--primary btn--lg btn--block" disabled={!valid} onClick={signInTest}>
                    {t("connect.continueTest")}
                  </button>
                )}
                <button type="button" className={`btn btn--block ${testWalletOffered ? "btn--secondary" : "btn--primary btn--lg"}`} disabled={!valid} onClick={signInSage}>
                  {t("connect.withSage")}
                </button>
              </div>
            )}

            <button type="button" className="text-button bet-sheet__customize" onClick={customize}>
              {t("sheet.customize")}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

/** The plain-words example under the choice: what a small move for the bet and against it does, and the price at which most or all of the bet is lost (Р-93: the remainder at the candle's extreme comes back, 0 on a gap). */
function Example({
  market,
  symbol,
  direction,
  stake,
  leverage,
  preview,
  fees,
}: {
  market: MarketDto | undefined;
  symbol: string;
  direction: Direction;
  stake: number;
  leverage: number;
  preview: NonNullable<ReturnType<typeof useLiquidationPreview>["preview"]>;
  /** Herd fee + network fee of this bet, from the server's preview; null while the preview does not name them (then the figures are gross). */
  fees: number | null;
}) {
  const t = useT();
  const ex = exampleFor(direction, stake, leverage, preview.percentToLiquidation, fees);
  const stepText = formatMovePercent(ex.step);
  // The side that wins is the side of the bet: DOWN wins when the price falls.
  const winFraction = direction === "UP" ? ex.step : -ex.step;
  const wipeFraction = ex.wipeoutSide === "up" ? ex.wipeoutMove : -ex.wipeoutMove;
  const price = market ? formatPrice(preview.liquidationPrice, market) : preview.liquidationPrice;
  return (
    <ul className="bet-sheet__lines mono">
      {/* With the fees known the figures are net of them. A win smaller than the fees is told as a loss, never as a win. */}
      <li className={ex.win < 0 ? "outcome-lost" : "outcome-won"}>
        {t(!ex.afterFees ? "sheet.exWin" : ex.win < 0 ? "sheet.exWinUnder" : "sheet.exWinNet", {
          symbol,
          move: signedMove(winFraction, stepText),
          amount: formatPlayAmount(ex.win, { sign: true }),
        })}
      </li>
      <li className="outcome-lost">
        {t(ex.afterFees ? "sheet.exLoseNet" : "sheet.exLose", { symbol, move: signedMove(-winFraction, stepText), amount: formatPlayAmount(ex.lose, { sign: true }) })}
      </li>
      <li className="outcome-lost bet-sheet__wipeout">
        {t(ex.afterFees ? "sheet.exWipeFees" : "sheet.exWipe", {
          symbol,
          move: signedMove(wipeFraction, wipeFraction === 0 ? formatPercent(0) : formatMovePercent(ex.wipeoutMove)),
          price,
          stake: formatPlayAmount(stake),
          fees: formatPlayAmount(fees ?? 0),
        })}
      </li>
    </ul>
  );
}
