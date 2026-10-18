# tools/bench — load-bench stand on the bench box

Step 1 of `docs/research/load_bench_design_2026-10-17.md` §11: environment, isolation, Postgres ceiling (S-F).
Results of the first run: `docs/research/bench_step1_2026-10-18.md`.

## Files

| File | Runs on | What |
|---|---|---|
| `docker-compose.bench.yml` | the bench box | override of `docker-compose.yml` (which is not edited): project `herdbench`, own network and volume, chainsvc/web off, chain switches off, synthetic prices, bots 0, treasury 10^12 PLAY (profile `tech`, design §2.4), cpusets |
| `ship.sh` | Mac | builds the server image for linux/amd64, `git archive` of a commit plus this directory to `~/herd-bench/repo`, `docker load` of the images (the bench box is air-gapped: no Maven Central, no registry) |
| `bench-ctl.sh` | the bench box | `up` / `down` / `purge` / `status` of project `herdbench` only |
| `pgbench-ceiling.sh` | the bench box | S-F: pgbench against Postgres of the bench stand + `pg_test_fsync` |
| `sql/` | the bench box | the one-INSERT pgbench script and the copy of `game_events` |

## Bring it up

1. the bench box is shared. Boot to Linux with the `the bench box` skill (its Linux step), then look for others:
   `ls ~/the bench box-leases/`, `docker ps`, `pgrep -fa 'java|mvn|chia|rsync'`, `who`. Another session's lease means: do not flip or reboot.
2. Take the lease: one line in `~/the bench box-leases/<session>.lease` (project, what runs, started, expected end).
3. From the Mac: `tools/bench/ship.sh` (add `--sha <commit>` for another commit; the result is the SHA, also in `~/herd-bench/repo/.bench-sha`).
4. On the bench box: `cd ~/herd-bench/repo && tools/bench/bench-ctl.sh up` (writes `.env.bench` with random secrets on first use, mode 600).
5. S-F: `tools/bench/pgbench-ceiling.sh` (about 25 minutes with the defaults; `BENCH_DURATION=10` for a smoke test).

## Take it down

`tools/bench/bench-ctl.sh down` (containers and network), `purge` also removes the volume `herdbench_pg`. Results stay in
`~/herd-bench/results/`. Then delete the lease; if no other lease, container or job is on the box, power it off (the `the bench box` skill's shutdown step).

## Isolation

- Compose project `herdbench`: containers `herdbench-*`, network `herdbench_default` (172.30.98.0/24, `internal: true`, so no route out),
  volume `herdbench_pg`. No published ports. The stand's fixed names `herd_default` / `herd_pg_live` are overridden.
- Cores (the bench box: 2 sockets x 12 cores x 2 threads; node0 = 0-11,24-35, node1 = 12-23,36-47): Postgres 0-7 (node0), server 12-19 (node1),
  load generators 8-11,20-23,32-35,44-47, cores 24-31,36-43 left to other sessions. Override with `HERD_BENCH_DB_CPUS`, `HERD_BENCH_SRV_CPUS`, `BENCH_GEN_CPUS`.
- Database on the SSD that holds Docker's root (`/var/lib/docker`, ext4 on `sdc`, ROTA=0). `/mnt/the data disk` (HDD) is not used.
- the bench box's nftables guard drops output to 172.16.0.0/12 outside 172.17-19.x: a process on the HOST cannot reach 172.30.98.x. Generators must run as
  containers on `herdbench_default` (as `pgbench-ceiling.sh` does) or via `docker exec`.
- chainsvc is not started (design §6.2); the server logs a DEBUG stack trace about once a second for the closed chain port (noise, harmless).

## What is measured here

S-F: transactions per second and latency percentiles (p50/p90/p99/p99.9/max, from the per-transaction log) of a single-row INSERT into a copy of
`game_events`, 1 and 8 clients, `synchronous_commit` on and off, 300 s each after a 30 s warm-up, plus `pg_test_fsync` and `pg_stat_wal`
(write/sync counts and times) and the disk counters of the DB disk before/after each run. Raw output goes to `~/herd-bench/results/<timestamp>-pgbench/`.
