import { Link } from "react-router-dom";
import { LEVERAGE_MIN, RATING_ROUNDS_THRESHOLD, STARTER_PACK_PLAY } from "../api/contract";
import { MultiAccountDisclosureLine, OracleDisclosureLine } from "../components/Common";
import { formatPlayAmount } from "../format";
import { useT } from "../i18n";
import { Rich } from "../i18n/Rich";

/** Static content, baked into the build (`ux_spec.md` §9 — предложение: this screen has no
 *  dynamic fields, so it works even if the backend is unreachable). The exchanges it names are the design's (Р-74, Р-76,
 *  `the decision log`): crypto from the exchange of the asset itself with a backup, a round on one exchange,
 *  stocks, indices and commodities from Hyperliquid, odds from Polymarket. The Status page lists what a running server
 *  really has. */
export function HowItWorksPage() {
  const t = useT();
  return (
    <div className="stack" style={{ gap: 28 }}>
      <h1>{t("howItWorks.title")}</h1>

      <section>
        <h2>{t("howItWorks.roundTitle")}</h2>
        <p>{t("howItWorks.round1")}</p>
        <p>{t("howItWorks.round2")}</p>
      </section>

      <section>
        <h2>{t("howItWorks.marketsTitle")}</h2>
        <p>
          <Rich text={t("howItWorks.markets1")} />
        </p>
        <p>{t("howItWorks.markets2")}</p>
      </section>

      <section id="liquidation">
        <h2>{t("howItWorks.levTitle")}</h2>
        <p>{t("howItWorks.lev1", { min: LEVERAGE_MIN })}</p>
        <p>{t("howItWorks.lev2")}</p>
      </section>

      <section>
        <h2>{t("howItWorks.crowdTitle")}</h2>
        <p>{t("howItWorks.crowd1")}</p>
        <p>{t("howItWorks.crowd2")}</p>
        <p>{t("howItWorks.sourcesIntro")}</p>
        <OracleDisclosureLine source="coinbase" />
        <OracleDisclosureLine source="kraken" />
        <OracleDisclosureLine source="hyperliquid" />
        <OracleDisclosureLine source="polymarket" />
      </section>

      <section>
        <h2>{t("howItWorks.priceTitle")}</h2>
        <p>{t("howItWorks.price1")}</p>
        <p>
          <Rich text={t("howItWorks.price2")} />
        </p>
        <p>
          <Rich text={t("howItWorks.price3")} />
        </p>
        <p>{t("howItWorks.price4")}</p>
        <p>
          <Rich text={t("howItWorks.price5")} to="/status" />
        </p>
      </section>

      <section id="fees">
        <h2>{t("howItWorks.feesTitle")}</h2>
        <p>
          <Rich text={t("howItWorks.fees1")} to="/fees" />
        </p>
      </section>

      <section>
        <h2>{t("howItWorks.ratingsTitle")}</h2>
        <p>
          <Rich text={t("howItWorks.ratings", { threshold: RATING_ROUNDS_THRESHOLD })} />
        </p>
        <MultiAccountDisclosureLine />
      </section>

      <section>
        <h2>{t("howItWorks.playTitle")}</h2>
        <p>{t("howItWorks.play1", { amount: formatPlayAmount(STARTER_PACK_PLAY) })}</p>
      </section>

      <p>
        <Link to="/">{t("howItWorks.back")}</Link>
      </p>
    </div>
  );
}
