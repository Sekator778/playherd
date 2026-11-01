# Case study: from a shared lock to a single-writer pipeline

Herd's first server applied each bet under a shared lock and committed it to Postgres one by one. This note describes how that design was measured, what replaced it, and what is still unproven.

## The problem, as measured

The load rig seeded 100,000 players and stepped up the arrival rate with `synchronous_commit=on`.

| Rate | Accepted | p99.9 |
|---|---|---|
| 25 bets/s | 24.8/s | 23 ms |
| 50 bets/s | 31.4/s | 59.5 s |

JFR showed where the time went. The median commit took 0.7 ms, but rare commits took hundreds of milliseconds. Each one held the lock, so up to 195 callers queued behind it and the lock wait reached 26 s. Round settlement, the first suspect, turned out not to be the bottleneck once it had moved outside the lock.

So the ceiling came from the shape of the system: one slow commit inside a shared lock stops everyone. Raw notes: [`bench_ceiling_2026-10-20.md`](../research/bench_ceiling_2026-10-20.md).

## The design

The target was the architecture Martin Fowler describes for [LMAX](https://martinfowler.com/articles/lmax.html): business logic in memory on one thread, with no I/O on that thread.

| Stage | Thread | Work |
|---|---|---|
| Logic | `herd-core-writer` | Takes commands, applies them in memory, prepares read views of the state after the batch without publishing them, hands the batch on |
| Journal | `herd-core-journal` | Writes everything waiting as one journal record with one sync: a group commit |
| Output | `herd-core-output` | For a synced batch, publishes read views, runs post-commit actions and answers callers |

Backpressure is a semaphore with one permit per batch in flight, 8 by default. Waiting for a drained pipeline means taking all the permits.

## Where it differs from LMAX

**It journals results, not inputs.** LMAX journals the input before the logic runs. Herd writes events after they are applied in memory, so between applying and syncing the engine is ahead of the journal. That state is speculative, and this rule follows from it:

> Nothing leaves the process before the sync: no answer, no published view, no push.

**It does not use the Disruptor.** Hand-off happens per batch, not per message: about 7,000 hand-offs per second at 64 clients on macOS, with 16 µs of waiting for the journal stage against a 178 µs logic cycle. On macOS at 1024 clients the bottleneck is the output stage, at 2.9 ms per batch waking callers, and on the Linux volume the sync was busy 97% of the time. A ring buffer would have optimised neither case, so a plain blocking queue stayed. The hand-off cost itself was not measured separately, so this is an inference from the upper bound of the cycle.

## Failure semantics

| Failure | Behaviour | Proof |
|---|---|---|
| Sync fails | The journal thread poisons everything queued behind the failed group. The logic thread stops, waits for every batch handed over, rebuilds from the journal and answers 503 to everything unsynced | `aFailedSyncWithSeveralBatchesInFlight…` |
| Journal record written but not confirmed | The next write voids it, because a queue record cannot be truncated. Sequence numbers are never reused | `ChronicleJournalStore` tests |
| A writer thread dies | The writer stops accepting commands, everyone waiting gets 503 and the process exits, so the container restarts from snapshot and journal | `aJournalThreadThatDies…`, `anOutputThreadThatDies…`, `aLogicThreadThatDiesWithCommandsInHand…` |
| Caller retries after a lost answer | The same `clientRequestId` returns the first answer, even after a restart | Event replay stores the id |

The sync-before-answer rule has its own test: `withTheSyncBlockedTheLogicThreadGoesOnButNothingIsAnsweredPublishedOrPushed`.

The rule that logic does no I/O has two guards. [`EngineIoRuleTest`](../../server/src/test/java/crowdvsmarket/server/core/EngineIoRuleTest.java) walks the bytecode call graph when the tests run, and [`IoGuard`](../../server/src/main/java/crowdvsmarket/server/core/IoGuard.java) catches violations during the test suite and is off by default in production. Both exempt the rebuild after a failed commit, and the call-graph test cannot see calls made through lambdas or reflection.

## Results

Measured by [`WriterThroughputBenchTest`](../../server/src/test/java/crowdvsmarket/server/bench/WriterThroughputBenchTest.java): in process, 10 s, closed loop, Linux container on a btrfs volume on a virtual disk.

| Clients | Before, bets/s | After, bets/s |
|---|---|---|
| 64 | 35,277 / 18,212 | 8,103 / 36,117 / 29,271 / 29,331 |
| 256 | 69,289 / 76,526 | 33,174 / 68,820 / 49,030 / 36,754 |
| 1024 | 66,367 / 65,331 | 114,070 / 133,442 / 133,354 / 130,030 |

The gain is clear at 1024 clients, about 2x. At 64 and 256 clients the spread between runs is larger than the difference, so no gain is proven there. In a closed loop one answer takes at least one sync, so the pipeline helps where batches pile up. At low client counts the sync was busy 99% of the time: the disk sets the ceiling.

These runs also taught a measurement lesson. An earlier in-process test of the journal against Postgres commits showed 9–10 times more bets/s on macOS, but `msync` there does not reach the medium, so that figure says nothing about a server. On the Linux volume a journal write cost 2.2 ms against 2.6 ms for a Postgres commit: the durable flush is expensive for both.

## Where Herd departs from LMAX

Herd is an open-source project at an early stage. This design removed the measured ceiling and stops there on purpose: with a small user base there is no load to justify more work yet. The remaining distance to LMAX is known, and each step below waits for real traffic to ask for it. Their gains are not measured.

| Herd today | LMAX | Step when load grows |
|---|---|---|
| The logic thread takes `engineLock` for each batch, a lock that predates the single writer | No locks in the business logic | Remove the lock and leave the gate as the only way to reach the engine between batches |
| Each request thread blocks on a future until its batch is synced, so the output stage has to wake every caller | Results leave through an output stage without blocked callers | Answer asynchronously from the RPC layer, then measure over HTTP |
| Events are journaled after the logic, so a failed sync means rollback and rebuild | Input is journaled before the logic and replay is deterministic | Keep it unless replication needs an input log |
| One server, no standby | Durability also rests on replicas | Replicate the journal to a hot standby |
| Blocking queues between stages | Disruptor ring buffers | Swap them when profiling shows the hand-off as the limit |
| One writer for all markets | One processor per stream, partitioned | Partition by market once the disk stops being the ceiling |

## What is not proven

- Power loss and kernel crash. A LazyFS harness with a negative control exists in [`tools/journal-powerloss`](../../tools/journal-powerloss/README.md), but it is blocked on the development VM: Chronicle hits `SIGBUS` on the FUSE mount before the first acknowledgement.
- Throughput over HTTP after this redesign, and on the production disk.
- A restart after a writer-thread death on the live server. Tests cover the stop and the answers, not the restart.
- The output stage is the bottleneck on macOS at 1024 clients and has not been parallelised.

Detailed working notes, in Russian: [`writer_pipeline_2026-10-21.md`](../research/writer_pipeline_2026-10-21.md), [`journal_chronicle_2026-10-21.md`](../research/journal_chronicle_2026-10-21.md), [`lmax_roadmap.md`](../research/lmax_roadmap.md).
