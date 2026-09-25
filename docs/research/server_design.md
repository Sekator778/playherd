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
