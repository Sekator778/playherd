# Power-loss test of the journal (LazyFS)

`run.sh` builds LazyFS (MIT, pinned commit in `Dockerfile`) in a container, runs `JournalKillMain` with the journal on the LazyFS mount,
drops the unsynced data (`lazyfs::clear-cache`), kills the child and checks with `JournalPowerLossVerifier` that every acknowledged batch
survived; a negative control (`JournalNoSyncMain`, no sync) must lose acknowledged batches, or the method is blind.

Status 2026-10-22: **BLOCKED** on OrbStack (kernel 7.2, arm64) - Chronicle gets `SIGBUS` on its first store to a mmap'ed file on the
LazyFS mount, before any acknowledgement; nothing is proven either way. Details: `docs/kb/topics/methods.md`, row "Отключение питания".
Needs docker with `/dev/fuse`; on the Mac `docker build` reaches the network only with `--network host`.
