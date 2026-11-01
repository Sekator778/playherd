# Неделя 1, день 1 — доступ к сети Chia testnet11 (the project plan §8.1)

Дата проверки всех фактов ниже — **2026-09-25** (если у факта не указана другая дата явно). Методы проверки: `gh api` (чтение исходников `xch-dev/chia-wallet-sdk`, `xch-dev/sage`, `Chia-Network/chia-blockchain`, `Chia-Network/chips` — репозитории публичные, читаются без побочных эффектов), `curl`/прямые POST на публичные read-only эндпойнты, `crates.io` API, `dig`/сырой TCP-коннект для проверки живости сети. Источники внутри проекта: `the project plan` §2, §3, §5, §8.1; `docs/research/c2_deep_review.md` §3b–3c.

Закрывает открытый вопрос `the project plan` §8 п.1.

---

## 0. Ответ на вопрос §8.1

**Своя полная нода не нужна.** Есть минимум два маршрута без своей ноды, оба подтверждены сегодня живыми запросами:

1. **coinset.org** — публичный HTTPS API, `https://testnet11.api.coinset.org`, ответил на реальный запрос сегодня (см. §3). Проще всего для публикации спенда/CAT-транзакций: обычный `push_tx` через HTTPS, без сертификатов, без узла.
2. **chia-sdk-client (P2P к пиру)** — подключение напрямую к любому живому full-node-пиру testnet11 по WebSocket с TLS; сертификат клиент генерирует сам локально (не нужен ни CA, ни своя нода). DNS-интродьюсер `dns-introducer-testnet11.chia.net` сегодня отдал 10 живых IP, 3 из 4 проверенных приняли TCP-соединение на порту 58444 прямо сейчас (см. §4).

Третий маршрут — **RPC самого Sage** — тоже не требует полной ноды (Sage — light-кошелёк, сам ходит в пиры/сеть), но требует поднятого Sage с включённым (по умолчанию выключенным) RPC и скопированным клиентским mTLS-сертификатом (см. §5). Это путь, который позволяет команде строить **неподписанные** спенды в своём Rust-инструменте и отдавать подпись+отправку отдельному testnet11-профилю Sage — технически подтверждён сегодня чтением кода `sage`.

Четвёртый вариант — **своя полная нода** — тоже возможен (`chia-sdk-coinset::FullNodeClient`, mTLS на `localhost:8555`, см. §6), но не нужен ни для одного из трёх маршрутов выше; в MVP не требуется.

**Рекомендация («предложение» — решить в неделю 1, обоснование ниже в §8):** для дня 3 (announcement-спенд оракула) и дня 2 (PLAY CAT + Offer) использовать **coinset.org push_tx** как основной путь публикации — он проще всего интегрировать в Rust-паблишер (`chia-sdk-coinset::CoinsetClient`, уже в зависимостях через `chia-wallet-sdk`), не завязан на состояние локального Sage-процесса и явно подтверждён живым сегодня. Sage RPC (маршрут «неподписанный спенд → подпись у Алекса») держать как альтернативу/резерв, если понадобится подпись реальными ключами Алекса вне автоматизированного паблишера.

---

## 1. testnet11 — параметры сети

| Параметр | Значение | Источник |
|---|---|---|
| `network_id` / `network_name` | `testnet11` | Живой ответ `testnet11.api.coinset.org/get_network_info` (curl, 2026-09-25) → `{"network_name":"testnet11","network_prefix":"txch","success":true}`; `Chia-Network/chia-blockchain`, `chia/util/initial-config.yaml:28` (ключ `network_overrides.constants.testnet11`), HEAD `main` = `38d4f8ce68cd`, читано 2026-09-25 |
| `address_prefix` | `txch` | там же |
| `genesis_challenge` (= `agg_sig_me_additional_data`) | `37a90eb5185a9c4439a91ddc98bbadce7b4feba060d50116a067de66bf236615` | `xch-dev/chia-wallet-sdk`, `crates/chia-sdk-types/src/constants.rs:12-14` (`TESTNET11_GENESIS_CHALLENGE`) и `:90-107` (`TESTNET11_CONSTANTS`), HEAD `main` = `55e23aca9159`, читано 2026-09-25. **Байт-в-байт совпадает** с `chia-blockchain` `initial-config.yaml:29,33` (`AGG_SIG_ME_ADDITIONAL_DATA`, `GENESIS_CHALLENGE`) — перепроверено вручную |
| `default_full_node_port` | `58444` | `chia-wallet-sdk` `crates/chia-sdk-client/src/network.rs:33` (`Network::default_testnet11()`); `chia-blockchain` `initial-config.yaml:69` |
| DNS-интродьюсер | `dns-introducer-testnet11.chia.net` | `chia-sdk-client/src/network.rs:35`. Живой `dig` (2026-09-25): вернул 10 IP (напр. `34.222.46.201`, `85.230.97.79`, `97.81.186.74`, …) |
| full-node RPC порт (если своя нода) | `8555`, mTLS | `chia-blockchain` `initial-config.yaml:355` (секция `full_node.rpc_port`); `chia-sdk-coinset::FullNodeClient::new` — дефолтный `base_url` `https://localhost:8555`, `crates/chia-sdk-coinset/src/full_node_client.rs:14` |
| coinset.org, testnet11 base URL | `https://testnet11.api.coinset.org` | `chia-sdk-coinset/src/coinset_client.rs:20-22` (`CoinsetClient::testnet11()`); живой POST, 2026-09-25 |
| Sage RPC порт | `9257`, по умолчанию **выключен** (`enabled: false`) | `xch-dev/sage`, `crates/sage-config/src/config.rs` (`struct RpcConfig { enabled: bool, port: u16 }`, `Default` → `enabled: false, port: 9257`), HEAD `main` = `f2ec89dd`, читано 2026-09-25 |

**Главный факт для §8.1**: `chia-wallet-sdk` **сам шьёт готовые константы testnet11** — `TESTNET11_CONSTANTS` (в `chia-sdk-types`) и `Network::default_testnet11()` (в `chia-sdk-client`, порт+genesis+DNS одним вызовом). Ничего вычислять/копировать из `chia-blockchain` вручную не нужно — только свериться (сверено, совпадает).

---

## 2. Таблица маршрутов

| Маршрут | Как | Свидетельства (URL / файл) | Проверено сегодня | Что нужно от Алекса |
|---|---|---|---|---|
| **coinset.org (публичный API)** | `chia-sdk-coinset::CoinsetClient::testnet11()` → HTTPS POST `push_tx`, `get_coin_records_by_puzzle_hash(es)`, `get_puzzle_and_solution`, `get_network_info` и т.д. — без сертификатов, без своей ноды | `crates/chia-sdk-coinset/src/coinset_client.rs:20-22`, `crates/chia-sdk-coinset/src/chia_rpc_client.rs:216 (get_coin_records_by_puzzle_hash), :270 (push_tx), :295 (get_network_info)`, `xch-dev/chia-wallet-sdk` HEAD `55e23aca9159`; crates.io `chia-sdk-coinset` 0.36.0, 44 207 загрузок (fetched 2026-09-25) | **Да** — `POST testnet11.api.coinset.org/get_network_info` → `200 {"network_name":"testnet11",...}`; `POST .../get_blockchain_state` → живая высота блока 4 735 290, активный мемпул (curl, 2026-09-25) | Ничего — работает без аккаунта/ключей |
| **chia-sdk-client → P2P к пиру testnet11** | `Network::default_testnet11()` + сгенерированный локально TLS-сертификат (`ChiaCertificate::generate()`) → `Peer::connect(...)` по `wss://<peer>:58444/ws` → `send_transaction(spend_bundle)`, `request_coin_state`/`request_puzzle_state` | `crates/chia-sdk-client/src/network.rs`, `tls.rs` (`load_ssl_cert`, `create_rustls_connector` — сервер не верифицируется, свой клиентский серт не нужно регистрировать нигде), `peer.rs:141-147` (`send_transaction`); crates.io `chia-sdk-client` 0.36.0, 73 250 загрузок | **Да** — `dig dns-introducer-testnet11.chia.net` вернул 10 живых IP; сырой TCP-коннект (Python `socket`, порт 58444, таймаут 5с, без отправки данных) — 3 из 4 выбранных IP приняли соединение прямо сейчас | Ничего — не требует аккаунта; TLS-сертификат инструмент генерирует сам при первом запуске |
| **Sage RPC** («неподписанный спенд → подпись у Sage») | Наш инструмент собирает `Vec<CoinSpendJson>` (любой пазл, не только «известные» Sage) → POST `https://127.0.0.1:9257/sign_coin_spends` (mTLS, клиентский серт = копия `<sage_data_dir>/ssl/wallet.crt`/`.key`) → Sage подписывает и по флагу `auto_submit` сам пушит в сеть; либо отдельно `POST /submit_transaction` с уже готовым `SpendBundle` | `xch-dev/sage`, `crates/sage-rpc/src/lib.rs` (`start_rpc`: биндится на `127.0.0.1:<rpc.port>`, mTLS `RustlsConfig` из `<path>/ssl/wallet.crt`+`.key`; router — по одному POST-эндпойнту на каждый метод `sage_api::Endpoint`), `crates/sage-rpc/src/cert_verifier.rs` (`WalletCertVerifier` — принимает **только** байт-в-байт совпадающий клиентский сертификат), `crates/sage/src/endpoints/transactions.rs:498 (sign_coin_spends), :530 (submit_transaction)`, `crates/sage-api/src/requests/transactions.rs:737 (SignCoinSpends), :797 (SubmitTransaction)` | **Частично** — код прочитан и прослежен построчно (сигнатуры, генерик по `coin_spends: Vec<CoinSpendJson>`, mTLS-механизм). **Не проверено живым запуском** Sage с включённым RPC в этой сессии (не наш маршрут по правилам задачи — это отдельная установка на машине Алекса) | 1) Установить Sage (desktop), 2) создать отдельный testnet11-профиль/fingerprint (см. открытый вопрос ниже — технический механизм изоляции профиля не до конца подтверждён), 3) включить RPC в конфиге (`rpc.enabled = true`, по умолчанию **выключен**), 4) скопировать `<sage_data_dir>/ssl/wallet.crt` и `.key` инструменту как клиентский mTLS-серт |
| **Своя полная нода** | Локальный `chia-blockchain` full node → `chia-sdk-coinset::FullNodeClient::new(cert, key)` (default `https://localhost:8555`, mTLS) — тот же трейт `ChiaRpcClient`, что и у `CoinsetClient` | `crates/chia-sdk-coinset/src/full_node_client.rs:12-15`; `chia-blockchain` `initial-config.yaml:355` (`full_node.rpc_port: 8555`) | Не тестировался (не требуется по §0) | Не нужно для MVP |

---

## 3. coinset.org — детали и живая проверка

Rust-клиент уже есть в зависимостях (`chia-sdk-coinset`, часть `chia-wallet-sdk`):

```rust
// crates/chia-sdk-coinset/src/coinset_client.rs
pub fn testnet11() -> Self {
    Self::new("https://testnet11.api.coinset.org".to_string())
}
pub fn mainnet() -> Self {
    Self::new("https://api.coinset.org".to_string())
}
```

Трейт `ChiaRpcClient` (`crates/chia-sdk-coinset/src/chia_rpc_client.rs`) даёт нужные для MVP методы «из коробки»: `push_tx(spend_bundle)` (:270), `get_coin_records_by_puzzle_hash(es)` (:216), `get_puzzle_and_solution` (:256), `get_network_info` (:295), `get_blockchain_state` (:26). Сериализация полей (`coin_spends`, `aggregated_signature`) уже реализована — писать HTTP-обвязку самим не нужно.

Живая проверка сегодня (harmless read-only POST, без пуша):

```
POST https://testnet11.api.coinset.org/get_network_info
→ {"network_name":"testnet11","network_prefix":"txch","success":true}

POST https://testnet11.api.coinset.org/get_blockchain_state
→ height 4735290, mempool_size 3412, genesis_challenge_initialized: true (живая сеть, не заглушка)

POST https://api.coinset.org/get_network_info   (санити-проверка на mainnet)
→ {"network_name":"mainnet","network_prefix":"xch","success":true}
```

Отдельного `docs.coinset.org` не существует (домен отдаёт 525/TLS-ошибку Cloudflare, curl 2026-09-25); человекочитаемый сайт — `https://www.coinset.org` (200 OK, Next.js-приложение, без отдельной публичной API-документации на нём — сами эндпойнты документируются только исходным кодом клиентов вроде `chia-sdk-coinset`/референсной реализации `Chia-Network/coinset-api`, не проверялось отдельно в этом проходе).

---

## 4. chia-sdk-client — P2P-подключение к пиру, без своей ноды

```rust
// crates/chia-sdk-client/src/network.rs
pub fn default_testnet11() -> Self {
    Self {
        default_port: 58444,
        genesis_challenge: TESTNET11_CONSTANTS.genesis_challenge,
        dns_introducers: vec!["dns-introducer-testnet11.chia.net".to_string()],
    }
}
```

`tls.rs::load_ssl_cert` создаёт свой сертификат локально, если файла ещё нет (`ChiaCertificate::generate()`), и `create_rustls_connector`/`create_native_tls_connector` явно **не проверяют** сертификат сервера (`danger_accept_invalid_certs`/кастомный verifier, всегда `Ok`) — то есть для P2P-протокола Chia (в отличие от RPC) не требуется ни CA-подписанный сертификат, ни регистрация где-либо: любой самоподписанный клиентский сертификат принимается публичным full-node-пиром. `peer.rs` даёт весь нужный набор: `send_transaction(spend_bundle) -> TransactionAck` (:141-147), `request_coin_state`/`request_puzzle_state` для чтения состояния монет.

Живая проверка сегодня:
- `dig dns-introducer-testnet11.chia.net` → 10 IP (не DNS-сервер, а «DNS-сидер»: A-записи хоста — это и есть список живых пиров, конвенция Chia).
- Сырой TCP-коннект (Python `socket.connect`, таймаут 5с, соединение сразу закрыто, данные не отправлялись) на порт 58444 к 4 случайным IP из списка: **3 приняли** (`34.222.46.201`, `85.230.97.79`, `97.81.186.74`), 1 отказал (`45.33.7.229`, `Connection refused`) — сеть live прямо сейчас, не только «теоретически доступна».

---

## 5. Sage RPC — детали

`xch-dev/sage` содержит отдельный крейт **`sage-rpc`** (не опубликован на crates.io, как и `sage-api`/`sage-wallet` — проверено `curl crates.io/api/v1/crates/{sage-rpc,sage-api,sage-wallet}`, все три отсутствуют, 2026-09-25; значит подключать как `cargo`-зависимость нельзя, только как обычный HTTPS-клиент по сети или git-зависимостью на исходники xch-dev/sage).

**Порт и включение:**
```rust
// crates/sage-config/src/config.rs
pub struct RpcConfig {
    pub enabled: bool,
    pub port: u16,
}
impl Default for RpcConfig {
    fn default() -> Self {
        Self { enabled: false, port: 9257 }
    }
}
```
RPC **выключен по умолчанию** и слушает **только `127.0.0.1`** (`crates/sage-rpc/src/lib.rs`: `let addr: SocketAddr = ([127, 0, 0, 1], app.config.rpc.port).into()`) — не внешний сервис, только локальный.

**Аутентификация — настоящий mTLS, не токен:**
```rust
// crates/sage-rpc/src/lib.rs
let config = load_rustls_config(
    app.path.join("ssl").join("wallet.crt")...,
    app.path.join("ssl").join("wallet.key")...,
)?;
```
`cert_verifier.rs::WalletCertVerifier` принимает клиента, только если его сертификат **байт-в-байт** совпадает с этим же `wallet.crt` — то есть клиенту нужна копия файлов `<sage_data_dir>/ssl/wallet.crt` и `wallet.key` с машины, где стоит Sage (путь к `sage_data_dir` — см. §7, `dirs::data_dir()/com.rigidnetwork.sage`).

**Генерик ли `sign_coin_spends` — да, подтверждено чтением кода:**
```rust
// crates/sage/src/endpoints/transactions.rs:498
pub async fn sign_coin_spends(&self, req: SignCoinSpends) -> Result<SignCoinSpendsResponse> {
    let coin_spends = req.coin_spends.into_iter().map(rust_spend).collect::<Result<Vec<_>>>()?;
    let spend_bundle = self.sign(coin_spends, req.partial).await?;
    ...
    if req.auto_submit { self.submit(spend_bundle).await?; }
    Ok(SignCoinSpendsResponse { spend_bundle: json_bundle(&spend_bundle) })
}
```
`SignCoinSpends { coin_spends: Vec<CoinSpendJson>, partial: bool, auto_submit: bool }` (`crates/sage-api/src/requests/transactions.rs:737-754`) — принимает **произвольные** coin spends (любой `puzzle_reveal`/`solution`, не только известные Sage puzzle-типы), в отличие от `send_xch`/`send_cat` и т.п., которые сами строят спенды из монет, которые Sage знает. Отдельно `submit_transaction(SubmitTransaction { spend_bundle: SpendBundleJson })` (`:530`, `sage-api/.../transactions.rs:797`) просто пушит уже готовый (например, частично собранный нашим инструментом и подписанный отдельно) `SpendBundle`. Это ровно тот флоу, который нужен для «наш Rust-инструмент строит unsigned spend кастомного синглтона → Sage подписывает и отправляет своими ключами».

**Не проверено (честно помечаю UNVERIFIED):** как именно `self.submit()` физически пушит бандл в сеть внутри Sage (через собственное P2P-подключение к пирам или через внешний API) — код не трассирован до конца в этом проходе, не критично для решения (в любом случае это не наш API, а внутренняя реализация Sage).

**Testnet11-профиль, отдельный от `a sibling project` — технически не до конца подтверждено, вижу так:**
- `NetworkConfig.default_network: String` (`crates/sage-config/src/config.rs`, дефолт `"mainnet"`) — это **одна глобальная настройка на весь Sage**, не per-wallet. Переключение сети переключает вид для всех кошельков в этом Sage разом.
- Путь к данным (`sage_data_dir`) у Tauri-приложения — `app.path().app_data_dir()` (`src-tauri/src/lib.rs:280`), это фиксированный, завязанный на bundle id (`com.rigidnetwork.sage`) каталог ОС (`dirs::data_dir()/com.rigidnetwork.sage`, тот же путь жёстко зашит и в `sage-cli/src/main.rs`). **Переменной окружения или CLI-флага для переопределения этого каталога в прочитанном коде не найдено** — то есть штатного способа «второй, полностью изолированный Sage» на одной машине/одном пользователе ОС кодом не подтверждён.
- Практический вывод: «отдельный профиль» из `the project plan`/`the project notes`, скорее всего, реализуем как отдельный **fingerprint/кошелёк** внутри одной установки Sage (Sage поддерживает несколько кошельков по фингерпринту) — но сама *сеть* (`mainnet`/`testnet11`) переключается глобально на уровне всего приложения, и это может пересекаться с тем, что делает `a sibling project` в той же установке Sage на той же машине. Полная OS-level изоляция потребовала бы либо отдельного пользователя ОС, либо отдельной машины/окружения — это выходит за рамки того, что подтверждено кодом сегодня. **Открытый вопрос — см. §9.**

---

## 6. Faucet testnet11

**Поправка к предыдущему проходу:** `faucet.chia.net` и testnet11-фаусет — это **два разных сайта с разными формами**, не одна единая страница. `https://faucet.chia.net/` (проверено curl 2026-09-25) — mainnet-фаусет, форма требует **Master Public Key**:
```html
<label for="master-pk">Master Public Key</label>
<input type="text" id="master-pk" name="master-pk" value="" />
```
Для **testnet11** правильный адрес — **`https://testnet11-faucet.chia.net/`** (curl 2026-09-25, `HTTP 200`, обычная server-rendered HTML-форма, не SPA). Её форма требует не Master Public Key, а **адрес получателя**:

```html
<form action="/request" method="POST">
  <div class="form-row">
    <label for="address">Receive Address</label>
    <input type="text" id="address" name="address" value="" />
  </div>
  <div class="form-row">
    <div class="g-recaptcha" data-sitekey="6LcjVL8aAAAAALgsNKuozexoCxtbpEnREyyVeELG"></div>
  </div>
  <div class="form-row">
    <input type="submit" value="Submit" class="button">
  </div>
</form>
```
Требования для testnet11: **Receive Address** (обычный `txch1...`-адрес кошелька, не Master Public Key) + Google reCAPTCHA v2 — капча делает форму непригодной для автоматизации (нужен ручной браузер), это уже отмечено в `c2_deep_review.md` §3f для mainnet-варианта и подтверждено сегодня и для testnet11 (тот же sitekey reCAPTCHA на обеих формах). **Монеты не запрашивались** (по правилам задачи; страница только прочитана, POST на `/request` не отправлялся).

---

## 7. Своя полная нода (справочно, не требуется для MVP)

`chia-sdk-coinset::FullNodeClient::new(cert_bytes, key_bytes)` по умолчанию стучится в `https://localhost:8555` с mTLS-идентичностью из сертификата (`crates/chia-sdk-coinset/src/full_node_client.rs:12-15`) — порт совпадает с `chia-blockchain`'овским `full_node.rpc_port: 8555` (`initial-config.yaml:355`). Реализует тот же трейт `ChiaRpcClient`, что и `CoinsetClient`, то есть код паблишера можно писать один раз против трейта и подставлять любой из трёх клиентов (coinset/full-node/в теории обёртку над Sage RPC) без переписывания бизнес-логики.

---

## 8. CHIP-51 `RewardDistributorType::Managed` — где искать, как работает, рекомендация

### Где код (важное обновление по сравнению с `the project plan`/`c2_deep_review.md`)

`the project plan` §3/§5 ссылается на `Yakuhito/slot-machine` как референс. Сегодня подтверждено (`gh api repos/xch-dev/chia-wallet-sdk/pulls/279`, `/425`, `/279/files`, `/425/files`, 2026-09-25): **основной драйвер-код CATalog/XCHandles/RewardDistributor переехал внутрь `xch-dev/chia-wallet-sdk`** как штатные примитивы SDK в **PR #279 «The Merge» (смержен 2025-07-29)** — 123 файла, +16938/−572 строк, файлы преимущественно `added` (новые), включая `catalog_registry(_info).rs`, `xchandles_registry(_info).rs`, `reward_distributor(_info).rs` в `crates/chia-sdk-driver/src/primitives/action_layer/`. **PR #425 «THE MERGE (PART 2)» (смержен 2026-08-15)** — более поздний крупный follow-up (151 файл, +15831/−3695 строк, 279 коммитов, файлы преимущественно `modified`), сосредоточенный на доработках `reward_distributor` (баг-фиксы `stake`/`unstake`/`initiate_payout`, новый `action_log.rs`, `refresh.rs`); ни в диффе, ни в сообщении merge-коммита #425 нет `.rue`-файлов или явного упоминания переноса пазлов на Rue — предыдущая формулировка про «перенос на Rue» этим коммитом не подтверждается и снята. Актуальные пути:
- `crates/chia-sdk-driver/src/primitives/action_layer/reward_distributor_info.rs` — типы `RewardDistributorType`, `RewardDistributorState`, `RewardDistributorConstants`, `RewardDistributorInfo`.
- `crates/chia-sdk-driver/src/primitives/action_layer/reward_distributor.rs` — сам примитив-драйвер.
- `crates/chia-sdk-driver/src/layers/action_layer/actions/reward_distributor/{add_entry,remove_entry,sync,new_epoch,...}.rs` — отдельные action-пазлы.
- `crates/chia-sdk-bindings/src/action_layer/reward_distributor.rs` + `napi/`/`pyo3/` биндинги — то есть API доступен и не только из Rust.

`Yakuhito/slot-machine` сегодня — это в первую очередь **CLI**-обвязка поверх `chia-wallet-sdk` (`src/cli/reward_distributor/*.rs` импортирует типы из SDK), полезен как справочник по использованию, но **не основной источник кода** больше.

**Важно для лицензии:** `Yakuhito/slot-machine` лицензирован под **Prosperity Public License 3.0.0** — свободно для некоммерческого использования, но коммерческое — только 30-дневный триал (`LICENSE.md`, репозиторий, проверено `gh api`, 2026-09-25). `xch-dev/chia-wallet-sdk` — **Apache-2.0** (`gh api repos/xch-dev/chia-wallet-sdk/license` → `spdx_id: "Apache-2.0"`, 2026-09-25; история коммитов там же прямо фиксирует «this repository is not ours to relicense — keep Apache-2.0», т.е. чужой Prosperity-код туда специально не пускали). Наш проект и так не монетизируется (`the project notes`), поэтому лицензия Prosperity формально не блокер, но раз реальный, актуальный, Apache-2.0-лицензированный код уже лежит в нашей же основной зависимости (`chia-wallet-sdk`) — разумнее и чище опираться на него, а не копировать что-либо из `slot-machine`.

### Как работает Managed-тип

```rust
// crates/chia-sdk-driver/src/primitives/action_layer/reward_distributor_info.rs:85-88
pub enum RewardDistributorType {
    Managed { manager_singleton_launcher_id: Bytes32 },
    NftCollection { ... },
    CuratedNft { ... },
    Cat { ... },
}
```
Managed-режим — это один из четырёх вариантов, каким может быть авторизован набор «привилегированных» действий дистрибьютора (`add_entry`/`remove_entry` — управление списком получателей и их долей). Механизм авторизации (на примере `RewardDistributorAddEntryAction`, `crates/chia-sdk-driver/src/layers/action_layer/actions/reward_distributor/add_entry.rs`):

1. Action-пазл каррируется с `manager_singleton_struct_hash` — хешем, однозначно завязанным на `manager_singleton_launcher_id` (строка 62-63 файла: `SingletonStruct::new(manager_launcher_id).tree_hash()`). Это привязка «только этот конкретный синглтон-менеджер может авторизовать».
2. При спенде (`fn spend`, строки 102-129) action создаёт `Conditions::new().send_message(18, ...)` — CHIP-50 **типизированное сообщение** (опкод 18 = `SEND_MESSAGE`) с payload `RewardDistributorReceivedMessagePrefix::add_entry(payout_puzzle_hash, shares)`, адресованное коину дистрибьютора.
3. Само сообщение реально «выполняется» только если **менеджер-синглтон в этой же транзакции** тратится и посылает такое сообщение — то есть менеджер должен подписать свой собственный спенд (обычным `AGG_SIG_ME` от своего inner-пазла, как у любого синглтона/DID), и именно это и есть фактическая авторизация. Без спенда правильного синглтона-менеджера в этой же транзакции action-пазл дистрибьютора спенд не пропустит (проверка через `ASSERT_MESSAGE`/аннотацию, стандартный CHIP-50 cross-coin-message паттерн).
4. Остальные действия дистрибьютора (`sync`, `add_incentives`, `commit_incentives`, `initiate_payout`, `new_epoch`, `withdraw_incentives`) **не** зависят от `RewardDistributorType` — они одинаковы во всех четырёх режимах и решают задачи начисления/раздачи вознаграждений, epoch-логики и т.п.

Иными словами: «Managed» — это просто «кто угодно, владеющий отдельным управляющим синглтоном, может добавлять/убирать записи получателей», реализовано как стандартный CHIP-50 cross-coin-message между двумя синглтонами внутри одной транзакции. Остальная масса кода RewardDistributor (epoch-раунды, reserve/резерв CAT или XCH, slots для хранения записей, payout-approval, withdrawal share bps и т.д.) — не про авторизацию, а про механику раздачи вознаграждений, к нашей задаче (публикация цены) отношения не имеет.

### Рекомендация («предложение» — решить в неделю 1)

**(c) — минимальный кастомный синглтон «подпись менеджера + пересоздание себя + announcement (price, ts, seq)», не переиспользовать RewardDistributor целиком и не портировать его action-layer/slot-machinery.**

Обоснование:
- **(a) Переиспользовать RewardDistributor целиком — избыточно.** Это CHIP-50 action-layer-приложение с epoch-раундами, резервом (CAT/XCH), slot-хранилищем записей, payout-approval — весь этот функционал спроектирован для распределения вознаграждений между многими получателями, нам не нужен ни один из этих кусков для «раз в день/час опубликовать одно число с подписью».
- **(b) Скопировать только managed-паттерн (cross-coin `send_message` между двумя синглтонами)** — тоже избыточно для нашего случая: сам смысл паттерна — позволить **произвольному отдельному менеджер-синглтону**, потенциально с собственной сменяемой логикой владения, авторизовать действие. У нас нет двух разных синглтонов и необходимости разносить «управляющего» и «управляемого» — оракул целиком контролируется одним и тем же ключом Алекса.
- **(c) Минимальный вариант** технически проще и дешевле в газе: один синглтон (стандартный `SINGLETON_TOP_LAYER`, драйверы уже есть в `chia-sdk-driver` — используются везде, включая DID/NFT) с простым inner-пазлом: проверка `AGG_SIG_ME` от ключа Алекса → `CREATE_COIN` (пересоздание себя, стандартный singleton-паттерн, odd-value coin) → `CREATE_COIN_ANNOUNCEMENT`/`CREATE_PUZZLE_ANNOUNCEMENT` с payload `(price, timestamp, sequence)`. Это то, что описано в `the project plan` §3 в блоке `OraclePublish` и полностью реализуемо стандартными примитивами SDK без обращения к CHIP-50 вообще — Action Layer явно и осознанно оставлен для отдельного, полноценного `create_market/resolve/redeem`-приложения недели 2–3 (там он действительно нужен — состояние рынка, несколько действий, слоты).

**Решено: Алекс выбрал вариант (c) — минимальный синглтон (D-11 в `the decision log`, 2026-09-25); `the project plan` обновлён.** Ниже — исходная постановка вопроса.

**Отклонение от зафиксированной рамки — решение за Алексом.** `docs/selection/final_top3.md` §2 и `the project plan` §3/§5 фиксируют путь «managed-синглтон по паттерну CHIP-51 (`RewardDistributorType::Managed`)» (см. цитаты и номера строк в начале этого раздела). Рекомендация (c) выше — минимальный кастомный синглтон без Action Layer вообще — **не уточняет эту рамку, а отклоняется от неё**. Это решение не автора этого документа, а Алекса; вынесено в `the decision log` → «Открытые вопросы» / журнал архитектурных решений. Два варианта и их конкретная цена:

- **(A) Скопировать `RewardDistributorType::Managed` (CHIP-51-паттерн, зафиксированный в рамке).** Нужны: отдельный «менеджер»-синглтон + сам синглтон-оракул под Action Layer (`crates/chia-sdk-driver/src/layers/action_layer/...`, `Slot`, `ActionLayer`, каррирование action-пазла с `manager_singleton_struct_hash`, CHIP-50 `SEND_MESSAGE` (opcode 18)/`ASSERT_MESSAGE` между двумя коинами в одной транзакции — механизм разобран выше в «Как работает Managed-тип»). На практике на каждый publish придётся тратить **оба** синглтона в одной транзакции (два `AGG_SIG_ME` вместо одного, минимум два `CREATE_COIN` на пересоздание себя, плюс сам action-пазл дистрибьютора поверх пазла оракула) вместо одного синглтона с одной подписью. Прямого замера cost для этого пути в проекте нет — Managed не реализован и не бенчмаркался в этом репозитории — **UNVERIFIED**: ожидаемо дороже варианта (B) за счёт второго коина/спенда и более тяжёлого action-пазла, но точное число не измерено.
- **(B) Минимальный кастомный синглтон (рекомендация (c) выше) — уже реализован и протестирован.** `chain/sim-spike/tests/oracle_singleton.rs`: один `SINGLETON_TOP_LAYER` со `StandardLayer`-inner (один `AGG_SIG_ME` от ключа Алекса) → `CREATE_COIN` (пересоздание) + `CREATE_PUZZLE_ANNOUNCEMENT` `(price, timestamp, sequence)`, без Action Layer/CHIP-50. Живой прогон теста сегодня (`cargo test --test oracle_singleton oracle_singleton_three_price_updates -- --nocapture`, 2026-09-25): исполнение самого синглтон-спенда — `107894` cost в установившемся режиме (`77046` в первом раунде); полный бандл (синглтон + независимый consumer-коин, проверяющий announcement) — `total_cost=29979226` (`byte=23844000`, `execution=135226`, `condition=6000000`) в раундах 2–3.

Оба варианта технически рабочие; выбор между «строго по зафиксированной рамке, дороже (A)» и «дешевле и проще, но отклонение от рамки (B)» — за Алексом, не решено этим проходом.

---

## 9. CHIP-50 / Action Layer — референсы для недели 2–3

- **Референсные приложения (CATalog, XCHandles, RewardDistributor)** — актуальный, поддерживаемый код теперь в `xch-dev/chia-wallet-sdk`:
  - `crates/chia-sdk-driver/src/primitives/action_layer/{catalog_registry,catalog_registry_info,xchandles_registry,xchandles_registry_info,reward_distributor,reward_distributor_info}.rs`
  - `crates/chia-sdk-driver/src/layers/action_layer/actions/{catalog,xchandles,reward_distributor}/*.rs`
  - Плюс общая инфраструктура Action Layer: `crates/chia-sdk-driver/src/primitives/action_layer/{slot.rs,reserve.rs,state_scheduler.rs,precommit_coin.rs,uniqueness_prelauncher.rs,cat_maker.rs,medieval_vault.rs}`.
  - `Yakuhito/slot-machine` — CLI/справочник по вызову этих же примитивов (Prosperity-лицензия, см. §8 — не копировать код, можно смотреть как пример использования).
- **CHIP-50 (Action Layer and Slots)** — `Chia-Network/chips`, `CHIPs/chip-0050.md`, статус **Final**, автор yakuhito, создан 2025-07-22, обсуждение `Chia-Network/chips#165` (проверено чтением файла, 2026-09-25). **CHIP-51 (Reward Distributor)** — `CHIPs/chip-0051.md`, тот же PR #165, тоже Final.
- **Rue-компилятор** — репозиторий `xch-dev/rue` («A typed Chia programming language targeting CLVM bytecode»), последний релиз **`0.10.0`** (опубликован 2026-07-26). Команда установки (`rue-lang.com/docs/installation`, читано 2026-09-25):
  ```
  cargo install rue-cli
  cargo install clvm_tools_rs   # рекомендовано
  rue init     # создать проект
  rue build    # собрать puzzles/*.rue → CLVM
  ```
  `rue-cli` опубликован на crates.io (default_version `0.10.0`, 4 398 загрузок на 2026-09-25) — совпадает с GitHub-релизом. Опционально: `cargo install rue-lsp` для LSP/VSCode-расширения.
- **Rust driver API для action-layer приложений — да, подтверждено.** Все три референса имеют полноценные Rust-драйверы прямо в `chia-sdk-driver` (используются в собственных unit/integration-тестах SDK через `chia-sdk-test`), плюс экспорт в `chia-sdk-bindings` (napi/pyo3/wasm) — то есть можно либо писать новый action-layer app «с нуля» по образцу этих трёх (структура: `*_info.rs` для типов состояния/констант, `*.rs` для примитива-драйвера, `actions/*.rs` для отдельных действий — единообразный паттерн во всех трёх), либо в перспективе переиспользовать общую инфраструктуру (`Slot`, `Reserve`, `SingletonAction`-трейт и т.д.) для своего `create_market/resolve/redeem`.

---

## 10. Что нужно от Алекса

1. Ничего не требуется для маршрута coinset.org и P2P-пиров (оба работают без установки/ключей/аккаунтов) — можно начинать день 1 прямо сейчас с них.
2. Если решаем использовать маршрут «Sage подписывает неподписанные спенды» (§5): установить Sage (desktop), включить RPC в конфиге (`rpc.enabled = true`, порт `9257` по умолчанию), скопировать `<sage_data_dir>/ssl/wallet.crt`+`.key` инструменту.
3. **Решить открытый вопрос §9 ниже** — как физически изолировать testnet11-профиль Sage от того, что может делать `a sibling project` в той же установке Sage на той же машине (программного способа переопределить каталог данных в прочитанном коде не нашлось).
4. Faucet (`https://testnet11-faucet.chia.net/`, **не** `faucet.chia.net` — тот mainnet-only) — вручную, через браузер (капча), с адресом получателя (`txch1...`, поле «Receive Address», не Master Public Key) нового testnet11-кошелька — только когда понадобятся первые testnet11-XCH для комиссий (не сделано в этом проходе намеренно, по правилам задачи).

---

## 11. Открытые вопросы, оставшиеся после этого прохода

- **Изоляция Sage-профилей.** Код не подтверждает штатного способа держать два полностью независимых Sage (разные `data_dir`, разные RPC-порты, разные `wallet.crt`) на одной машине/одном пользователе ОС одновременно — `NetworkConfig.default_network` глобален на всё приложение, `app_data_dir()` жёстко завязан на bundle id. Нужно решить: отдельный fingerprint внутри одной установки (сеть переключается вручную и может конфликтовать с параллельной работой `a sibling project`), отдельный пользователь ОС, или что-то ещё. **UNVERIFIED**, не наш вопрос без явного решения Алекса — заявлено к решению, не решено этим проходом.
- **Точный флоу `Sage::submit()`** (как Sage физически пушит подписанный bundle в сеть) — не оттрассирован до конца, не критично для архитектурного решения.
- **Публичная документация `coinset.org` API** (за пределами исходников клиентов) — отдельного `docs.coinset.org` нет; человеко-читаемый сайт `www.coinset.org` есть, но не проверялось, документирует ли он именно REST-эндпойнты или только показывает данные сети.
