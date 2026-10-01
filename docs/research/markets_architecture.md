# Рынки, категории и живые цены — архитектура и фиксированные интерфейсы

Дата: 2026-10-01. Основание: Р-68…Р-71 (`docs/product/decisions.md`). Заменяет как рабочий план `docs/research/multi_pair_architecture.md` (тот остаётся источником обоснований; семь его вопросов §11 закрыты ниже техническими решениями Т-N). Факты об источниках данных — `docs/research/hl_multi_asset_live.md` и `docs/research/polymarket_data_api.md` (оба 2026-10-01, живые запросы). Референс навигации — `docs/product/design/polymarket-mobile-reference.md`.

Все пункты Т-N — технические решения сессия («предложение»): Алекс может пересмотреть любой, код построен так, чтобы выбор менялся конфигом или одной реализацией интерфейса.

## 1. Что строим

1. Лобби с панелями категорий → список пар → комната пары со ставкой с плечом (Р-69).
2. Все пары идут по живой цене источника; повтор истории остаётся только для тестов (Р-70).
3. Сверх уровня Polymarket (Р-71):

| Что | У Polymarket (2026-10-01, `polymarket-mobile-reference.md`) | В Herd |
|---|---|---|
| Плечо | только в разделе Perps, до 20x | на каждой паре любой категории: крипто до x40, акции/индексы/сырьё по лимиту Hyperliquid, шансы спорта и политики до x10 |
| Толпа | цена/шанс и объём | живой расклад UP/DOWN на карточке и в комнате, ставка «с толпой / против толпы» в одно касание, счёт толпы по каждой паре с результатом |
| Мои позиции | внутри раздела | одна полоса открытых ставок и позиций по всем парам с живым P&L |
| График | цена | цена + линии входа и ликвидации своей позиции и позиции толпы |
| Доверие к итогу | оракул UMA | цена и хеш итогов каждого раунда заякорены в Chia, отметка на итоге и публичный верификатор |
| Игра | — | короткие раунды с отсчётом, моменты итога, серии побед, сезоны и рейтинги |

## 2. Решения

| # | Решение |
|---|---|
| Т-1 | Категории: `crypto`, `politics`, `sports`, `stocks`, `indices`, `commodities`. Первые три — в порядке слов Алекса (Р-69); остальные три — потому что у Hyperliquid есть такие перпы (`hl_multi_asset_live.md` §7), а у Polymarket Perps — такие же чипы. |
| Т-2 | Два вида рынков. `PERP` — mark price перпа Hyperliquid в USD (основной dex и dex `xyz`). `ODDS` — вероятность (0..1) одного исхода рынка Polymarket (CLOB midpoint). Механика одна: раунд, UP/DOWN, плечо, ликвидация по формуле Hyperliquid. |
| Т-3 | Лот-цена. Движок считает цены с 2 знаками (`Candle`, `LiquidationCalculator`). Сервер подаёт в движок цену лота: `engine = raw × 10^lotExp`, `lotExp = clamp(3 − floor(log10(raw)), 0, 8)` в момент листинга (для `ODDS` всегда 4), дальше не меняется. `priceDecimals = 2 + lotExp`. Наружу (API, график) идёт сырая цена с `priceDecimals` знаками. P&L и ликвидация от масштаба не зависят. |
| Т-4 | Плечо рынка: `PERP` — `min(40, maxLeverage)` из `meta` Hyperliquid (тир 0); `ODDS` — 10 (`herd.markets.odds.max-leverage`). Ставка поддерживающей маржи — `1/(2·maxLeverage)`. |
| Т-5 | Один баланс PLAY на игрока. Позиция — одна на игрока на раунд пары (Р-13 читается «на раунд пары»): в одном окне можно держать позиции по разным парам. |
| Т-6 | Счёт толпы — свой на каждую пару: $100 000, свой график, линии «держать актив» и «анти-толпа» (Р-17, Р-25, Р-26 в рамках пары). Сводка по всем парам — отдельным запросом. |
| Т-7 | Рейтинги и сезоны — общие по всем парам и сериям (как Р-30). |
| Т-8 | Боты: каждый бот в каждом раунде серии ставит на `herd.bots.markets-per-round` (3) случайных активных пар; плечо — в пределах лимита пары. |
| Т-9 | Оракул: один синглтон, один общий `seq`, запись на каждый раунд каждой пары. Актив — целочисленный индекс седьмым элементом announcement; для BTC (индекс 0) элемент опускается, история байт-в-байт прежняя. Реестр индексов публичен. |
| Т-10 | Список пар — конфиг плюс каталог Polymarket (топ по объёму за сутки с фильтрами). Параметры пары фиксируются при листинге и не меняются; изменилось у источника — новая пара. |
| Т-11 | Границы раундов для новой БД — кратные длительности «дневной» серии от эпохи Unix (в live: часовые на hh:00, дневные на 00:00 UTC; на стенде 3 и 15 минут — на :00, :03, … и :00, :15, …). Существующая БД сохраняет своё начало отсчёта. |
| Т-12 | Режимы: `replay` — прежний, только BTC, для тестов; `live` — рынки и цены из источников. `herd.prices.source = live \| synthetic`: `synthetic` — детерминированное случайное блуждание для CI, e2e и работы без сети. Стенд: `live`, раунды 180/900 с. |
| Т-13 | Совместимость: старая БД и журнал событий воспроизводятся без изменений — отсутствие рынка в событии означает BTC; `schema_version` событий не повышается; идентификаторы раундов и канонический JSON итогов BTC прежние. |

## 3. Модель

### 3.1 Рынок (сервер)

Интерфейсы — в коде, они и есть фиксированный контракт: `server/src/main/java/crowdvsmarket/server/markets/` — `Market` (запись: `id`, `kind`, `category`, `symbol`, `title`, `subtitle`, `iconUrl`, `source`, `sourceRef`, `sourceMeta`, `maxLeverage`, `lotExp`, `oracleIndex`, `status`, `listedAtMs`, `closedAtMs`, `closeReason`, `endsAtMs`; методы `spec()`, `priceDecimals()`, `toEngine`, `toRaw`, `rawString`, `lotExpFor`), `NewMarket`, `MarketKind`, `MarketStatus`, `Category`, `MarketRegistry` (`all`, `active`, `byId`, `require`, `categories`, `list`, `close`, `setListener`), `MarketListener` (`onListed`, `onClosed`).

BTC всегда есть в реестре: строку создаёт миграция `V7__markets.sql` (`id=BTC`, `lotExp=0`, `maxLeverage=40`, `oracleIndex=0`). Индекс оракула выдаёт реестр — следующий по порядку, никогда не переиспользуется.

### 3.2 Цены

Интерфейсы — `server/src/main/java/crowdvsmarket/server/prices/`: `MarketPrices` (`latest`, `series`, `resolve` — может ходить в сеть и никогда не вызывается под блокировкой движка, `soFar` — только память), `PricePoint`, `PriceQuote`, `RoundPriceData` (цены лота для `GameEngine.runRound`), `PriceUnavailableException`.

Правила (Т-9 цены, Р-23):

1. **Тик** — (время получения ответа, сырая цена). Источник опрашивается по расписанию с `concurrentExecution = SKIP`: Hyperliquid основной dex — `metaAndAssetCtxs` раз в 3 с, поле `markPx`; dex `xyz` — тот же запрос с `"dex":"xyz"` раз в 6 с (вместе 600 из 1200 единиц лимита в минуту на IP, `hl_multi_asset_live.md` §5); Polymarket — один `POST https://clob.polymarket.com/midpoints` со всеми токенами раз в 3 с.
2. **Хранение.** Каждый опрос — одна строка `price_snapshots(source, at_ms, prices JSONB)`; в памяти — буфер тиков на рынок на `2 × длительность дневной серии + 10 мин`. При старте буфер восстанавливается из таблицы. Строки старше окна удаляются раз в 10 минут.
3. **Цена на границе** `P(t)` — последний тик с `at ≤ t`, не старше `staleMs` (Hyperliquid 15 с, `xyz` 20 с, Polymarket 30 с). `open(N) = P(start)`, `close(N) = P(end)`; значит `close(N) = open(N+1)` (Р-40).
4. **Путь раунда** — тики из `(start, end]`, свёрнутые не более чем в 30 свечей OHLC равной длины; первая свеча открывается ценой открытия, последняя закрывается ценой закрытия. Ликвидация проверяется по `low/high` свечей, как сейчас.
5. **Дыра.** Если между соседними тиками окна (включая границы) больше `maxGapMs` (Hyperliquid 30 с, Polymarket 90 с) — дыра добирается из истории источника: Hyperliquid `candleSnapshot` 1m, строки выбираются по `t` (не `get(0)`), каждая свеча даёт четыре точки O, H, L, C; Polymarket `data-api /v2/prices-history`, бакеты 60 с. Нет цены на границе и после добора — `PriceUnavailableException`; планировщик повторяет попытку, через час после конца окна раунд отменяется с возвратом ставок (Р-23).
6. **Синтетический источник** — случайное блуждание с фиксированным зерном на рынок, тик раз в секунду; в тестах тики можно подать вручную.
7. В режиме `replay` всё это не участвует: цены BTC идут из `ReplayRoundPrices`, как раньше.

### 3.3 Каталог

- `PERP`: при старте для каждого имени из `herd.markets.crypto|stocks|indices|commodities`, которого нет в реестре, берётся `meta` источника: `maxLeverage`, `szDecimals`, `marginTableId`, текущая цена → `lotExp`; делистнутые (`isDelisted`) пропускаются. Источник недоступен при первом старте — рынок листится при следующей успешной попытке (раз в минуту).
- `ODDS` (`herd.markets.polymarket.enabled`): раз в 5 минут `GET gamma-api /markets/keyset?tag_id=<2|1>&closed=false&order=volume24hr&ascending=false&limit=50`; кандидаты — `acceptingOrders`, `enableOrderBook`, два исхода, конец не раньше чем через 30 минут; затем живой `midpoint` и `spread` из CLOB: `0.10 ≤ mid ≤ 0.90`, спред ≤ 0.05. Берётся исход с индексом 0. Держим `herd.markets.politics.count` (3) и `herd.markets.sports.count` (3) активных; для спорта сначала идущие матчи (`events/keyset?tag_id=1&live=true`). Одно событие — не больше одного рынка.
- Закрытие `ODDS`: рынок закрыт у источника, либо токен пропал из ответа `/midpoints` на 60 с, либо `mid` вышел из `[0.03, 0.97]`, либо до конца меньше 5 минут. Закрытый рынок не открывает новых раундов; раунд в окне ставок и идущий раунд отменяются с возвратом ставок (причина «market closed»).

## 4. Раунды

1. Ключ серии — `(marketId, Interval)`. `RoundProgress`, `EngineHub`, боты, планировщик работают по этому ключу. Движки одного рынка делят его `CrowdAccount`; все движки делят один `LedgerBook`.
2. Новый рынок получает как идущее окно то, в котором находится «сейчас» (а не первое окно календаря).
3. **Расчёт атомарен.** Цена берётся до блокировки (`MarketPrices.resolve`). Под блокировкой на запись: повторная проверка `round_results` (раунд уже рассчитан — выход), затем одна транзакция БД: событие `RoundResolved`, строка `round_results`, строки `player_round_history`, запись оракула; применение события к движку — внутри той же транзакции. Ошибка — транзакция откатывается и состояние в памяти пересобирается из журнала (`EngineRebuildService.rebuild()`). То же для отмены.
4. Тик планировщика — `concurrentExecution = SKIP`.
5. **Догон после простоя.** Окно со ставками всегда рассчитывается или отменяется. Пустое окно, просроченное больше чем на одну длительность, не рассчитывается: указатель серии переводится на окно, содержащее «сейчас», без события, итога и записи оракула.
6. `placeBet` отклоняет раунд, который уже есть в `round_results` (отменён оператором), рынок не `ACTIVE`, плечо выше лимита рынка.
7. Путь в событии `RoundResolved` — не больше 30 свечей; для раунда без ставок — одна свеча (open → close).

## 5. Движок

FIXED INTERFACE (E) — `engine/README.md`, раздел «Markets»: `MarketSpec(id, maxLeverage, maintenanceMarginRate)`, `RoundWindow(market, interval, startMs, endMs)`, `RoundWindow.parseRoundId`, перегрузки `LiquidationCalculator`/`Position` с параметром рынка, `ExecutionAdapter.openPosition(spec, …)`, `CrowdAccount(startingCapital, adapter, spec)`, `GameEngine` берёт рынок из своего `CrowdAccount`. Идентификатор раунда: BTC — прежний `1h-<startMs>`; остальные — `<marketId>-1h-<startMs>`.

Исправления в движке: архив «богачей» прошлого сезона считается от баланса на момент перехода (`LedgerBook.seasonEndBalance`); счётчики раунда пишутся в сезон времени расчёта; движение, проведённое после перехода, но относящееся к прошлому сезону (поздний расчёт раунда, откат вывода или ввода оператором), добавляется к базе всех следующих сезонов (`LedgerBook.restateLaterBaselines`), так что рост прошлого сезона его включает, а рост следующих — нет.

## 6. События и БД

- `GameEvent.BetPlaced`, `BetCancelled`, `RoundResolved`, `RoundCancelled` получают необязательное поле `market`; `null` при чтении означает `BTC`. `EventStore.CURRENT_SCHEMA_VERSION` остаётся 1. Старые золотые фикстуры событий должны читаться как есть; фикстуры с рынком — в отдельном каталоге.
- Миграция `V7__markets.sql`: таблицы `markets` (поля записи `Market`; строка BTC) и `price_snapshots`; `round_results`, `player_round_history`, `oracle_records` получают `market TEXT NOT NULL DEFAULT 'BTC'`; `oracle_records` — `asset_index INT NOT NULL DEFAULT 0`; индексы `(market, interval, window_start_ms)`.
- `round_results.open_price_cents/close_price_cents` и `oracle_records.price_cents` — цена лота в сотых, то есть `raw × 10^priceDecimals`.
- Канонический JSON итогов: для BTC без изменений; для остальных рынков добавляется корневой ключ `market`. Сохранённый `canonical_json` не пересчитывается никогда.

## 7. Оракул v2 (FIXED INTERFACE O)

1. **Запись** сервер → chainsvc: `{seq, ts, priceCents, resultsHash, source, sourceTs, asset}`; `asset` — индекс реестра, по умолчанию 0. В Rust поле `#[serde(default)]` (файл очереди со старыми записями читается).
2. **Announcement**: `(seq ts priceCents resultsHash sourceFlag sourceTs)` при `asset == 0` и `(seq ts priceCents resultsHash sourceFlag sourceTs asset)` при `asset > 0`. Декодер lineage читает оба (`asset` по умолчанию 0).
3. **Статус** `GET /v1/oracle/status` дополняется `maxKnownSeq` (максимум по accepted, confirmed, conflicts, pending, in_flight) и `recordFormat: 2`.
4. **Пол seq.** Сервер перед выдачей первого `seq` и при каждом старте поднимает свой счётчик до `maxKnownSeq` (`ServerMetaRepository.raiseOracleSeqTo`, монотонно). При `herd.oracle.require-seq-floor=true` (стенд, VPS) планировщик не рассчитывает раунды, пока пол не получен хотя бы раз. Сервер не шлёт записи с `asset > 0`, пока статус не показал `recordFormat ≥ 2`.
5. **Реестр**: `GET /api/markets/registry.json` — `[{index, id, kind, symbol, priceDecimals, source, sourceRef, listedAtMs}]`.
6. **Журнал записей для верификатора**: `GET /api/oracle/records?fromSeq=&limit=` — `{firstSeq, records:[{seq, roundId, marketId, asset, ts, priceCents, resultsHash, state}]}`; `limit ≤ 1000`.
7. **Верификатор** (`tools/verify-oracle`): читает announcement из 6 и 7 элементов (3-элементный первый остаётся в «неразобранных»); со `--stand-url` берёт соответствие `seq → раунд` из журнала записей, сверяет `sha256(results.json)`, цену закрытия и индекс актива; `seq` меньше `firstSeq` стенда показываются как «до эпохи этой БД» и проверяются только по цепи; `--from-seq`; сетевые ошибки — код выхода 2; `--strict` — пропуски и несопоставленные `seq` дают код 1. Офлайн-тесты на фиксированных данных.
8. `MAX_RECORDS_PER_SPEND` поднимается до 40 при наличии теста на симуляторе с 40 записями.
9. Запас комиссионных монет: chainsvc держит не меньше `C2_FEE_COINS_MIN` (4) свободных монет подходящего размера, дробя крупную отдельным спендом; статус показывает число свободных монет и их сумму.

## 8. API (FIXED INTERFACE A)

Источник правды — `web/src/api/contract.ts` (раздел «Markets and categories» и изменённые типы). Сервер зеркалит типы в `server/.../dto`. Транспорт прежний: `POST /api/rpc/<method>` с `{"args":[…]}`.

| Метод | Аргументы | Ответ | Сессия |
|---|---|---|---|
| `markets` | — | `MarketsDto` | нет |
| `priceSeries` | `marketId`, `sinceMs?` | `PriceSeriesDto` — тики за последние 15 минут или с `sinceMs`, не больше 600 точек | нет |
| `referencePrice` | `marketId` | `MarketReferenceDto` | нет |
| `btcReferencePrice` | — | то же для BTC (совместимость) | нет |
| `rounds` | `interval`, `marketId?` (по умолчанию `BTC`) | `RoundsPairDto` | нет |
| `round`, `betFeed`, `placeBet`, `cancelBet` | как раньше; рынок определяется из `roundId` | как раньше | как раньше |
| `previewLiquidation` | `{marketId?, direction, leverage, stake, referencePrice?}` | `LiquidationPreviewDto` | нет |
| `myPositions` | — | `MyPositionDto[]` | да |
| `crowdAccount` | `marketId?` (по умолчанию `BTC`) | `CrowdAccountDto` | нет |
| `crowdOverview` | — | `CrowdOverviewDto` | нет |

Все цены в ответах — сырые, строкой с `priceDecimals` знаками рынка. Новые коды ошибок: `MARKET_NOT_FOUND`, `MARKET_CLOSED`, `PRICE_UNAVAILABLE`. `home` остаётся и описывает BTC.

Клиентский расчёт ликвидации повторяет серверный так: цена умножается на `10^(priceDecimals − 2)`, размер округляется до 8 знаков, применяются прежние формулы с 2 знаками и `l = 1/(2·maxLeverage)`, результат делится обратно.

## 9. Веб

Экраны: лобби (лента категорий «All · Crypto · Politics · Sports · Stocks · Indices · Commodities», чипы «Hot · Closing soon · Favorites», поиск, карточки пар), комната пары `/play/:marketId` (график, серии раундов чипами по длительности, арена UP/DOWN с раскладом толпы, ставка с плечом до лимита пары, «с толпой / против толпы»), полоса «мои позиции», статистика с выбором пары и сводкой, профиль с серией побед. Дизайн и детали — гейм-дизайнер (`.claude/agents/game-designer.md`), по `docs/product/design/`.

## 10. Конфигурация

| Свойство | Переменная | По умолчанию |
|---|---|---|
| `herd.mode` | `HERD_MODE` | `replay` (стенд и VPS — `live`) |
| `herd.prices.source` | `HERD_PRICES_SOURCE` | `live` |
| `herd.markets.crypto` | `HERD_MARKETS_CRYPTO` | `BTC,ETH,SOL,XRP,DOGE,HYPE` |
| `herd.markets.stocks` | `HERD_MARKETS_STOCKS` | `xyz:NVDA,xyz:TSLA` |
| `herd.markets.indices` | `HERD_MARKETS_INDICES` | `xyz:SP500,xyz:XYZ100` |
| `herd.markets.commodities` | `HERD_MARKETS_COMMODITIES` | `xyz:GOLD,xyz:BRENTOIL` |
| `herd.markets.polymarket.enabled` | `HERD_MARKETS_POLYMARKET_ENABLED` | `true` |
| `herd.markets.politics.count` / `sports.count` | `HERD_MARKETS_POLITICS_COUNT` / `HERD_MARKETS_SPORTS_COUNT` | `3` / `3` |
| `herd.markets.odds.max-leverage` | `HERD_MARKETS_ODDS_MAX_LEVERAGE` | `10` |
| `herd.bots.markets-per-round` | `HERD_BOTS_MARKETS_PER_ROUND` | `3` |
| `herd.oracle.require-seq-floor` | `HERD_ORACLE_REQUIRE_SEQ_FLOOR` | `false` (стенд и VPS — `true`) |
| `herd.public-domain` | `HERD_PUBLIC_DOMAIN` | `herd.local` |

Идентификатор рынка из имени источника: `:` заменяется на `_` (`xyz:NVDA` → `xyz_NVDA`), символ на карточке — часть после префикса (`NVDA`). Рынок Polymarket — `PM<id рынка в Gamma>`.

## 11. Что не проверено

- Условия использования данных Polymarket (`polymarket.com/tos`) не читались — **UNVERIFIED**; по принципу проекта условия источников проверяет тот, кто разворачивает (`docs/selection/final_top3.md` §«Принцип»).
- Поведение `markPx` перпов `xyz` в нерабочие часы бирж и `midpoint` Polymarket в момент старта матча — не измерялось.
- Частота «плоских» раундов на шансах (цена не изменилась за 3 минуты) — не измерялась.
- Рост журнала событий: около 450 расчётов раундов и 1 400 ставок ботов в час при 18 парах; снимки состояния для быстрого старта не делаются.
