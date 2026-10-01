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
          Every round bets on one thing: will the market's price — or, on an odds market, its odds — be higher or
          lower when the round closes than when it opened? Every market has two round lengths (the room names them,
          for example "3 MIN" and "15 MIN"). Betting on a round is open for the entire previous round — there is
          always exactly one round taking bets and one round already running, per market and length.
        </p>
        <p>
          While betting is open you can see how the crowd is split — the share of the stake on UP and on DOWN, and
          how many players have bet — and a live feed of the bets as they are placed (test bots are marked BOT).
          When betting closes, every bet becomes a real position at that moment's price, and each position's entry and
          liquidation price are revealed. When the round ends, positions are settled against the closing price.
        </p>
      </section>

      <section>
        <h2>Markets</h2>
        <p>
          Markets are grouped in panels: Crypto, Politics, Sports, Stocks, Indices, Commodities. A <strong>price
          market</strong> follows the price of a Hyperliquid perpetual in dollars. An <strong>odds market</strong>{" "}
          (politics, sports) follows the probability of one outcome of a Polymarket market, shown as a percentage —
          you bet on whether that probability goes up or down during the round, <strong>not on who wins</strong> the
          election or the match.
        </p>
        <p>
          Each market has its own highest leverage (the leverage dial stops there). If the market behind an odds
          market closes — the event is decided or withdrawn — its open rounds are cancelled and every open bet is
          refunded in full.
        </p>
      </section>

      <section id="liquidation">
        <h2>Leverage and liquidation</h2>
        <p>
          Leverage multiplies the size of your position relative to your stake — from x{LEVERAGE_MIN} up to the
          market's own maximum (at most x{LEVERAGE_MAX}; lower on odds markets and smaller assets). Higher leverage
          means a smaller price move wipes you out, but a bigger payout if you're right.
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
          Every market has a crowd account: a single running position that represents everyone playing that market —
          at the start of each round it opens a position in whichever direction the majority of stake voted for, sized
          at 10% of its own capital, with the stake-weighted average leverage of the winning side. It trades paper
          dollars, never your PLAY, and it can be liquidated exactly like any player. That majority is "the herd" in
          the "With the herd" / "Against the herd" shortcuts of the bet slip — shortcuts, not advice.
        </p>
        <p>
          The chart on the Stats screen tracks three lines from the same starting capital: the crowd account itself, a
          plain buy-and-hold of that market for comparison, and an "anti-crowd" account that always takes the
          opposite side of the majority. Capital never resets between seasons — only the season markers on the
          chart do.
        </p>
        <OracleDisclosureLine />
        <OracleDisclosureLine source="polymarket" />
      </section>

      <section>
        <h2>Where the price comes from</h2>
        <p>
          Prices come from Hyperliquid and odds from Polymarket (a test deployment can run on recorded or simulated
          prices instead — when it does, the Wallet screen says so). There is no decentralized price oracle here —
          prices are read and published on-chain by a key our team controls. If a reliable price can't be read within
          an hour of a round closing, the round is cancelled and every bet is refunded, including any that would have
          been liquidated. A cancelled round always shows its reason on the round's page.
        </p>
        <p>
          After a round settles, its results and their hash are published in an oracle record on Chia. The round's
          page says where that record is — queued, sent, confirmed, or not anchored — and links the results file
          the hash commits to, so anyone can check that a round was not rewritten afterwards.
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
          {formatPlayAmount(STARTER_PACK_PLAY)} PLAY. You can move PLAY on and off testnet11 as a CAT token from the
          Wallet screen (a deposit is credited only once it is confirmed on chain). Nothing on this site involves real
          funds.
        </p>
      </section>

      <p>
        <Link to="/">← Back to the lobby</Link>
      </p>
    </div>
  );
}
