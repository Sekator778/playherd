# chain/c2-svc — HTTP-сервис `chainsvc` (FIXED INTERFACE 3)

Дата первой версии — 2026-09-25. Реализует ровно контракт «Server ⇄ chain service» из задания
недели 2 («FIXED INTERFACE (3)»): `POST /v1/auth/verify`, `POST /v1/oracle/records`,
`GET /v1/oracle/status`, `GET /health`, порт 8090. Код — `chain/c2-svc/`. Факты о ключевых
Chia-примитивах (деривация ключей, схема подписи, минимальный оракул-синглтон) взяты из уже
существующих в проекте research-файлов недели 1 и не пересматриваются здесь заново; ниже —
только то, что специфично для этого сервиса, плюс факты, проверенные заново сегодня (живые
`curl`-запросы к testnet11 и к самому Chia-стеку через локальный кеш crates.io).

**Обновление того же дня (2026-09-25, позже)** — §9 ниже: продакшн-инцидент на живом стенде
(зависший in-flight спенд оракул-паблишера, `INVALID_FEE_TOO_CLOSE_TO_ZERO`) и его исправление
(«rebuild with higher fee»). Все факты в §9 проверены живьём на реальном testnet11 в рамках этого
прохода, отдельно от фактов §1–8.

## 0. Итог одной строкой

`cargo test --workspace` в `chain/` (включая `sim-spike` и уже существующий `c2-cli`) —
**68 passed, 1 ignored, 0 failed** (было 44/1/0 до §10; +24 теста на неделю 3: Bearer-auth,
durable-delivery backfill и root-cause регрессию, см. §10.5). `chain/c2-cli` не изменён ни одной
строкой ни в первой версии, ни в фиксах §9/§10 — `c2-svc` подключает его как обычную
path-зависимость (`c2-cli = { path = "../c2-cli" }`) и
переиспользует его типы напрямую.

## 1. Почему `c2-cli` не тронут, и что именно из него переиспользуется

Задание допускало рефакторинг `c2-cli` в lib+bin ради общего кода — но `c2-cli` **уже** так
устроен (`chain/c2-cli/src/lib.rs` экспортирует `builders`, `chain`, `cost`, `keys`, `keystore`,
`sign`, `spend_json`, `state` как публичные модули, `main.rs` — тонкая CLI-обвязка поверх них,
см. `chain/README.md`). Поэтому весь переиспользуемый код уже был открыт как публичный API
библиотеки `c2_cli`, и `c2-svc` подключает его напрямую как path-зависимость, не трогая ни
одного файла `c2-cli` — это единственный безопасный способ гарантированно «keep its CLI
behaviour and tests green» (реально проверено: `cargo test -p c2-cli` после появления `c2-svc`
в workspace даёт те же 22 прохождения, что и раньше).

Из `c2_cli` переиспользуется:

- `keys::{WalletKey, derive_wallet_key, master_sk_from_mnemonic, address_of, TXCH_PREFIX}` —
  та же деривация BIP-39 → мастер-ключ → синтетический ключ → `txch1...`-адрес, что и у `c2`
  (`chain/c2-cli/src/keys.rs`, там же — полная цепочка источников на каждый шаг).
- `sign::sign_transaction` — тот же примитив подписи спенда (`AGG_SIG_ME`, testnet11-константы).
- `keystore::KeyFile` — формат `{mnemonic, created_utc, network}`, тот же, что пишет
  `c2 keygen`. `c2-svc` читает его из своего `C2_DATA_DIR` вместо хардкода `~/.c2/testnet11`
  (`chain/c2-svc/src/keys_file.rs`) — реализация чтения не копирует `keystore.rs`, а просто
  десериализует тот же публичный тип по другому пути.
- `state::{OracleState, ProofDto}` — тот же формат `<name>.oracle.json`, что пишет
  `c2 oracle-launch`/`c2 oracle-publish`. Проверено живьём: реальный файл на этом стенде,
  `~/.c2/testnet11/operator.oracle.json` (запущен в неделю 1, launcher id
  `0xc5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20`,
  `docs/research/week1_testnet_results.md`), сериализуется ровно в эту Rust-структуру
  (`chain/c2-svc/src/state_file.rs` читает/пишет по произвольному `data_dir` тем же типом).
  Сегодняшний `get_coin_record_by_name` (coinset.org testnet11) подтверждает: текущая монета
  синглтона из этого файла (`next_sequence: 2`) реально подтверждена в блоке **4 735 705** и до
  сих пор не потрачена (`spent: false`) — то есть локальное состояние на стенде и цепочка
  согласованы прямо сейчас.
- `builders::oracle::{SINGLETON_AMOUNT, build_oracle_launch}` и `builders::fee::build_fee_only` —
  использованы как есть (launch — только в тестах-фикстурах `c2-svc`, fee-only coin — в
  публикующем воркере, см. §3).
- `cost::{SIMULATOR_MAX_COST, measure_bundle_cost}` — то же измерение CLVM-стоимости.
- `chain::{ADDRESS_GAP, find_funding_coin, balance}` — та же логика подбора монеты по gap-скану
  адресов, вызывается через обёртку `ChainClient` (§5).

Единственный новый Chia-специфичный код — `chain/c2-svc/src/oracle/spend.rs`
(`build_oracle_publish_batch`): расширение того же паттерна (`SingletonLayer` +
пересоздание + `CREATE_PUZZLE_ANNOUNCEMENT`) на **несколько** записей в одном спенде, чего в
`c2-cli` нет (там — ровно один `(price, timestamp, sequence)` на спенд).

## 2. `POST /v1/auth/verify`

Реализует схему из `docs/research/week1_wallet_auth.md` §2–4 (решение Р-4/Р-45): Sage
подписывает `sha256tree(("Chia Signed Message" . message))` синтетическим ключом схемой
AugSchemeMPL; `chia_signMessageByAddress` отдаёт именно **синтетический** публичный ключ (тот
же, которым куррится стандартный p2-пазл, `chia_puzzle_types::standard::StandardArgs::
curry_tree_hash`) — поэтому сервис не делает никакой дополнительной синтетической деривации
над присланным `publicKey`, а сразу проверяет, что его p2-пазл-хеш совпадает с адресом.

Реализация (`chain/c2-svc/src/auth.rs`) использует ровно те примитивы, что и сам `chia-bls`
(тот же крейт и версия, которой пользуется Sage, `chia-bls = "=0.36.1"`, пин совпадает с
`c2-cli`): `clvm_utils::{tree_hash_atom, tree_hash_pair}` для sha256tree-конструкции и
`chia_bls::verify` (фиксированный DST `BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_AUG_`,
Augmented-схема) — нулевой риск разойтись с оригиналом, тот же принцип «вариант A», который
`week1_wallet_auth.md` §3 рекомендует.

**Собственное решение сервиса (предложение, не из источника)**: поле `message` в этом HTTP-
запросе всегда хешируется как сырые UTF-8-байты, без попытки воспроизвести на сервере
Sage-эвристику «весь текст — hex → раскодировать как hex» (`week1_wallet_auth.md` §2, «ловушка»
`parse_signature_message`). Ответственность за то, чтобы challenge-сообщение никогда не
состояло целиком из hex-цифр, лежит на компоненте, который его формирует (веб-сервер, не эта
задача) — так обе стороны (Sage при подписи и этот сервис при проверке) всегда согласны насчёт
байтов. Тест `all_hex_message_is_hashed_as_utf8_not_decoded`
(`chain/c2-svc/src/auth.rs`) и одноимённый тест-вектор фиксируют это поведение явно.

### Тест-векторы

`chain/c2-svc/tests/vectors/auth_vectors.json` — 5 векторов, сгенерированы детерминированно из
фиксированных seed'ов (`sha256(label)`, 32 байта → `chia_bls::SecretKey::from_seed`, EIP-2333,
тот же примитив, что и деривация мастер-ключа из мнемоники) генератором
`chain/c2-svc/tests/gen_auth_vectors.rs` (запуск:
`cargo test -p c2-svc --test gen_auth_vectors -- --ignored`; детерминирован, перезапуск даёт
байт-в-байт тот же файл). Ровно 5 кейсов из задания:

| case | что проверяет |
|---|---|
| `positive` | корректная четвёрка (address, publicKey, message, signature) → `valid: true` |
| `wrong_address` | тот же publicKey/message/signature, адрес — от другого кошелька → `valid: false` |
| `wrong_message` | сообщение изменено после подписи → `valid: false` |
| `tampered_signature` | один бит подписи испорчен → `valid: false` |
| `all_hex_message` | сообщение целиком из hex-цифр, подписано и проверено как UTF-8 с обеих сторон → `valid: true` |

`chain/c2-svc/tests/auth_verify.rs` прогоняет все пять через `c2_svc::auth::verify` (ту же
функцию, что дёргает HTTP-хендлер) и дополнительно проверяет чувствительность к каждому полю
по отдельности и что некорректный hex не паникует, а возвращает `valid: false` с причиной.

## 3. `POST /v1/oracle/records` / `GET /v1/oracle/status` — мультизаписный оракул-паблишер

### 3.1 Формат записи и announcement-сообщения

Ровно по заданию: запись `{seq, ts, priceCents, resultsHash, source, sourceTs}`
(`chain/c2-svc/src/oracle/types.rs::OracleRecordDto`), announcement на запись —
`CREATE_PUZZLE_ANNOUNCEMENT` с CLVM-списком `(seq ts priceCents resultsHash sourceFlag
sourceTs)` (`sourceFlag` 0=live, 1=replay), до 20 записей на один спенд синглтона
(`chain/c2-svc/src/oracle/spend.rs::build_oracle_publish_batch`,
`MAX_RECORDS_PER_SPEND = 20`). CLVM-список собран `clvm_traits::clvm_list!` — тот же макрос,
что раскрывается в цепочку вложенных пар и опирается на встроенные `ToClvm`-реализации
`i64`/`Bytes32` (`clvm-traits`/`chia-protocol`, те же пины, что у `c2-cli`), а не на
самодельную (де)сериализацию.

Симулятор-тест `chain/c2-svc/tests/oracle_multi_announce.rs` проверяет ровно то, что просило
задание: один спенд с тремя записями, три независимых consumer-монеты, каждая
`ASSERT_PUZZLE_ANNOUNCEMENT`-ит **свою** запись из **того же** спенда (позитивный кейс) плюс
негативный (announcement, которого не было в батче, отклоняется симулятором с
`AssertPuzzleAnnouncementFailed` — тот же failure mode, что и у одиночного билдера в
`chain/sim-spike/tests/oracle_singleton.rs`).

### 3.2 Надёжная очередь: «queue records durably while a spend is in flight»

`chain/c2-svc/src/oracle/queue.rs` — файл `<data_dir>/<name>.oracle_queue.json`, пишется
атомарно (temp-файл в той же директории + `fsync` + `rename`,
`chain/c2-svc/src/atomic_file.rs` — `rename` в пределах одной файловой системы атомарен и на
Linux, и на macOS). Два поля: `pending` (записи, ещё не вошедшие ни в один спенд) и `in_flight`
(**не больше одного** одновременно) — с полностью подписанным `SpendBundle` внутри, чтобы при
рестарте процесса не пересобирать спенд заново (пересборка могла бы выбрать другую fee-монету
из изменившегося набора UTXO), а просто повторить `push_tx` над теми же байтами.

Оркестрация — `chain/c2-svc/src/oracle/publisher.rs::Publisher::tick`, один вызов которого
делает ровно одно из (по приоритету): (1) если `in_flight` подтвердился на цепи — закрыть его,
продвинуть `operator.oracle.json`; (2) если не подтвердился — может быть, повторить `push_tx`
(бэкофф, ниже); (3) если `in_flight` пуст и есть `pending` — собрать, подписать, **сохранить
durable-очередь ДО broadcast**, потом `push_tx`; (4) иначе — простой. Фоновый воркер
(`chain/c2-svc/src/main.rs`) дёргает `tick()` по таймеру (10 с, предложение) — POST-хендлер сам
только кладёт записи в очередь и не блокируется на сети.

### 3.3 «Never double-spend the singleton»

Два независимых барьера: (а) новый спенд стартует только когда `in_flight == None` — то есть
пока предыдущий не подтверждён/не расчищен, второй просто не строится; (б) прямо перед стартом
нового спенда сервис спрашивает coinset о текущей `current_coin` из `operator.oracle.json` — и
если она уже помечена потраченной без соответствующего `in_flight` в локальном состоянии
(признак рассинхронизации после сбоя), билд нового спенда **отказывается** с ошибкой вместо
того, чтобы строить поверх устаревшего состояния (`Publisher::start_new_batch`, тест
`refuses_to_build_on_stale_spent_current_coin`).

### 3.4 «Detect confirmation via coinset» + «retries with backoff»

Подтверждение — `GET`-эквивалент `get_coin_record_by_name` для **следующей** (пересозданной)
монеты синглтона: раз её `coin_id` детерминированно зависит от `coin_id` текущей монеты +
puzzle hash + amount, её существование на цепи означает ровно то, что спенд текущей монеты
прошёл (тот же принцип, что уже использует `chain/sim-spike/tests/oracle_singleton.rs`). Сегодня
живьём проверено на реальном testnet11-состоянии стенда (`~/.c2/testnet11/operator.oracle.json`,
§1) — запрос `get_coin_record_by_name` для ненайденной монеты возвращает `{"success": false,
"error": "Coin record ... not found"}` **без** поля `coin_record`
(`chain/c2-svc/src/oracle/chain_client.rs::CoinsetChainClient::coin_record` явно полагается
только на `coin_record: Option<...>`, игнорируя `success`/`error` для этого случая — оба пути
проверены живым запросом к `https://testnet11.api.coinset.org/get_coin_record_by_name`).

Бэкофф — если `in_flight` не подтвердился и не прошло `retry_backoff` секунд с последней
попытки, тик ничего не делает; иначе — повторяет `push_tx` над тем же сохранённым бандлом,
инкрементируя счётчик попыток; по достижении `max_retry_attempts` тик перестаёт сам
ретраить и только отражает застрявший спенд в `GET /v1/oracle/status` (`inFlightBundleId`,
Р-43 — «операторский CLI + публичная страница статуса»). Оба значения — `retry_backoff` (45 с)
и `max_retry_attempts` (8) — **предложение**, конфигурация не зафиксирована ни в одном
источнике (`chain/c2-svc/src/config.rs`).

### 3.5 Комиссия: `get_fee_estimate`

`chia-sdk-coinset` версии `=0.36.0` (та же, что пинует `c2-cli`) **не оборачивает**
`get_fee_estimate` — проверено чтением исходников крейта сегодня (`chia_rpc_client.rs`, полный
список методов трейта `ChiaRpcClient` не включает `get_fee_estimate`). Реальный ответ testnet11
проверен живым `curl` сегодня (2026-09-25):

```
curl -s https://testnet11.api.coinset.org/get_fee_estimate \
  -X POST -H 'Content-Type: application/json' \
  -d '{"target_times":[60,120,300],"cost":5000000}'
→ {"current_fee_rate":0.0711332...,"estimates":[355666,355666,23184],
   "fee_rate_last_block":0.0,"fees_last_block":0,"full_node_synced":true,
   "last_block_cost":10988972070,"last_peak_timestamp":1790356642,
   "last_tx_block_height":4735850,"mempool_fees":0,"mempool_max_size":110000000000,
   "mempool_size":90058282762,"node_time_utc":1790356698,"num_spends":3103,
   "peak_height":4735852,"success":true,"target_times":[60,120,300]}
```

`estimates[i]` — уже итоговая комиссия в mojo для достижения `target_times[i]`, не курс за
единицу cost (соответствует памяти о `full_node_rpc_api.py::get_fee_estimate` в
chia-blockchain). `mempool_size ≈ 82%` от `mempool_max_size` — совпадает с наблюдением недели 1
(«testnet11's mempool was observed consistently 60-100% full», `chain/README.md`). Сервис
вызывает трейтовый метод `ChiaRpcClient::make_post_request` напрямую с этим форматом,
`target_times: [60]` (предложение — 60 с, ориентир на «подтвердиться быстро», не из источника),
и при любой ошибке (сеть, неожиданный формат ответа) откатывается на `fee = 0` с предупреждением
в лог, а не падает (`chain/c2-svc/src/oracle/chain_client.rs::CoinsetChainClient::
fee_estimate_mojos`, `Publisher::start_new_batch`).

### 3.6 Очередной unit-тест на «fake chain client»

`chain/c2-svc/tests/oracle_queue.rs` — полностью офлайновый набор (`FakeChainClient`,
in-memory, без сети и без симулятора; исходное состояние синглтона получено чистым локальным
вызовом `c2_cli::builders::oracle::build_oracle_launch`, так же, как `c2 oracle-launch`
работает полностью офлайн в dry-run): по одному тесту на каждое требование задания — батч в
один спенд + подтверждение, накопление записей пока спенд в полёте, ретраи с бэкоффом,
остановка ретраев по лимиту попыток, отказ строить спенд поверх уже потраченной монеты,
и dry-run (`C2_PUSH=0`) ничего не меняет на диске и не дёргает `push_tx`.

## 4. Формат ошибок и сессия

`chainsvc` — внутренний сервис (его дёргает только Java-сервер, никогда браузер напрямую), не
часть публичного веб-контракта FIXED INTERFACE (1) — поэтому ошибки здесь простые
`{"error": "..."}` с обычным HTTP-статусом (`chain/c2-svc/src/app.rs::AppError`), а не
SNAKE_CASE-конверт `{"error":{"code":...}}` из интерфейса (1); последний реализует сам
веб-сервер (другая задача), а не `chainsvc`.

## 5. `--dry-run` / `C2_PUSH=0`

И CLI-флаг (`c2-svc --dry-run`), и переменная окружения `C2_PUSH=0` (по умолчанию push
включён) дают одно и то же: `Publisher` собирает и подписывает спенд, логирует его, но никогда
не вызывает `push_tx` и не трогает ни `operator.oracle.json`, ни очередь (взятый в батч набор
записей кладётся обратно в `pending` тем же тиком) — тот же принцип, что уже задокументирован
для `c2-cli` («dry-run only creates nothing on chain, so caching its speculative result would
desync the cache from reality», `chain/README.md`).

## 6. Docker

`chain/c2-svc/Dockerfile` — двухстадийная сборка (`rust:1-bookworm` → `debian:bookworm-slim`),
контекст сборки — `chain/` (нужен ради path-зависимости на `c2-cli` и общего `Cargo.lock`):

```
docker build -f chain/c2-svc/Dockerfile -t c2-svc chain/
```

**Не проверено сборкой в этой задаче** — `docker build` в этой изолированной среде не имеет
сетевого доступа изнутри контейнера (`index.crates.io`/`deb.debian.org` не резолвятся из
build-контейнера, хотя у самой сессии есть выход в интернет — ограничение песочницы, не код).
Сам код собирается и проходит тесты офлайн локальным `cargo` (см. §7); Dockerfile написан по
тому же паттерну, каким собираются остальные Rust-бинарники проекта, и будет впервые реально
собран на стенде (Р-44) как часть недели 3 интеграции.

## 7. Тесты — реальные числа

```
cd chain && cargo clean -p c2-svc -p c2-cli -p sim-spike && cargo test --workspace
```

| крейт | unit | integration | всего |
|---|---|---|---|
| `c2-cli` (не изменён) | 12 | issue_play 2, offer_compress_compat 2, offer_play_xch 1, oracle_singleton 3, send 2 = 10 | 22 |
| `c2-svc` | **18** (было 1 — `auth::tests` 1, +`config::tests` 8, +`service_auth::tests` 9 в §10) | auth_verify 3, oracle_multi_announce 2, oracle_queue **18** (было 11, +7 в §10.5), gen_auth_vectors 0/1 ignored | 41 passed + 1 ignored |
| `sim-spike` (не изменён) | 0 | cat_play 1, offer_play_xch 1, oracle_singleton 2, standard_coin 1 | 5 |

**Итого: 68 passed, 1 ignored (генератор тест-векторов, не баг), 0 failed** (было 44/1/0 до §10 —
таблица здесь обновлена после недели 3: FIXED INTERFACE (a)/(b) + root-cause фикс, см. §10 —
актуальные 68/1/0 подтверждены дважды подряд в этом проходе, тёплая сборка). `cargo clippy -p
c2-svc --all-targets` после §10 даёт те же стилевые замечания, что и раньше
(`cloned_ref_to_slice_refs`, включая уже отмеченное в §9.4 `oracle/spend.rs:118`) — ноль новых
предупреждений от кода из §10 (один новый `collapsible_if` в `queue.rs::record_state` во время
работы над §10 был сразу исправлен, не попал в финальный код).

## 8. Не сделано в этой задаче (по прямой инструкции)

- Реальные транзакции на testnet11 **не отправлялись** — прямой запрет задания («Do NOT push
  real transactions in this task»). Все сетевые обращения этой сессии к coinset.org сегодня —
  только `GET`-эквиваленты (`get_network_info`, `get_coin_record_by_name`, `get_fee_estimate`),
  без побочных эффектов на цепи. **Это ограничение относится только к исходной задаче недели 2**
  — задача §9 (тот же день, позже) прямо требовала обратного: собрать образ, перезапустить
  `chainsvc` и проверить реальные push на живом стенде, что и сделано (§9.3).
- `docker build` не пройден до конца (см. §6, ограничение песочницы). **Снято в §9** —
  `docker compose -p herd up -d --build chainsvc` на реальном стенде собрал образ полностью
  (`network: host` для build-стадии уже настроен в `docker-compose.yml`, см. §9.3).
- `chainsvc` ожидается сервером (другой компонент, другая задача) по FIXED INTERFACE (3) —
  сама интеграция «сервер вызывает `/v1/auth/verify` при логине» и «сервер шлёт
  `/v1/oracle/records` на границе раунда» не входит в эту задачу. **Уже работает на стенде** к
  моменту §9 (`herd-server-1` реально шлёт записи в `chainsvc`, см. §9.1).

## 9. Инцидент «зависший in-flight спенд» и его исправление (2026-09-25, позже)

### 9.1 Проблема

На живом стенде (`compose`-проект `herd`, контейнер `herd-chainsvc-1`) паблишер оракула застрял:
бандл `0x6d8df4426c4639e213acf75fc10b3e0f0f7293ed142ab5e5744e7510379d3f9e` (реальный canonical
tx_id ноды — `0xdaa03530b19827cbe41a0e0c6d66b688cb07b1eb1091b33d0ea612cf118ab7ce`, см. §9.2 —
отображаемый `bundle_id` в `GET /v1/oracle/status` был локальным `sha256` подписи, не совпадал с
tx_id, который называет сама нода) с комиссией **3 486 230 mojo** раз за разом отклонялся
testnet11 (`push_tx` → `"Failed to include transaction daa0353..., error
INVALID_FEE_TOO_CLOSE_TO_ZERO"`; мемпул был заполнен на 88–97 % на момент диагностики,
`get_blockchain_state.mempool_cost` 97 014 169 452 / 110 000 000 000 — тот же класс перегрузки,
что уже документирован в `docs/research/week1_testnet_results.md` §5.1 для `c2 issue-play`, тот
же самый код ошибки). После `max_retry_attempts` (8) `tick()` возвращал `StuckInFlight` и логировал
`"needs operator attention"` раз в тик **бесконечно** — старая логика (`chain/c2-svc/src/oracle/
publisher.rs::advance_in_flight`, версия до этого фикса) не отличала «нода отклонила пуш, монета
свободна» от «бандл реально висит в мемпуле» и просто переставала действовать навсегда.
`pendingRecords` тем временем рос на каждый POST от `herd-server-1` (к моменту фикса — записи
seq 41…48 в `pending`, плюс seq 40 в `in_flight`).

### 9.2 Ключевой факт: `bundle_id` ≠ canonical tx_id ноды

`InFlightSpend::bundle_id` (`chain/c2-svc/src/oracle/queue.rs`) — это `sha256` одной только
агрегированной подписи, локальный, для отображения в `GET /v1/oracle/status`; он **не** совпадает
с тем, что нода/coinset называют tx_id спенда. Настоящий canonical tx_id — `chia_protocol::
SpendBundle::name()` (`self.hash()`, где `hash()` — `sha256` полной streamable-сериализации
бандла, `chia-traits-0.36.1/src/streamable.rs:69` + `chia-protocol-0.36.1/src/
spend_bundle.rs:38`). Проверено сегодня напрямую на застрявшем бандле этого стенда (временный
пример `cargo run -p c2-svc --example verify_bundle_name`, удалён после проверки):
`bundle.name() = 0xdaa03530b19827cbe41a0e0c6d66b688cb07b1eb1091b33d0ea612cf118ab7ce` — байт-в-байт
совпадает с tx_id из текста ошибки `push_tx` и с тем, что `get_mempool_item_by_tx_id` понимает как
`tx_id` (см. ниже). Это значит: `advance_in_flight`, проверяя мемпул, обязан использовать
`in_flight.bundle.name()`, а не `bundle_id`.

Живьём проверено сегодня (`https://testnet11.api.coinset.org`, до фикса):
- `get_coin_record_by_name` для монеты-синглтона, которую тратит застрявший спенд
  (`0x41bf221c1ce18b91cc29be4fde4fb5fbca671d9a824bcaca5a4fd1341d7ba3b0`) → `spent: false` —
  монета реально свободна, спенд не мог быть «в полёте» в смысле реального consensus-состояния.
- `get_mempool_item_by_tx_id` для `0xdaa0353...` → `{"success":false,"structuredError":
  {"code":"TX_NOT_IN_MEMPOOL",...}}` — бандл действительно нигде не сидит, ни на цепи, ни в
  мемпуле.

Оба факта вместе — ровно то условие, при котором задание требует пересобрать спенд с большей
комиссией (не «double-spend», монета свободна).

### 9.3 Исправление и живая проверка

**Код** (`chain/c2-svc`, полный список изменений — `src/oracle/chain_client.rs`,
`src/oracle/publisher.rs`, `src/oracle/types.rs`, `src/config.rs`, `src/main.rs`,
`tests/oracle_queue.rs`; `c2-cli` снова не тронут ни одной строкой):

- `ChainClient::in_mempool(tx_id)` — новый метод трейта, у `CoinsetChainClient` вызывает
  `get_mempool_item_by_tx_id` (тот же паттерн «отсутствие — не ошибка», что уже был у
  `coin_record` для `get_coin_record_by_name`).
- `advance_in_flight` (`publisher.rs`) теперь для незавершённого спенда сначала спрашивает цепь:
  (а) монета-синглтон, которую тратит этот спенд, всё ещё непотрачена; (б) `in_flight.bundle.
  name()` отсутствует в мемпуле. Если и то, и другое — спенд рассылается заново
  (`rebuild_in_flight`): те же записи **плюс** любые новые из `pending` (не больше 20, как в
  обычном батче), заново `get_fee_estimate` на **укороченный** target_time (треть от обычных
  60 с, «предложение»), итоговая комиссия — `max(старая_комиссия × 2, новая_оценка)`, зажатая
  сверху `C2_MAX_FEE_MOJOS` (новая env-переменная, по умолчанию 50 000 000 mojo = 0.00005 TXCH,
  ровно как предложено в задании). Счётчик `attempts` для пересобранного бандла стартует заново
  с 1 — это материально другая транзакция (другая комиссия → другой `bundle.name()`), у неё свой
  бюджет ретраев; поэтому и застрявшее с прошлого запуска состояние (`attempts: 8`) не блокирует
  восстановление навсегда — автоматическое восстановление после рестарта (требование задания)
  получается без отдельного кода «на старте», просто из порядка проверок в `advance_in_flight`.
- Если бандл **есть** в мемпуле (`in_mempool == true`) — спенд не трогается вообще (ни
  пересборка, ни повторный `push_tx`), только ожидание. Правило Chia mempool replacement
  (`chia-blockchain`'s `mempool_manager.py::check_removals`/conflicting-spend-эвикшн): замена
  принятого в мемпул спенда требует потратить **надмножество** тех же монет с **строго большей**
  комиссией; пересборка с той же единственной монетой-синглтоном и большей комиссией под это
  правило не подпадает (не надмножество, тот же набор монет) — поэтому такая замена (RBF) не
  реализована, оставлено буквально «просто ждать», как и просит задание.
- Если комиссия достигла `C2_MAX_FEE_MOJOS` и `attempts` исчерпан на этом (уже некапанном дальше)
  бандле — `TickOutcome::StuckInFlight { reason }` и `GET /v1/oracle/status`'s новое поле
  `stuckReason` (`Option<String>`, аддитивное расширение статуса — `chainsvc` не часть публичного
  контракта FIXED INTERFACE (1), см. §4) вместе называют причину («fee cap of N mojos reached: ...»
  / «exceeded N retry attempts: ...») — задание «clear operator attention status with the
  reason».

**Тесты** — `cargo test --workspace` в `chain/`: **44 passed, 1 ignored, 0 failed** (было 39/1/0,
см. §0/§7). Прогнано дважды подряд в этом проходе, оба раза идентично.

**Сборка и рестарт на стенде** (`docker compose -p herd up -d --build chainsvc`, только этот
контейнер, `herd-web-1`/`herd-server-1`/`herd-db-1`/контейнеры другого проекта `telegram-*` не
тронуты — проверено `docker ps` до и после): образ собрался полностью за ~35 с
(`network: host` build-стадии, см. §6/§8 — сеть внутри build-контейнера сегодня доступна,
предыдущее ограничение снято), контейнер пересоздан, старые данные (`~/.c2/testnet11`,
bind-mount) не тронуты.

**Живой результат** (в течение ~3 минут после рестарта, без ручных действий оператора):
1. Первый же тик (фоновый луп, `publisher_tick=10s`, но `tokio::time::interval` выполняет первый
   тик немедленно) обнаружил зависший спенд, подтвердил условия §9.2 ещё раз (уже после рестарта)
   и пересобрал его: та же монета-синглтон, записи seq 40 (застрявшая) объединены с seq 41…52,
   накопившимися в `pending` за время простоя (13 записей в одном спенде, `MAX_RECORDS_PER_SPEND
   = 20` позволяет), **новая комиссия 6 972 460 mojo** — ровно `3 486 230 × 2` (получившаяся
   оценка `get_fee_estimate` на тот момент была ниже удвоения старой, поэтому сработало именно
   удвоение). Подтверждено в блоке **4 736 449**. Комиссия проверена не по «предположению», а
   изолированно на цепи: монета-плательщик `0x15d4c970263668c2ae3998dc1057899fb54f94ba27468180
   222ba5096ead70b5` (9 869 514 299 mojo) потрачена в этом блоке, её единственный ребёнок-сдача —
   9 862 541 839 mojo; разница 6 972 460 mojo **точно равна** `fees` этого блока в
   `get_block_record_by_height` — блок не делился с чужими транзакциями, комиссия полностью наша.
2. Следующий обычный батч (записи seq 53–54, уже без всякой пересборки — свежая оценка
   `get_fee_estimate`) подтверждён в блоке **4 736 453**, комиссия (по тому же способу,
   изолированно) — **3 114 473 mojo**.
3. Ещё один обычный батч подтверждён вскоре после (лог `"oracle publish batch confirmed
   records=1"`, 20:26:50Z) на высоте **4 736 464**.
4. `GET /v1/oracle/status` через ~3 минуты после рестарта:
   `{"lastConfirmedSeq":55,"lastConfirmedHeight":4736464,"pendingRecords":0,
   "inFlightBundleId":null,"stuckReason":null}` — очередь полностью очищена, паблишер в чистом
   рабочем состоянии, новые записи от `herd-server-1` продолжают публиковаться штатно.

Единственная шероховатость: `tracing::info!("rebuilt oracle publish batch...")` и
подтверждение самого первого (13-записевого) батча не попали в `docker logs herd-chainsvc-1`
(видна только запись о подтверждении второго батча, `records=2`, и последующая, `records=1`) —
на функциональный результат это не влияет (полностью перепроверено выше по данным cети и
файлам состояния, не по логам), но сам факт пропажи одной строки лога **не объяснён** и здесь
помечается как открытый вопрос, а не факт.

## 10. Неделя 3: тестовая волна 1 нашла seq 2/3/4/62 «acked, но не опубликованы» — root cause и фикс (FIXED INTERFACE (a)/(b), 2026-09-25)

Источник задания: Алекс, 2026-09-25 — «стоит провести серию тестирований снова и после этого
ревью независимого критика пригласить»; тестовая волна 1 нашла проблему ниже, эта секция —
её разбор и фикс. Assigned paths этой задачи — `chain/c2-svc` и этот файл; серверная сторона
(reconciliation, FIXED INTERFACE (c), Flyway-миграция) — отдельный путь, другой агент, не
описана здесь.

### 10.1 Симптом и прямая улика

Живая проверка линии оракул-синглтона на testnet11 (обход `get_coin_records_by_parent_ids` /
`get_puzzle_and_solution` от launcher `0xc5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20`
через все 46 поколений (generation 0..45) до текущей монеты, тот же метод, что и в §9.2) декодировала announcement
каждого спенда (`(seq ts priceCents resultsHash sourceFlag sourceTs)`, формат §3.1) и собрала
полный список опубликованных `seq`: 65 announcement'ов на цепи покрывают диапазон 1..69, но
**seq 2, 3, 4 и 62 в этом диапазоне отсутствуют** — ни в одном спенде, ни как дубликат, ни как
поздняя запись. При этом `resultsHash` для этих четырёх `seq` реально существует в таблице
раундов сервера (значения `0xf0ef7afd…`/`0xb112438d…`/`0xa5819d33…` для seq 2/3/4,
`0x70b95d41…` для seq 62 — те самые раунды, что видны и остальным подтверждённым `seq` в той же
таблице) — то есть сервер эти записи реально сгенерировал и отправил, это не «раунд не
наступил», а именно «отправлено, потеряно после этого».

Хронология по высоте блока (тот же обход, `confirmed_block_index`/`spent_block_index` каждого
поколения):

- `seq 1` — announcement в спенде, подтверждённом на высоте **4 735 705** (~16:30 UTC).
- Следующий спенд после этого — `seq 5, 6` вместе, подтверждён на высоте **4 736 071**
  (~18:22:14 UTC) — то есть к моменту, когда этот спенд был собран, `seq 2/3/4` уже отсутствовали
  в `pending` паблишера, хотя (по данным тестовой волны 1) были приняты сервисом ~18:10–18:22 UTC,
  за минуты до этого — **задолго до** пересборки зависшего спенда из §9 (та пересборка —
  подтверждённый батч на высоте **4 736 449**, ~20:22 UTC, см. §9.3) -- значит именно то
  событие §9 не может быть причиной пропажи seq 2–4 (оно случилось на ~2 часа позже).
- `seq 61` — announcement в спенде, подтверждённом на высоте **4 736 521** (~20:44:59 UTC);
  следующий спенд — `seq 63` — на высоте **4 736 532** (~20:47 UTC). `seq 62` отсутствует в обоих
  и нигде между ними. Это окно вплотную примыкает к тому же классу события, что и §9 (пересборка
  спенда после отклонения) — то есть тот же код-путь, только другой конкретный запуск.

### 10.2 Root cause: «lost update» без блокировки очереди — найдено чтением кода, воспроизведено тестом

Проверка по чек-листу задания:

- **«Записан ли файл очереди до 202?»** — уже было верно на момент начала этой задачи:
  `Publisher::enqueue` вызывает `QueueFile::save_atomic` (fsync + rename, `atomic_file.rs`) и
  только после этого HTTP-хендлер (`app.rs::oracle_records`) отвечает `202`. Не являлось причиной.
- **«Продвинут ли watermark раньше, чем запись стала durable?»** — в пределах одного вызова
  `enqueue` нет: `last_accepted_seq` и `pending` мутировались и сохранялись в одном атомарном
  `save_atomic`. Но это не защищает от второго бага ниже.
- **«Слияние при пересборке роняет записи зависшего бандла?»** — нет, сама арифметика слияния в
  `rebuild_in_flight` (`records = in_flight.records.clone(); records.extend(queue.take_batch(room))`)
  корректно сохраняет каждую запись, которую ей передали (см. новый тест
  `rebuild_merge_keeps_every_original_and_newly_queued_record`, §10.5).
- **«Загружает ли рестарт устаревшую очередь?»** — нет (см. §10.3, отдельная находка, не причина
  этого конкретного инцидента).

Настоящая причина — не в одном из перечисленных пунктов по отдельности, а в том, что ни
`Publisher::enqueue` (HTTP-хендлер `POST /v1/oracle/records`), ни `Publisher::tick`
(фоновый цикл: `advance_in_flight`/`rebuild_in_flight`/`start_new_batch`) **не были взаимно
исключены**. Оба независимо делали `QueueFile::load` → мутация в памяти → `save_atomic` без
какой-либо блокировки друг против друга. `rebuild_in_flight` в частности `await`-ит **четыре**
сетевых обращения (`coin_record` ×2, `in_mempool`, `fee_estimate_mojos`, и внутри
`attach_fee_coin` — `find_funding_coin`) между своим `QueueFile::load` (в начале `tick()`) и своим
финальным `save_atomic` — окно от сотен миллисекунд до нескольких секунд, в течение которого
конкурентный `POST /v1/oracle/records` может загрузить тот же файл, дописать свою запись,
fsync'нуть её, вернуть `202` вызывающему — и всё это **до того**, как более поздний
`save_atomic` из `tick()` (собранный из ЕГО собственного, уже устаревшего снимка `queue`,
взятого до того, как этот POST вообще начался) молча перезапишет файл и сотрёт эту запись.
Ровно «lost update» / TOCTOU-гонка. Она же объясняет, почему сервер (клиент) ничего не заметил:
`POST /v1/oracle/records` каждый раз отвечал `202` на первую отправку, а старая watermark-логика
дедупликации (`seq <= last_accepted_seq` ⇒ «дубликат») делала любую повторную попытку той же
`seq` неотличимой от настоящего дубликата и молча её отбрасывала навсегда — то есть даже если бы
сервер когда-нибудь переотправил потерянную запись, старый код её бы просто проигнорировал.

**Доказано тестом-регрессией**, а не только чтением кода: `chain/c2-svc/tests/oracle_queue.rs::
concurrent_post_during_a_slow_rebuild_does_not_lose_the_record` воспроизводит ровно эту гонку
(`tokio::join!(publisher.tick(), publisher.enqueue(...))` с искусственной задержкой на каждом
вызове `FakeChainClient`, тот же приём, что уже использует `status_answers_while_a_publish_is_in_flight`).
Временный откат фикса (`queue_lock` заменён на no-op) и повторный прогон именно этого теста в
рамках этой задачи дал:

```
thread 'concurrent_post_during_a_slow_rebuild_does_not_lose_the_record' panicked:
seq 2 must survive the race, in pending or in flight -- queue on disk:
QueueFile { pending: [], in_flight: Some(InFlightSpend { records: [ ...seq: 1... ] }), ... }
```

— запись `seq 2` пропадает из `pending` и не появляется в `in_flight` целиком, байт-в-байт то же
поведение, что видно в лайнэдже §10.1. С восстановленным фиксом тест зелёный (проверено дважды
подряд).

**Фикс**: `Publisher::queue_lock: tokio::sync::Mutex<()>` (`chain/c2-svc/src/oracle/publisher.rs`),
захватывается на всё тело и `enqueue()`, и `tick()` (не только вокруг файлового I/O — чтобы между
чужим `load` и чужим `save` гарантированно ничего не могло вклиниться), но **не** `status()` —
она обязана оставаться без блокировки по уже задокументированной причине
(`balance_snapshot`'а доккомент: `status()` не должен зависеть от того, сколько идёт медленный
сетевой вызов внутри конкурентного `tick()`, task fix из §7/§9 — если бы `status()` тоже ждала
`queue_lock`, это регрессировало бы именно тот более ранний фикс).

### 10.3 Второй, независимый баг того же класса: watermark-дедупликация делала потерю необратимой

Даже без гонки из §10.2, старая логика `QueueFile::enqueue` (`if seq <= last_accepted_seq { skip
as duplicate }`) была принципиально не в состоянии отличить «настоящий дубликат» от «эта запись
когда-то была потеряна». Это ровно то, что требует закрыть FIXED INTERFACE (b): запись с `seq`
ниже watermark, которая не находится ни в `confirmed`, ни в `in_flight`, ни в `pending`, должна
приниматься как поздняя (backfill), а не отбрасываться. Реализовано: `QueueFile::record_state(seq)`
проверяет реальное присутствие записи в этих трёх местах (никогда — watermark), и `enqueue`
дедуплицирует по нему. Прямой юнит-тест на сам этот баг (независимо от гонки из §10.2, потому что
запись может пропасть из вида и по другим причинам — например, ручное вмешательство оператора):
`late_record_below_watermark_and_unknown_everywhere_is_backfilled`.

### 10.4 Что рассмотрено и отброшено

- **Рестарт с устаревшей очередью** — `atomic_file.rs::write_json_atomic` уже делал `fsync`
  временного файла перед `rename` до начала этой задачи, и это не совпадает по времени ни с одним
  из двух инцидентов §10.1 (ни один не приходится на перезапуск контейнера). Не причина. Отдельная
  находка того же модуля, сделанная в ходе этого ревью, но не устранённая (вне assigned paths
  тонкой доводки в этой задаче, фиксируется как открытый риск): `fsync` делается на данных
  временного файла, но не на родительской директории после `rename` — на Linux это означает, что
  сама метаданная переименования не гарантированно durable при жёстком крахе (SIGKILL/потеря
  питания, в отличие от штатного `docker compose up -d --build`, где процесс останавливается
  через `SIGTERM` с грейс-периодом). Не влияет на инциденты §10.1, но стоит закрыть отдельной
  задачей.
- **Пересборка контейнера `chainsvc` в 20:23 UTC (§9.3)** — хронологически **позже**, чем момент,
  когда `seq 2–4` уже отсутствовали в pending (~18:10–18:22 UTC) — не может быть их причиной.
  Похоже, это тот же класс события (`rebuild_in_flight`), что и вызвавшее пропажу `seq 62` (§10.1)
  — но не то же самое событие.
- **Арифметика слияния при пересборке** (`rebuild_in_flight`'s `records.extend(...)`) — корректна
  сама по себе (§10.2, пункт 3 чек-листа); пропажа происходила на уровень выше, в незащищённом
  цикле load/save вокруг неё, не в самом слиянии.

### 10.5 Тесты (`chain/c2-svc/tests/oracle_queue.rs`, если не указано иное)

| тест | что проверяет |
|---|---|
| `enqueue_persists_durably_before_returning` | FIXED INTERFACE (b): fsync-before-ack — независимое чтение файла сразу после `enqueue()` видит записи |
| `resubmitting_a_known_record_is_idempotent_in_every_state` | идемпотентный re-POST для `pending`/`in_flight`/`confirmed` — ничего не задваивается |
| `late_record_below_watermark_and_unknown_everywhere_is_backfilled` | прямой юнит-тест на root-cause бага §10.3 — поздняя запись принимается, а не отбрасывается |
| `record_status_reports_every_state` | `GET /v1/oracle/records/{seq}`: `unknown`/`queued`/`in_flight`/`confirmed` (+ `height`/`coinId`) |
| `restart_resumes_a_pending_in_flight_spend` | «restart with records in queue/in_flight»: новый `Publisher` на тот же `data_dir` корректно доводит зависший спенд до подтверждения |
| `rebuild_merge_keeps_every_original_and_newly_queued_record` | пересборка после отклонения объединяет и старые, и новые записи — ни одна не теряется и не дублируется |
| `concurrent_post_during_a_slow_rebuild_does_not_lose_the_record` | регрессия на сам root cause §10.2 — воспроизводит гонку и доказывает, что фикс её закрывает (см. живой прогон с откаченным фиксом в §10.2) |
| `chain/c2-svc/src/service_auth.rs::tests` (9 тестов) | FIXED INTERFACE (a): `401` без заголовка / с неверным токеном / без `Bearer `-префикса, `200` с верным токеном, `/health` никогда не защищён, режим `C2_SVC_ALLOW_NO_AUTH` пропускает всё |
| `chain/c2-svc/src/config.rs::tests` (8 тестов) | парсинг `C2_BIND_ADDR` (одиночный адрес по умолчанию, несколько через запятую с пробелами, пустая строка и мусор — ошибка) и правило «отказ стартовать без токена, кроме явного bypass» — как чистые функции, без мутации `env` (гонки между параллельными `#[test]`) |

Полные числа — §0/§7. `cargo test --workspace` в `chain/`: **68 passed, 1 ignored, 0 failed**,
дважды подряд, тёплая сборка.

### 10.6 FIXED INTERFACE (a): Bearer-токен

`chain/c2-svc/src/service_auth.rs` — мидлварь `require_bearer_token`, навешена на все `/v1/*`
маршруты через `axum::middleware::from_fn_with_state` (`app.rs::router`); `GET /health`
зарегистрирован на отдельном `Router`, смёрженном **после** вызова `.layer(...)` на защищённом —
поэтому он физически не проходит через эту мидлварь ни при каком значении токена (тест
`health_route_outside_the_layer_is_never_protected`).

- Токен — `C2_SVC_TOKEN` (`config.rs`), сверяется с `Authorization: Bearer <token>`.
- Сравнение — константное по времени над **фиксированной длины дайджестами**: обе строки сначала
  хешируются `SHA-256` (так что время сравнения не зависит от *длины* исходных строк), затем все
  32 байта дайджеста сравниваются `XOR`-накоплением без ранних `return` (так что время не зависит
  и от того, *где* оказался первый несовпадающий байт) — `constant_time_token_eq`.
- Отказ стартовать без токена — `main.rs` вызывает `config::check_startup_auth_config` до открытия
  какого-либо сокета; проходит только если задан `C2_SVC_TOKEN`, либо явно выставлен
  `C2_SVC_ALLOW_NO_AUTH=1` (только для тестов — в этом режиме мидлварь пропускает всё без
  проверки, `SvcTokenState = None`).
- `C2_BIND_ADDR` — список адресов через запятую (по умолчанию `127.0.0.1` один); `main.rs`
  открывает по одному `TcpListener` на каждый адрес и запускает по одной задаче `axum::serve` на
  каждый, все — с одним и тем же `Router` (дешёвый `.clone()`, внутри `Arc`). На стенде (`compose`
  `network_mode: host` у `chainsvc`, `server` достаёт его через шлюз `172.28.77.1`, см. §9.3)
  переменная должна быть выставлена в `172.28.77.1,127.0.0.1` — **это изменение в
  `docker-compose.yml` в этой задаче не сделано намеренно**: файл в корне репозитория общий с
  окружением сервера (`HERD_CHAINSVC_TOKEN` должен совпадать по значению с `C2_SVC_TOKEN`), которое
  собирает параллельно другой агент/другой assigned path — трогать его отсюда означало бы гонку
  правок на общем файле. Открытый интеграционный пункт для той стороны или для Алекса.

### 10.7 FIXED INTERFACE (b): durable delivery

- `POST /v1/oracle/records` теперь отвечает `{"queued": N, "acceptedSeqs": [...]}` — `acceptedSeqs`
  может быть короче присланного списка, если часть `seq` уже была известна
  confirmed/in_flight/queued (идемпотентный re-POST, не ошибка).
- Новый `GET /v1/oracle/records/{seq}` → `{seq, state, height, coinId}`,
  `state ∈ {"queued","in_flight","confirmed","unknown"}` (`c2-svc/src/oracle/types.rs::
  OracleRecordStatusResponse`, `Publisher::record_status`, читает `QueueFile` без блокировки —
  та же эвентуальная консистентность, что и у `GET /v1/oracle/status`, обоснование то же).
- `QueueFile` получил `confirmed: BTreeMap<u64, ConfirmedRecordInfo>` (`#[serde(default)]` —
  существующий `<name>.oracle_queue.json` на стенде это поле не содержит, обратная совместимость
  обязательна) — заполняется на **каждую** запись подтверждённого батча (не только на последнюю
  по `seq` — после появления backfill порядок `in_flight.records` больше не гарантированно
  возрастающий, см. `publisher.rs::advance_in_flight`'а обновлённый докком-мент), поэтому
  `last_confirmed_seq` теперь тоже считается через `.max()` по всем записям батча, а не `.last()`.
  Поздняя (backfill) запись публикуется тем же форматом announcement, что и любая другая (§3.1) —
  порядок on-chain может стать не строго последовательным, гарантия — «каждый seq ровно один раз»
  (`record_state`/`enqueue`, §10.3), проверено симулятором в `oracle_multi_announce.rs` (announcement
  не зависит от порядка записей в батче) и юнит-тестами (§10.5).

### 10.8 Не сделано в этой задаче (по scope, не по ограничению)

- FIXED INTERFACE (c) (серверная реконсиляция каждые 60 с, Flyway-миграция, `oracleAnchor?` в
  DTO раунда) — другой assigned path, другой агент; здесь не реализовано и не должно быть.
- `docker-compose.yml` (корень репозитория) не тронут — см. §10.6, `C2_SVC_TOKEN`/`C2_BIND_ADDR`
  для стенда нужно выставить со стороны, которая параллельно правит этот файл.
- Директориальный `fsync` после `rename` в `atomic_file.rs` (§10.4, вторичная находка) — не
  причина этих двух инцидентов, не устранена в этой задаче, зафиксирована как открытый риск.
- Реальные транзакции на testnet11 в рамках этой задачи не отправлялись — весь фикс проверен
  `cargo test --workspace` (fake chain client, без сети), как и просило задание («cargo test
  --workspace green (real counts)»); живая проверка на стенде — часть следующей волны тестирования
  (Алекс, см. заголовок §10).
