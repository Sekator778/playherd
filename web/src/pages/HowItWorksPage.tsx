import { Link } from "react-router-dom";
import { LEVERAGE_MAX, LEVERAGE_MIN, RATING_ROUNDS_THRESHOLD, STARTER_PACK_PLAY } from "../api/contract";
import { MultiAccountDisclosureLine, OracleDisclosureLine } from "../components/Common";
import { formatPlayAmount } from "../format";

/** Static content, baked into the build (`ux_spec.md` §9 — предложение: this screen has no
 *  dynamic fields, so it works even if the backend is unreachable). */
export function HowItWorksPage() {
  return (
    <div className="stack" style={{ gap: 28 }}>
      <h1>How it works</h1>

      <section>
        <h2>How a round works</h2>
        <p>
          Every round bets on one thing: will BTC's price be higher or lower when the round closes than when it
          opened? Hourly rounds run for an hour, daily rounds for a day. Betting on a round is open for its entire
          previous round — there is always exactly one round taking bets and one round already running.
        </p>
        <p>
          When betting closes, every bet becomes a real position at that moment's price. Directions and amounts stay
          private until then — before a round starts, all you can see is how many players have voted, not which way.
          When the round ends, positions are settled against the closing price.
        </p>
      </section>

      <section id="liquidation">
        <h2>Leverage and liquidation</h2>
        <p>
          Leverage multiplies the size of your position relative to your stake — from x{LEVERAGE_MIN} to x
          {LEVERAGE_MAX}. Higher leverage means a smaller price move wipes you out, but a bigger payout if you're
          right.
        </p>
        <p>
          If the price moves against you enough to reach your liquidation price, your position is liquidated and you
          lose your entire stake — no partial refunds. The liquidation price is shown before you place a bet, using
          the same isolated-margin formula Hyperliquid uses for its own perpetual futures.
        </p>
      </section>

      <section>
        <h2>The crowd account</h2>
        <p>
          The crowd account is a single running position that represents everyone: at the start of each round it
          opens a position in whichever direction the majority of stake voted for, sized at 10% of its own capital,
          with the stake-weighted average leverage of the winning side. It can be liquidated exactly like any player.
        </p>
        <p>
          The chart tracks three lines from the same starting capital: the crowd account itself, a plain
          buy-and-hold of BTC for comparison, and an "anti-crowd" account that always takes the opposite side of the
          majority. Capital never resets between seasons — only the season markers on the chart do.
        </p>
        <OracleDisclosureLine />
      </section>

      <section>
        <h2>Where the price comes from</h2>
        <p>
          Round-opening and round-closing prices come from Hyperliquid's BTC perpetual market. There is no
          decentralized price oracle here — prices are read and published on-chain by a key our team controls. If a
          reliable price can't be read within an hour of a round closing, the round is cancelled and every bet is
          refunded, including any that would have been liquidated. A cancelled round always shows its reason on the
          round's page.
        </p>
      </section>

      <section>
        <h2>Ratings</h2>
        <p>
          Two season rankings: <strong>Richest</strong> (how much your balance grew this season, including anything
          you withdrew to your wallet) and <strong>Prophets</strong> (what share of your rounds you called the right
          direction, regardless of leverage or liquidation). Both need at least {RATING_ROUNDS_THRESHOLD} rounds
          played this season before you appear.
        </p>
        <MultiAccountDisclosureLine />
      </section>

      <section>
        <h2>PLAY is not real money</h2>
        <p>
          PLAY is a toy currency on Chia's testnet11 — it has no real-world value. New players start with{" "}
          {formatPlayAmount(STARTER_PACK_PLAY, { decimals: 0 })} PLAY. You can move PLAY to your own testnet11
          wallet as a CAT token and back, but nothing on this site involves real funds.
        </p>
      </section>

      <p>
        <Link to="/">← Back to Home</Link>
      </p>
    </div>
  );
}
