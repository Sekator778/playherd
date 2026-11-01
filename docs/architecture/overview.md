# Architecture overview

## Life of a bet

1. A client calls the JSON-RPC API. HTTP threads validate the request, and hot reads come from published views without a lock.
2. The bet becomes a command in a bounded queue. If the queue stays full past a timeout, the caller is refused, so a burst cannot hang the HTTP threads.
3. The logic thread takes a batch of commands, applies them to the in-memory engine and prepares read views of the state after the batch.
4. The journal thread writes every waiting batch as one record and syncs it.
5. The output thread publishes the views, pushes updates over WebSocket and answers the callers.
6. The projector copies journal events into Postgres on its own thread. The bet itself never waits for Postgres; only a session-cache miss reads it.

The reasoning and failure rules are in the [case study](single-writer-pipeline.md).

## Modules

| Module | Responsibility | Boundary |
|---|---|---|
| `engine` | Rounds, payouts, liquidation math, ledger, ratings | Plain Java with no framework and no I/O |
| `server` | API, writer, journal, snapshots, market data, schedulers | During normal operation only the journal stage appends to the journal |
| `bots` | Trading strategies behind ports | Runs against the same rules as players |
| `chain/c2-cli` | Key derivation, signing, offers, oracle state | Rust library and `c2` binary |
| `chain/c2-svc` | Signature verification and the oracle publisher | HTTP service with a durable queue |
| `web` | React client, Android through Capacitor | Talks only to the public API |

## State and recovery

- **Source of truth:** the event journal. Each record carries a batch of events and a CRC32C, so a reader sees a whole batch or nothing.
- **Projection:** Postgres tables follow the journal under the same sequence numbers.
- **Snapshots:** the engine state is written between batches in a canonical text form, with a SHA-256 of that text.
- **Restart:** the best verified snapshot plus the journal tail, or a full replay without one.
- **Check:** a scheduled job replays from zero up to a snapshot's sequence number and compares digests of the journal-determined part of the state.

## Market data

Crypto prices come from primary exchanges, with a backup venue where one exists, stocks, indices and commodities from Hyperliquid, and odds from Polymarket. A round opens on one venue and runs on it to the end. If the price does not move, the round closes flat and stakes return. A round with no price is cancelled with a recorded reason, so a dead source cannot hold up the series for long.

## Verifiable results

For each settled round the server publishes `GET /api/rounds/{id}/results.json`. The chain service writes the close price and the SHA-256 of that file to testnet11 through an oracle singleton, batching many records into one spend.

`tools/verify-oracle` walks the singleton's lineage from the launcher coin through the public API. It reports gaps and conflicts in sequence numbers and, given a stand URL, checks that hashes and prices match what the site serves. It proves the record was not rewritten after the round. It does not prove the first write was honest: the oracle has a single key, which is listed in the [known limits](../../ROADMAP.md).

## Execution

The crowd account places orders through an `ExecutionAdapter`. The default is [`PaperTradingAdapter`](../../engine/src/main/java/crowdvsmarket/engine/execution/PaperTradingAdapter.java). A [`HyperliquidAdapter`](../../server/src/main/java/crowdvsmarket/server/execution/HyperliquidAdapter.java) builds real orders against the official API but is off by default, and it cannot send them: signing is not implemented, and real money is out of scope for this project.
