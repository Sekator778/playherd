# Архитектурное ревью Herd: компоненты, масштаб, модуль ботов

Дата: 2026-10-16. Основание — слова Алекса 2026-10-16: «пригласить архитектора… чтобы он посмотрел просто верхнеуровнево на компоненты»; цель — «в будущем у нас миллионы пользователей в секунду все ставят… акцент на то, чтобы мы могли скейлиться и использовать очень мало ресурсов, но при этом давать огромную скорость»; «сейчас переписывать не надо, надо просто заложить фундамент». Ветка `feature/bot-strategies`, рабочее дерево на момент чтения (HEAD `9ffdbf9`). Только чтение: код не менялся, стенд и VPS не трогались.

Правила этого текста: каждый факт — со ссылкой `файл:строка` или на документ; **UNVERIFIED** — не проверено; **вывод** — моё заключение с указанием, на чём оно стоит. Нагрузочных измерений нет ни у кого, в том числе у меня: ни одного числа пропускной способности здесь не выдаётся за измеренное.

## Итог на один экран

1. **Что есть.** Один JVM-процесс (Quarkus) держит всё состояние игры в памяти — один `LedgerBook` на всех игроков и рынки плюс по два `GameEngine` и `CrowdAccount` на рынок (`server/.../domain/EngineHub.java:16-23`, `:75-79`) — и меняет его только под **одной глобальной блокировкой на запись** (`service/GameService.java:93-110`, `:212-213`). Ставка проходит: `POST /api/rpc/placeBet` → поиск сессии в Postgres → `GameService.placeBet` под write-lock → `SELECT` в `round_results` → `INSERT` события в `game_events` в своей транзакции → применение события к движку (`GameService.java:728-839`). Итог раунда идёт в цепь не из ставки, а из расчёта раунда: одна запись оракула на раунд рынка (`:1045-1065`), которую Rust-сервис публикует пачками ≤40 в один спенд синглтона, не больше одного спенда в полёте (`chain/c2-svc/src/oracle/publisher.rs:9-21`, `spend.rs:50`). **Цепь не на пути ставки** — это правильно и это главное, что надо сохранить.
2. **Хороший фундамент:** event-sourcing с одним путём применения событий для live и replay (`events/EventApplier.java:25-37`); чистый движок без зависимостей (`engine/pom.xml:36-37`); состояние по рынкам уже разделено на объекты (`EngineHub.MarketEngines`); чтения из памяти, не из БД (`service/MarketViewService.java:33-38`); цепь изолирована в отдельном процессе с токеном и своей durable-очередью (`chain/c2-svc/src/app.rs:71-102`, `oracle/queue.rs:1-20`); контракт API — один файл (`web/src/api/contract.ts`), проверяемый «стражем провода» (`docs/research/server_design.md` §21).
3. **Что заблокирует масштаб** (по убыванию цены изменения потом): (а) один глобальный write-lock + синхронный commit в Postgres под ним на каждую ставку — предел ≈ «одна ставка за время commit» на весь мир (**вывод** из `GameService.java:731-837`); (б) один `LedgerBook` на всех — нет ключа шардирования баланса; (в) пересборка памяти = полный replay `game_events` без снимков (`events/EventStore.java:57-62`, `scheduler/EngineStateBuilder.java:82-85`; 510 мс на 91 тыс. событий — `docs/research/markets_architecture.md` §4 п.13); (г) один `seq` оракула на одной строке `server_meta` под `SELECT … FOR UPDATE` (`persistence/ServerMetaRepository.java:92-97`); (д) `SELECT` сессии из Postgres на каждый RPC-вызов при опросе каждые 2 с (`api/RpcResource.java:107`, `auth/SessionService.java:36`, `web/src/api/useMarkets.ts:15`); (е) rate limiter и кэши — в памяти одного процесса.
4. **Что делать сейчас, дёшево, без переписывания** — раздел 4: ключи партиционирования в `game_events` (колонки `market`, `player_id`), `snapshot()/restore()` у движка как интерфейс (без реализации хранения), вынос `SELECT round_results` из-под блокировки, кэш сессий, публичный RPC свечей, экономические условия ставки в `markets`, стабильный `clientRequestId`/`botId` в ставке, запрет сетевых и БД-вызовов под блокировкой как правило `ArchUnit`-теста.
5. **Модуль ботов** (`docs/research/bots_module_design.md`): принципы верные — чистый «мозг», два порта, сигнал/характер врозь, детерминизм, каталог, бэктест до подключения. До кода изменить: (1) порты описать в терминах публичного API (`MarketSeriesDto`, `BetDto`), а не внутренних объектов, и сразу написать оба адаптера — in-process и HTTP-клиент; (2) добавить третий вход — состояние самого бота (баланс, последний итог) — в `BettingPort` или отдельный `BotStatePort`; (3) свечи считать на сервере и отдавать публичным RPC `candles` — иначе HTTP-бот не прогреет Donchian(55)/MACD(12,26,9): `priceSeries` даёт 15 минут и ≤600 точек (`service/PriceSeriesService.java:49-51`), буфер тиков — 2×15 мин + 10 мин (`prices/PricesConfig.java:455-457`); (4) зерно RNG = функция `(botId, roundId)`, не долгоживущее состояние; (5) не дублировать `EconomyRules` — зависеть от `engine` (у него нет runtime-зависимостей) или получать условия из API; (6) бэктест считать в P&L с ликвидацией через `engine` (`PositionSimulator`), а не «угадал/не угадал», иначе проверка математика (Р-91/Р-92) не на чем; (7) источник свечей для бэктеста — публичный API Coinbase/Hyperliquid, а не файлы из scratchpad (`docs/research/game_economy_math.md:3` — данные лежат в `<tmp>`, сохранность UNVERIFIED).
6. **Rust/Java.** Разделение разумное и для заявленной цели достаточное: Rust держит ключи, спенды и очередь к цепи — то, что ограничено блокчейном, а не нашим кодом; Java держит горячий путь. Скорость горячего пути сегодня упирается не в язык, а в п. 3(а)–(в). Переписывать не нужно; если когда-нибудь ядро матчинга потребует Rust, его перенос облегчён тем, что ядро уже чистое, а контракт — JSON-события в `game_events` (`events/GameEvent.java:35-73`).

---

## 1. Карта компонентов как есть

### 1.1 Процессы и сеть

| Компонент | Где | Факт |
|---|---|---|
| `web` | `docker-compose.yml:273-296` | nginx-образ со статикой React; единственный порт наружу `${HERD_WEB_PORT:-8480}:80` (`:285-286`); на VPS перед ним Caddy (`tools/vps/edge/Caddyfile.tmpl:39` `reverse_proxy 127.0.0.1:@@WEB_PORT@@`) |
| `server` | `docker-compose.yml:141-271` | Quarkus, порт 8081 (`server/src/main/resources/application.properties`: `quarkus.http.port=8081`); зависимости — `quarkus-rest-jackson`, `quarkus-jdbc-postgresql`, `quarkus-agroal`, `quarkus-flyway`, `quarkus-scheduler` (`server/pom.xml:60-84`); плюс `engine` |
| `db` | `docker-compose.yml:47-72` | Postgres 16, один том `herd_pg_live` |
| `chainsvc` | `docker-compose.yml:79-134` | Rust (axum, tokio), порт 8090, `network_mode: host`, bearer-токен на `/v1/*` (`chain/c2-svc/src/app.rs:100-102`); состояние — файлы в `${HOME}/.c2/testnet11` (`docker-compose.yml:124`) |
| Внешние источники | `prices/PriceFeedService.java:18-40` | Hyperliquid (REST, 3 с / 6 с), Polymarket (3 с), Coinbase/Kraken/Hyperliquid spot (WebSocket + REST-фолбэк, опрос памяти каждые 2 с); каждый поток — на виртуальном потоке (`prices/StreamSupervisor.java:10-16`) |

Компоненты Maven: `engine`, `server` (`pom.xml:22-25`); `engine` — без runtime-зависимостей (`engine/pom.xml:36-37`). Rust workspace: `sim-spike`, `c2-cli`, `c2-svc` (`chain/Cargo.toml`).

Хостинг сегодня: один Contabo VPS, 4 vCPU / 7,9 ГБ (`the internal notes` §2, запись 2026-10-11), стенд держит 343 МиБ, из них `server` 282 (там же).

### 1.2 Путь ставки: браузер → леджер → журнал → цепь

1. **Клиент.** `web/src/api/realApi.ts:122` — `fetch("/api/rpc/<method>", {method: "POST", credentials: "include", body: {args}})`. Состояние клиент получает **только опросом**: `markets` каждые 2 с (`web/src/api/useMarkets.ts:15`), `priceSeries` каждые 2 с (`usePriceSeries.ts:18`), `myPositions` каждые 2 с (`useMyPositions.ts:15`), комната рынка `POLL_MS = 2000` (`web/src/pages/PairRoomPage.tsx:82`); опрос останавливается в скрытой вкладке (`polledStore.ts:85`). Серверного push (SSE/WebSocket к клиенту) нет: единственные WebSocket-классы в сервере — исходящие к биржам (`prices/JdkWebSocketTransport.java`, `StreamSupervisor.java`).
2. **Вход в сервер.** `api/RpcResource.java:36-43` — все методы `Api` из `contract.ts` как `POST /api/rpc/{method}`; `:96-108`: rate limit по IP для `authChallenge`, `authVerify`, `placeBet`, `cancelBet` (`:62-63`; ведро в `ConcurrentHashMap` одного процесса — `api/RateLimiter.java:31`), затем **на каждый вызов** `sessions.playerIdFor(cookie)` (`:107`) → `SELECT … FROM sessions WHERE session_id = ?` (`auth/SessionService.java:36`, `persistence/SessionRepository.java:24-29`). Кэша сессий нет.
3. **`GameService.placeBet`** (`service/GameService.java:728-839`), синхронно, на потоке HTTP-воркера:
   - `:730` проверка рынка в реестре (память); `:731` `lockWrite()` — глобальный write-lock (`:260-267`) с проверкой готовности движка;
   - под блокировкой: `:739-741` окно ставок из `RoundProgress` (память, `domain/RoundProgress.java:44`); `:750` проверка времени; **`:756` `roundResults.exists(roundId)` — `SELECT` в Postgres под write-lock** (`persistence/RoundResultRepository.java:77-80`); `:763` игрок в кэше (`domain/PlayerDirectory.java:15-27`); `:783-788` правила экономики — чистые функции (`engine/.../economy/EconomyRules.java:53-57`, `:83-91`); `:796` circuit breaker; `:812-824` баланс и замена прежней ставки; `:826` `requireExposureRoom` — цикл по всем активным рынкам категории, в памяти (`:2249-2271`);
   - `:828-831` событие `BetPlaced` (`events/GameEvent.java:68-73`: игрок, интервал, окно, направление, плечо, ставка в mojo, время, рынок, комиссии, параметры ликвидации); **`:832` `persist` = `db.tx(append)` — `INSERT INTO game_events` и commit под write-lock** (`:328-333`, `events/EventStore.java:50-54`); `:833` `eventApplier.apply(event)` → `GameEngine.replayBet` → `LedgerBook.reserveStake` + `BetBook.place` (`events/EventApplier.java:76-91`, `engine/.../game/GameEngine.java:143-153`, `engine/.../round/BetBook.java:19-24`); `:837` unlock.
   - Инвариант «событие сначала зафиксировано, потом применено к памяти» — осознанный: память никогда не впереди БД (`service/GameService.java:121-126`).
4. **Расчёт раунда** — не ставкой, а планировщиком: `@Scheduled(every = "1s", SKIP)` (`scheduler/RoundSchedulerService.java:185`); один тик последовательно: посев серий, открытие раундов, **`botService.tick`** (`:245`), затем для каждого рынка × интервала `resolveOverdueRounds` (`:249-264`), затем `oraclePublisher.publishPending()` (`:266`). `GameService.resolveRound` (`:902-944`): фаза 1 — цены **без** блокировки (`:905`, может ходить в сеть); фаза 2 — под write-lock одна транзакция: `append(RoundResolved)` + `apply` + `storeSettledResult` (`:924-930`), где пишутся `round_results`, `player_round_history` (`:1029-1031`) и **запись оракула с резервом `seq`** (`:1032`, `:1059-1065` → `ServerMetaRepository.reserveOracleSeqRange`: `SELECT value … FOR UPDATE` на одной строке `server_meta`, `persistence/ServerMetaRepository.java:83-97`). Сбой транзакции после применения → пересборка памяти из журнала (`:935-939`, `scheduler/EngineRebuildService.java:217-227`).
5. **Цепь.** `OraclePublisherService` на отдельном фоновом потоке с очередью длины 1 (`scheduler/OraclePublisherService.java:104-116`) читает `oracle_records WHERE chainsvc_queued = FALSE` (`persistence/OracleRecordRepository.java:64-67`) и шлёт `POST /v1/oracle/records` пачками по 20 (`:90`, `:144-150`). `chainsvc` принимает в durable-очередь-файл (`chain/c2-svc/src/oracle/queue.rs:1-20`), тик паблишера собирает ≤40 записей в один спенд синглтона и держит не больше одного спенда в полёте (`oracle/publisher.rs:9-21`, `oracle/spend.rs:50`). Маршруты: `/v1/auth/verify`, `/v1/oracle/records`, `/v1/oracle/records/:seq`, `/v1/oracle/status`, `/v1/play/withdrawals`, `/v1/play/deposit-address`, `/v1/play/deposits`, `/v1/play/treasury` (`app.rs:86-99`). Ставка в цепь не попадает: на цепь — одна запись на (рынок, раунд), а не на ставку (`GameService.java:1045-1047`).
6. **Вывод PLAY** — под write-lock событие + строка `withdrawal_requests` (`GameService.java:1667-1699`), вызов `chainsvc` — **после** unlock (`:1701`), подтверждение — опросом раз в 30 с (`scheduler/WithdrawalPollerService.java:61`); депозиты — опрос 30 с (`DepositPollerService.java:50`).

### 1.3 Где живёт состояние

| Состояние | Где | Durable? |
|---|---|---|
| Балансы, стартовые пакеты, казна/HERD, открытые ставки, аудит-лог движений | `LedgerBook` в памяти: `accounts` (`LinkedHashMap`), `entries` (`ArrayList`, только растёт — `engine/.../ledger/LedgerBook.java:40-41`, `:887`), счета казны (`:52-76`) | Нет; восстанавливается replay `game_events` |
| Ставки на раунд, счёт толпы, сравнительные линии | `EngineHub.State.markets` — на рынок `CrowdAccount` + 2×`GameEngine` (каждый со своим `BetBook`) + `ComparisonAccounts` (`domain/EngineHub.java:39-53`, `:75-79`) | Нет; replay |
| Указатели серий, лента ставок, граничные цены, индекс окон со ставками | `RoundProgress` (`domain/RoundProgress.java:44`), `BetFeed` (`domain/BetFeed.java:14-20`), `BoundaryPrices`, `BetWindows` | Нет; replay / `round_results` |
| Журнал событий | `game_events(id BIGSERIAL, event_type, payload JSONB, at_ms, schema_version)` (`server/src/main/resources/db/migration/V1__init.sql:24-29`, `V3`) — 18 типов (`events/EventStore.java:83-101`), `schema_version = 1` (`:22`) | Да, единственный источник истины для памяти |
| Read-модели | `round_results` (+`canonical_json` для байт-в-байт `results.json`), `player_round_history`, `withdrawal_requests`, `oracle_records`, `markets`, `price_snapshots`, `sessions`, `players` (`V1__init.sql`, `V7__markets.sql:7-36`) | Да; не реплеятся (`events/GameEvent.java:16-18`) |
| Тики цен | `TickRing` на рынок в памяти, retention 2×DAILY + 10 мин (`prices/TickStore.java:17-24`, `prices/PricesConfig.java:455-457`), восстанавливаются из `price_snapshots` | Частично |
| Оракул, выводы, депозиты в цепи | файлы `chainsvc` (`oracle_queue.json`, `operator.oracle.json`) | Да, на диске одного хоста |
| Сессии, rate limit, кэш `markets` (1 с) | Postgres / память процесса (`api/RateLimiter.java:31`, `service/MarketViewService.java:57`) | сессии — да |

### 1.4 Что синхронно, что фоном

- **Синхронно на HTTP-потоке**: все RPC, включая `placeBet`, `cancelBet`, `requestWithdrawal` (до unlock), `authVerify` (вызов `chainsvc` `/v1/auth/verify` — `auth/AuthService.java:92`).
- **Фоновые задания** (все `@Scheduled`, `SKIP`): тик игры 1 с; опросы цен 1–6 с и prune 10 мин (`prices/PriceFeedService.java:106-163`); депозиты и выводы 30 с; реконсиляция оракула 60 с (`scheduler/OracleReconciliationService.java:56`); статус и пол `seq` оракула 5 с (`chain/OracleStatusCache.java:55`, `chain/ChainOracleSeqGate.java:66`); каталоги рынков (`markets/catalog/*Catalog.java`).
- **Пересборка памяти** (старт и восстановление после сбоя) — под write-lock: `players.reload()`, `marketRegistry.reload()`, `EventStore.loadAll()` + `apply` каждого события (`scheduler/EngineRebuildService.java:170-200`, `EngineStateBuilder.java:74-104`).
- **Боты** — внутри тика планировщика, последовательно, через тот же `placeBet` (`bots/BotService.java:161-168`, `:231-258`); хозяйственные операции ботов берут write-lock каждые 5 с (`:130-149`, `GameService.java:2305-2342`, `:2350-2380`).

---

## 2. Что уже хороший фундамент (сохранить)

1. **Event-sourcing с одним путём применения.** Live и replay идут через один `EventApplier.apply` (`events/EventApplier.java:25-37`); событие несёт всё для детерминированного повторения, включая исходное время и зафиксированные комиссии/параметры ликвидации (`events/GameEvent.java:9-14`, `:54-66`). Это ровно тот контракт, который нужен для будущих шардов, снимков и внешних потребителей (аналитика, боты-наблюдатели).
2. **Память никогда не впереди БД; fail-stop.** Фиксация события до применения; пересборка «рядом, затем подмена» (`scheduler/EngineStateBuilder.java:25-39`, `domain/EngineHub.java:100-102`); 503 `ENGINE_NOT_READY` вместо ложного состояния (`EngineRebuildService.java:32-51`). Это дисциплина, которую надо унаследовать любому шарду.
3. **Чистое ядро без фреймворка.** `engine` не зависит ни от чего (`engine/pom.xml:36-37`); движок — «однопоточный, функциональное ядро/мутируемая оболочка» (`docs/research/round_engine_design.md:120`); правила экономики — статические функции (`engine/.../economy/EconomyRules.java`). Переносимо в любой процесс и язык.
4. **Состояние уже нарезано по рынку.** `BetBook`, `CrowdAccount`, `ComparisonAccounts` — свои у каждого рынка (`domain/EngineHub.java:39-53`); ключ серии `(marketId, Interval)` везде (`docs/research/markets_architecture.md` §4 п.1). Общий между рынками только `LedgerBook`.
5. **Чтения — из памяти, с кэшем на секунду** (`service/MarketViewService.java:40-45`, `:57`); `priceSeries` — без движка, отвечает даже в восстановлении (`service/PriceSeriesService.java`, javadoc). Это та форма, из которой дёшево сделать fan-out (push-снимки по рынку) без переделки модели.
6. **Цепь отделена и не на горячем пути.** Отдельный процесс, бearer-токен, durable-очередь, один спенд в полёте, идемпотентность по `seq`/`requestId`, реконсиляция (`chain/c2-svc/src/app.rs:71-117`, `oracle/queue.rs`, `scheduler/OracleReconciliationService.java`). Публичная сверка независимым инструментом (`tools/verify-oracle`).
7. **Контракт API в одном месте** (`web/src/api/contract.ts`, `Api` `:1171-1272`), зеркальные DTO в `server/.../dto`, «страж слов» и «страж провода» (`docs/research/server_design.md` §21). Внешний клиент уже есть: `tools/e2e/run.mjs` играет четырьмя игроками с собственными ключами через те же RPC (`run.mjs:12`, `:60`, `:120`).
8. **Экономика как данные, а не константы.** Потолки, κ, ставка комиссии — колонки `markets` (`V11__economy_stage_a.sql`), параметры ставки замораживаются в событии (`GameEvent.java:62-66`). Еженедельный пересчёт не ломает журнал.
9. **Планировщик с `SKIP` и отсрочкой**; ни один сбой серии не роняет другие (`scheduler/RoundSchedulerService.java:32-40`, `:249-264`).

---

## 3. Что заблокирует масштаб (по убыванию цены изменения потом)

Контекст: сегодня на стенде за 4 ч 20 мин — 7 876 событий (`the internal notes` §4), то есть порядка 40–45 тыс. в сутки при 20 ботах (**вывод** — линейная экстраполяция). Нагрузка не измерялась.

### 3.1 Одна глобальная блокировка + синхронный commit под ней на каждую ставку — самое дорогое менять

- **Код:** `GameService.java:212-213` один `ReentrantReadWriteLock` на всё; `placeBet` держит write-lock от `:731` до `:837`, внутри — `SELECT` (`:756`) и транзакция с commit (`:832`). Тот же lock берут расчёт раунда (`:907`), выводы (`:1667`), депозиты, регистрация, боты (`:2306`, `:2351`, `:452`) и пересборка (`:219-226`).
- **Следствие (вывод):** пропускная способность ставок на весь кластер ≤ 1 / (латентность commit в Postgres + работа под блокировкой). Параллельность между рынками невозможна, хотя объекты рынков раздельны (`EngineHub.MarketEngines`). Второй экземпляр `server` над той же БД невозможен: память одного не видит событий другого, а `EventStore.append` не защищён от второго писателя (`events/EventStore.java:50-54`).
- **Почему дорого потом:** переход к блокировке на рынок требует разделить `LedgerBook` (баланс — межрыночная сущность) на «резерв ставки» и «запись ставки», то есть двухфазной операции и двух событий; это меняет инварианты сохранения баланса, которые сейчас проверяются фаззингом (`the internal notes` §4, «пробы рецензентов»).
- **Сейчас:** не переписывать. Дёшево — вынести `SELECT` из-под блокировки (раздел 4, п. 3) и зафиксировать правило «под engineLock — ни БД, ни сети, кроме одного `append`» тестом (п. 8). **Подождать может:** сама замена блокировки — до первой измеренной очереди на ней.

### 3.2 Один `LedgerBook` на всех игроков и рынки — нет ключа шардирования

- **Код:** `domain/EngineHub.java:16-23`, `:76` — `final LedgerBook ledger = new LedgerBook()` в `State`; все `GameEngine` построены над ним (`:47-51`). Аудит `entries` растёт без границы в памяти (`engine/.../ledger/LedgerBook.java:41`, `:887`) и копируется целиком при чтении (`:123-125`).
- **Следствие (вывод):** память процесса растёт со всей историей движений всех игроков; при миллионах игроков один процесс не вместит ни аккаунты, ни аудит. Шардирование по игроку (баланс) и по рынку (раунд) — классическое решение, но у событий нет явного ключа партиции: `game_events` имеет только `id` (`V1__init.sql:24-29`), рынок и игрок — внутри JSONB.
- **Сейчас, дёшево:** колонки/индексы `market`, `player_id` в `game_events` (раздел 4, п. 1); интерфейс `LedgerBook.snapshot()`/`restore()` (п. 2). **Подождать может:** сам шардинг.

### 3.3 Пересборка памяти = полный replay без снимков

- **Код:** `events/EventStore.java:57-62` `SELECT … ORDER BY id ASC` всего журнала; `scheduler/EngineStateBuilder.java:82-85` применяет каждое событие; это делается на старте и при **каждом** восстановлении после сбоя расчёта (`EngineRebuildService.java:217-227`), под write-lock. Измерено (не мной): 25 мс + 5,6 мкс на событие, 510 мс при 91 тыс. (`docs/research/markets_architecture.md` §4 п.13). Известно и не закрыто: при сбое каждой серии движок пересобирается раз за шаг отсрочки (там же).
- **Следствие (вывод):** время старта и недоступность при восстановлении растут линейно с историей; при 10⁸ событий — минуты под глобальной блокировкой.
- **Сейчас, дёшево:** интерфейс снимка (раздел 4, п. 2) без хранилища. **Подождать может:** реализация снимков — до ~10⁶ событий (**вывод** из 5,6 мкс/событие: ~6 с replay).

### 3.4 Один `seq` оракула — одна строка под `FOR UPDATE` в каждой транзакции расчёта

- **Код:** `persistence/ServerMetaRepository.java:83-97`; вызывается из `GameService.java:1061` внутри транзакции расчёта. Поток `seq` один на все рынки (`scheduler/OraclePublisherService.java:48-50`); запись уже несёт `asset` (индекс рынка) (`docs/research/markets_architecture.md` §7 п.1).
- **Следствие:** расчёты всех рынков сериализуются на одной строке. Сегодня это 36 расчётов на 3 минуты — незаметно. При шардировании по рынкам — глобальная точка.
- **Подождать может.** Готовый выход потом: один синглтон-оракул на группу рынков (ключ — `asset`, уже в формате записи); верификатор читает `asset` (§7 п.7).

### 3.5 `SELECT` сессии на каждый RPC-вызов при опросе каждые 2 с

- **Код:** `api/RpcResource.java:107` → `SessionService.java:36` → `SessionRepository.java:24-29`, для **всех** методов, включая публичные `markets`/`priceSeries`, если у клиента есть cookie. Клиент шлёт 3–4 опроса каждые 2 с (`useMarkets.ts:15`, `usePriceSeries.ts:18`, `useMyPositions.ts:15`, `PairRoomPage.tsx:82`).
- **Следствие (вывод):** при N открытых вкладок ≈ 1,5–2 запроса/с на вкладку и столько же `SELECT` в Postgres — БД станет узким местом раньше движка.
- **Сейчас, дёшево:** кэш сессий (раздел 4, п. 4). Потом: подписанный токен без обращения к БД; push-снимки вместо опроса (`markets` уже собирается раз в секунду — `MarketViewService.java:57`).

### 3.6 Состояние одного процесса: rate limit, кэши, боты, индексы окон

- `api/RateLimiter.java:31` — ведро на IP в памяти процесса; `MarketViewService.marketsCache` (`:73`); `BotService.shots/planned` (`bots/BotService.java:87-88`). За балансировщиком всё это разъезжается. **Подождать может** — пока один процесс.

### 3.7 Цепь: один спенд в полёте, ≤40 записей на спенд

- `oracle/publisher.rs:9-21`, `spend.rs:50`. Ограничение задано блокчейном и уже спроектировано правильно — одна запись на раунд рынка, не на ставку. Масштаб числа рынков упрётся сюда (при 18 рынках — ~10 тыс. записей/сутки, `the internal notes` §6.1 п.1), ставок — нет. **Не трогать.**

### 3.8 Что к масштабу не относится, но стоит рядом

- `GameService.java` — 2 500 строк, 25 `@Inject` (`:144-193`); один класс соединяет ставки, расчёт, выводы, депозиты, ботов, реконсиляцию. Разрезать можно позже без изменения поведения; для модуля ботов важно, что `BettingPort` в адаптере должен касаться ровно одного метода — `placeBet`.

---

## 4. Дешёвые шаги фундамента сейчас, без переписывания

Ранжировано по ценности/усилию. Каждый — локальное изменение, совместимое с текущим журналом (`schema_version` остаётся 1).

| # | Шаг | Где | Зачем | Усилие |
|---|---|---|---|---|
| 1 | **Ключи партиции в журнале.** Миграция V13: колонки `market TEXT`, `player_id TEXT` в `game_events` (заполняются при `append` из события; для старых строк — одноразовый `UPDATE … SET market = payload->>'market'`), индексы `(market, id)`, `(player_id, id)` | `events/EventStore.java:50-54`, `db/migration/` | Будущий replay по рынку или по игроку и перенос истории на шард — без разбора всего JSONB. Ничего в поведении не меняет | малое |
| 2 | **Интерфейс снимка движка.** `LedgerBook.snapshot()`/`LedgerBook.restore(Snapshot)` и то же для `BetBook`/`CrowdAccount`/`ComparisonAccounts` — чистые immutable-записи; тест «snapshot → restore → те же ответы, что replay». Хранилище и `last_event_id` — потом | `engine/.../ledger/LedgerBook.java:40-76`, `domain/EngineHub.java:75-79` | Снимает §3.3 на уровне интерфейса; реализация таблицы `engine_snapshots` — когда понадобится | среднее (сериализовать ~15 полей `LedgerBook`) |
| 3 | **Убрать `SELECT round_results` из-под write-lock в `placeBet`.** Отмены «наперёд» уже известны в памяти (`RoundCancellations`/`BetWindows`): вести множество отменённых окон впереди указателя в памяти и спрашивать его; либо делать `exists` до `lockWrite()` и повторять только при сомнении | `service/GameService.java:753-758` | Под блокировкой остаётся ровно один `INSERT` — это и есть граница, которую потом заменит групповой commit | малое |
| 4 | **Кэш сессий.** `SessionService.playerIdFor` — `ConcurrentHashMap<sessionId, (playerId, expiresAt)>` с TTL ≤ 60 с; `destroy` инвалидирует. Строка сессии неизменяема до истечения (`V1__init.sql:97-102`) | `auth/SessionService.java:32-39` | Убирает `SELECT` на каждый опрос. Один метод — единственная точка входа, потом сюда же встанет stateless-токен | малое |
| 5 | **Публичный RPC `candles(marketId, "1m", limit)`** — минутные свечи из `TickRing` (агрегация в сервере, read-модель без движка, как `priceSeries`) | `service/PriceSeriesService.java`, `prices/TickStore.java`, `contract.ts` | Нужен модулю ботов (раздел 5.3) и графику; делает HTTP-адаптер ботов равноправным in-process; одна реализация агрегатора | малое–среднее |
| 6 | **Условия ставки в API.** В `MarketSeriesDto` уже есть `maxLeverage`, `herdFeeBp`, `bettingClosesAtMs` (`contract.ts:766-786`); добавить `minStake` и ступени `EconomyRules` (границы 250/500/1200 и делители — `EconomyRules.java:34-36`) в `serverInfo` или `MarketDto` | `dto/`, `contract.ts`, `service/ServerInfoService.java` | Внешний клиент (бот, SDK) считает допустимое плечо сам, без 400 «методом тыка» | малое |
| 7 | **Идентификация ставки от клиента.** `PlaceBetRequest` (`dto/Betting.java:38`) — без идентификатора запроса; добавить необязательный `clientRequestId` и класть его в `BetPlaced` (поле `@JsonInclude(NON_NULL)` по образцу `feeHerdMojos`) | `events/GameEvent.java:68-73`, `GameService.java:828-831` | Трассировка и корреляция ставок внешних ботов/нагрузочного генератора с журналом; семантика «одна ставка на раунд» уже идемпотентна (`BetBook.java:19-24`) | малое |
| 8 | **Правило «под `engineLock` — ни БД, ни сети» как тест.** ArchUnit-тест (или простой байткод-грепп) на `GameService`: методы, вызываемые между `lockWrite()`/`lockRead()` и `unlock`, не вызывают `Db.read`, `ChainSvcClient`, `MarketPrices.resolve`. Исключение — один `append` | `server/src/test` | Фиксирует дисциплину, которую модуль ботов и будущие правки не должны нарушить; сегодня нарушения: `:756`, чтения в `roundsPair()`/`home()` (`the internal notes` §6.2) | малое |
| 9 | **Отдельный интерфейс `LedgerPort` между `GameEngine` и `LedgerBook`.** `GameEngine` вызывает у леджера 4 операции: `reserveStake`, `refundStake`, `refundFees`, `settleRound` (`engine/.../game/GameEngine.java:149`, `:170-173`, `:226`), плюс `payoutCapTreasury` (`:221`). Выделить их в интерфейс, `LedgerBook` его реализует | `engine/.../game/GameEngine.java`, `engine/.../ledger/` | Готовый шов для будущего «баланс на другом шарде/в другом процессе» без правки движка раундов | малое |
| 10 | **Метрики под блокировкой.** Счётчик и гистограмма времени удержания `engineLock` write/read, число ожидающих; `game_events` в секунду; экспорт через `/q/metrics` (Micrometer, у Quarkus есть расширение) | `GameService.java:260-277` | Чтобы решение «когда менять блокировку» принималось по измерению, не по ощущению. Нагрузочный тест — `tools/e2e` уже умеет играть N игроков | малое |

Не делать сейчас: второй экземпляр `server`, блокировку на рынок, снимки в БД, push-транспорт, вынос ботов в отдельный сервис — всё это становится дешёвым после шагов 1–9 и бессмысленно без измерения (шаг 10).

---

## 5. Ревью `docs/research/bots_module_design.md`

### 5.1 Что верно

- **Чистый «мозг»** (§2 п.1): вход — рынок, состояние бота, условия раунда, RNG с зерном; выход — намерения. Совпадает с тем, как устроен `engine` (чистые функции, тесты без фреймворка) и не трогает горячий путь.
- **Два порта** (§2 п.2): `MarketDataPort`, `BettingPort`; адаптер сегодня — над `GameService`, завтра — HTTP-клиент. Внешний клиент публичного API уже существует и подписывает вход своими ключами (`tools/e2e/run.mjs:12`, `:60`, `:120`), так что «бот = обычный внешний игрок» достижимо без новых серверных механизмов входа.
- **Сигнал и характер врозь** (§2 п.3) — правильная факторизация: 9 сигналов × 6 характеров без взрыва классов; источники характера — B1–B6 в `bot_behavior_sources.md`.
- **Нулевые зависимости, свои индикаторы** (§2 п.4), **O(1) на тик, никакой работы под блокировкой** (п.5), **мягкая деградация** (п.6), **бэктест и математик до подключения** (§6, Р-91/Р-92 — `the decision log:97-98`) — всё верно.
- Каталог 24 ботов с детерминированным составом (§3 `catalog`) ложится на существующие колонки `players.is_bot`, `bot_strategy` (`V1__init.sql:13-20`) и на респаун (`bots/BotService.java:93-128`).

### 5.2 Что пропущено

1. **Третий вход — состояние бота.** §2 п.1 перечисляет «состояние бота (баланс, прошлый итог, серия)», а §3 `port` знает только `MarketDataPort` и `BettingPort`. In-process это `LedgerBook.balance` и `round_results`/`player_round_history`; по HTTP — `myAccount`, `myProfile` или `unseenResults` (`contract.ts:1211-1241`). Нужен либо `BettingPort.state(botId) → BotState`, либо отдельный `BotStatePort`. Без него характеры B5/B6 (риск после выигрыша/проигрыша) в HTTP-режиме не реализуемы.
2. **Порядок внутри `BotBrain`.** §3 `brain`: «выбор рынков → сигнал → характер». Но §5: «Выбор рынка: чаще — рынки с резким движением за последние минуты» — выбор зависит от сигналов/движения **всех** кандидатов. Правильный порядок: `MarketView` для всех активных рынков → характер выбирает рынки (по движению и своей частоте) → сигнал по выбранным → размер/плечо. Зафиксировать явно, иначе появятся два разных «выбора рынка».
3. **Зерно RNG.** «Генератор с заданным зерном» как долгоживущий объект теряет состояние при рестарте и расходится между in-process и внешним запуском. Детерминизм даёт `seed = hash(botSeed, marketId, roundId)` — новый `Random` на решение; тогда повтор любого решения воспроизводим из журнала без состояния модуля.
4. **Прогрев после рестарта — конкретное ограничение, не «мягкая деградация».** Буфер тиков сервера — 2×DAILY + 10 мин = 40 мин при 15-минутном DAILY (`prices/PricesConfig.java:455-457`; восстанавливается из `price_snapshots`, `TickStore.java:17-24`); история из источника сидируется на 15 минут (`prices/HistorySeeder.java:29-33`). Значит in-process у бота после рестарта ≤40 минутных свечей, а по HTTP через `priceSeries` — 15 минут и ≤600 точек (`PriceSeriesService.java:49-51`). Donchian(55), MACD(5,35,5), EMA(12,26)+MACD(9) ≈ 35 не прогреются. Либо снизить N, либо ввести серверный `candles` с собственной глубиной (раздел 4, п. 5), сидируемый из публичных свечей бирж (у Hyperliquid, Coinbase, Kraken история в источниках есть: `HistorySeeder.java:29`, `:47`, `CoinbasePriceSource.java:374-375`).
5. **Метка BOT для внешнего бота.** Сегодня `is_bot` ставит только `createBotPlayer` (`GameService.java:451-470`); внешний бот, вошедший подписью, будет человеком в ленте ставок и рейтингах. Нужен операторский путь «пометить игрока как бота» или список ключей ботов — решить до HTTP-адаптера. Иначе честность «BOT видим» (Р-46) нарушается.
6. **Данные бэктеста.** §6 п.1: «сохранены при расчёте экономики». В `game_economy_math.md:3` скрипты и данные лежат в `<tmp>` — временный каталог; сохранность UNVERIFIED. Первичный путь — публичный API свечей (Coinbase candles, Hyperliquid `candleSnapshot`), скрипт загрузки — часть модуля (`backtest/`), с указанием даты и диапазона в выводе.
7. **Что считает бэктест.** «Угадано / не угадано / без сигнала» не даёт математику (§6 п.2) ответа «сколько казна отдаёт ботам»: итог ставки — P&L с плечом, комиссиями и ликвидацией по пути (`engine/.../game/GameEngine.java:200-223`). Бэктест должен прогонять каждое намерение через `PositionSimulator`/`LiquidationCalculator` и `EconomyRules.fees` на реальных свечах окна — это и есть «проверка на историческом P&L», которую Р-91 требует.
8. **Один бот — одна ставка на раунд рынка** уже обеспечивает `BetBook` (замена, `BetBook.java:19-24`); но `BotService` стреляет в случайный момент окна (`:210-213`). При HTTP-режиме намерения должны нести `fireAtMs`, а адаптер — планировать отправку; иначе «характер» теряет частоту/время.

### 5.3 Что изменить до кода

1. **Порты — в терминах публичного API, оба адаптера сразу.** `MarketDataPort` отдаёт `MarketView` из полей `MarketDto`/`MarketSeriesDto` (`id`, `kind`, `maxLeverage`, `bettingRoundId`, `bettingClosesAtMs`, `majorityDirection`, `upStake/downStake` — `contract.ts:766-786`, `:802-816`) плюс свечи; `BettingPort.place(BetIntent)` → то, что возвращает `placeBet` (`BetDto`, включая `maxLeverageForStake` — `:556`). In-process адаптер и HTTP-адаптер пишутся в одном PR с одним контрактным тестом на оба. Так модуль действительно «не знает» сервера, а не «не знает до первого рефакторинга».
2. **Свечи — read-модель сервера, не часть модуля.** `BarAggregator` живёт в `server/prices` (там уже `TickRing`, `GapBridge`, история источников) и отдаётся RPC `candles`; модуль получает `BarSeries` готовой через порт. Иначе HTTP-бот и in-process-бот видят разные свечи.
3. **Зависимость от `engine` — разрешить.** `engine` без runtime-зависимостей (`engine/pom.xml:36-37`); `EconomyRules.maxLeverage/fees` (`EconomyRules.java:53-57`, `:83-91`), `PositionSimulator`, `Candle`/`CandleSeries` (`engine/.../market/CandleSeries.java:18-34` уже читает CSV свечей) — нужны и мозгу, и бэктесту. Дублировать `LeverageLimit` — второй источник правды для правила, которое пересчитывается еженедельно (`V11`). Принцип «нулевые зависимости» переформулировать: «только `engine` и JDK».
4. **`BotState` и третий порт** — п. 5.2(1).
5. **Зерно от `(botId, marketId, roundId)`** — п. 5.2(3).
6. **Бэктест = P&L через `engine`** и загрузчик свечей из публичных API — п. 5.2(6–7). Выход — таблица на вариант × горизонт: доля сигналов, P(угадал), средний P&L на ставку с комиссией, P(ликвидации), перевод казна→боты в сутки при заданном характере. Это то, что смотрит математик.
7. **Каталог как данные.** `BotCatalog` — JSON/YAML (id, стратегия, параметры, характер, зерно), валидируемый тестом; `players.bot_strategy` хранит `catalogId`. Тогда внешний запуск и серверный читают один файл, и «24 вместо 20» — правка данных, не кода.
8. **Переход 20 → 24 и респаун.** `BotService.ensureBotsExist` раздаёт стратегии по модулю от числа ботов (`:110`) — заменить на обход каталога; респаун берёт `bot_strategy` погибшего (`:120`) — совместимо, если `catalogId` стабилен.
9. **Что убрать как избыточное для первого шага:** `Macd` с тремя вариантами при `EmaCross` с тремя — пересекаются по смыслу (знак гистограммы MACD ≈ разность EMA); хватит одного семейства на первый прогон, второе — после бэктеста. §C источника (копирование трейдеров OKX/Hyperliquid) в модуль не брать — там по тексту самого документа сигнал «настроение», не предсказание, и зависимость от неофициальных эндпойнтов.

---

## 6. Rust и Java: разумно ли разделение

- **Факт.** Rust (`chain/c2-svc`) — только граница с цепью: верификация подписи входа, оракул, выводы/депозиты, ключи и durable-очереди (`app.rs:86-99`, `main.rs:85-102`); к игре обращается только сервер по HTTP с токеном. Java (`engine`, `server`) — всё остальное, включая горячий путь ставки. На пути ставки Rust нет (`GameService.java:728-839`).
- **Оценка.** Для цели «миллионы ставок, мало ресурсов, большая скорость» это разделение верное: то, что ограничено блокчейном (один спенд в полёте, ≤40 записей, время блока), вынесено в свой процесс и не связывает игру; то, что ограничено нашим кодом, — в одном языке с ядром и тестами. Пропускная способность игры упирается в §3.1–§3.3, а не в JVM: Quarkus + plain JDBC (`persistence/Db.java:15-20`) + память — адекватная база; переписывание горячего пути на Rust сейчас ничего не дало бы, пока блокировка одна и commit синхронный.
- **Если когда-нибудь** ядро матчинга понадобится в Rust (латентность, память на игрока), перенос облегчён: ядро чистое, события — JSON с явными полями (`GameEvent.java:68-73`), `EventApplier` — один путь; граница переноса — `EventApplier`/`LedgerPort` (раздел 4, п. 9). Это не рекомендация, а констатация, что фундамент такой перенос допускает.
- **Один риск размещения:** `chainsvc` хранит состояние в файлах одного хоста (`docker-compose.yml:124`, `queue.rs:1-20`) и правило «один издатель» (`the internal notes` §2, 2026-10-08) — это правильно для синглтона, но значит, что `chainsvc` не масштабируется горизонтально по определению; масштаб числа рынков потребует несколько синглтонов (по `asset`), не несколько экземпляров сервиса. Не блокер.

---

## Приложение: не проверено

- Поведение под нагрузкой — не измерялось никем (`the internal notes` §4 — только функциональные прогоны и фаззинг инвариантов).
- Сохранность данных свечей из расчёта экономики в scratchpad (`game_economy_math.md:3`).
- Есть ли у `CoinbasePriceSource` история глубже 15 минут через `historyOf` — читал только `HistoryKind` (`CoinbasePriceSource.java:374-375`), не реализацию.
- Чтения из БД под read-lock в `roundsPair()`/`home()` — по `the internal notes` §6.2, в коде проверил только `placeBet`/`resolveRound`/`requestWithdrawal`/`reconciliation`.
- Размер `LedgerBook.entries` на VPS сегодня — не смотрел (стенд не трогал).
