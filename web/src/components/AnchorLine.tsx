import type { OracleAnchorDto } from "../api/contract";
import { describeAnchor } from "./anchorModel";
import { AnchorIcon } from "./icons";
import "./AnchorLine.css";

/**
 * "Anchored on Chia" on a settled round (Р-40/Р-41, `RoundSettled.oracleAnchor` + `resultsHash`): where the
 * round's oracle record is on the chain, in words that match what chainsvc really reports — confirmed,
 * sent, queued, not queued yet, or in conflict — then the results hash and the link to the public
 * `results.json` it commits to, so anyone can check the round was not rewritten afterwards. The line
 * about whose key publishes it ("not a decentralized oracle") sits right next to it on the round page.
 *
 * Renders nothing when the server sent neither an anchor nor a hash (an older server, the mock): no
 * "anchored" claim without something behind it.
 */
export function AnchorLine({ roundId, resultsHash, anchor }: { roundId: string; resultsHash?: string; anchor?: OracleAnchorDto }) {
  if (!anchor && !resultsHash) return null;
  const said = anchor ? describeAnchor(anchor) : null;
  const short = resultsHash ? `${resultsHash.slice(0, 10)}…${resultsHash.slice(-6)}` : null;
  const href = `/api/rounds/${encodeURIComponent(roundId)}/results.json`;
  return (
    <div className={`anchor anchor--${said?.tone ?? "idle"}`}>
      <AnchorIcon className="anchor__icon" />
      <div className="anchor__body">
        {said ? (
          <>
            <p className="anchor__title">
              {said.title} <span className="anchor__chip">{said.chip}</span>
            </p>
            <p className="anchor__detail">{said.detail}</p>
          </>
        ) : (
          <p className="anchor__title">Results published</p>
        )}
        <p className="anchor__hash mono">
          {short ? (
            <>
              Results hash: {short} —{" "}
            </>
          ) : (
            <>Results hash not published yet — </>
          )}
          <a href={href} target="_blank" rel="noopener noreferrer">
            verify results.json
          </a>
        </p>
      </div>
    </div>
  );
}
