# bots — bot strategies as a separate module

Pure, deterministic bot brain for Herd, behind three ports. Spec: [`docs/research/bots_module_design.md`](../docs/research/bots_module_design.md); reasons for its shape: [`architecture_review_2026-10-16.md`](../docs/research/architecture_review_2026-10-16.md) §5; sources of rules and behaviour: [`bot_behavior_sources.md`](../docs/research/bot_behavior_sources.md). First backtest: [`bots_backtest_2026-10-16.md`](../docs/research/bots_backtest_2026-10-16.md).

Depends on `engine` only (stake tiers, fees, liquidation, payout cap are taken from it, not rewritten). No runtime libraries; JUnit 5 and AssertJ in tests. The server is not touched: nothing calls this module yet.

## Layout (`crowdvsmarket.bots`)

| package | content |
|---|---|
| `api` | `MarketSnapshot`, `BarDto`, `BetRequest`/`BetResult`, `BotAccount`, `LastOutcome` |
| `port` | `MarketDataPort`, `BettingPort`, `BotStatePort` |
| `indicator` | `Sma`, `Ema`, `Rsi` (Wilder), `Bollinger`, `Donchian` — own code from the formulas |
| `signal` | `SignalStrategy`, `Signal`, `MarketView`; Momentum, RsiReversion, BollingerBounce, DonchianBreakout, EmaCross, CrowdFollow, CrowdContrarian, Coin |
| `persona` | `Persona`; Disciplined, HouseMoney, LossChaser, Overtrader, Occasional, Maximalist, Cautious |
| `brain` | `BotBrain` (pure), `BotProfile`, `BetIntent`, `Seeds`, `BotRunner` (reads ports, places intents) |
| `catalog` | `catalog.json` (21 bots as data), `Catalog`, `Strategies`, a small JSON reader |
| `backtest` | `Backtester`, `BacktestMain`, `CandleFiles`, `BacktestMarket` |

## Run

```
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-oracle
mvn -s ~/.m2/settings-oss.xml -f pom.xml -pl engine,bots -am verify          # tests (also: make test-java)

python3 -I bots/scripts/download_candles.py --days 14                         # candles into .local/bots-candles/
java -cp bots/target/bots.jar:engine/target/engine.jar \
    crowdvsmarket.bots.backtest.BacktestMain .local/bots-candles [--stake 100] [--out report.md]
```

The ports contract test is published as `bots-tests.jar` (classifier `tests`): extend `crowdvsmarket.bots.port.PortsContractTest`, supply the three ports and a seeded bot. The in-memory fake (`InMemoryExchange`, same jar) passes it; a server adapter and an HTTP client must too.

## Design choices where the spec was silent

- **Direction type.** `engine`'s `Direction` and `MarketSpec`/`Interval`/`PlayAmount` are used in the API records (the spec says "public-API terms" and also "depends on `engine`"). `BarDto` prices are `double`; money stays in `PlayAmount`.
- **`MarketSnapshot`** carries the `MarketSpec` and the round length; leverage ceiling by stake tier and fees are derived (`EconomyRules`), not copied.
- **`BotStatePort.account`** returns `Optional` (unknown bot is empty); `BotAccount` also lists the round ids the bot has bet in, so the brain skips them (one bet per round of a market). The server's refusal still comes back as a `BetResult`, never an exception.
- **History.** `MarketView.of` keeps only the trailing run of candles exactly one minute apart; a hole shortens the usable history, and a rule below its warm-up stays silent. The brain asks for 100 candles.
- **Warm-ups.** Momentum n+1; RSI max(3n, n+2) (Wilder seed plus smoothing); Bollinger n; Donchian n+1 (the last candle is not in its own channel); EMA cross 2×slow. EMA is seeded with the SMA of its first n values. A flat series is silent for momentum, Bollinger, EMA and (RSI = 50) RSI.
- **Confidence** (0..1) is a proposal-level scale, only used for reporting; nothing sizes a bet by it.
- **Fallback** when the rule is silent (personas with policy FALLBACK): previous majority if known, else a coin flip from the decision's seed. Persona policies: Disciplined, Occasional, Cautious skip a silent market; the others fall back.
- **Persona numbers** (all marked as assumptions in the classes): Disciplined 85% of rounds, 2 markets, constant stake 100/150/200/250 PLAY chosen by bot id, "realistic mix" leverage copied from the server's `BotStrategy.realisticMix`; HouseMoney: stake x1.5 and leverage +50% per win in a row (max 3) after a win; LossChaser: leverage +50% per loss in a row (max 3) after a loss; Overtrader 95%, 3 markets, minimum stake; Occasional 35%, 1 market; Maximalist 90%, 3 markets, the tier's maximum leverage; Cautious 90%, 3 markets, leverage 2..5, stake 100..200. Stakes are multiples of 10 PLAY (no source; `bot_behavior_sources.md` B7, UNVERIFIED).
- **Market choice** is weighted by `1 + 3 * move / maxMove`, `move` being the relative close change over the last 5 candles (B3 lean towards moving markets; the weight is ours).
- **Seeds.** `hash(botId, marketId, roundId)` is 64-bit FNV-1a over UTF-8 with a zero byte between parts (pinned in `BotBrainTest`). The choice of markets, which spans several, uses `("*", marketId:roundId list)` as its market/round parts. Generator: `SplittableRandom`.
- **Catalog.** 21 bots: 5 price strategies x 3 variants, crowd follow x2, crowd contrarian x2, coin x2. Personas are dealt in turn (7 personas x 3 bots), so variants of one strategy get different characters; `catalogId` = `<strategy variant>-<persona>`. The mix is a proposal; change the JSON.
- **Backtest.** One aggregate candle per window for liquidation (the spec says "the window's high/low"); fixed stake 100 PLAY, persona leverage with persona state fed from the replay; no participation sampling and no fallback (a silent window is no bet); crowd strategies are not replayed (no majority in candles). Market terms are the V11 migration's starting values (`BacktestMarket`). Settlement mirrors `GameEngine#resolveRound`.
- **Candle download.** Coinbase 300 candles per call; Hyperliquid serves only about the last 5 000 one-minute candles, so `xyz:` markets get about 3.5 days per run; the script merges into existing files so history accumulates.
