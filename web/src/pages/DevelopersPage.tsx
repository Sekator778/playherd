import { Link } from "react-router-dom";
import { useSession } from "../api/ApiContext";
import { MAX_API_BOTS, RATING_ROUNDS_THRESHOLD } from "../api/contract";
import { CopyButton } from "../components/Common";
import { useT } from "../i18n";
import { CALL_EXAMPLES, ERROR_ROWS, PYTHON_BOT, type CallExample } from "./developers/examples";
import "./DevelopersPage.css";

const AUTH_EXAMPLE = `POST /api/rpc/markets
Authorization: Bearer herd_<your key>
Content-Type: application/json

{"args": []}`;

const PLAIN_BET_BODY = `{"args": [{"roundId": "1h-1792058880000", "direction": "UP", "leverage": 5, "stake": "250.000"}]}`;

const json = (value: unknown) => JSON.stringify(value, null, 2);

function CallEntry({ example }: { example: CallExample }) {
  return (
    <details className="dev-call" id={`call-${example.method}`}>
      <summary>
        <code>{example.method}</code> <span className="muted">{example.what}</span>
      </summary>
      <div className="dev-call__body">
        <pre className="dev-code" aria-label={`${example.method} request`}>
          {`{"args": ${JSON.stringify(example.args)}}`}
        </pre>
        {example.response !== undefined && (
          <pre className="dev-code" aria-label={`${example.method} response`}>
            {json(example.response)}
          </pre>
        )}
        {example.note && <p className="muted">{example.note}</p>}
      </div>
    </details>
  );
}

/**
 * The developers' page (Р-106, `/developers`): how to get a key, how a request is signed, every call a key may make with a request and a
 * response taken from the server's recorded answers, rounds, fees, limits, errors, the place kept for the second payout mode, and a
 * minimal Python bot. The headings and the sentences are translated; the code, the call names and the error table are the reference and
 * stay in English. The same material is `docs/api/bots.md`; `developers/examples.test.ts` keeps both in step with the server.
 */
export function DevelopersPage() {
  const t = useT();
  const { player } = useSession();
  return (
    <div className="stack dev-page" style={{ gap: 20 }}>
      <h1>{t("developers.title")}</h1>
      <p>{t("developers.lead")}</p>

      <section className="stack" style={{ gap: 8 }} aria-labelledby="dev-get-key">
        <h2 id="dev-get-key">{t("developers.getKeyTitle")}</h2>
        <p>{t("developers.getKeyBody", { count: MAX_API_BOTS })}</p>
        <p>
          <Link to="/profile/me">{player ? t("bots.title") : t("developers.signInFirst")}</Link>
        </p>
      </section>

      <section className="stack" style={{ gap: 8 }} aria-labelledby="dev-auth">
        <h2 id="dev-auth">{t("developers.authTitle")}</h2>
        <p>{t("developers.authBody")}</p>
        <pre className="dev-code">{AUTH_EXAMPLE}</pre>
      </section>

      <section className="stack" style={{ gap: 8 }} aria-labelledby="dev-calls">
        <h2 id="dev-calls">{t("developers.callsTitle")}</h2>
        <p>{t("developers.callsBody")}</p>
        <div className="dev-calls">
          {CALL_EXAMPLES.map((example) => (
            <CallEntry key={example.method} example={example} />
          ))}
        </div>
      </section>

      <section className="stack" style={{ gap: 8 }} aria-labelledby="dev-rounds">
        <h2 id="dev-rounds">{t("developers.roundsTitle")}</h2>
        <p>{t("developers.roundsBody")}</p>
      </section>

      <section className="stack" style={{ gap: 8 }} aria-labelledby="dev-fees">
        <h2 id="dev-fees">{t("developers.feesTitle")}</h2>
        <p>{t("developers.feesBody")}</p>
      </section>

      <section className="stack" style={{ gap: 8 }} aria-labelledby="dev-errors">
        <h2 id="dev-errors">{t("developers.errorsTitle")}</h2>
        <p>{t("developers.errorsBody")}</p>
        <div className="dev-table-wrap">
          <table className="dev-table">
            <thead>
              <tr>
                <th>{t("developers.colCode")}</th>
                <th>{t("developers.colHttp")}</th>
                <th>{t("developers.colMeaning")}</th>
              </tr>
            </thead>
            <tbody>
              {ERROR_ROWS.map((row) => (
                <tr key={row.code}>
                  <td>
                    <code>{row.code}</code>
                  </td>
                  <td>{row.http}</td>
                  <td>{row.meaning}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="stack" style={{ gap: 8 }} aria-labelledby="dev-payout">
        <h2 id="dev-payout">{t("developers.payoutTitle")}</h2>
        <p>{t("developers.payoutBody")}</p>
        <pre className="dev-code">{PLAIN_BET_BODY}</pre>
      </section>

      <section className="stack" style={{ gap: 8 }} aria-labelledby="dev-example">
        <h2 id="dev-example">{t("developers.exampleTitle")}</h2>
        <p>{t("developers.exampleBody")}</p>
        <pre className="dev-code dev-code--long" aria-label="bot.py">
          {PYTHON_BOT}
        </pre>
        <CopyButton value={PYTHON_BOT} />
      </section>

      <section className="stack" style={{ gap: 8 }} aria-labelledby="dev-boards">
        <h2 id="dev-boards">{t("developers.boardsTitle")}</h2>
        <p>{t("developers.boardsBody", { count: RATING_ROUNDS_THRESHOLD })}</p>
        <p className="muted">{t("developers.docsFile")}</p>
      </section>
    </div>
  );
}
