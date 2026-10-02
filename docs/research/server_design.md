# Дизайн Quarkus-бэкенда (`server/`)

Дата: 2026-09-25. Реализует FIXED INTERFACE (1)/(2)/(3) из постановки задачи «поднять Quarkus
backend» поверх домена `engine/` (Р-20) и подключается к отдельному `chainsvc` (порт 8090,
FIXED INTERFACE 3) для подписи/оракула. Что помечено «предложение» — решение этой сессии,
не зафиксировано в `docs/product/decisions.md`, подлежит пересмотру. Все Р-N — из
`docs/product/decisions.md`.

## 1. Модули и сборка

Корневой `pom.xml` (предложение) — плоский Maven-агрегатор `<modules>engine, server</modules>`,
**не** является `<parent>` ни для одного из модулей: `engine/pom.xml` не тронут (правило проекта —
только аддитивные изменения в `engine/`), оба модуля по-прежнему собираются самостоятельно
(`cd engine && mvn …`, `cd server && mvn …`) — реактор с корня нужен только для удобства
одной команды.

`server/pom.xml` — Quarkus **3.33.3** (LTS-линия анонсирована 2026-03-25, действует до
2027-03-25 — проверено `quarkus.io/blog/quarkus-3-33-released` и GitHub wiki
`quarkusio/quarkus`, 2026-09-25). `3.33.3` — не «3.33.4»: это последний патч, реально
опубликованный для *платформенного* `io.quarkus.platform:quarkus-bom`/`quarkus-maven-plugin`
(компонентный `io.quarkus:quarkus-bom` сам по себе доходит до `3.33.4`, но плагину нужна именно
платформенная версия — проверено live-запросом `maven-metadata.xml`, 2026-09-25). Артефакты веб/
клиентского стека в этой версии называются `quarkus-rest-jackson`/`quarkus-rest-client-jackson`
(современная линия «Quarkus REST», не устаревшие `quarkus-resteasy-reactive-*`), а тестовые —
`quarkus-junit`/`quarkus-junit-mockito` (`quarkus-junit5`/`quarkus-junit5-mockito` — старые
alias-имена, вызывают relocation-предупреждение и не тянут `quarkus-test-common` транзитивно
до конца) — всё это выяснено методом «собери и прочитай ошибку», а не документацией.

Java 21, `mvn -s ~/.m2/settings-oss.xml …` (правило CLAUDE.md).

## 2. Пакеты `server/src/main/java/crowdvsmarket/server/…`

| Пакет | Роль |
|---|---|
| `dto` | Java-записи 1:1 из `web/src/api/contract.ts` (Rounds/Ledger/Crowd/Ratings/Home/Auth/Identity/Betting/ServerInfo/Paging/Enums, а с волны 7 ещё `Markets` — DTO лобби, цен, позиций и сводки толпы) — сгруппированы во вложенные `record` внутри нескольких файлов, а не по файлу на тип, ради скорости; на JSON это не влияет (Jackson сериализует по именам компонентов записи, не по имени Java-класса) |
| `domain` | Живое состояние процесса: `EngineHub` (**один** общий `LedgerBook`; на каждый рынок — `CrowdAccount`, два `GameEngine` HOURLY/DAILY и `ComparisonAccounts` — линии «держать актив»/«анти-толпа», Р-26, Т-6), `RoundProgress` (курсор «следующее окно к резолву» на серию = пара (рынок, интервал)), `RoundCalendar` (origin-эпоха, Т-11), `PlayerDirectory` (кэш игроков), `BetFeed` (лента ставок, Р-62), `WinStreak` (правило серии побед профиля, Р-71) |
| `markets` | Рынки (Т-1…Т-4, Т-10): `Market`, `NewMarket`, `Category`, `MarketKind`, `MarketStatus`, `MarketRegistry` (интерфейс; реализации `DbMarketRegistry` над таблицей `markets` и запасная `InMemoryMarketRegistry`), `MarketListener` (`GameService` — его слушатель: листинг даёт движки и серию, закрытие отменяет раунды с возвратом) |
| `markets.catalog` | Каталоги — какие рынки листить: `HyperliquidCatalog` (перпы из `herd.markets.*`), `PolymarketCatalog` (шансы политики и спорта), `SyntheticCatalog` (офлайн-набор для `herd.prices.source=synthetic`) |
| `prices` | Живой ценовой конвейер (Т-12, §4.2): интерфейс `MarketPrices` и его реализация `LiveMarketPrices`, `PriceFeedService` (опрос по расписанию), `TickStore`/`TickRing` (буфер тиков), источники `HyperliquidPriceSource`/`PolymarketPriceSource`/`SyntheticPriceSource`, `RoundPath` (свёртка в ≤30 свечей) |
| `events` | Event sourcing: `GameEvent` (замкнутый интерфейс с записями-вариантами; события раундов несут необязательное `market`, `null` = BTC, Т-13), `EventStore` (append-only `game_events`), `EventApplier` (**единственный** код, мутирующий `EngineHub` — используется и живым трафиком, и полным реплеем при рестарте, в том числе реплеем «рядом» при пересборке), `StoredRoundPrices` |
| `persistence` | `Db` (тонкая JDBC-обвязка поверх Agroal `DataSource`, ручные транзакции, JSONB через Jackson), плюс по репозиторию на read-model таблицу (`RoundResultRepository` — итоги раундов, история игроков, серия побед; `OracleRecordRepository`, `ServerMetaRepository` и др.) |
| `replay` | `CandleLoader` (парсер CSV, не завязан на `engine.market.CandleSeries`/`Interval` — см. §4), `ReplayRoundPrices` — источник цен режима `replay` (только BTC) |
| `live` | Осталась одна `HyperliquidClient` (generic `/info` REST-клиент) — ею пользуется только адаптер исполнения (§15.7, выключен по умолчанию). `LivePriceSampler` и `LiveRoundPrices` (старый поллер `markPx` и построитель путей) удалены: их заменил пакет `prices` |
| `price` | `PriceService` — только режим `replay`: replay-серия BTC и «референсная» цена `replayReference` |
| `chain` | `ChainSvcClient` (мокируемый CDI-интерфейс) + `ChainSvcRestClient` (сырой MP REST Client, FIXED INTERFACE 3 дословно) + `ChainSvcClientImpl`; `OracleSeqGate` (нижняя граница `seq` оракула: пока она не получена, планировщик ничего не рассчитывает), `OracleStatusCache` (фоновое обновление статуса оракула) |
| `auth` | `AuthService` (challenge/verify, Р-4/Р-45; домен в тексте подписи — `herd.public-domain`), `SessionService` (`herd_session`) |
| `bots` | `BotStrategy` (5 стратегий), `BotService` (Р-46, Т-8: ставки на несколько рынков за раунд) |
| `scheduler` | `RoundSchedulerService` (главный тик, §5), `OraclePublisherService` (Р-40/41/47), `OracleReconciliationService`, `EngineRebuildService` + `EngineStateBuilder` (рестарт и восстановление после сбоя расчёта: состояние строится рядом и подменяется целиком, fail-stop), поллеры вывода/ввода PLAY |
| `service` | `GameService` — оркестратор всех мутирующих операций Api и большинства чтений, замок движка и его узкое API чтения (`readEngine`, `requireEngineReady`); вокруг него: `SeriesSeeder` (откуда стартует серия), `RoundCancellations`, `RoundPriceChecks`; **модель чтения рынков** (волна 7, §20): `MarketViewService` (`markets`, `myPositions`, `crowdOverview`), `OpenBets`, `CrowdStatsMemo`, `MarketMapper`, `PriceSeriesService` + `PriceThinning` (`priceSeries`), `ServerInfoService` (`serverInfo`) |
| `api` | `RpcResource` (`POST /api/rpc/{method}`), `AdminResource` (Р-43), `ResultsJsonResource` (`GET /api/rounds/{id}/results.json`), `ApiExceptionMapper`, `RateLimiter` |
| `util` | `ResultsHasher` (канонический JSON итогов и его хеш; для BTC байт-в-байт прежний, Т-13), `LogThrottle`, `ConstantTimeTokens` |
| `config` | `GameConfig`, `GameMode`, `JacksonConfig` |

Источник списка — дерево `server/src/main/java/crowdvsmarket/server/` на 2026-10-02 и `docs/research/markets_architecture.md` §3–§6.

## 3. Персистентность — предложение: event log + read models

`V1__init.sql` (Flyway): `server_meta` (origin-эпоха, oracle-seq), `players` (измерение,
не реплеится), `game_events` (append-only — **единственная** таблица, которую реплеит
`EngineRebuildService` при старте), `round_results` (+ `canonical_json` для байт-в-байт
`results.json`), `player_round_history` (профиль игрока, отдельно от `round_results.positions_json`,
чтобы не делать JSONB-containment запрос на каждый профиль), `withdrawal_requests`/
`deposit_requests`, `auth_challenges`, `sessions`, `oracle_records`.

**Почему так:** движок (`LedgerBook`/`GameEngine`/`CrowdAccount`) не хранит историю сам по себе
(кроме `CrowdAccount.history()`, которая накапливается только пока жив сам объект) — значит,
чтобы «пережить рестарт», нужно либо (а) переигрывать поток команд заново через тот же движок,
либо (б) хранить снепшоты. Выбран (а): `game_events` хранит ровно то, что нужно, чтобы вызвать
`GameEngine.placeBet/cancelBet/runRound` или `LedgerBook.request*/confirm*/fail*` заново в том же
порядке и с теми же аргументами (включая исходный `atMs`, а не время рестарта) — `EventApplier`
это делает, и **это тот же код**, что применяет событие сразу после записи в БД при живом
трафике (`GameService.persist(event, now); eventApplier.apply(event);`). Ни одного отдельного
«восстановительного» пути нет — значит, ему негде разойтись с тем, что реально произошло.

`round_results`/`player_round_history`/`withdrawal_requests`/… — это read-model'и, они пишутся
один раз при первом наступлении события и **не** переигрываются — их не нужно реплеить, они уже
durable. Из этого следует нюанс для `CrowdRoundResultDto`/`ComparisonAccounts`: `CrowdAccount.
history()` восстанавливается «бесплатно» при реплее (`GameEngine.runRound` сам вызывает
`crowdAccount.processRound(...)` внутри), а `ComparisonAccounts` («держать BTC»/«анти-толпа») —
устройство **этого сервера**, не движка (contract.ts прямо помечает их «предложение», класса
`BtcHoldAccount`/`AntiCrowdAccount` в `engine/` нет) — поэтому `EventApplier` после каждого
`RoundResolved` кормит `comparisonAccounts.onRoundResolved(report, path)` тем же вызовом,
что и при живом резолве, так что и эта история восстанавливается тем же реплеем.

**Id заявок на вывод/ввод** (`WithdrawalRequestDto`/`DepositRequestDto.requestId`) — предложение:
не изобретается отдельный UUID, а переиспользуется id, который `LedgerBook.requestWithdrawal`/
`requestDeposit` генерирует сам (`"w-N"`/`"d-N"`, один общий счётчик). Поскольку и живой вызов,
и реплей идут через один и тот же метод в том же относительном порядке, id воспроизводится
детерминированно —读-модельная таблица просто хранит то, что вернул движок, а не решает
самостоятельно.

**Вывод/ввод PLAY (Р-6) — предложение, заглушка `OnChainWalletPort`:** реального моста в
testnet11-CAT нет (в FIXED INTERFACE (3) для этого нет эндпоинта у `chainsvc` — только auth и
oracle) — `requestWithdrawal`/`requestDeposit` сразу же переводятся в `CONFIRMED` тем же кодом,
что обработал бы настоящее подтверждение. Честно задокументировано здесь и в самом коде
(`GameService`, комментарий у секции withdraw/deposit) — это не то же самое, что «реальные
деньги» (речь по-прежнему только о PLAY, toy-currency, Р-2/Р-6), но реальный ончейн-мост —
отдельная будущая задача.

**С волны 7 (рынки, Р-69…Р-71; `docs/research/markets_architecture.md` §6, §20 ниже):** миграция
`V7__markets.sql` добавила таблицы `markets` (реестр рынков; строку BTC создаёт сама миграция) и
`price_snapshots` (по строке на опрос источника цен — восстанавливает буфер тиков после рестарта), а
`round_results`, `player_round_history` и `oracle_records` получили колонку `market` (по умолчанию
`'BTC'`: вся прежняя история — BTC без пересчёта), `oracle_records` ещё и `asset_index`. События
раундов в `game_events` несут необязательное поле `market` (`null` = BTC), `schema_version` не
повышался (Т-13): старый журнал воспроизводится как есть. Где в тексте выше сказано «BTC» про
`ComparisonAccounts`, «счёт толпы» и `EngineHub` — теперь это по одному комплекту на рынок.

## 4. Режимы (Р-46) — replay (по умолчанию) и live

### 4.1 Replay

`server/src/main/resources/replay/btc_1m.csv` — снятые **2026-09-25 ~17:18 UTC** последние 5129
закрытых минутных свечей BTC (2026-09-22T03:49 → 2026-09-25T17:17), тот же формат колонок, что
`engine/…/fixtures/*.csv`, provenance — `replay/PROVENANCE.md` рядом. Не переиспользован
`engine.market.CandleSeries`/`Interval` (у `Interval` только `HOURLY`/`DAILY`, добавлять `MINUTE`
означало бы трогать все `switch`, которые сейчас исчерпывающи без `default` —
`RoundClock.stepMs` и т.п.) — вместо этого `CandleLoader` строит `List<Candle>` напрямую тем же
парсером, без завязки на engine-класс, который для этого не предназначен.

`ReplayRoundPrices` — предложение: скорость `speedup=20` (config `herd.replay.speedup`), т.е.
**одна историческая минута = 3 реальных секунды** — подобрано так, что Р-46'овский тестовый
HOURLY-раунд (180 с) проигрывает ровно 60 исторических минут (= 1 час, отсюда и название).
DAILY-раунд (900 с) при этом проигрывает 300 минут (5 часов), не буквально «день» — реальное
соотношение 24× не сохранено намеренно (иначе DAILY в тесте занимал бы 72 минуты), это
осознанное упрощение, не противоречащее ни одному Р-N (Р-46 фиксирует только длительности и то,
что цена — «ускоренный повтор реальных исторических цен», не точный коэффициент 24).
Отображение «окно → индекс свечи» — **по индексу массива**, не по абсолютному timestamp
(`ReplayRoundPrices.indexAt`), с зацикливанием по модулю на конце файла — задокументированный
краевой случай: на границе зацикливания возможен один синтетический скачок цены внутри раунда,
это допустимо для локального тестового стенда.

**Выравнивание границ раундов на эпоху старта сервера** (task: «boundaries aligned to the server
start epoch»): `RoundCalendar`/`ServerMetaRepository.originMsOrInit` фиксируют `origin_ms` в
`server_meta` при первом запуске и переиспользуют при каждом следующем — иначе рестарт сдвинул
бы все границы раундов из-под уже сделанных ставок. `RoundClock` (движок) получил для этого два
аддитивных метода — `stepMs(interval, RoundDurations)` и
`windowContaining(interval, epochMs, RoundDurations, originMs)` — старые методы не изменились
(эквивалент вызову с `RoundDurations.DEFAULT`/`originMs=0`), с юнит-тестами в
`RoundClockTest` (5 новых кейсов).

### 4.2 Live (Р-70, Т-12)

В `herd.mode=live` играются **все активные рынки** реестра (`GameService.runs`/`runningMarkets`),
а цены — живые: раунд рассчитывается по ценам источника на его границах, а не по повтору истории.
Архитектура и правила — `docs/research/markets_architecture.md` §3.2–§3.3; здесь — как это лежит в
коде (`server/src/main/java/crowdvsmarket/server/prices/`, `…/markets/catalog/`).

- **Опрос.** `PriceFeedService` по расписанию (`@Scheduled`, `concurrentExecution = SKIP`) опрашивает
  активные рынки каждой ленты: Hyperliquid основной dex раз в 3 с и dex `xyz` раз в 6 с (один
  `metaAndAssetCtxs`, поле `markPx`), Polymarket — один `POST /midpoints` по всем токенам раз в 3 с.
  При `herd.prices.source=synthetic` вместо них раз в секунду тикает `SyntheticPriceSource` —
  детерминированное блуждание без сети (для CI, e2e, работы офлайн; `HERD_PRICES_SOURCE`). Ответ — по
  тику на рынок (время прихода ответа, сырая цена) и одна строка `price_snapshots`.
- **Буфер.** `TickStore`: кольцо тиков на рынок (`TickRing`) глубиной `2 × длительность суточной
  серии + 10 мин` (`PricesConfig.retentionMs`); при старте восстанавливается из `price_snapshots`,
  раз в 10 минут обрезается.
- **Цена на границе, путь, дыра.** `LiveMarketPrices` (реализация `MarketPrices`): `open(N) =
  P(start)`, `close(N) = P(end)` — последний тик не позже границы и не старше `staleMs` ленты, поэтому
  `close(N) = open(N+1)` (Р-40); путь раунда — тики окна, свёрнутые не более чем в 30 свечей
  (`RoundPath`); дыру больше `maxGapMs` добирает история источника (не чаще раза в
  `herd.prices.backfill-cooldown-ms` на рынок). Нет цены даже после добора —
  `PriceUnavailableException`: планировщик повторяет, через час после конца окна раунд отменяется с
  возвратом ставок (Р-23, §5). `resolve` может ходить в сеть и поэтому зовётся **до** блокировки
  движка; `latest`, `series`, `soFar` — только память.
- **Каталоги** (`markets/catalog`): `HyperliquidCatalog` листит перпы из `herd.markets.crypto|stocks|
  indices|commodities`, `PolymarketCatalog` — шансы политики и спорта (топ по объёму с фильтрами,
  закрывает рынок, ушедший от источника), `SyntheticCatalog` — офлайн-набор (перпы тех же списков и
  `PMSYN…` для политики и спорта). Листинг идёт через `MarketRegistry.list`, и слушатель `GameService`
  выдаёт рынку движки и серию.
- **Режим `replay` остаётся** (только BTC, `ReplayRoundPrices`, §4.1) — для тестов и работы без сети;
  стенд и VPS — `live` (`docs/research/markets_architecture.md` §10).

Старые `live.LivePriceSampler` (поллер раз в 2 с) и `live.LiveRoundPrices` удалены; не покрыто живым
интеграционным тестом против реальных источников — сеть в тестах не ходит; весь конвейер проверен
на синтетическом источнике (`LiveServerEndToEndTest`, §20.6), реальные ответы — только записанными
образцами 2026-10-01 (`server/src/test/resources/fixtures/`, фикстуры конвейера).

## 5. Планировщик и «текущий раунд» (Р-19)

`RoundProgress` хранит явный курсор «следующее окно к резолву» на серию — серия это пара **(рынок,
интервал)** (`docs/research/markets_architecture.md` §4 п.1) — и **не** пересчитывается наивным
`RoundClock.windowContaining(interval, now, …)` на каждое чтение: ровно в момент границы это дало бы
гонку с планировщиком, который ещё не успел резолвить истёкший раунд. Курсор — это раунд «идёт»
(`RUNNING`), следующий за ним — «открыт для ставок» (`BETTING`).

Тик `RoundSchedulerService` — раз в секунду (`@Scheduled(every = "1s", concurrentExecution = SKIP)`;
ключ `herd.scheduler.tick-ms` читается в `GameConfig`, но период тика не задаёт); `tick(now)` принимает
часы аргументом — так тесты ведут время сами:

```
tick(now):
  если движок не готов (EngineRebuildService.isReady) — только пробует пересборку и выходит
  если OracleSeqGate.open() == false — ничего не рассчитывает и не отменяет, раз в минуту пишет в журнал
  для каждого играемого рынка без серии — GameService.ensureSeries(рынок, now)
  BotService.tick(now)
  для каждого (рынок, интервал): resolveOverdueRounds(рынок, интервал, now)
  OraclePublisherService.publishPending()

resolveOverdueRounds(рынок, интервал, now):
  while (курсор есть && now >= курсор.endMs):
      target = firstWindowToResolve(курсор, now)     // пустое окно, просроченное больше чем на шаг, пропускается:
      if target != курсор: jumpTo(курсор → target)   //   без события, итога и записи оракула (строка в журнале)
      elif round_results.exists(курсор): advance     // уже отменён оператором заранее
      else:
          try resolveRound(курсор, now)              // цена берётся до блокировки, расчёт — одна транзакция БД
          catch (любой сбой расчёта):                // нет цены, цена негодна, сбой источника, транзакция не прошла
              если now >= курсор.endMs + 1ч: cancelRound(курсор, причина) с возвратом ставок; advance
              иначе: return                          // повтор на следующем тике
          advance
```

Это же «догоняет» пропущенное после рестарта или паузы: каждый шаг цикла детерминированно резолвит
одно окно по его собственным границам, сколько бы реального времени ни прошло, а окно со ставкой
рассчитывается или отменяется **всегда**, как бы поздно ни было (`docs/research/markets_architecture.md`
§4 п.5, п.8). Источник — `scheduler/RoundSchedulerService.java`, `service/GameService.java`
(`resolveRound`, `firstWindowToResolve`), `service/SeriesSeeder.java`.

**Оператор** (Р-32/Р-43) может отменить **текущий running ИЛИ текущий betting** раунд серии;
`GameService.cancelRound` не трогает `RoundProgress` сам — если это был `next(…)`, планировщик на
следующем тике увидит уже существующую запись в `round_results` и просто шагнёт дальше (ветка
«уже отменён» выше) — так что отмена заранее открытого-для-ставок раунда и отмена уже идущего
работают одним и тем же кодом. Закрытие рынка отменяет оба его раунда с возвратом и останавливает его
серию (`RoundCancellations.closeSeries`).

**«RUNNING» — предложение**, синтезируется на лету (`GameService.buildRunningDto`), не хранится
как состояние движка (сам `round_engine_design.md` §2 это явно оговаривает: `GameEngine.
runRound` резолвит атомарно, отдельного состояния «идёт» в модели нет). Показ раскрытых позиций
во время RUNNING считается **без мутации** `LedgerBook`/`CrowdAccount` — по частичному пути цены
от начала окна до «сейчас» (`MarketPrices.soFar`, только память) через `PositionSimulator`/
`LiquidationCalculator` напрямую; настоящее списание/начисление происходит только в момент реального
резолва. Пока цена открытия неизвестна (рынок только что появился, после рестарта буфер ещё пуст,
сбой источника через границу), `RoundRunning.openPrice` — `null`, позиций и позиции толпы нет: ни
нуля, ни последней цены вместо неё не показывается (contract.ts).

## 6. Боты (Р-46, Т-8)

`BotService` держит 20 (config `herd.bots.count`) серверных игроков (`is_bot=true`),
по 4 на 5 стратегий (`BotStrategy` — предложение, конкретные названия/параметры не из
источников): всегда максимальное плечо, контрарианец (против прошлого большинства раунда серии),
за большинством, случайный, осторожный (низкое плечо). Ставка идёт через тот же
`GameService.placeBet`, что и у игрока-человека — те же проверки (минимальная ставка, плечо
2..предел рынка, одна ставка на раунд рынка).

**Рынки (Т-8, `docs/research/markets_architecture.md` §2, §10):** в каждом раунде серии каждый бот
ставит на `herd.bots.markets-per-round` (по умолчанию 3) разных случайных активных рынков — меньше,
если рынков меньше, — по одной ставке на рынок, в случайный момент окна ставок этого рынка (не позже
90% его длины). Плечо — в пределах лимита рынка (`Market.maxLeverage`); «прошлое большинство» для
контрарианца и «за большинством» — прошлое большинство **этого** рынка и этой серии
(`RoundResultRepository.lastMajorityDirection(market, interval)`). В `live` бот не ставит на рынок
без свежей цены (нет тика или он просрочен): такой рынок выпадает из выбора и проверяется ещё раз в
момент ставки. Окна ставок рынков, закрывающиеся одновременно, планируются вместе (`BotService.
planBettingWindows`); источник — `bots/BotService.java`.

## 7. Auth (Р-4/Р-45)

`AuthService.challenge` — nonce (24 случайных байта, base64url), сообщение начинается с
нехексового префикса `"Herd sign-in\n"` (ловушка hex-парсинга Sage,
`docs/research/week1_wallet_auth.md` §2), TTL 5 мин (config), одноразовое использование
(`auth_challenges.used`, атомарный `UPDATE … WHERE used=false`). `verify` пересобирает и
проверяет nonce **на сервере**, зовёт `chainsvc POST /v1/auth/verify` (реальная BLS-криптография —
целиком забота `chainsvc`, этот сервер её не реализует) и только по `valid:true` заводит/трогает
игрока. Локальный тестовый кошелёк в браузере (Р-45) подписывает то же сообщение той же схемой —
для этого сервера разницы нет вообще, он просто шлёт `(address, publicKey, message, signature)`
в `chainsvc`.

Идентичность игрока — `walletAddress` (первый подписавший адрес, Р-1/Р-4); повторный вход тем же
адресом находит того же `playerId`. Каждый *успешный* `authVerify` (не только первый) заново
дёргает `ensureSeasonPack` через `PlayerRegistered`-событие — это то, что держит видимый
`myAccount().currentSeason` точным без отдельного публичного геттера в `LedgerBook`
(см. javadoc `GameService.account`/`registerOrTouchPlayer`).

## 8. Оракул (Р-40/41/47)

Каждый резолв раунда (в т.ч. тестовый режим — **на каждой границе раунда**, не только раз в час,
Р-47) кладёт одну строку в `oracle_records` (свой монотонный `seq`, `server_meta`), помеченную
`source="replay"`/`"live"` и с `sourceTs` — реальным историческим моментом цены при реплее (не
сжатым временем раунда). `OraclePublisherService` на каждом тике планировщика забирает ещё не
отправленные записи (`chainsvc_queued=false`) пачками по 20 (max singleton-спенд, по FIXED
INTERFACE 3) и шлёт `POST /v1/oracle/records`; неудача — запись остаётся, повтор на следующем
тике. Резолв раунда **никогда** не ждёт ответа `chainsvc` (задача: «never block the game on chain
confirmation»).

`resultsHash` (FIXED INTERFACE 2) — канонический JSON строится `ResultsHasher` по правилу
«ключи отсортированы, без пробелов» **буквально**: `TreeMap` на каждом уровне (естественный
байт-лексикографический порядок ASCII-имён полей) — расходится с порядком перечисления полей в
самой задаче (тот порядок смысловой, не алфавитный), но следует явно сформулированному правилу
canonicalization, а не иллюстративному списку. Предложение (не зафиксировано текстом задачи):
`outcome` ∈ `{SETTLED, LIQUIDATED, CANCELLED}`; отменённый раунд тоже получает `resultsHash`
(все цены/крауд — `null`, каждая позиция — полный рефанд с `outcome=CANCELLED`), чтобы у *любого*
раунда была проверяемая страница `results.json`, не только у settled.

## 9. Оператор (Р-43)

`/api/admin/*` — bearer-токен из `HERD_OPERATOR_TOKEN` (config `herd.operator.token`),
сравнение constant-time. **Это временная заглушка**, не подпись ключом оператора — Р-43 говорит
про «CLI с подписью ключом оператора», которого пока нет; токен — то же самое признание
ограничения, что и у `OnChainWalletPort` (§3). `tools/herd-operator.sh` — curl-обёртка
(`status`/`cancel <roundId> [reason]`). Веб-админки нет (Р-43).

## 10. Docker

`server/Dockerfile` — двухстадийная сборка: `maven:3.9.9-eclipse-temurin-21` компилирует
`engine`+`server` (реактор с корневого `pom.xml`, **обычный `~/.m2/settings.xml` образа**, не
хостовый `settings-oss.xml` — задача явно требует «plain Maven Central» внутри Docker), рантайм —
`eclipse-temurin:21-jre-jammy`, непривилегированный пользователь, fast-jar layout Quarkus
(`/deployments/quarkus-run.jar`), порт 8081.

**Проверено в этой сессии** (сборка + прогон, не только `docker build`):
`docker build -f server/Dockerfile -t herd-server:test .` из корня репозитория — собрался,
образ поднят с настоящим (не Dev Services) `postgres:16-alpine` в отдельной docker-сети,
`POST /api/rpc/serverInfo`/`POST /api/rpc/rounds` ответили ожидаемым JSON в prod-режиме;
`tools/herd-operator.sh status`/`cancel <roundId>` тоже проверены живым запуском против этого
контейнера (успешная отмена реального текущего раунда, неверный токен — `401 UNAUTHORIZED`,
несуществующий/неверный roundId — `400 BAD_REQUEST`). Все тестовые контейнеры/сеть/образ после
проверки удалены, посторонние контейнеры на машине не тронуты.

**Особенность именно этой сессии** (не относится к самому Dockerfile): бридж-сеть Docker по
умолчанию в этом sandboxed-окружении агента не резолвит DNS наружу (`apk add`/обычный
`docker build` без флага падал на `Unknown host repo.maven.apache.org`), хотя прямой доступ в
интернет с хоста (`curl`, `mvn` вне Docker) работает. Сборка образа в этом проходе делалась с
`docker build --network host …` только чтобы обойти это ограничение конкретно здесь — обычный
докер-хост (например, Alex'ов Mac, Р-44) такого ограничения иметь не должен, и `docker-compose`-
стенд проекта `herd` эту проблему видеть не будет; если всё-таки увидит — решение то же самое
(host-сеть на этапе сборки, не в готовом Dockerfile).

## 11. Тесты и реальные числа

Полный прогон (`mvn -s ~/.m2/settings-oss.xml -f pom.xml clean install`, 2026-09-25):

- `engine`: **6519 тестов, 0 упавших** (не изменилось по сути — плюс 5 новых кейсов в
  `RoundClockTest` на аддитивные `RoundDurations`/origin-перегрузки).
- `server`: **12 тестов, 0 упавших**:
  - `ResultsHasherTest` (4) — сортировка ключей на каждом уровне, sha256 от точных байт,
    `null`-crowd, детерминированность.
  - `ReplayRoundPricesTest` (7) — индекс на origin, скорость 3с/минута, зацикливание, путь/
    open/close ровно по границам окна, короткое окно даёт ≥1 свечу, валидация конструктора.
  - `GameFlowIntegrationTest` (1, `@QuarkusTest` + Dev Services Postgres + `@InjectMock
    ChainSvcClient`) — **тот самый** тест из задачи: тестовый профиль сжимает раунды до 4с
    (HOURLY)/6с (DAILY), два игрока входят локальной тестовой подписью (chainsvc замокан —
    настоящая криптография не участвует), ставят противоположные позиции на текущий
    betting-раунд; проверено: (a) во время BETTING виден только `participantCount`, структурно
    невозможно увидеть направление/сумму чужой ставки (Р-22); (b) после SETTLED обе позиции
    видны всем, с верным направлением/плечом; (c) баланс игрока меняется (ставка списана,
    выплата начислена); (d) `GET results.json` даёт байты, чей sha256 **точно** равен
    `resultsHash`, и текст содержит `roundId`/оба `playerId`, без пробелов; (e) ожидаются ещё 2
    раунда (боты играют сами — всего 3 «быстрых» раунда); (f) `EngineRebuildService.rebuild()`
    вызван напрямую (тот же код, что и при реальном рестарте процесса) — баланс игрока, капитал
    и история «толпы» до и после совпадают, и игрок может продолжать ставить после рестарта.
- Общий реактор: **6531 тест, 0 упавших, 0 ошибок.**

`Dockerfile`: собран и запущен живьём (см. §10) — отдельно от Maven-тестов, вручную в этой сессии.

## 12. Каталог «предложений» этого прохода (сводно)

Помимо перечисленного по разделам выше:
- Один общий `LedgerBook`+`CrowdAccount` на обе серии (HOURLY/DAILY), раздельные `BetBook`
  (по одному на `GameEngine`) — иначе рейтинги/баланс/график толпы задваивались бы.
- `PageParams.limit` по умолчанию 20, максимум 200; профиль без пагинации ограничен последними
  100 раундами истории.
- `home()`/`ratings()` — топ-5 в каждом списке.
- Сезонная доходность (`SeasonReturnDto`) считается от капитала на конец предыдущего сезона (или
  стартового капитала — для самого первого).
- `ApiErrorCode` дополнен тремя значениями, которых нет в `contract.ts` (`UNAUTHORIZED`,
  `NOT_FOUND`, `BAD_REQUEST`) — для admin/RPC-ошибок, которые `contract.ts` не описывает (он
  документирует только ошибки уровня `Api`-методов).
- `withdrawal(requestId)`/`deposit(requestId)` требуют подписанной сессии и владения этой заявкой
  (`NOT_FOUND`, если чужая), хотя `contract.ts` не называет их `myWithdrawal`/`myDeposit`:
  `requestId` — маленький предсказуемый счётчик (`LedgerBook`'овский `"w-N"`/`"d-N"`, см. §3), без
  этой проверки перебор id раскрыл бы чужую историю выводов/вводов.

## 13. Волна 2 (после первого раунда тестирования, перед ревью критика) — 2026-09-25

Правит найденное первым раундом тестирования: chainsvc-аутентификация (клиентская сторона), сверка
durable delivery (FIXED INTERFACE (c)), входная валидация из wave-1 security findings. Затронуты
только `server/`, `docker-compose.yml`, `tools/stand.sh` — `chain/` (сам `chainsvc`, стороны (a)/(b)
FIXED INTERFACE) правит отдельная сессия параллельно; здесь описана только клиентская/серверная
половина, которую видит этот модуль.

**FIXED INTERFACE (a), клиент** — `ChainSvcClientImpl` шлёт `Authorization: Bearer
<herd.chainsvc.token>` на каждый вызов `ChainSvcRestClient`, кроме `GET /health` (задача явно
исключает его из аутентификации). Токен — новый конфиг `herd.chainsvc.token`
(`HERD_CHAINSVC_TOKEN`), должен совпадать с `chainsvc`'овским `C2_SVC_TOKEN` — `tools/stand.sh`
генерирует оба в `.env` одним значением (переиспользует существующее, если `.env` уже частично
заполнен вручную, чтобы не разъехались). `docker-compose.yml`: `chainsvc` получает
`C2_BIND_ADDR=127.0.0.1,172.28.77.1` — сервис работает в `network_mode: host` (см. §10-соседний
комментарий в самом файле), значит `0.0.0.0` слушал бы снаружи машины; нужны оба адреса, потому что
`server` достаёт его через шлюз `herd_default` (`172.28.77.1`, см. существующий комментарий про
`HERD_CHAINSVC_URL`), а не только с самого хоста chainsvc-контейнера.

**FIXED INTERFACE (c), сверка durable delivery** — новый `OracleReconciliationService`
(`@Scheduled(every="60s")`): для каждой строки `oracle_records`, уже хотя бы раз отданной chainsvc
(`chainsvc_queued=TRUE`) и ещё не известной `confirmed`, зовёт новый
`GET /v1/oracle/records/{seq}` (FIXED INTERFACE (b)) и сохраняет `state`/`height`/`coinId`
(V2-миграция: `oracle_records.state|height|coin_id|state_checked_at_ms`, `state` по умолчанию
`'unknown'` — честный ответ «ещё не спрашивали»). Запись, которую chainsvc всё ещё называет
`"unknown"` спустя 2 минуты после постановки в очередь этим сервером, перепосылается той же
`POST /v1/oracle/records` — по FIXED INTERFACE (b) chainsvc принимает такую запись как
LATE/backfill идемпотентно («каждый seq ровно один раз»), так что повторная отправка на каждом
тике, если состояние так и не сдвинулось, безопасна. Заодно `OraclePublisherService` перешёл на
`acceptedSeqs` из ответа (`{queued:int, acceptedSeqs:[int]}`, FIXED INTERFACE (b)) вместо сравнения
счётчика с размером пачки — идемпотентно принятая (уже `confirmed`/`queued` на стороне chainsvc)
запись не должна навсегда застревать «в ожидании» из-за несовпадения счётчика.

**`oracleAnchor` (опционально) на `RoundSettled`/`RoundCancelled`** — `{seq, state,
height|null}`, отдельный lookup в `RoundResultRepository.findAnchorByRoundId` (не JOIN в
`toRoundDto`: это данные из другой таблицы, которой владеет `OracleRecordRepository`, и они
принципиально eventually-consistent). Отсутствует (`null`/поле не появляется — Java-record
компонент всё равно сериализуется, но со значением `null`), если раунду ни разу не завели запись
оракула — сегодня это все отменённые раунды (`GameService.cancelRound` не зовёт
`enqueueOracleRecord`, только `resolveRound`/`storeSettledResult` для settled). `web/` не трогали
(задача это прямо запрещает) — фронт подхватит поле отдельно.

**Входная валидация (wave-1 security findings)**:
- `setNickname` — trim + allow-list `[A-Za-z0-9 _.-]{3,24}` одним regex'ом (`matches()` требует
  совпадения ВСЕЙ строки, так что allow-list сам по себе уже отбрасывает управляющие символы и
  NUL — отдельной проверки не нужно) → `400 INVALID_NICKNAME` (предложение, additive
  `ApiErrorCode`, тот же случай, что уже задокументированный `UNAUTHORIZED`/`NOT_FOUND`/
  `BAD_REQUEST` в §12 — не в `contract.ts`).
- `authVerify`/`previewLiquidation`/`placeBet` — новый `RpcResource#requiredObj` вместо `obj`:
  отсутствующее/`null` тело аргумента теперь `400 BAD_REQUEST` вместо `NullPointerException` →
  500 (эти три метода первым делом разыменовывают компонент своего request-record).
- Ошибки самого Jackson (несовпадение типа, неразбираемый JSON) — везде generic `"invalid
  request body"`/`"invalid JSON body"`, без `e.getMessage()`: тот мог называть Java-класс DTO
  (`RpcResource#obj`'ов `IllegalArgumentException` от `convertValue`) или сырой текст запроса
  (`argsArray`). Плюс `ApiExceptionMapper` теперь ловит `JsonProcessingException` напрямую (400,
  не 500) — нужно для `AdminResource`'ового JAX-RS-биндинга тела, который не проходит через
  `RpcResource#obj` вообще.
- `leverage` (и вообще любое целочисленное поле любого RPC-DTO) — Jackson по умолчанию тихо
  округляет JSON-float до `int` (`ACCEPT_FLOAT_AS_INT`, `10.5` → `10`); новый
  `JacksonConfig implements ObjectMapperCustomizer` отключает эту фичу глобально, так что
  нецелое число теперь честная `400`, а не тихая порча значения.
- `Db.tx` — SQLException, брошенный самим `work` (например, `prepare()`'ов `"failed to serialize
  jsonb param"` при плохом jsonb-значении, или constraint violation), раньше долетал наружу как
  `RuntimeException("db connection failure", …)` — неотличимо от реального разрыва соединения.
  Теперь это `"db operation failed: …"`; `"db connection failure"` — только для
  getConnection()/setAutoCommit()/commit()/close(). `ApiException`/прочий `RuntimeException` из
  `work` (бизнес-валидация) по-прежнему пролетает не тронутым.
- `AdminResource` — сравнение bearer-токена теперь через новый
  `util.ConstantTimeTokens.equals` (SHA-256-дайджест обеих сторон, потом
  `MessageDigest.isEqual`) — старое сравнение уходило с ранним `return false` при несовпадении
  *длины*, до входа в константно-временный цикл, что само по себе давало через тайминг узнать
  длину токена.
- Rate limit (Р-38, заглушка до VPS) — `RateLimiter`: token bucket в памяти, ключ
  `"<ip>:<method>"`, на `authChallenge`/`authVerify`/`placeBet` (`RpcResource` проверяет до
  диспетчеризации, отдельный бакет на каждый метод — иначе шумный вызов одного метода съедал бы
  бюджет другого). Предложение-дефолты (не из источника): capacity 30, refill 0.5/сек
  (`herd.ratelimit.*` / `HERD_RATELIMIT_*`) — «generous limits» по формулировке задачи, ничем
  жёстче не зафиксировано. Не переживает рестарт процесса и не общий между инстансами — сознательно
  временная замена для локального стенда/демо-VPS, не для реального продакшена с несколькими
  инстансами.

**Тесты и реальные числа** (`mvn -s ~/.m2/settings-oss.xml -f pom.xml clean verify`,
2026-09-25): `engine` — **6519, 0 упавших** (не тронут, аддитивное правило проекта соблюдено —
изменений в `engine/` в этом проходе нет вообще). `server` — **56, 0 упавших**: новые
`RpcValidationTest` (12), `AdminResourceValidationTest` (5), `RateLimitTest` (2, свой
`@TestProfile` с capacity=2/refill=0 — детерминированно за 3 запроса, а не выжиганием реального
«generous» лимита), `OracleReconciliationServiceTest` (5), `ChainSvcClientImplTest` (7, чистый
Mockito-юнит на `ChainSvcRestClient`, без `@QuarkusTest`), `DbTxErrorLabelingTest` (5, чистый
Mockito-юнит на замоканных `DataSource`/`Connection`), `ConstantTimeTokensTest` (6) — плюс
обновлённые под новый `ChainSvcClient.OracleRecordsResult` `GameFlowIntegrationTest`/
`BotRestartRecoveryTest`. Реактор целиком: **6575, 0 упавших**, `BUILD SUCCESS`.

Попутно найдено и по возможности исправлено (не отдельные Р-N, находки при самом прогоне):
`AdminResource`'овский путь (JAX-RS `@Consumes`-биндинг тела запроса, в отличие от
`RpcResource`'овского ручного `mapper.convertValue`) на самом деле не бросает
`com.fasterxml.jackson.core.JsonProcessingException` напрямую — Quarkus REST
(`ServerJacksonMessageBodyReader`) сам оборачивает её в `jakarta.ws.rs.WebApplicationException` с
уже правильным `400`-ответом внутри, который старый `ApiExceptionMapper` (ловил только
`ApiException`/`NoSuchElementException`/`IllegalArgumentException`) не узнавал и топил в общем
`500` — проверено прогоном, не предположением; добавлена ветка на `WebApplicationException` (4xx
→ сохранить статус, но подменить тело на обычный `{"error":{...}}` с общим сообщением, не тем, что
несёт сама обёрнутая `JsonProcessingException`).

`BotRestartRecoveryTest` — не результат этого прохода, но зафиксировано при повторных локальных
прогонах (несколько запусков подряд для проверки новых тестов): тест изредка красный сам по себе,
двумя разными способами — (а) документированная вероятностная природа таймингов ботов (см. его же
javadoc про «~1% на бота на раунд») и (б) выявленная здесь же гонка в самих тестах этого файла
(и `GameFlowIntegrationTest`, тот же паттерн): `@InjectMock ChainSvcClient` начинает получать
реальные вызовы от фоновых `@Scheduled`-тиков (`RoundSchedulerService`/`OraclePublisherService`,
раз в `HERD_TICK_MS`) сразу после старта Quarkus-приложения — то есть ещё до/во время
многострочной настройки `when(...).thenAnswer(...)`/`when(...).thenReturn(...)` внутри самого
теста; конкурентный вызов на том же моке в этот момент — известная нить-небезопасность Mockito при
пошаговой настройке стаба, а не что-то новое в этой волне (структура настройки в обоих файлах не
менялась, кроме типа возврата `oracleRecords`). Не в assigned paths этой волны и не входит в
перечисленные Р-N/пункты задачи — не тронуто; здесь только задокументировано как факт для
критика/следующего прохода.

## 14. Волна 2 (security/concurrency-фиксы по тестам, перед ревью критика) — 2026-09-26

Правит находки второго раунда тестирования. Затронуты `server/`, `engine/` (аддитивно —
`round_engine_design.md` §6), `tools/herd-operator.sh`. `web/` не трогали.

### 14.1 CRITICAL — `requestWithdrawal("-5000.000")` минтил PLAY

`GameService.requestWithdrawal`/`requestDeposit` теперь первым делом проверяют
`amt.isPositive()` → `ApiException.invalidAmount` (новый `ApiErrorCode.INVALID_AMOUNT`, `400`,
аддитивный, не в `contract.ts` — та же ситуация, что `INVALID_NICKNAME`/`RATE_LIMITED`) **до**
создания `GameEvent`, т.е. это единственная реальная точка входа для внешнего атакующего (RPC —
единственный способ достучаться до `LedgerBook`). `placeBet`'овский stake уже был защищён косвенно
(`MIN_STAKE=100 > 0`); добавлен явный `LedgerBook.reserveStake`-guard для честности, а не только как
побочный эффект минимума (`round_engine_design.md` §6 — там же подробно объяснено, почему
аналогичный guard **намеренно не** добавлен внутрь `LedgerBook.requestWithdrawal`/`requestDeposit`
самих по себе: это тот же код, что реплеит уже случившееся историческое событие при рестарте, а
именно испорченное `WithdrawalRequested(amountMojos=-5000000)` уже лежит в `game_events` этого
стенда и трогать/удалять его нельзя).

### 14.2 Честная коррекция испорченного баланса (Р-32/Р-43 pattern)

Из evidence волны 2 (`scratchpad/wave2b/step1_output.log`, `step1_setup_and_edges.mjs`): игрок
`3e93a5a8-b5f0-47fa-adaa-d1c103c6ae1d` (ник `W2C wave2c edge negwith`, локальный тестовый кошелёк
Р-45), заявка на вывод `requestId="w-5"`, `amount="-5000.000"`, `state=CONFIRMED` — баланс вырос с
`10000.000` до `15000.000`.

Новый эндпоинт `POST /api/admin/withdrawals/{requestId}/reverse` (`{"reason": "..."}`, operator
bearer-токен, как `cancel`) → `GameService.reverseWithdrawal` → `GameEvent.WithdrawalReversed`
(новый, аддитивный к sealed `GameEvent`) → `LedgerBook.reverseWithdrawalEffect`
(`round_engine_design.md` §6 — самодостаточная коррекция, не ищет исходную заявку заново при
реплее). Не трогает и не удаляет `WithdrawalRequested`/`WithdrawalConfirmed` — только добавляет
новую запись (`OPERATOR_CORRECTION`).

**Публично, с причиной**: новый RPC-метод `ledgerCorrections` (`RpcResource`, без сессии — тот же
паттерн, что уже неаутентифицированный `cancellations`) листает `LedgerBook.corrections()`
постранично (`Ledger.LedgerCorrectionDto`); дополнительно попадает в `AdminStatusDto.
recentLedgerCorrections` (публичная страница статуса оператора, §9). Двойное применение одной и той
же `requestId` отклоняется (`400`).

`tools/herd-operator.sh` получил `reverse-withdrawal <requestId> "reason"` (curl-обёртка, тот же
стиль, что `cancel`).

**Команда, которую нужно выполнить на живом стенде после деплоя этого фикса** (сам вызов —
не в этой сессии, по задаче: «apply it… after deploy is done by the verify step»):

```
HERD_OPERATOR_TOKEN=<значение из .env живого стенда> tools/herd-operator.sh reverse-withdrawal \
  w-5 "wave-2 security test: requestWithdrawal(-5000.000) minted PLAY for player 3e93a5a8-b5f0-47fa-adaa-d1c103c6ae1d (nickname 'W2C wave2c edge negwithd') — reversed per docs/research/server_design.md §14.2, event WithdrawalRequested left unaltered"
```

(`HERD_SERVER_URL` — по умолчанию `http://localhost:8081`, менять только если стенд слушает не там.)
Перед выполнением стоит свериться с текущим `GET /api/admin/status` этого же стенда, что заявка
`w-5` всё ещё непровереная (не была уже кем-то исправлена) — команда идемпотентна в том смысле, что
повторный вызов на уже исправленной заявке безопасно вернёт `400`, а не испортит баланс второй раз.

### 14.3 MAJOR — `home()`/`ratings()` (и другие чтения) читали состояние движка без блокировки

`GameService` был `synchronized`-методным на мутациях, чтения — вообще без блокировки; конкурентное
чтение `LedgerBook.entries()`/`accounts` или `BetBook`'овских `LinkedHashMap` во время параллельной
мутации могло бросить `ArrayIndexOutOfBoundsException`/`ConcurrentModificationException`. Заменено
на `ReentrantReadWriteLock` (не просто `synchronized` и на чтениях): читающие методы (`home`,
`ratings`, `roundsPair`, `round`, `account`, `seasonRecord`, `myLedger`, `crowdAccount`,
`publicProfile`, `ledgerCorrections` — буквально «любой другой путь чтения», не только два названных
в баг-репорте) держат `readLock()` (конкурентны друг с другом), мутирующие — `writeLock()`
(эксклюзивны против всех). Выбор именно `ReadWriteLock`, а не общий `synchronized` и на чтениях —
обоснование: сервис read-heavy (каждый клиент поллит `home`/`rounds`/`ratings` заметно чаще, чем
кто-то ставит/выводит/резолвится раунд), обычный мьютекс сериализовал бы читателей друг за другом и
раздувал бы p99 именно под нагрузкой, которую бьёт тест из задачи; при этом запись по-прежнему
полностью эксклюзивна, как и раньше. `EngineRebuildService.rebuild()` (полный сброс+реплей
`EngineHub`) раньше синхронизировался на `synchronized(gameService)` — теперь берёт тот же
write-lock через новый `GameService.runExclusively(Runnable)`, с тем же смыслом: реплей не может
пересечься ни с одним чтением/записью.

**Тест** — `GameServiceConcurrencyTest`: 8 читающих потоков (`home`/`ratings` в цикле) параллельно с
4 пишущими (регистрация игрока + `placeBet`), проверяет, что из чтений не вылетает ничего, кроме
ожидаемых доменных `ApiException` (например, ставка ровно на границе раунда).

### 14.4 MINOR — `placeBet` принимал ставку до ~1с после старта раунда

`roundProgress.bettingWindow(interval)` меняется только когда `RoundSchedulerService`'овский тик
(раз в `herd.scheduler.tick-ms`, ~1с) фактически резолвит предыдущий раунд — то есть до этого тика
курсор ещё называет уже стартовавший раунд «открытым для ставок». Фикс — сравнение по
`nowMs >= bettingWindow.startMs()` (время самого запроса, не повторный `System.currentTimeMillis()`)
в дополнение к проверке `roundId`, в `placeBet` и симметрично в `cancelBet`. Тест
(`PlaceBetWindowBoundaryTest`) зовёт `GameService` напрямую с управляемым `nowMs` (не гоняется за
реальным планировщиком): `startMs()-1` — успех, `startMs()` и позже — `ROUND_NOT_OPEN_FOR_BETTING`.

### 14.5 MINOR — публикация оракула блокировала обработку раундов

`OraclePublisherService.publishPending()` раньше звал `chainSvc.oracleRecords(...)` синхронно на том
же потоке, что `RoundSchedulerService.tick()` резолвит раунды и гоняет ботов — медленный/недоступный
chainsvc (до `connect-timeout+read-timeout`) откладывал резолв уже просроченного раунда на весь этот
тик. Теперь `publishPending()` только сабмитит работу в выделенный `ThreadPoolExecutor` (1 поток,
очередь `ArrayBlockingQueue(1)`, `DiscardPolicy`) и сразу возвращается; если предыдущая публикация
ещё не закончилась, следующий тик просто отбрасывается — ничего не теряется (строка остаётся
`chainsvc_queued=false` и попробуется в следующий раз, `OracleReconciliationService` — независимая
защита от «отправили, но не подтвердили»). `quarkus.rest-client.chainsvc.*-timeout` подняты
(3000/5000 → 5000/15000) — раз путь больше не блокирует раунды, можно дать chainsvc больше времени
на ответ вместо жёсткого таймаута, который раньше был нужен именно чтобы не блокировать надолго.
Тест — `OraclePublisherServiceTest` (2): `publishPending()` не блокируется на намеренно медленном
моке (< 500 мс при реальной задержке мока 1500 мс), запись всё равно улетает асинхронно;
перекрывающиеся вызовы не порождают неограниченную параллельную работу (ограничено очередью).

### 14.6 Тесты и реальные числа

`mvn -s ~/.m2/settings-oss.xml -f pom.xml clean verify`, 2026-09-26: `engine` — **6527, 0 упавших**
(+8 в `PlayAmountTest`/новых, `round_engine_design.md` §6). `server` — **75, 0 упавших**: новые
`AdminReverseWithdrawalTest` (5), `GameServiceConcurrencyTest` (1, гоняет 1200 чтений + 600 попыток
ставки), `PlaceBetWindowBoundaryTest` (4), `scheduler.OraclePublisherServiceTest` (2), плюс
`RpcValidationTest` вырос на 7 (`requestWithdrawal`/`requestDeposit`
invalid-amount/positive-still-works, `placeBet` с отрицательным stake). Реактор целиком: **6602
теста, 0 упавших, 0 ошибок**, `BUILD SUCCESS`. Устойчивость новых конкурентных/асинхронных тестов
(`GameServiceConcurrencyTest`, `OraclePublisherServiceTest`) проверена тремя последовательными
прогонами подряд — стабильно зелёные каждый раз.

## 15. Волна 3 (независимая критика 2026-09-26) — фиксы без решения Алекса

Правит 7 находок `docs/reviews/2026-09-26-independent-critique.md`, ограниченных задачей как «не
требующие решения Алекса» (открытые вопросы — Sage-логин, VPS, чистка git-истории, Р-6-в-полном-
объёме — не в этой волне). Затронуты `server/`, `engine/` (аддитивно), `chain/c2-svc` (только
контрактные фикстуры), `docker-compose.yml`, `tools/stand.sh`, `tools/herd-operator.sh`,
`.github/workflows/`, `README.md` (один абзац, явно разрешено формулировкой задачи item 7).
`web/`/`docs/product/`/`PLAN.md`/`docs/selection/` не трогали — на них параллельно работали другие
сессии.

### 15.1 BLOCKER — `requestDeposit` кредитовал любую самозаявленную сумму без проверки в сети

Живой репро критика (`reviewCheck`, +5 000 000 PLAY за 10 секунд) и БД стенда (`d-2`/`d-4`/`d-7`/
`d-10`, все `CONFIRMED` без проверки в сети) подтверждены и исправлены:

- `GameService.requestDeposit`/`requestWithdrawal` теперь первым делом проверяют
  `GameConfig.depositEnabled()`/`withdrawalEnabled()` (новые `herd.deposit.enabled`/
  `herd.withdrawal.enabled`, `application.properties`, **default `false`**) → `ApiException
  .depositVerificationUnavailable()`/`withdrawalUnavailable()` (новые `ApiErrorCode
  .DEPOSIT_VERIFICATION_UNAVAILABLE`/`WITHDRAWAL_UNAVAILABLE`, `503`, аддитивные) — до парсинга
  суммы, т.е. отклоняется вообще любой новый депозит/вывод, а не только некорректная сумма. Выбран
  вариант «честный отказ», не «PENDING_VERIFICATION навсегда» — нет верификатора, значит нет
  честного «pending» состояния, которое когда-нибудь разрешится само. Вывод отключён тем же
  способом («prefer disabling withdrawal too» из формулировки задачи) — он тоже auto-confirm без
  реального ухода CAT в сеть, значит тоже ложное заявление.
- Публичный оператор-реверс для депозита — точное зеркало уже существующего `reverseWithdrawal`
  (§14.2): новый `GameEvent.DepositReversed` (аддитивный к sealed `GameEvent`) →
  `LedgerBook.reverseDepositEffect` (самодостаточная коррекция, debit исходной суммы + откат
  `SeasonRecord.deposited`) → `POST /api/admin/deposits/{requestId}/reverse` (`AdminResource
  .reverseDeposit`) → публично со причиной через `ledgerCorrections` (тот же RPC, что и раньше).
  `LedgerCorrection.relatedWithdrawalRequestId` переименован в `relatedRequestId` (несёт и `w-N`,
  и `d-N` — аддитивный DTO, не в `contract.ts`, ни один вызывающий код в `web/` его не читал).
  `tools/herd-operator.sh` получил `reverse-deposit <requestId> "reason"` (та же curl-обёртка).
- **Выполнено на живом стенде** (2026-09-26, после пересборки со всеми фиксами этой волны):

  ```
  HERD_OPERATOR_TOKEN=<из .env> tools/herd-operator.sh reverse-deposit d-2  "<reason>"
  HERD_OPERATOR_TOKEN=<из .env> tools/herd-operator.sh reverse-deposit d-4  "<reason>"
  HERD_OPERATOR_TOKEN=<из .env> tools/herd-operator.sh reverse-deposit d-7  "<reason>"
  HERD_OPERATOR_TOKEN=<из .env> tools/herd-operator.sh reverse-deposit d-10 "<reason>"
  ```

  (полный текст `<reason>` — см. коммит/эту сессию; каждый вызов вернул `200` с новым
  `correctionId` `c-2..c-5`, дельты `-50.000`/`-50.000`/`-1234.000`/`-5000000.000` соответственно —
  ровно инверсия исходного кредита). После — реальный рестарт `herd-server-1` (не
  `EngineRebuildService.rebuild()` в тесте, а `docker restart`): реплей 7053 событий, включая все 4
  `DepositReversed`, без ошибок — регрессионный тест на это ниже (§15.4).

### 15.2 BLOCKER — лимит запросов за nginx считал всех клиентов за одного

`RpcResource#clientIp` не менялся (по-прежнему просто `request.remoteAddress().host()`) — вместо
ручного парсинга заголовка решение на уровне Quarkus, ровно как просит формулировка задачи:
`quarkus.http.proxy.proxy-address-forwarding=true` + `quarkus.http.proxy.trusted-proxies=
172.28.77.0/24` (CIDR той самой подсети, что уже зафиксирована в `docker-compose.yml`'s
`networks.default.ipam` — единственный член этой подсети, кроме `db`/`server` самих себя,
это `web`/nginx) + `allow-x-forwarded=true`/`allow-forwarded=false` (nginx шлёт `X-Forwarded-For`,
не RFC-`Forwarded`). Проверено чтением байткода `quarkus-vertx-http-3.33.3.jar`
(`ForwardedParser`/`ForwardedServerRequestWrapper`/`TrustedProxyCheckPartConverter` — CIDR
действительно поддерживается, `TrustedProxyCheckPartConverter`'s `CIDR_PATTERN = ".+/\\d+"`) и
живым автотестом (см. ниже) — не просто предположение по документации.

Два теста, оба через реальный HTTP-стек Quarkus (не runtime-парсинг заголовка вручную):
`RateLimitTrustedProxyTest` (свой `@TestProfile`, доверяет `127.0.0.1` — реальный peer тестового
клиента) — два разных `X-Forwarded-For` от этого доверенного peer получают раздельные ведро́;
`RateLimitSpoofedProxyTest` (реальный дефолт приложения, `127.0.0.1` НЕ входит в
`172.28.77.0/24`) — поддельный `X-Forwarded-For` от недоверенного peer игнорируется, оба вызова
делят одно ведро́ — ровно тот DoS, что критик живьём воспроизвёл (`27×200` затем `13×429` с одного
вызывающего).

### 15.3 Security-минорные (по отчёту)

- **`AuthService` больше не эхо́ит сырой текст исключения chainsvc клиенту** — `chainsvc auth
  verification unavailable: <e.getMessage()>` заменено на `LOG.warnf` + generic `"sign-in
  temporarily unavailable — please try again shortly"` (`ApiException.unauthorized`). Тест —
  `RpcValidationTest#authVerifyNeverEchoesChainsvcsRawExceptionMessage` (мок chainsvc бросает
  различимую «внутреннюю» строку, проверяется её отсутствие в ответе).
- **Пароль Postgres генерируется в `.env`**, не хардкод `herd`/`herd` в `docker-compose.yml` —
  `tools/stand.sh`'s `ensure_env_file` генерирует `HERD_DB_PASSWORD` (та же схема, что токены),
  `docker-compose.yml`'s `db.POSTGRES_PASSWORD`/`server.HERD_DB_PASSWORD` теперь оба читают его из
  `${HERD_DB_PASSWORD:?...}`. **Существующий `herd_pg`-volume не сброшен** (задача явно это
  запрещает): Postgres применяет `POSTGRES_PASSWORD` только к пустой data dir, поэтому пароль на
  уже инициализированном volume был сменён напрямую — `docker exec herd-db-1 psql -U herd -d herd
  -c "ALTER USER herd WITH PASSWORD '...'"` (локальное unix-socket подключение внутри контейнера
  идёт через `trust`-аутентификацию, старый пароль не нужен) — выполнено на живом стенде 2026-09-26
  **до** пересборки `server`/`db` образов этой волны, так что `server` ни на секунду не терял
  доступ к БД. Новое значение записано в `.env` (заменило placeholder, который параллельная
  «game-designer»-сессия оставила там же с явной пометкой «whoever lands that should overwrite this
  line»).
- **`herd_session`-cookie получает `Secure`, когда сервер за TLS** — новый флаг
  `herd.cookie.secure` (`GameConfig`, default `false` — верно для локального http-стенда Р-44),
  `RpcResource`'s `sessionCookie()`/`clearSessionCookie()` оба зовут `.secure(config
  .cookieSecure())`. Для VPS-деплоя (Р-38, отдельное решение) выставить `HERD_COOKIE_SECURE=true`
  **только после** реальной TLS-терминации перед сервером — иначе cookie с `Secure` без HTTPS
  браузер вообще не отправит.

### 15.4 Эволюция схемы событий (`game_events`)

- **`schema_version`**: `V3__event_schema_version.sql` добавляет колонку `INT NOT NULL DEFAULT 1`
  (бэкфилл всех уже существующих строк версией 1, ровно то, что `EventStore
  .CURRENT_SCHEMA_VERSION` уже предполагает для всего написанного раньше). `EventStore.decode`
  теперь принимает и версию, и `rowId`; версия ≠ 1 — громкий `IllegalStateException` с `rowId`
  (нет ветки под версию 2 — пока и не нужна, это только крючок на будущее).
- **Толерантный декодер / алиас-таблица переименований**: `EventStore.TYPE_ALIASES` (пока пустая —
  ни один `GameEvent`-тип реально не переименовывался) + `resolveTypeAlias(type, aliases)` —
  чистая функция, протестирована напрямую синтетической картой алиасов
  (`EventStoreSchemaEvolutionTest#aliasedTypeNameResolvesToCurrentType`), т.к. реального примера
  переименования пока не существует.
- **Неизвестный `event_type` при старте падает громко, с `rowId`** (не молча пропускает money-
  событие) — было и раньше (`IllegalStateException`), теперь сообщение включает `id` строки
  (`"game_events row id=<N> has unknown event_type: <type>"`) — единственное изменение поведения
  здесь, но именно оно и требовалось задачей («fail loudly with the row id»).
- **Золотые JSON-фикстуры на каждый из 13 текущих типов `GameEvent`** —
  `server/src/test/resources/events/golden/<TypeName>.json` +
  `EventStoreGoldenFixturesTest` (round-trip: фикстура → объект → `equals` ожидаемому; replay:
  та же фикстура реально вставляется строкой в `game_events` через реальный Postgres и читается
  обратно через тот же `EventStore.loadAll()`-путь, не переимплементацию). Отдельный тест
  (`everyGameEventPermittedSubtypeHasAFixture`) сверяет список фикстур со
  `GameEvent.class.getPermittedSubclasses()` — забыть добавить фикстуру для нового типа события
  теперь падает сборкой, а не тихо остаётся дырой.
- Регрессионный тест на реплей после реверса — **для депозита** (задача явно требует): `Admin
  ReverseDepositTest#reversalSurvivesEngineRebuildReplay` — тот же паттерн, что уже был у
  `AdminReverseWithdrawalTest` для `WithdrawalReversed` (§14.2), теперь и для `DepositReversed`
  через `EngineRebuildService.rebuild()`.

### 15.5 Контрактный тест Java↔Rust

`chain/c2-svc/tests/contract_fixtures.rs` (новый) пишет JSON-фикстуры из настоящих Rust-типов
`chain/c2-svc` (`AuthVerifyRequest`/`Response`, `OracleRecordDto`/`OracleRecordsRequest`/
`Response`, `OracleRecordStatusResponse`, `OracleStatusResponse`) под
`chain/c2-svc/tests/contract/*.json` — auth-кейсы построены не вручную, а прогнаны через реальный
`auth::verify()` с реальным BLS-материалом из уже существующего `tests/vectors/auth_vectors.json`
(`positive`/`wrong_address`), так фикстура — настоящий пример, а не выдумка. `AuthVerifyRequest`/
`OracleRecordsRequest` получили `Serialize` (были только `Deserialize` — аддитивно, продакшен-код
их по-прежнему только десериализует). Регенерация: `cargo test -p c2-svc --test contract_fixtures`
из `chain/`.

`server/src/test/java/crowdvsmarket/server/chain/ChainSvcContractFixturesTest.java` читает те же
файлы (`../chain/c2-svc/tests/contract/`, соседняя директория — то же допущение о рабочей
директории Surefire, что уже использует `EventStoreGoldenFixturesTest`) и
десериализует/сериализует их `ChainSvcRestClient`'ными DTO.

**Реальная находка контрактного теста** (не просто новый тест — он действительно поймал дрейф):
`OracleStatusResponse` (и Rust-, и Java-сторона) несёт `stuckReason`/`conflicts` уже с фиксов §11/
§12 (`chain_service.md`) — но Java-версия (`ChainSvcRestClient.OracleStatusResponse`, `ChainSvcClient
.OracleStatus`) их не имела вообще, Jackson молча отбрасывал оба поля при десериализации
(default-игнор неизвестных полей). Исправлено аддитивно (два новых поля на конец обеих Java-
записей, `ChainSvcClientImpl` прокидывает); все существующие вызовы конструкторов обновлены
(`GameFlowIntegrationTest`, `ChainSvcClientImplTest`) — дальше эти поля никуда не прокинуты
(`ServerInfoDto`/UI) — вне объёма этой волны.

### 15.6 CI — `server.yml`/`chain.yml`

- `.github/workflows/server.yml`: `mvn -B -f pom.xml -pl server -am verify` против настоящего
  `postgres:16-alpine` service-контейнера (тот же образ, что `docker-compose.yml`'s `db`), Dev
  Services отключены явной подстановкой `-Dquarkus.datasource.jdbc.url=...` (Quarkus не поднимает
  Dev Services, когда datasource URL уже сконфигурирован для активного профиля).
- `.github/workflows/chain.yml`: `cargo test --workspace --locked` + `cargo clippy --workspace
  --all-targets --locked` — **без `-D warnings`**: `cargo clippy` по всему workspace сегодня НЕ
  чист (`cloned_ref_to_slice_refs`/`inconsistent_digit_grouping` в тестах `c2-cli`, вне
  `chain/c2-svc` — единственного assigned-пути этой сессии под `chain/`), задача прямо говорит
  добавлять `-D warnings` только когда уже чисто.
- Оба заменяют `engine.yml`/`web.yml`'s комментарий «нет GitHub remote, не запустится» на
  реальный триггер — репозиторий на самом деле уже имеет `origin` (`git@github.com:Sekator778/
  herd.git`), это устаревшее допущение дня 1.

### 15.7 Execution adapter (PLAN.md §3, заявление README)

- `crowdvsmarket.engine.execution.ExecutionAdapter` (новый пакет, чистый интерфейс без фреймворка
  — тот же паттерн, что уже `engine.ledger.OnChainWalletPort` для withdraw/deposit) —
  `openPosition`/`getPosition`/`closePosition` (PLAN's `open_position`/`get_position`/
  `close_position`).
- `PaperTradingAdapter` (`engine/`) — ровно `Position.openFromMargin` + `PositionSimulator
  .simulate`, те же два вызова, что `CrowdAccount`/`ComparisonAccounts` уже делали инлайн.
  **`CrowdAccount` реально переведён на него**: новый конструктор `CrowdAccount(BigDecimal,
  ExecutionAdapter)`, старые `CrowdAccount()`/`CrowdAccount(BigDecimal)` не изменили поведения
  (дефолтят на `new PaperTradingAdapter()`) — байт-в-байт то же поведение, весь существующий набор
  тестов (`CrowdAccountTest`, `FullSweepReplayTest`, `MonthLongHourlySimulationTest`,
  `HistoricalScenarioTest`, 6269-кейсовый `PositionSizingPropertyTest`) прошёл без единого
  изменения ожиданий. `ComparisonAccounts` (анти-толпа линия, `server/`) переведён тем же образом.
- `crowdvsmarket.server.execution.HyperliquidAdapter` (`server/`, т.к. нужен HTTP-клиент —
  `engine/` намеренно остаётся без инфраструктурных зависимостей) — **настоящая** сборка запроса
  по официальному API Hyperliquid (`HyperliquidExchangeRequests`, поля/формат сверены с
  `hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint`, зафетчено
  2026-09-26) — но **отключён по умолчанию** (`herd.execution.hyperliquid.enabled=false`) и
  отказывается собирать/слать что-либо без явного адреса кошелька
  (`herd.execution.hyperliquid.wallet-address`, пусто по умолчанию — нигде в репозитории/`.env` не
  задан). Ничто в кодовой базе не создаёт этот адаптер с реальными ключами и не подключает его к
  игровому пути — `CrowdAccount`/`ComparisonAccounts` используют исключительно
  `PaperTradingAdapter` (Р-38: активация реальными деньгами — решение того, кто разворачивает).
  `getPosition` — единственный метод, который реально может выполниться уже сегодня: `POST /info`
  `clearinghouseState` — публичный, без подписи (сверено с `hyperliquid.gitbook.io/hyperliquid-
  docs/for-developers/api/info-endpoint/perpetuals`, зафетчено 2026-09-26) — но его ответ
  намеренно не отображается на `PositionSimulator.Outcome` (этот проект не хранит источники, из
  которых такое отображение можно вывести не гадая) — возвращает `Optional.empty()`.
- **EIP-712-подпись L1-экшенов Hyperliquid НЕ реализована** — задача прямо разрешает этот отказ от
  объёма («if full signing is too large... implement... a clearly failing signer stub and say so
  precisely»). `HyperliquidSigner.NotImplementedSigner` всегда бросает
  `SigningNotImplementedException` с точным описанием недостающего пайплайна (источники,
  зафетчены 2026-09-25/26): EIP-712 domain `{name: "Exchange", version: "1", chainId: 1337,
  verifyingContract: 0x0..0}` и `Agent`-структура `{source, connectionId}` (Python SDK,
  `hyperliquid/utils/signing.py`, `sign_l1_action`), `connectionId = keccak256(msgpack.packb
  (action) ++ nonce as 8 BE bytes ++ vaultAddress-флаг)`, финальная ECDSA-подпись поверх
  typed-data хэша этой структуры. Ничего из этого не реализовано (нужна msgpack-библиотека +
  keccak256 + secp256k1/EIP-712 — сама документация Hyperliquid прямо советует не писать это
  вручную) — честная заглушка вместо гадания, которое могло бы оказаться тихо неверным.
  `openPosition`/`closePosition` даже при `enabled=true` + сконфигурированном кошельке доходят
  ровно до вызова `signer.sign(...)` и падают там — тест `HyperliquidAdapterEnabledTest` это прямо
  проверяет (падение на подписи, не на валидации/конфиге).
- **README.md** (один абзац, явно разрешено формулировкой задачи item 7) исправлен: было
  «чтение цены с Hyperliquid реализовано в коде, торговый адаптер... пока не начат» (уже устарело
  к моменту этой волны — другая параллельная сессия успела смягчить исходное заявление критика, но
  сама заявила меньше, чем стало правдой после этой волны) → теперь называет `ExecutionAdapter`/
  `PaperTradingAdapter`/`HyperliquidAdapter` по имени и честно называет ровно то, что не работает
  (подпись).

### 15.8 Тесты и реальные числа

`mvn -s ~/.m2/settings-oss.xml -f pom.xml clean verify`, 2026-09-26: `engine` — **6530, 0 упавших**
(было 6527 в §14.6 + 3 новых `PaperTradingAdapterTest`). `server` — **120, 0 упавших**: новые
`AdminReverseDepositTest` (6), `WithdrawalDepositUnavailableTest` (3), `RateLimitTrustedProxyTest`
(1), `RateLimitSpoofedProxyTest` (1), `events.EventStoreGoldenFixturesTest` (14),
`events.EventStoreSchemaEvolutionTest` (4), `chain.ChainSvcContractFixturesTest` (7),
`execution.HyperliquidAdapterTest` (4), `execution.HyperliquidAdapterEnabledTest` (3), плюс
`RpcValidationTest`/`AdminReverseWithdrawalTest`/`ChainSvcClientImplTest`/
`GameFlowIntegrationTest` выросли/поправлены под новые поля/поведение. Реактор целиком: **6650
теста, 0 упавших, 0 ошибок**, `BUILD SUCCESS` (перепроверено двумя последовательными прогонами —
первый словил один известный флейк, `OracleReconciliationServiceTest
#reconcileDoesNotBackfillARecordStillUnknownButRecentlyQueued`, не относящийся к этой волне
(scheduler-таймингового происхождения, файл не трогался; трижды зелёный в изоляции), второй прогон
реактора целиком — чисто).

`chain/`: `cargo test --workspace --locked` — все существующие сьюты (аутентификация, очередь
оракула, CAT/Offer, синглтон) плюс новый `contract_fixtures` — зелёные, без изменений в
проверяемом поведении. `cargo clippy --workspace --all-targets` — не чист (см. §15.6), не
относящиеся к этой сессии предупреждения не трогали.

Живой стенд (`docker compose -p herd`, все 4 сервиса) пересобран и перезапущен с этой волной
(2026-09-26): Postgres-пароль ротирован без сброса `herd_pg` (§15.3), `V3` миграция применилась
чисто, реальный `docker restart herd-server-1` реплеил все 7053 события (включая 4 новых
`DepositReversed`) без единой ошибки, все 4 фиктивных депозита (`d-2`/`d-4`/`d-7`/`d-10`) реально
реверснуты на живых данных (не только в тесте) — команды и результат см. §15.1.

## 16. Волна 4 (Р-53: настоящий on-chain вывод/ввод PLAY) — 2026-09-26

Реализует Р-53 (Алекс, 2026-09-26, `docs/product/decisions.md`: «сделать до альфы») поверх FIXED
INTERFACE постановки задачи (`server ⇄ chainsvc`, `/v1/play/*`) — `POST /v1/play/withdrawals`,
`GET /v1/play/withdrawals/{requestId}`, `POST /v1/play/deposit-address`,
`GET /v1/play/deposits?sinceHeight=N`. Затронуты только `server/`, `engine/` (аддитивно) — задача
явно не давала правки `chain/`. Параллельно (эта же сессия, чтением исходников) обнаружено, что
Rust-сторона (`chain/c2-svc/src/play/{types,mod,deposit,chain_client,withdrawal_*}.rs`) уже
частично написана другой сессией — имена полей (`camelCase` через `#[serde(rename_all =
"camelCase")]`) сверены с ней напрямую и **совпадают** с тем, что описывает FIXED INTERFACE (см.
`ChainSvcClientImplTest`'s Р-53-кейсы); контрактных JSON-фикстур (`chain/c2-svc/tests/contract/
*Play*.json`) на момент этого прохода ещё нет — `chain/c2-svc/tests/contract_fixtures.rs` их не
генерирует, поэтому `ChainSvcContractFixturesTest` не расширен новыми (упавшими бы) кейсами, только
задокументирован (её же javadoc) как «добавить, когда фикстуры появятся».

### 16.1 `playerIndex` — стабильный индекс для деривации адреса

`V4__player_index.sql`: новая колонка `players.player_index` (`UNIQUE NOT NULL`), детерминированный
бэкфилл существующих игроков (`ROW_NUMBER() OVER (ORDER BY created_at_ms, player_id)`), счётчик для
новых игроков — `server_meta` ключ `next_player_index`, тот же паттерн «reserve-and-advance под
`FOR UPDATE`», что уже `ServerMetaRepository.reserveOracleSeqRange` (переиспользован, не
скопирован заново — `reserveNextPlayerIndex`). `PlayerDirectory` получил обратный индекс
`playerIdForIndex` (нужен паблишеру депозитов — чейнсвик отдаёт только `playerIndex`, не
`playerId`). Оффсет от oracle/treasury-ключей в деривации — забота `chain/`, этому серверу нужно
только передать стабильный целочисленный `playerIndex`.

### 16.2 `myDepositAddress()` — кэш адреса

Новый RPC (аддитивный, не в `contract.ts`, тот же случай, что `ledgerCorrections`/доп.
`ApiErrorCode`): `GameService.myDepositAddress` — при первом вызове зовёт `POST
/v1/play/deposit-address {playerIndex}`, кэширует тройку `address`/`innerPuzzleHash`/
`catPuzzleHash` в новой таблице `player_deposit_addresses` (V5-миграция) и больше никогда не
перезапрашивает (адрес детерминирован по `playerIndex`) — игроку отдаётся только `{address}`
(буквально по формулировке задачи), остальные два поля — только для собственной бухгалтерии этого
сервера. Гейтится `herd.deposit.enabled` (см. §16.5 — смысл этого флага поменялся).

### 16.3 Депозиты — `DepositPollerService`, exactly-once по `coinId`

`DepositPollerService` (`@Scheduled(every="30s")`, отключается вместе с `!herd.deposit.enabled`):
курсор `sinceHeight` — `server_meta` ключ `deposit_poll_since_height` (тот же `originMsOrInit`-
паттерн), `GET /v1/play/deposits?sinceHeight=N` → для каждого элемента резолвит `playerIndex` →
`playerId` (`PlayerDirectory.playerIdForIndex`) и зовёт новый `GameService.creditOnChainDeposit`.

**Exactly-once — предложение, явный компромисс**: новая таблица `deposit_confirmations`
(`PRIMARY KEY(coin_id)`) — `creditOnChainDeposit` сначала пытается `INSERT ... ON CONFLICT DO
NOTHING` (свою же транзакцию) и только если это **её** инсерт реально что-то вставил — кредитует
баланс (`persist` + `EventApplier` нового события, см. §16.6) под `engineLock`. Порядок «сначала
резервируем coinId, потом кредитуем» выбран намеренно **не** в обратную сторону: крэш между двумя
шагами может (редко, только при падении процесса ровно в этом окне) оставить монету
«зарезервированной, но не начисленной» — почётно чинится вручную (оператор ищет строку
`deposit_confirmations` без соответствующей `DEPOSIT`-записи в леджере), — но никогда не даёт
задвоить кредит. Это то же самое смещение риска, что весь проект уже явно выбирал раньше
(«никогда не минтить лишний PLAY» — CRITICAL-класс багов волн 2–3) — обратный порядок («кредитуем,
потом резервируем») отдавал бы противоположный, куда более опасный компромисс (двойной кредит при
том же крэше). Курсор `sinceHeight` продвигается до `tipHeight` только если **весь** батч этого
тика обработан без исключений (см. `DepositPollerService`'s javadoc) — частичный сбой просто
повторно перечитывает тот же (в основном безвредный из-за дедупа) диапазон на следующем тике.

### 16.4 Выводы — `requestWithdrawal` (реальный чейн-вызов) + `WithdrawalPollerService`

`GameService.requestWithdrawal` теперь (после валидации: положительная сумма, `>= MIN_WITHDRAWAL`,
`<= balance`) — как и раньше — эскроу-дебетует баланс тем же `WithdrawalRequested`-событием, но
вместо мгновенного фейкового `CONFIRMED` синхронно (вне `engineLock`) шлёт `POST
/v1/play/withdrawals {requestId, toAddress, amountMojos}`. **`toAddress` всегда — собственный
зарегистрированный адрес игрока** (`players.wallet_address`, Р-57: identity = адрес входа) —
предложение: `contract.ts`'s `requestWithdrawal(amount)` не несёт параметра адреса, а получение его
из уже-подписанной сессии (а не из недоверенного клиентского ввода) закрывает по конструкции риск
«увести вывод на чужой адрес через скомпрометированную сессию»; поле «вывести на другой адрес» — вне
объёма этого прохода (не запрошено задачей, потребовало бы правки `web/`, которая этим проходом не
разрешена).

Новый `WithdrawalPollerService` (`@Scheduled(every="30s")`, тот же паттерн, что
`OraclePublisherService`/`OracleReconciliationService` — «reuse its patterns» из формулировки
задачи): (а) ретраит `POST /v1/play/withdrawals` для заявок, чью синхронную попытку
`requestWithdrawal` не подтвердил (chainsvc был недоступен) — безопасно повторять сколько угодно,
FIXED INTERFACE прямо называет этот endpoint идемпотентным по `requestId`; (б) опрашивает `GET
/v1/play/withdrawals/{requestId}` для уже отправленных, но ещё не терминальных заявок →
`confirmed` → новое событие `WithdrawalConfirmedOnChain` (coinId/height публично видны в
`Ledger.WithdrawalRequestDto` — прямое требование задачи, «public in the player's ledger»);
`failed` → переиспользованное `WithdrawalFailed` (тот же рефанд, что уже был) + `failureReason` в
read-model; `queued`/`in_flight` — просто обновление read-model, без события.

**`MIN_WITHDRAWAL` — предложение, не зафиксировано в `docs/product/decisions.md`**: 100 PLAY, та
же цифра, что `LedgerBook.MIN_STAKE`, выбрана по аналогии (не по отдельному обоснованию комиссии) —
подлежит пересмотру, если у чейнсвика окажется другая реальная минимальная экономически осмысленная
сумма вывода.

### 16.5 `requestDeposit` (self-report) — ПОЛНОСТЬЮ и НАВСЕГДА отключён, независимо от флага

Важное решение этого прохода, не буквально продиктованное текстом задачи, но необходимое по
безопасности: до Р-53 `herd.deposit.enabled=true` включал и (а) старый self-report `requestDeposit`
(вводишь любую сумму — сразу кредит, ровно тот баг, что волна 3 уже нашла CRITICAL/BLOCKER) и не
имел отдельного пути (б) для реального ончейн-ввода, потому что реального пути не существовало.
Теперь путь (б) реализован — но если бы `herd.deposit.enabled=true` по-прежнему **тоже** включал
(а), включение настоящих депозитов заново открыло бы фрод-вектор, который уже дважды был
CRITICAL-находкой в этом проекте. Поэтому `GameService.requestDeposit` теперь **безусловно**
бросает `DEPOSIT_VERIFICATION_UNAVAILABLE` (тот же код ошибки, что уже был — клиент не видит
разницы), независимо от `herd.deposit.enabled` — этот флаг теперь целиком означает «реальный путь
(`myDepositAddress`/`DepositPollerService`) включён», не «self-report включён». Метод `deposit
(requestId)` (чтение) не тронут — нужен оператору для просмотра/реверса исторических `d-N` заявок
(`AdminReverseDepositTest`, чья фикстура теперь строит `DepositRequested`+`DepositConfirmed`
напрямую через `EventStore`/`EventApplier`, а не через отключённый метод — тот же исторический
формат события, что и раньше, воспроизводимый ровно потому, что `EventStore`/`EventApplier` не
меняют своей семантики для уже существующих типов событий). `contract.ts`/`web/` не тронуты — тот
же self-report RPC формально остаётся в контракте, просто больше никогда ничего не кредитует.

### 16.6 Новые события и золотые фикстуры

`GameEvent.DepositConfirmedOnChain(playerId, playerIndex, amountMojos, coinId, height, atMs)` —
единственное событие для реального депозита (в отличие от старой пары `DepositRequested`+
`DepositConfirmed` — на чейне «запрос» и «подтверждение» неразличимы, монета уже подтверждена к
моменту, когда чейнсвик вообще её показывает); `LedgerBook.creditOnChainDeposit` (новый, аддитивный
метод) — кредитует немедленно, без промежуточного состояния. `GameEvent.WithdrawalConfirmedOnChain
(requestId, coinId, height, atMs)` — эффект на движок идентичен старому `WithdrawalConfirmed`
(эскроу уже списан при `WithdrawalRequested`), разница только в read-model (`coinId`/`height`
публичны). Оба добавлены в `EventStore.decode`'s switch и получили золотые JSON-фикстуры
(`server/src/test/resources/events/golden/{DepositConfirmedOnChain,WithdrawalConfirmedOnChain}.json`
+ соответствующие `Fixture`-записи в `EventStoreGoldenFixturesTest`) — по тем же «schema-version»
правилам, что волна 3 ввела (§15.4); отдельной записи в `EventStore.TYPE_ALIASES` не потребовалось
— это НОВЫЕ типы событий, не переименование существующих (алиас-таблица — только для переименований,
см. её же javadoc).

### 16.7 Тесты и реальные числа

`mvn -s ~/.m2/settings-oss.xml -f pom.xml -pl engine,server -am clean verify`, 2026-09-26:
`engine` — **6531, 0 упавших** (+1 — `LedgerBookTest#creditOnChainDepositCreditsImmediatelyAndCountsTowardSeasonDeposited`).
`server` — **140, 0 упавших**: новый `scheduler.PlayOnChainTest` (11 — кэш `myDepositAddress`,
недоступность чейнсвика, exactly-once депозита при повторном опросе того же `coinId`, неизвестный
`playerIndex` не роняет поллер, депозит виден в `myLedger`, подтверждение/отказ вывода с
рефандом, ретрай отправки после сбоя, реплей после `EngineRebuildService.rebuild()`, параллельные
запросы на вывод никогда не уводят баланс в минус, параллельные кредиты одного и того же `coinId`
применяются ровно один раз), `chain.ChainSvcClientImplTest` вырос на 8 (Р-53-методы, включая
graceful-degradation на недоступность), `events.EventStoreGoldenFixturesTest` вырос на 2,
`RpcValidationTest` — минус 3 старых депозитных amount-теста (self-report теперь безусловно
недоступен, валидировать больше нечего), плюс `requestWithdrawalRejectsBelowMinimum` и
`requestDepositIsPermanentlyRetiredEvenWhenDepositEnabledIsTrue` (нетто −1), `AdminReverseWithdrawalTest`/
`AdminReverseDepositTest` — те же 6 тестов каждый, but adapted: withdrawal-фикстура теперь ожидает
`REQUESTED` (не мгновенный fake-`CONFIRMED`), deposit-фикстура строится напрямую через
`EventStore`/`EventApplier` (см. §16.5). Реактор целиком: **6671 тест, 0 упавших, 0 ошибок**,
`BUILD SUCCESS`.

Живой стенд (`herd-*`, `docker ps`) не тронут этим проходом (не входило в задачу — только `mvn
verify`, никакого пересобранного образа/рестарта контейнеров); замечено (не являлось целью проверки)
одно фоновое `401 Unauthorized` от реального `herd-chainsvc-1` в логах тестового прогона — фоновый
`@Scheduled`-тик тестового Quarkus-приложения из другого тестового класса достучался до реального
локального `localhost:8090` (Dev Services/тестовый профиль не переопределяет `HERD_CHAINSVC_URL`) с
тестовым, не настоящим, токеном; чейнсвик корректно отверг запрос по 401, никакого состояния не
изменилось — тот же класс фоновой не-детерминированности с реальными `@Scheduled`-тиками, что уже
задокументирован в §13 (`BotRestartRecoveryTest`'s заметка про гонку с `@InjectMock`), не новая
находка этого прохода.

### 16.8 Не сделано в этом проходе (явно, по объёму задачи)

- Вывод на адрес, отличный от адреса входа — не запрошено, потребовало бы правки `web/`/`contract.ts`.
- Автоматическая сверка «зарезервированный, но не начисленный `coinId`» (крайне редкое окно крэша,
  §16.3) — предложено оператору искать вручную, отдельный сервис не строился.
- Верхний предел/backoff на «застрявший» вывод (вечно `queued`/`in_flight`) — не запрошено.
- Контрактные JSON-фикстуры для `/v1/play/*` (см. врезку в начале §16) — появятся, когда
  `chain/c2-svc/tests/contract_fixtures.rs` их напишет; `ChainSvcContractFixturesTest` явно
  задокументирован как место, куда их добавить.

## 17. Волна 5 (Р-53 live-verification follow-up, 2026-09-26) — три находки живой проверки

Три пункта задания по итогам живой проверки стенда `herd` после волны 4 (§16): дубликат
`PlayerRegistered` в `game_events`, ожидание контрактных фикстур `/v1/play/*` для
`ChainSvcContractFixturesTest`, и per-request payout `coinId` в Withdrawal DTO. Assigned paths —
`server/` и этот файл; `chain/`/`web/`/`engine/` не тронуты.

### 17.1 Дубликат `PlayerRegistered` — причина и фикс

**Причина** (буквально то, что нашла живая проверка): не гонка и не двойной клик — до этого прохода
`GameService.registerOrTouchPlayer` **по дизайну** дописывало новый `PlayerRegistered` в
`game_events` на КАЖДЫЙ успешный `authVerify`, включая возвращающегося игрока (предыдущая редакция
javadoc так и говорила: «Always re-touches ensureSeasonPack via a fresh PlayerRegistered event,
even for a returning player»). Значит два ряда для одного `playerId`/адреса (id 8985 и 9048 на
живом стенде) — это ровно два обычных входа подряд, не баг гонки.

**Фикс** — `GameService.registerOrTouchPlayer`: для нового игрока поведение не изменилось (один
`PlayerRegistered` при первой регистрации). Для возвращающегося — перед тем как дописывать событие,
читаем (без мутации) `LedgerBook.seasonRecord(playerId, Season.of(now))`: если запись уже есть,
`ensureSeasonPack` всё равно был бы no-op (см. его же javadoc), значит новый вход в ТОМ ЖЕ сезоне
теперь тоже no-op в логе — ничего не дописывается. Если сезон в самом деле сменился с последнего
касания — событие пишется, как и раньше (это то, что держит рабочим Р-2 «стартовый пакет сразу при
входе», см. `GameService.account`'s javadoc). Общий код записи вынесен в приватный
`GameService.appendPlayerRegistered`, которым теперь пользуются и `registerOrTouchPlayer`, и
`createBotPlayer` (было — дублирующийся `persist`+`apply`).

**Толерантность реплея к уже существующим дубликатам** (id 8985/9048 и любым другим, уже
записанным до фикса) — отдельного изменения не потребовалось: `EventApplier`'s `PlayerRegistered`
case как раньше просто зовёт `ensureSeasonPack`, которая уже была идемпотентна по сезону (повторный
вызов в том же сезоне — no-op, второго стартового пакета не начисляет). Фикс останавливает НОВЫЕ
дубликаты, а не чинит модель, которая их и так уже переживала корректно.

Тест — `server/src/test/java/crowdvsmarket/server/PlayerRegistrationIdempotencyTest.java`: два
входа одним и тем же кошельком в одном сезоне → ровно один `PlayerRegistered` в `game_events`
(читается напрямую через `EventStore.loadAll()`, не мок); отдельный кейс на обычную регистрацию
нового игрока.

### 17.2 `ChainSvcContractFixturesTest` — фикстуры `/v1/play/*` всё ещё не появились

Поллинг `chain/c2-svc/tests/contract/` каждые 15с в течение 4 минут (2026-09-26, ~10:03–10:07 CEST)
— по-прежнему только уже существовавшие `Auth*`/`Oracle*` фикстуры из §16-й врезки;
`chain/c2-svc/tests/contract_fixtures.rs` по-прежнему не содержит ни одного `write_fixture` для
Play. Ничего не добавлено в `ChainSvcContractFixturesTest` — фикстур-кейсы против несуществующих
файлов просто упали бы. Класс дополнен только заметкой о повторной проверке (дата/длительность
поллинга) поверх уже имевшейся врезки, которая описывает, какие кейсы добавить «когда
`contract_fixtures.rs` их напишет» (по аналогии с уже существующими oracle-кейсами). Java-сторона (DTO
`ChainSvcRestClient`/`ChainSvcClient`) уже сверена с реальными Rust-типами напрямую (§16-я врезка,
не повторялось в этом проходе) — готова принять фикстуры, когда они появятся.

### 17.3 Withdrawal DTO: `coinsetVerifyHint` + найденный chain-side баг per-request `coinId`

`Ledger.WithdrawalRequestDto` получил новое аддитивное поле `coinsetVerifyHint` (не в
`contract.ts`, тот же принцип, что и у `coinId`/`height`/`chainState` из §16): непустая строка вида
`POST https://testnet11.api.coinset.org/get_coin_record_by_name {"name":"<coinId>"}` — готовая
команда, которой игрок может сам, без доверия этому серверу, проверить свою выплаченную монету на
публичном `coinset.org`, тем же способом, что `tools/verify-oracle/verify_oracle.py` уже делает для
оракула (`docs/research/week1_chain_access.md` §3 — тот же базовый URL). `null`, пока `coinId`
неизвестен. Строится статическим `Ledger.coinsetVerifyHint(coinId)`, вызывается из
`WithdrawalDepositRepository.toWithdrawalDto` — единственного места, где этот DTO собирается.
Хост захардкожен на testnet11 (не читается из `GameConfig.oracleNetwork()`), тем же принципом, что
уже захардкожено «Network: testnet11» в сообщении для подписи в `AuthService` — пересмотреть, если
проект когда-нибудь будет работать больше чем с одной сетью.

**Побочная находка (не в объёме этого прохода, для агента `chain/`):** прямое чтение
`chain/c2-svc/src/play/withdrawal_publisher.rs::advance_in_flight` (строки ~221–237) показывает,
что подтверждённый `coin_id`, который сохраняется в `queue.confirmed` для КАЖДОЙ заявки в батче —
это `in_flight.next_coin.coin_id()`, то есть id общей монеты сдачи казначейства (§13.2 в этом же
файле), один и тот же для всех до 20 заявок батча, а не индивидуальная payout-монета конкретной
заявки, реально отправленная на её `toAddress`. Задание прямо называет это ожидаемым и обещает
починку на стороне `chain/` («it will be corrected on the chain side») — здесь это не тронуто (вне
assigned paths этого прохода), но задокументировано в javadoc `Ledger.WithdrawalRequestDto` с точной
ссылкой на файл/диапазон строк, чтобы `chain/`-агент нашёл её без повторного ревью. Серверная сторона
уже готова: она передаёт `coinId` от chainsvc как есть, без своей интерпретации — когда починка
на chain-стороне будет сделана, `coinId`/`coinsetVerifyHint` автоматически станут корректными без
дополнительных изменений здесь.

### 17.4 Тесты и реальные числа

`mvn -s ~/.m2/settings-oss.xml -f pom.xml -pl engine,server -am clean verify`, 2026-09-26 (стенд
`herd-*` не тронут — `docker ps` до/после идентичен, эфемерные Testcontainers-контейнеры Postgres/
Ryuk от `mvn verify` убраны Ryuk'ом автоматически): `engine` — **6531, 0 упавших** (без изменений —
задача не трогала `engine/`). `server` — **142, 0 упавших** (было 140 в §16.7; +2 —
`PlayerRegistrationIdempotencyTest`); `PlayOnChainTest` остался 11 тестов (добавлены только новые
`assertThat`-проверки `coinsetVerifyHint` внутри уже существующих кейсов, не новые методы),
`ChainSvcContractFixturesTest` остался 7 (без новых кейсов, см. §17.2). Реактор целиком: **6673
теста, 0 упавших, 0 ошибок**, `BUILD SUCCESS`.

### 17.5 Не сделано в этом проходе

- Сам chain-side баг «shared batch coin_id вместо per-request payout coin_id» (§17.3) — не
  починен, это `chain/`, не `server/`.
- Контрактные JSON-фикстуры `/v1/play/*` — по-прежнему не появились после 4-минутного поллинга
  (§17.2); `ChainSvcContractFixturesTest` остаётся готов принять их.
- Сезонный touch без входа (например, скорректировать читающие пути, чтобы не зависеть от логина
  для видимости нового стартового пакета) — не запрошено, вне объёма фикса дубликата.

## 18. Волна 6 (Р-62: живой расклад толпы, лента ставок, «пока вас не было») — 2026-09-26

Задание по итогам Р-61/Р-62/Р-63 (`docs/product/decisions.md`): концепция «Аркада», раскрытие
расклада толпы и ленты ставок вживую во время окна ставок (заменяет Р-22), и отдельно — фикс
основной сессии «игрок, не заходивший к моменту закрытия раунда, никогда не видит момент итога».
Assigned paths — `server/`, `engine/` (аддитивно — не тронут, см. §18.4), `web/src/api/contract.ts`
(только аддитивно) и этот файл.

### 18.1 Живой расклад толпы во время `BETTING` (Р-62, пункт 1)

`GameService.buildBettingDto` (вызывается из `rounds()`/`round()` для ещё не начавшегося раунда)
теперь дополнительно считает `upStake`/`downStake`/`upCount`/`downCount`/`majorityDirection`/
`majorityLeverage` из ставок, всё ещё лежащих в `BetBook` этого раунда — та же математика
большинства по сумме ставок и то же средневзвешенное плечо (Р-14), что уже использует
`buildRunningDto`'s `crowdPreview` и `CrowdAccount.processRound` при реальном расчёте, просто
на фазу раньше (позиция ещё не открыта, поэтому margin/entryPrice/liquidationPrice здесь не
считаются — их и не было в задании, только сам расклад и превью направления/плеча толпы).
`majorityDirection`/`majorityLeverage` оба `null` только при ровной ничьей (включая 0 против 0) —
то же правило, что уже у `CrowdRoundPositionDto`/`CrowdRoundResultDto`.

**Согласование формы с `web/`-агентом.** К моменту, когда этот проход дошёл до
`contract.ts`, там уже лежали `RoundBetting.upStake?`/`downStake?`/`recentBets?`/`LiveBetDto` и
`Api.unseenResults?`/`acknowledgeResults?` — тот же агент параллельно проектировал фронт на
mock-данных (Р-35) и написал свою версию контракта первым. Не переписано заново: реальный бэкенд
подстроен под уже написанную (и уже используемую `MockApi`/`fx/useAwayReplay.ts`/
`useSettlementWatcher`) форму, а не наоборот — `upStake`/`downStake` остались плоскими полями
`RoundBetting` (не завёрнуты в отдельный DTO), RPC названы `unseenResults`/`acknowledgeResults`
(не `myUnseenResults`/`ackResults`, как в изначальной формулировке задания — просто другое имя
того же самого, `RpcResource`'s dispatch-строка это и есть весь контракт метода). Аддитивно
добавлено то, чего в уже написанной версии не было: `upCount`/`downCount`/`majorityDirection`/
`majorityLeverage` на `RoundBetting`, плюс отдельный `betFeed` RPC (§18.2) — `web/`-агенту ничего
менять не нужно, чтобы получить настоящие данные вместо мока под уже написанными полями.

### 18.2 Живая лента ставок — `betFeed` RPC + `RoundBetting.recentBets` (Р-62, пункт 2)

Новый класс `server/domain/BetFeed.java` — in-memory, append-only, по одной записи на каждый
`BetPlaced` (включая правку ставки, Р-13 — правка не переписывает старую запись, добавляет новую,
как и не удаляет предыдущий голос зрителя в Twitch Predictions). Единственный писатель —
`EventApplier`'s `BetPlaced`-ветка (тот же паттерн, что уже даёт `ComparisonAccounts`: и живой
трафик, и полный реплей при рестарте проходят одним кодом, `EngineRebuildService.rebuild()` зовёт
`betFeed.reset()` перед реплеем ровно как `comparisonAccounts.reset()`). Два независимых предела:
`MAX_ENTRIES_PER_ROUND=120` записей на раунд (старые вытесняются), `MAX_ROUNDS_TRACKED=500` раундов
всего (предложение — числа нигде не зафиксированы, выбраны с запасом для локального стенда, Р-46:
20 ботов).

Два способа читать одни и те же данные, под разные задачи:

- **`betFeed(roundId, sinceSeq?)`** — публичный RPC (сессия не нужна, та же видимость, что у самого
  раунда во время BETTING), полная история курсорной пагинацией: `entries` — от старых к новым,
  `seq > sinceSeq`, не больше `BetFeed.PAGE_SIZE=50` за вызов; `hasMore=true` — сразу звать ещё раз
  с `seq` последней записи, не дожидаясь следующего тика поллинга. Ряд — `BetFeedEntryDto{seq, atMs,
  playerDisplayName, isBot, direction, stake, leverage}` (без `playerId`/адреса — только то, что уже
  публично по Р-15/Р-29).
- **`RoundBetting.recentBets`** — маленький, всегда присутствующий срез той же ленты (новые
  первыми, не больше `RECENT_BETS_LIMIT=14`, то самое число, что уже было в mock-версии
  `web/`-агента), встроенный прямо в ответ `rounds()`/`round()` — не нужен отдельный вызов, если
  экрану достаточно последних N. Ряд — `LiveBetDto{player: PlayerSummaryDto, direction, leverage,
  stake, atMs}` (полная идентичность, не сплющенная, как в `betFeedFeedEntryDto` — так уже было
  написано в контракте до этого прохода).

`BetFeed.Entry` хранит `playerId` ровно для того, чтобы `recentBets`' `LiveBetDto.player` мог
собрать полный `PlayerSummaryDto` через `GameService.summaryOrFallback` (тот же
«should not happen, но на всякий случай» fallback, что уже есть в `storeSettledResult`) —
`BetFeedEntryDto` сам этот `playerId` наружу не отдаёт.

### 18.3 «Пока вас не было» — `unseenResults`/`acknowledgeResults` (Р-62, пункт 3)

Фикс находки основной сессии: игрок, чья вкладка/приложение были закрыты в момент расчёта раунда,
никогда не видел «момент итога» (`docs/product/design/result-moment.md`) — `useSettlementWatcher`
(уже в `web/`) ловит расчёт только пока приложение открыто и поллинг идёт. Новая колонка
`player_round_history.acked_at_ms` (`V6__result_ack.sql`, `NULL` = ещё не показан) — не отдельная
таблица: у каждой сыгранной позиции и так уже есть ровно одна строка здесь (`V1__init.sql`).

- `unseenResults()` (RPC) → `RoundResultRepository.unseenResults`: собственные расчёты этого
  игрока с `acked_at_ms IS NULL`, **новые первыми** — так же, как `playerHistory`, и так, как уже
  зафиксировал doc-комментарий `Api.unseenResults` в контракте до этого прохода (`fx/
  useAwayReplay.ts` сам разворачивает список, чтобы проиграть моменты от старого к новому, считая
  баланс до/после в обратном порядке от текущего реального баланса — этот порядок не менялся, только
  реализована серверная сторона под него). Ограничение `UNSEEN_RESULTS_LIMIT=20` (предложение) —
  если после долгого отсутствия непросмотренных больше, лишние (самые старые) просто придут
  следующим вызовом, ничего не теряется.
- `acknowledgeResults(roundIds)` (RPC) → `RoundResultRepository.ackResults`: `UPDATE ... WHERE
  player_id = ? AND round_id = ? AND acked_at_ms IS NULL` — идемпотентно (повторный ack и ack
  неизвестного/чужого `roundId` — тихий no-op, не ошибка) и всегда со скобкой по `player_id`, так
  что один игрок не может подтвердить чужую запись, даже зная её `roundId`.

Оба — чтение/запись read-model таблицы, не состояния `EngineHub` — не требуют `engineLock` (та же
логика, что у уже существующего `cancellations`), и переживают рестарт/реплей без всякого
дополнительного кода: `acked_at_ms` — обычная колонка обычной таблицы, не часть `game_events`.

### 18.4 Что не тронуто

`engine/` — не тронут вообще: вся математика для §18.1 (majority-by-stake, stake-weighted leverage,
клэмп 2..40) уже существовала на уровне `server/` (`GameService.weightedLeverage`,
скопирован из уже отревьюженного `buildRunningDto`), новый код не добавляет доменной логики,
только читает уже посчитанное. `oracle_records`/`results.json`/хеш итогов (Р-41) — семантика
не менялась: живая лента и «пока вас не было» — обе read-model-надстройки поверх уже
опубликованных/захешированных результатов, не влияют на то, что подписывается оракулом. Асимметрия
по активам (§0 задания, `asset` уже `"BTC"`-литерал на `RoundBetting`/`RoundRunning`/
`RoundSettled`/`RoundCancelled`) дополнена только на двух НОВЫХ DTO, которых раньше не было:
`ProfileRoundEntryDto` (аддитивное поле `asset`, используется и `unseenResults`, и
`publicProfile`/`myProfile`) и `BetFeedDto` (поле `asset` на самом ответе `betFeed`, раз он больше
не вложен внутрь раунда) — реализации других активов это не добавляет, только место, куда её потом
вписать.

### 18.5 Тесты и реальные числа

Новые: `BetFeedCapsTest` (порядок по `seq`, `sinceSeq`-пагинация, правка ставки = новая запись, оба
предела — `PAGE_SIZE` на один вызов и `MAX_ENTRIES_PER_ROUND` суммарно на раунд, через прямые
вызовы `GameService`, `herd.bots.count=0` для детерминированного счёта), `UnseenResultsTest`
(появление после расчёта → исчезновение после ack → идемпотентность повторного ack → переживает
`EngineRebuildService.rebuild()`; изоляция по игроку — ack одного не трогает другого).
`GameFlowIntegrationTest` обновлён: секция «reveal rules» (была — Р-22, «во время ставок раунд
показывает 0 голосующих и никаких направлений/сумм») переписана под Р-62 — расклад
(`upStake`/`downStake`/`upCount`/`downCount`, согласованность с `participantCount`,
`majorityDirection`/`majorityLeverage` присутствуют парой или оба `null`) и лента (`betFeed`,
порядок по `seq`, `sinceSeq`) видны вживую уже во время `BETTING`, полный список позиций
(`positions`) по-прежнему появляется только на `RUNNING`/`SETTLED` — так и должно быть по заданию
(это разные вещи: агрегат+лента вживую vs. полный список с историей после раскрытия).

## 19. CI-регрессия: shared Postgres между `@QuarkusTest`-классами — 2026-09-26

CI (`.github/workflows/ci.yml` на ветке `ci/setup`, задача `engine-server`: `mvn -B -f pom.xml -pl
server -am verify` против реального `postgres:16-alpine`-сервис-контейнера, Dev Services выключен
явным `quarkus.datasource.jdbc.url`) стабильно падал в `AdminReverseDepositTest`/
`AdminReverseWithdrawalTest` с `NoSuchElementException: unknown withdrawal request: w-golden-1`,
локально — стабильно проходил. Assigned paths: `server/src/test/`, `server/src/main/` (только если
причина там — не пригодилось, причина целиком в тестах), этот файл.

### 19.1 Воспроизведение

Тот же способ, что и CI: одноразовый `postgres:16-alpine` в Docker на свободном порту (`herd`/
`herd`/`herd`, без volume), `mvn -s ~/.m2/settings-oss.xml -B -f pom.xml -pl server -am clean
verify -Dquarkus.datasource.jdbc.url=jdbc:postgresql://localhost:<port>/herd
-Dquarkus.datasource.username=herd -Dquarkus.datasource.password=herd`, JDK 21 (`~/.sdkman/
candidates/java/21.0.11-oracle`). Воспроизвелось с первой попытки, byte-for-byte то же исключение,
что и в описании задачи.

### 19.2 Первопричина

`EventStoreGoldenFixturesTest` (задание item 4, event schema evolution) для каждого из 14 типов
`GameEvent` делает НАСТОЯЩИЙ, вручную закоммиченный (`Db#tx` — свой JDBC-коннекшен, ручной commit,
никакого JTA-роллбэка, см. `Db`'s javadoc) `INSERT INTO game_events` — включая синтетические строки
`WithdrawalConfirmed("w-golden-1", …)`/`WithdrawalReversed(relatedWithdrawalRequestId="w-golden-1",
…)`. Эта строка НИКОГДА не удалялась и оставалась в `game_events` до конца прогона (все
`@QuarkusTest`-классы модуля делят один Postgres/одну таблицу на весь `mvn verify`, а не только
одну JVM — приложение шарит датасорс между классами по определению `Db`).

Шесть тестовых классов вызывают `EngineRebuildService#rebuild()` напрямую (тот же код, что и при
реальном рестарте — `AdminReverseDepositTest`, `AdminReverseWithdrawalTest`,
`BotRestartRecoveryTest`, `GameFlowIntegrationTest`, `UnseenResultsTest`,
`scheduler.PlayOnChainTest`) — `rebuild()` делает `eventStore.loadAll()` по ВСЕЙ таблице и
реплеит каждую строку в свежий `EngineHub`. `w-golden-1` — синтетическая метка ровно для теста
декодирования, а не id, который когда-либо выдал `LedgerBook.requestWithdrawal` (тот генерирует
`"w-N"` сам, реальному `WithdrawalRequested`-фикстуре из этого же файла достаётся другой id) — так
что любой из этих шести классов, кто выполнялся ПОСЛЕ `EventStoreGoldenFixturesTest` в конкретном
прогоне, реплеил `WithdrawalConfirmed("w-golden-1")` против свежего `LedgerBook`, где такой заявки
никогда не было → `NoSuchElementException` из `LedgerBook.transitionWithdrawal`. Порядок классов у
Surefire по умолчанию — `filesystem` (порядок сканирования classpath), он зависит от ОС/файловой
системы раннера, не от алфавита — этим объясняется «стабильно на CI (Linux-раннер), стабильно
проходит локально» (другой, тоже стабильный, но другой порядок).

### 19.3 Фикс 1 — `EventStoreGoldenFixturesTest` больше не оставляет свои строки в общей таблице

`roundTripsAndReplaysThroughTheRealEventStore` теперь удаляет свою же вставленную строку
(`DELETE FROM game_events WHERE id = ?`) в `finally`, сразу после проверки реплея — round-trip
через настоящий Postgres/JSONB остаётся, но `game_events` для остальной части прогона выглядит
так, будто этого теста не было. Устраняет первопричину независимо от порядка классов.

### 19.4 Фикс 2 — изоляция между классами (`CleanDatabasePerTestClass`)

Одного фикса 19.3 недостаточно для зелёного полного прогона: та же db-техника (INSERT в обход
event-lifecycle) — не единственный источник межклассовой связанности, которую вскрыл этот же
механизм (`rebuild()` реплеит вообще всё, что накопилось в `game_events`/`players` к моменту его
вызова, от ЛЮБОГО более раннего класса в том же прогоне). Реально наблюдалось (после фикса 19.3,
до фикса 19.4, тот же способ прогона §19.1): `BotRestartRecoveryTest` (ожидает ровно
`herd.bots.count` ботов — видел 20 «чужих», созданных дефолтным профилем более ранней тестовой
конкретной вкладки), `BetFeedCapsTest` (ожидает ровно 3 записи в ленте — видел лишние от бота из
ещё живого фонового `RoundSchedulerService` предыдущего класса), `scheduler.PlayOnChainTest`
(`IllegalStateException: withdrawal w-10 already CONFIRMED` — тот же паттерн, что и 19.2, только
без синтетики: настоящая заявка одного класса реплеилась в контекст другого).

Новый `server/src/test/java/crowdvsmarket/server/testsupport/CleanDatabasePerTestClass.java`
(регистрируется через `META-INF/services/io.quarkus.test.junit.callback.
QuarkusTestBeforeEachCallback`, действует автоматически на все `@QuarkusTest`-классы модуля, без
изменений в каждом классе по отдельности) перед первым тестовым методом каждого класса (флаг
"уже сброшено" на статический `Set<Class<?>>` — Surefire по умолчанию гоняет весь модуль в одном
форке, так что это ровно "один раз на класс за весь прогон"): `TRUNCATE` всех мутируемых таблиц
(`game_events`, `players`, `round_results`, `player_round_history`, `withdrawal_requests`,
`deposit_requests`, `auth_challenges`, `sessions`, `oracle_records`, `player_deposit_addresses`,
`deposit_confirmations` — не тронуты `server_meta` и `flyway_schema_history`, см. класса javadoc)
+ `EngineRebuildService#rebuild()`, тем же способом, каким сам процесс восстанавливается после
рестарта. Пробовался более лёгкий вариант — `TRUNCATE` из `QuarkusTestAfterAllCallback` (после, а
не до, полагаясь на то, что Quarkus сам перезапустит приложение для следующего класса с другим
`@TestProfile`) — не сработал: Quarkus не всегда перезапускает контейнер между классами с разными
профилями (`BetFeedCapsTest` реально унаследовал ещё живой инстанс от `AdminReverseWithdrawalTest`
— наблюдалось напрямую по логам, «herd-server stopped/started» между ними не было), так что только
`TRUNCATE` без принудительного `rebuild()` не гарантирует, что in-memory `EngineHub`/`BetFeed`/
`PlayerDirectory` заметят пустую БД.

### 19.5 Фикс 3 — три теста с 3–6-секундными окнами получили реальный запас

`RoundCalendar`'s origin epoch — фиксированный момент первого запуска приложения в рамках всего
прогона (персистится в `server_meta`, не сбрасывается фиксом 19.4 намеренно) — окно раунда поэтому
«где-то в своей 3-секундной решётке» чисто по случайности момента, когда конкретный класс
физически стартовал, независимо от `EngineRebuildService.rebuild()`. `BotRestartRecoveryTest`
(окна 3с/4с) и `UnseenResultsTest`/`GameFlowIntegrationTest` (аналогично) писались/проходили как
самостоятельный прогон одного класса — там `EngineRebuildService` вызывается ровно один раз (плюс
явный вызов в самом тесте), и этого хватало. В составе полного прогона добавочная работа фикса 19.4
(`TRUNCATE` + `rebuild()` перед первым тестом класса) и просто другое положение в общей временной
шкале иногда не оставляли реальным тикам планировщика (`herd.scheduler.tick-ms` по умолчанию
1000мс) шанса сработать до закрытия ставок — `BotRestartRecoveryTest` падал на «ни один бот не
поставил», `UnseenResultsTest`/`GameFlowIntegrationTest` — на `ROUND_NOT_OPEN_FOR_BETTING`.

Правка (все три — только тестовый профиль/сам тест, не движок): `hourly-sec`/`daily-sec` подняты
с 3–4/4–6 до 8/10 (запас в разы, `await()`-таймауты у всех — 30с, с большим отступом хватает) — и
дополнительно, только в `BotRestartRecoveryTest` (единственный, кто реально зависит от асинхронного
тика планировщика, а не от синхронного вызова с явным `nowMs`): `ensureComfortableMargin(Interval)`
— если в текущем окне осталось меньше половины его длительности, тест дожидается СЛЕДУЮЩЕГО (полного)
окна вместо гонки с закрытием ставок в текущем. Идентичная регрессия — «бот резюмирует ставки после
рестарта» — проверяется тем же самым способом, просто не привязана к конкретному окну, которое
могло выпасть неудачно.

### 19.6 Проверено — реальные числа (два прогона в каждом из двух режимов, 2026-09-26)

CI-like (одноразовый `postgres:16-alpine`, `-Dquarkus.datasource.jdbc.url=…`, Dev Services выключен
— §19.1 способ), `mvn … clean verify`, свежий контейнер на каждый прогон:

- Прогон 1: `engine` **6531 тест, 0 упавших**; `server` **146 тестов, 0 упавших, 0 ошибок**.
  `BUILD SUCCESS`.
- Прогон 2 (новый одноразовый Postgres): те же **6531**/**146**, 0/0. `BUILD SUCCESS`.

Обычный локальный режим (Dev Services Postgres, без `-Dquarkus.datasource.jdbc.url`), `mvn … clean
verify`, дважды подряд: оба раза **6531** (`engine`) / **146** (`server`), 0 упавших, 0 ошибок,
`BUILD SUCCESS`. (Dev Services поднимает отдельный одноразовый контейнер на каждую смену
`@TestProfile`, а не один на весь прогон — сама по себе менее подвержена §19.2/19.4, но фикс не
предполагает и не полагается на это различие.)

До фикса 19.3 тем же способом (§19.1) — воспроизведено дословно то же `NoSuchElementException:
unknown withdrawal request: w-golden-1`, что и в описании задачи. После 19.3, до 19.4/19.5 — та же
установка стабильно давала 3–4 других падения (§19.4/19.5) вместо исходного.

## 20. Волна 7 (рынки: модель чтения, новые RPC, сквозной тест live) — 2026-10-02

Основание: Р-68…Р-71 (`docs/product/decisions.md`); архитектура и зафиксированные интерфейсы —
`docs/research/markets_architecture.md` (Т-1…Т-13, §8 «API»); источник правды по именам полей —
`web/src/api/contract.ts`. Этот срез — «API-поверхность рынков на сервере»: чтение из памяти (новый
`MarketViewService` и соседи), четыре новых RPC, дополнения `serverInfo` и профиля, мелкие правки и
сквозной тест всего сервера в режиме `live` на настоящем ценовом конвейере. Ядро рынков, конвейер цен
и каталоги построены предыдущими срезами; их описывают §2, §4.2, §5, §6 и `markets_architecture.md`
§3–§4. Все числа, помеченные «предложение», — решения этой сессии, а не источников.

### 20.1 Ядро рынков в одном абзаце

Реестр рынков — `DbMarketRegistry` над таблицей `markets` (снимок для чтений без блокировок;
листинг и закрытие уведомляют слушателя — `GameService`). `EngineHub` держит **один** `LedgerBook` и на
каждый рынок `CrowdAccount`, два `GameEngine` и `ComparisonAccounts`; `RoundProgress` ведёт курсор на
серию (рынок, интервал). Расчёт раунда атомарен (цена берётся до блокировки, дальше одна транзакция БД с
событием, итогом, историей игроков и записью оракула; сбой — пересборка из журнала), а если
пересборка тоже не удалась — движок «не готов» и всё, что меняет состояние или читает из движка, отвечает
503 `ENGINE_NOT_READY` (fail-stop, `markets_architecture.md` §4 п.3, п.10; `scheduler/EngineRebuildService.java`).

### 20.2 Модель чтения: общее устройство

`GameService` получил два узких метода для чтения движка (`service/GameService.java`):
`readEngine(Supplier)` — выполняет чтение под **read-замком** движка и отвечает 503 `ENGINE_NOT_READY`, пока
движок не готов; `requireEngineReady()` — та же проверка без замка (кэшированный ответ строился из памяти,
которой больше нельзя верить, поэтому его тоже не отдают). Под замком — только память: ни запроса в БД, ни
сети (расчёт, ждущий write-замок, не должен стоять за чтением, которое чего-то ждёт). Остальное лежит в
новых классах пакета `service`, чтобы не раздувать `GameService`:

| Класс | Что делает |
|---|---|
| `MarketViewService` | собирает ответы `markets`, `myPositions`, `crowdOverview`; кэш ответа `markets` |
| `OpenBets` | **один** обход серий, из которого сделаны и `myPositions`, и `PlayerAccountDto.inPlay`, — поэтому `inPlay` равен сумме ставок, которые перечисляет `myPositions`, по построению |
| `CrowdStatsMemo` | считает «сыграно/выиграно/как кончился последний» по истории счёта толпы один раз на раунд, а не на вызов |
| `MarketMapper` | чистые правила: сырая цена со знаками рынка, живой расклад раунда, время начала матча, доходность |
| `PriceSeriesService`, `PriceThinning` | `priceSeries` и правило прореживания |
| `ServerInfoService` | `serverInfo` (переехал из `GameService`; `AdminResource.status` берёт его же) |

Правило цен (Т-3): наружу идут только **сырые** цены строкой ровно с `priceDecimals` знаками рынка
(`MarketMapper.rawPrice`): перп с лот-экспонентой 0 — 2 знака, с экспонентой 4 — 6, рынок шансов
(`ODDS`, экспонента 4) — 6, перп вроде DOGE (экспонента 5) — 7.

### 20.3 `markets` (контракт: `MarketsDto`)

- **Что в списке.** Рынки, которые играются на этом сервере — все в `live`, **только BTC в `replay`**
  (что бы ни лежало в реестре от прежних запусков, `GameService.runs`), — активные плюс закрытые не
  более 10 минут назад (`MarketViewService.CLOSED_LISTED_MS`), в порядке листинга. `categories` — только
  непустые, в порядке `Category.ORDER`, `marketCount` — число **активных** рынков категории.
- **Рынок.** Поля записи `Market` как есть (`subtitle`, `iconUrl`, `endsAtMs` — `null` без значения;
  закрытый несёт `closedAtMs` и `closeReason`). `eventStartsAtMs` — число `gameStartMs` из `sourceMeta`,
  если оно есть; иначе читается `gameStartTime`, который каталог Polymarket реально пишет (текст в
  написании Gamma, `"2026-10-01 18:45:00+00"`, или ISO; `markets/catalog/PolymarketCatalog.toNewMarket`,
  образцы `server/src/test/resources/fixtures/polymarket/`); нечитаемое значение даёт `null`, лобби от
  кривой заметки каталога не падает. Т.е. поставщик может писать любое из двух.
- **Цена.** Последний тик (`MarketPrices.latest`; в `replay` — `PriceService.replayReference`): сырая
  цена, время, `change24h` (как дал источник, `null` без значения), `stale`. Пока тика нет — `price: null`.
- **Серии.** По одной на интервал, **короткая первой** (сортировка по реальной длине, а не по имени
  `HOURLY`/`DAILY`): `durationSec`, раунд ставок и когда он закрывается, идущий раунд и когда он кончается,
  живой расклад раунда ставок (`participantCount`, `upStake`, `downStake`, `majorityDirection` — `null` при
  ничьей, включая 0 против 0, Р-5/Р-62). У рынка без идущих раундов (закрыт, ещё не получил серию) `series`
  пуст.
- **Толпа.** `capital`, `returnPct = capital/100000 − 1`, `roundsTraded`, `roundsWon`, `lastOutcome`.
  «Выиграно» — торговый раунд, закончившийся **бо́льшим** капиталом, чем начался; ликвидация — `LIQUIDATED`;
  торговый раунд «в ноль» (закрытие равно открытию — на шансах с лот-экспонентой 4 ожидается часто;
  `markets_architecture.md` §11: частота не измерялась) не выигран, а `lastOutcome` для него говорит `LOSS`:
  в `contract.ts` значения «ровно» нет, правило то же, что у mock-версии `web/` (`web/src/api/mockCrowdHistory.ts`,
  `crowdBrief`: `won = crowd.capital > crowdBefore`), и оно занижает толпу, а не льстит ей. Раунд без торговли
  (ничья/нет ставок) не считается.
- **Кэш.** `markets` опрашивает каждое открытое лобби раз в 2–3 с, поэтому собранный ответ живёт
  `MarketViewService.CACHE_MS` = 1 с (предложение — «около секунды» из задания); `serverTimeMs` в ответе — время
  самого вызова; кто пришёл во время сборки, ждёт её, а не собирает своё; `invalidate()` сбрасывает ответ (для
  тестов и действий оператора). Счётчики толпы пересчитываются только по новым раундам (`CrowdStatsMemo`:
  ключ — объект счёта и длина его истории; пересборка движка оставляет новый объект и так обнуляет память).

### 20.4 `priceSeries(marketId, sinceMs?)` (контракт: `PriceSeriesDto`)

Тики последних 15 минут или, с `sinceMs`, тики с `atMs > sinceMs` (строго), от старых к новым, сырые строки.
**Не больше 600 точек**: если их больше, они прореживаются равномерно по всему запрошенному диапазону, самый старый
и **самый новый** тик остаются всегда (`PriceThinning`: k-я оставленная — `round(k·(n−1)/(max−1))`, целочисленно;
при `n > max` шаг больше 1, так что позиции строго возрастают). Глубже буфера (`PricesConfig.retentionMs`) смотреть
бессмысленно, `sinceMs` обрезается им; `sinceMs` не раньше конца диапазона даёт пустой ответ сразу (в `replay`
тики вычисляются, а не читаются, и `sinceMs` порядка `Long.MAX_VALUE` иначе заставил бы перебирать секунды
миллиардами — `PriceSeriesServiceTest.aSinceAtOrBeyondTheEndOfTheRangeIsEmptyEvenAtTheLargestNumber`).
Закрытый рынок больше не тикает: его «последние 15 минут» — 15 минут **до закрытия** (как в mock-версии `web/`:
комната только что ушедшего рынка рисует последний отрезок). Неизвестный
рынок — 404 `MARKET_NOT_FOUND`; `sinceMs`, который не целое число, — 400 (`RpcResource.optionalLong`: мягкий
`asLong()` превратил бы `"abc"` в 0 и отдал бы все тики). Нужен без движка: отвечает и пока он не готов.

**Решение для `replay`** (в задании «решить и задокументировать»): у повтора нет собственных тиков — только
свечи на ускоренных часах, — поэтому его «собственный недавний путь» это replay-серия, **снятая раз в
целую секунду** реального времени, каждый тик — то, что сказал бы `referencePrice("BTC")` в эту секунду (открытие
свечи, на которой стоят виртуальные часы; при `speedup=20` свеча длится 3 с, и цена повторяется трижды подряд —
ступенчатая линия, как и сам повтор). Только BTC; любой другой рынок там — `MARKET_NOT_FOUND`. Глубина — не больше
двух часов (`PriceSeriesService.REPLAY_MAX_LOOKBACK_MS`), 7 200 отсчётов до прореживания.

### 20.5 `myPositions` и `inPlay` (контракт: `MyPositionDto[]`, нужна сессия)

Каждая ставка в окне ставок (`BETTING`) и каждая открытая позиция (`RUNNING`) игрока по всем рынкам и обеим
сериям — один обход `OpenBets` (раунд, на который указывает курсор, — `RUNNING`: его ставки лежат в `BetBook` до
расчёта, отдельного состояния «идёт» в движке нет, §5; следующий — `BETTING`). `durationSec` — длина окна,
`untilMs` — когда раунд начнётся (`BETTING`) или кончится (`RUNNING`). Для `RUNNING` `entryPrice`,
`liquidationPrice`, `size` и `liquidated` считаются по `MarketPrices.soFar` (память) теми же формулами, что
`buildRunningDto`; пока цена открытия неизвестна, три поля — `null`, `liquidated=false`. Порядок — «кто раньше
кончится» (`web/src/api/useMyPositions.ts` сохраняет порядок сервера), при равных `untilMs` — по рынку, интервалу и
идущий раунд раньше того, что начнётся, когда он кончится. `PlayerAccountDto.inPlay` теперь — `OpenBets.stake`,
сумма ставок этого же обхода (`GameService.inPlayStake` стал его вызовом).

### 20.6 `crowdOverview` (контракт: `CrowdOverviewDto`)

Сводка по счетам толпы всех играемых рынков (в `replay` — только BTC): строка на рынок в порядке листинга
(`marketId`, `symbol`, `title`, `status`, `category`, `capital`, `returnPct`, `roundsTraded`, `roundsWon`) и
итоги — `totalCapital`, `totalStartingCapital` (100 000 на рынок), `returnPct` суммы. Закрытый рынок **сохраняет
строку** (движок держит счёт каждого рынка реестра), хотя `markets()` забывает его через 10 минут.

### 20.7 `serverInfo`: что добавлено

`priceSource` — `"replay"` в режиме `replay`, иначе `herd.prices.source` (`"live"`/`"synthetic"`); `markets` —
число активных играемых рынков; `oracle.firstSeq` — наименьший `seq` в `oracle_records` (`SELECT MIN(seq)` по
первичному ключу, `null` пока таблица пуста; запрос в `ServerInfoService`, `OracleRecordRepository` не тронут);
`playAssetId` — `herd.play.asset-id` (`HERD_PLAY_ASSET_ID`), по умолчанию выпуск testnet11
`0x4b88ce97…6080b70` (`docs/research/week1_testnet_results.md` §3, то же число `web/src/components/playAsset.ts`),
приводится к `0x` + 64 hex в нижнем регистре, а значение, которое не asset id, не отдаётся (`null`, одно
предупреждение в журнал): кошелёк вставит его в Sage, чужой токен показывать нельзя; `testWallet` —
`herd.test-wallet.enabled` (`HERD_TEST_WALLET_ENABLED`, по умолчанию `true`). Ответ по-прежнему не требует
движка.

### 20.8 Профили: серия побед и длина раунда (Р-71)

`PublicProfileDto.streak {current, best}` — по **всем** рассчитанным раундам игрока, не по странице `history` (она
обрезана `PROFILE_HISTORY_LIMIT=100`, и лучшая серия старше страницы иначе потерялась бы). Правило
(`domain/WinStreak.java`): раунд выигран, если `payout > stake` (ликвидация — проигрыш); «ровный» раунд
(`payout == stake`) серию ни продлевает, ни рвёт — как будто его нет; отменённый раунд — не раунд (строки в
`player_round_history` у него нет, ставка возвращена в журнале); `current` — выигрыши подряд от самого нового
раунда, `best` — самый длинный ряд в истории. Запрос — `RoundResultRepository.playerStreak`: одна проходка по
двум колонкам, новые первыми (при равном `settled_at_ms` — по рынку и id раунда по убыванию, чтобы ответ не
менялся от вызова к вызову), ровные раунды отсеяны в SQL. `ProfileRoundEntryDto.durationSec` — реальная длина
раунда (`round_results.window_end_ms − window_start_ms` через `LEFT JOIN`) в истории профиля и в
`unseenResults`; `null` для строки без итога раунда (в нормальной работе не бывает).

### 20.9 Мелочи этого среза

- **Домен в тексте подписи.** `AuthService.challenge` брал `Domain: herd.local` литералом; теперь —
  `herd.public-domain` (`HERD_PUBLIC_DOMAIN`, по умолчанию `herd.local`; compose его уже передаёт,
  `docker-compose.yml`, `deploy/compose.prod.yml`). Сохранённый с nonce текст — тот же, что показан игроку
  (`AuthServiceDomainTest`).
- **Коды ошибок.** `ApiErrorCodeContractTest` читает union `ApiErrorCode` из `web/src/api/contract.ts` и
  сверяет с `Enums.ApiErrorCode`: каждый код union кроме `OTHER` есть на сервере под тем же именем и его производит
  фабрика `ApiException` (`RATE_LIMITED` — с HTTP 429); коды сервера, которых нет в union (клиент читает их как
  `OTHER`: `UNAUTHORIZED`, `NOT_FOUND`, `BAD_REQUEST`, `INVALID_NICKNAME`, `INVALID_AMOUNT`,
  `DEPOSIT_VERIFICATION_UNAVAILABLE`, `WITHDRAWAL_UNAVAILABLE`, `WITHDRAWAL_BELOW_MINIMUM`,
  `CHAIN_SERVICE_UNAVAILABLE`, `ENGINE_NOT_READY`), перечислены в тесте — новый код сервера роняет тест, пока
  кто-то не решит, называть ли его в union.
- **Лимит запросов.** `RATE_LIMITED_METHODS` прежний (`authChallenge`, `authVerify`, `placeBet`). Новые чтения
  (`markets`, `priceSeries`, `myPositions`, `crowdOverview`) **не ограничены**: их опрашивает каждая открытая
  вкладка раз в 2–3 с (ведро 30 с пополнением 0,5/с на IP душило бы одну честную вкладку и всех за одним NAT), и
  каждое дёшево по построению — память, `markets` из кэша в секунду, `priceSeries` не больше 600 точек,
  `myPositions` — ставки одной сессии (`MarketsRpcTest.thePolledReadsAreNotRateLimited`).

### 20.10 Тесты и реальные числа

Новые тесты (`server/src/test/java/crowdvsmarket/server/…`):

- Чистые правила, без Quarkus: `service/PriceThinningTest`, `domain/WinStreakTest`, `service/MarketMapperTest`
  (сырые цены перпа с лот-экспонентой 0 и 4 и рынка шансов, расклад, время матча, доходность),
  `service/CrowdStatsMemoTest`, `service/OpenBetsTest`.
- Сборка ответов на настоящих `EngineHub`/`RoundProgress`/книгах ставок с заглушками вместо приложения:
  `service/MarketViewServiceTest` (оба режима, кэш, 503, закрытые рынки, позиции, сводка),
  `service/PriceSeriesServiceTest`, `service/ServerInfoServiceTest`, `auth/AuthServiceDomainTest`,
  `ApiErrorCodeContractTest`.
- Нагрузка: `MarketViewConcurrencyTest` — четыре читателя гоняют `markets`/`myPositions`/`crowdOverview` без
  пауз, пока под ними две секунды подряд переставляются ставки одного раунда, потом идут ставки, расчёты
  планировщиком, листинг и закрытие рынка; никто не падает и не видит невозможного (отрицательной ставки,
  одной позиции дважды, серии не из двух); в конце `inPlay` равен сумме `myPositions`. Проверено на зубастость:
  если `readEngine` не берёт read-замок, тест падает (`ArrayIndexOutOfBoundsException` в читателе).
- RPC по HTTP: `MarketsRpcTest` (имена полей `contract.ts` без лишних и пропущенных, явные `null`, сессия,
  ошибки, не-лимит чтений, 503 при невосстановленном движке), `ProfileRpcTest` (серия на настоящих расчётах
  через `scheduler.tick(now)`, отменённый раунд, 130 раундов против страницы в 100, `durationSec`),
  `ServerInfoRpcTest`, `ReplayModeApiTest` (режим `replay`: BTC один, `priceSeries` повтора, 404 для чужих).
- **Сквозной тест в реальном времени:** `LiveServerEndToEndTest` и `LiveServerWithBotsEndToEndTest` на профилях
  `LiveServerProfile`/`LiveServerWithBotsProfile` — `herd.mode=live`, `herd.prices.source=synthetic`, раунды 6 и
  12 с, планировщик включён, сеть и сторонние сервисы не нужны (мокируется только chainsvc). После старта
  синтетический каталог листит пять рынков (BTC, ETH, DOGE и по одному рынку шансов политики и спорта),
  `markets` показывает их цены со знаками каждого вида; игрок ставит на BTC и на рынок шансов в открытых
  раундах, `myPositions` показывает обе ставки сначала `BETTING`, потом `RUNNING` с ценой входа, оба раунда
  рассчитываются по ценам конвейера, баланс + ставки в игре = стартовый пакет + Σ(выплата − ставка) по истории,
  `priceSeries` отдаёт тики, итоги раундов и история профиля несут верный рынок и длину раунда, и
  закрытие раунда N равно открытию N+1 у BTC, DOGE и рынка шансов; вариант с тремя ботами проверяет те же книги
  среди ставок ботов. Все ожидания — «до тех пор, не дольше»: ни одного ожидания фиксированной паузой.
  Каталог опрашивается раз в 2 с (`herd.markets.synthetic.catalog-every=2s`): сброс БД между классами
  (`CleanDatabasePerTestClass`) может удалить уже выставленные рынки, и следующий проход листит их снова.

Прогон 2026-10-02 (`mvn -B -s ~/.m2/settings-oss.xml -f pom.xml -pl server -am verify
-Dquarkus.http.test-port=0`, Dev Services Postgres, 4 мин 41 с): `engine` **7 321** тест, 0 упавших; `server`
**698** тестов (до среза было 569, добавлено 129), упали **2** — `ChainSvcContractFixturesTest.
oracleStatusResponseFixtureRoundTrips` и `.oracleRecordsRequestFixtureRoundTrips`: фикстуры `chain/c2-svc/tests/
contract/*.json` несут поля (`asset`, `maxKnownSeq`, `recordFormat`, `feeCoins*`), которых нет в
`ChainSvcClient.OracleRecord`/`OracleStatus` — это срез проводки оракула, не этот. Сквозные тесты отдельно
прогнаны три раза подряд (по 33–37 с каждый класс), зелёные; на зубастость проверены мутациями (поломка правила
серии, прореживания, обхода ставок, десятичных знаков, памяти счётчиков толпы, read-замка — каждая роняет свои тесты).

### 20.11 Не сделано и открыто

- **`ENGINE_NOT_READY` нет в union `ApiErrorCode`** (`contract.ts`): клиент читает его как `OTHER` и показывает
  сообщение сервера; повторять запрос он не умеет — это для `web/`.
- **`walletConnectProjectId` в `serverInfo`** — поле описано в `contract.ts`, сервер его по-прежнему не отдаёт
  (вне этого среза).
- **Два имени для начала матча**: каталог пишет `gameStartTime` (текст), задание ожидало число
  `gameStartMs`; читается любое (§20.3). Если каталог перейдёт на `gameStartMs`, ничего менять не нужно.
- **`HERD_PLAY_ASSET_ID` и `HERD_TEST_WALLET_ENABLED` не прописаны в compose-файлах** (они не тронуты этим
  срезом): без них действуют значения по умолчанию.
- **Профиль считает серию запросом по всем строкам игрока** при каждом открытии профиля; для игрока с десятками
  тысяч расчётов это, по расчёту (не по замеру), единицы-десятки миллисекунд на открытие профиля (бот при
  раундах 180/900 с и `herd.bots.markets-per-round=3` набирает порядка 1 700 расчётов в сутки — 3 рынка ×
  (20 + 4 раунда в час) × 24, оценка), но кэша нет.
