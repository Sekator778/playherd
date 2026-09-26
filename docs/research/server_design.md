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
| `dto` | Java-записи 1:1 из `web/src/api/contract.ts` (Rounds/Ledger/Crowd/Ratings/Home/Auth/Identity/Betting/ServerInfo/Paging/Enums) — сгруппированы во вложенные `record` внутри нескольких файлов, а не по файлу на тип, ради скорости; на JSON это не влияет (Jackson сериализует по именам компонентов записи, не по имени Java-класса) |
| `domain` | Живое состояние процесса: `EngineHub` (общий `LedgerBook`+`CrowdAccount`, два `GameEngine` — HOURLY/DAILY), `ComparisonAccounts` («держать BTC»/«анти-толпа», Р-26), `PlayerDirectory` (кэш игроков), `RoundCalendar` (origin-эпоха), `RoundProgress` (курсор «следующий раунд к резолву» на серию) |
| `events` | Event sourcing: `GameEvent` (замкнутый интерфейс с записями-вариантами), `EventStore` (append-only `game_events`), `EventApplier` (**единственный** код, мутирующий `EngineHub` — используется и живым трафиком, и полным реплеем при рестарте), `StoredRoundPrices` |
| `persistence` | `Db` (тонкая JDBC-обвязка поверх Agroal `DataSource`, ручные транзакции, JSONB через Jackson), плюс по репозиторию на read-model таблицу |
| `replay` | `CandleLoader` (парсер CSV, не завязан на `engine.market.CandleSeries`/`Interval` — см. §4), `ReplayRoundPrices` |
| `live` | `HyperliquidClient` (generic `/info` REST-клиент), `LivePriceSampler` (тики каждые 2с), `LiveRoundPrices` (+ Р-23 fallback на `candleSnapshot`) |
| `price` | `PriceService` — выбор replay/live реализации `RoundPrices`, текущая «референсная» цена |
| `chain` | `ChainSvcClient` (мокируемый CDI-интерфейс) + `ChainSvcRestClient` (сырой MP REST Client, FIXED INTERFACE 3 дословно) + `ChainSvcClientImpl` |
| `auth` | `AuthService` (challenge/verify, Р-4/Р-45), `SessionService` (`herd_session`) |
| `bots` | `BotStrategy` (5 стратегий), `BotService` (Р-46) |
| `scheduler` | `RoundSchedulerService` (главный тик), `OraclePublisherService` (Р-40/41/47), `EngineRebuildService` (рестарт) |
| `service` | `GameService` — единственный оркестратор всех мутирующих операций Api + большинства чтений |
| `api` | `RpcResource` (`POST /api/rpc/{method}`), `AdminResource` (Р-43), `ResultsJsonResource` (`GET /api/rounds/{id}/results.json`), `ApiExceptionMapper` |
| `config` | `GameConfig`, `GameMode` |

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

### 4.2 Live

`LivePriceSampler` каждые 2с читает `POST /info {"type":"metaAndAssetCtxs"}` (тот же
live-эндпоинт из `docs/research/hl_liquidation_math.md` §4/§8.10) и берёт `markPx` по имени
`"BTC"` (не по индексу). `LiveRoundPrices` строит вырожденные свечи (open=high=low=close=цена)
из буфера тиков за окно раунда; если тиков нет вообби (например, только что стартовал сервер) —
разовый fallback на `candleSnapshot` минутной свечи вокруг нужного момента (Р-23). Если и это не
даёт цены — `RoundSchedulerService` не резолвит раунд, а на каждом тике пробует снова, и только
спустя **1 час** после `window.endMs()` вызывает `GameService.cancelRound` с причиной (Р-23:
«если за 1 час цену получить не удалось — раунд отменяется целиком»). Буфер тиков — только в
памяти (не персистится) — предложение-упрощение: реплей-режим — основной протестированный путь
для локального стенда (Р-46), live не покрыт интеграционным тестом отдельно (юнит-покрытие —
только чистая логика `ReplayRoundPrices`).

## 5. Планировщик и «текущий раунд» (Р-19)

`RoundProgress` хранит явный курсор «следующее окно к резолву» на серию — **не** пересчитывается
наивным `RoundClock.windowContaining(interval, now, …)` на каждое чтение: ровно в момент границы
это дало бы гонку с планировщиком, который ещё не успел резолвить истёкший раунд. Вместо этого:

```
while (now >= next(interval).endMs()) {
    if (round_results.exists(next.roundId())) { advance(); continue; }   // уже отменён оператором заранее
    try { resolveRound(next, now); }
    catch (IllegalStateException e) {                                     // live: нет цены
        if (now >= next.endMs() + 1h) cancelRound(next, "no price…", now);
        else return;                                                      // ждём следующего тика
    }
    advance();
}
```

Это же естественно «догоняет» пропущенные раунды после рестарта/паузы — каждый шаг цикла
детерминированно резолвит один раунд по его собственному `window`, независимо от того, сколько
реального времени прошло.

**Оператор** (Р-32/Р-43) может отменить **текущий running ИЛИ текущий betting** раунд;
`GameService.cancelRound` не трогает `RoundProgress` сам — если это был `next(interval)`,
планировщик на следующем тике увидит уже существующую запись в `round_results` и просто
шагнёт дальше (см. ветку "already resolved" выше) — так что отмена заранее открытого-для-ставок
раунда и отмена уже идущего работают одним и тем же кодом.

**«RUNNING» — предложение**, синтезируется на лету (`GameService.buildRunningDto`), не хранится
как состояние движка (сам `round_engine_design.md` §2 это явно оговаривает: `GameEngine.
runRound` резолвит атомарно, отдельного состояния «идёт» в модели нет). Показ раскрытых позиций
во время RUNNING считается **без мутации** `LedgerBook`/`CrowdAccount` — по частичному пути цены
от начала окна до «сейчас» через `PositionSimulator`/`LiquidationCalculator` напрямую;
настоящее списание/начисление происходит только в момент реального резолва.

## 6. Боты (Р-46)

`BotService` держит 20 (config `herd.bots.count`) серверных игроков (`is_bot=true`),
по 4 на 5 стратегий (`BotStrategy` — предложение, конкретные названия/параметры не из
источников): всегда максимальное плечо, контрарианец (против прошлого большинства раунда серии),
за большинством, случайный, осторожный (низкое плечо). На каждый новый betting-раунд каждому боту
назначается случайный момент внутри окна (не позже 90% его длины); ставка идёт через тот же
`GameService.placeBet`, что и у игрока-человека — те же проверки (минимальная ставка, плечо
2..40, одна ставка на раунд).

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
