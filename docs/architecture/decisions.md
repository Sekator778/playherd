# Design decisions

The decisions that shaped the system, with the evidence behind each one. Tags such as `Р-96` in code comments point to entries of a private decision log.

## 1. One writer thread with no I/O in the logic

**Context.** With a commit per bet under a shared lock, the ceiling was between 25 and 50 bets/s.

**Decision.** All state changes go through one thread working in memory. Durability moves to a separate stage.

**Trade-off.** Everything must fit on one thread per process. Code on that thread does no database, network or journal I/O, except the rebuild after a failed commit. A bytecode call-graph test checks this when the tests run, and a runtime guard checks it in the test suite and is off by default in production. The thread does wait for room in the pipeline.

## 2. Journal events, not commands

**Context.** LMAX journals inputs before processing.

**Decision.** Herd journals the resulting events after applying them, keeping the event format the system already had.

**Trade-off.** State between applying and syncing is speculative. Answers, published views and pushes therefore wait for the sync, and a failed sync means rolling back and rebuilding.

## 3. No Disruptor

**Context.** The Disruptor is the natural choice for this architecture.

**Decision.** A bounded blocking queue and a semaphore. Hand-off is per batch, about 7,000 per second at 64 clients, and the measured bottlenecks were the disk on Linux and the output stage on macOS.

**Trade-off.** If profiling ever shows the hand-off itself as the limit, this decision comes back. The cost of the hand-off alone has not been measured.

## 4. Chronicle Queue as the durable log, Postgres as a projection

**Context.** Postgres was both the log and the read model, so the bet path depended on it.

**Decision.** A memory-mapped journal is the source of truth, and a projector fills Postgres behind it.

**Trade-off.** Operations get harder, and durability depends on `msync` behaving on the real disk. On a Linux volume the write cost 2.2 ms against 2.6 ms for a Postgres commit, so the benefit is decoupling more than raw speed. Power-loss behaviour is still unproven.

## 5. Snapshots with state hashes

**Decision.** Restart reads a snapshot and the journal tail, and a scheduled replay from zero compares SHA-256 digests of the journal-determined part of the state.

**Why.** A replay check catches a snapshot that drifted from the events, which a restart alone would hide.

## 6. A minimal oracle singleton, one key

**Decision.** A small singleton publishes close prices and result hashes, batched into one spend.

**Trade-off.** Anyone can check the record was not rewritten, but one key writes it. This is enough for a testnet demo and is stated openly.

## 7. Action Layer deferred

**Context.** A market application on Chia's Action Layer (CHIP-50) was in the original plan. A spike was done, and the standard itself says a new application needs a full audit.

**Decision.** Keep the game on the server and the proof on chain for now.

## 8. No real-money path

**Decision.** The project publishes code and a testnet demo. The Hyperliquid adapter stays off and cannot sign orders.

**Why.** Running real money needs licences and operations that are outside the aim of this project.

## 9. Measure before and after every step

**Decision.** A load rig came before the rewrite: seeded data, stepped arrival rates, JFR events, and written notes for each step.

**Why.** The first suspect, round settlement, was not the final bottleneck, and macOS numbers turned out not to transfer to a server. Both would have cost weeks if found later.
