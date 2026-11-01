# Documentation

## Start here, in English

- [Architecture overview](architecture/overview.md)
- [Case study: from a shared lock to a single-writer pipeline](architecture/single-writer-pipeline.md)
- [Design decisions](architecture/decisions.md)
- [Bot API](api/bots.md)
- [Public oracle verifier](../tools/verify-oracle/README.md)
- [Roadmap and known limits](../ROADMAP.md)

## Working notes, in Russian

The project was built with its notes in Russian. They are kept as written, because they show the measurements and dead ends behind each step.

| Folder | Contents |
|---|---|
| [`research/`](research) | Load tests, journal and pipeline notes, chain work, economy models |
| [`kb/topics/`](kb/topics) | Methods, liquidation math, fees |
| [`product/`](product) | Game rules, payout rules, interface specification |

Tags such as `D-96` point to entries of a private decision log. Those that shaped the system are explained in [Design decisions](architecture/decisions.md).
