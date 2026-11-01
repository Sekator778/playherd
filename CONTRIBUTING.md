# Contributing

## Run and test

| Part | Command |
|---|---|
| Local stand | `tools/stand.sh up`, then `tools/stand.sh status` |
| All gates | `make check` |
| Engine | `cd engine && mvn -B verify` |
| Server | `mvn -B -f pom.xml -pl server -am verify` |
| Web | `cd web && npm ci && npm run typecheck && npm run lint && npm test -- --run && npm run build` |
| Chain | `cd chain && cargo test --workspace --locked && cargo clippy --workspace --all-targets --locked` |
| End to end | `make e2e`, with the stand running |
| Scripts | `make test-tools` |

## Conventions

- Branch from `main`: `feature/<slug>`, `fix/<slug>`, `docs/<slug>` or `chore/<slug>`.
- New commits and pull request titles should follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).
- A rule the architecture depends on gets a test. Code on the logic thread does no I/O.
- A performance claim comes with its conditions: machine, disk, load model, number of runs.
- Pin third-party GitHub Actions to a full commit SHA with the version in a comment.
- Never commit keys, tokens, `.env` or keystore files.

## Security

See [SECURITY.md](SECURITY.md).
