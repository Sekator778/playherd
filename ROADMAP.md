# Roadmap and known limits

## Done

- Rounds on 18 markets in six categories, with live prices and odds
- Leverage with Hyperliquid liquidation math in isolated margin
- PLAY as a CAT on testnet11, with deposits and withdrawals implemented through the Sage wallet
- Oracle singleton with batched publishing and a public verifier
- Single-writer pipeline, event journal, snapshots with state hashes
- Public crowd account on paper execution
- 21 labelled bots and a bot API
- Web client in six languages and an Android build
- Live demo at [playherd.app](https://playherd.app)

## Known limits

Stated plainly, each with the next step.

| Limit | State today | Next step |
|---|---|---|
| Power loss | Unproven. The LazyFS harness with a negative control exists but is blocked by `SIGBUS` on the development VM | Run it on a Linux host with `/dev/fuse` |
| HTTP throughput after the redesign | Not measured. Published figures are in process | Repeat the stepped load test through the full HTTP path |
| Production disk | Not measured | Benchmark `msync` on the server |
| Output stage | Bottleneck on macOS at 1024 clients; on the Linux volume the sync was the limit | Measure on the server, then parallelise waking callers if it still shows |
| Writer-thread death | Tested up to stop and answers, not the restart on the live server | Exercise it on a copy of the live stand |
| Single server | One VPS, no replica | Replicate the journal to a second server |
| Oracle | One key | Move to a threshold of keys |
| Action Layer | Spike only | Revisit if on-chain markets become the goal |
| Audience | A small test group so far | Grow it, then let real load choose the next optimisation |
| Documentation | Architecture in English, working notes in Russian | Translate the notes that outside readers use |

## Next

1. Finish the load picture: HTTP path, production disk, power loss.
2. Replicate the journal to a second server.
3. Partition the writer by market once disk stops being the ceiling.
