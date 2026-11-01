# Неделя 1 — вход подписью Chia-кошелька (Sage), решение D-4

Дата проверки всех фактов ниже — **2026-09-25** (если у факта не указана другая дата явно). Методы проверки: `gh api` (чтение исходников `xch-dev/sage`, `Chia-Network/chia-blockchain`, `Chia-Network/chia_rs`, `Chia-Network/chips`, `dashpay/dashj-bls`, `Chia-Network/walletconnect-rpcs-dapp` — все репозитории публичные, читаются без побочных эффектов, без транзакций и без faucet-запросов), `search/code`/`search/repositories` (GitHub code search), `crates.io`/Maven Central (`search.maven.org`) API. Источники внутри проекта: `the decision log` D-4, D-1, D-2, D-12, D-15, D-20; `the project plan` §3; `docs/research/sage_apps_research.md`.

Закрывает пункты 1–5 задания недели 1 по входу игрока.

---

## 0. Ответ одной строкой

Для веб-клиента (React, неделя 4) правильный маршрут — **WalletConnect v2, методы `chia_signMessageByAddress` (предпочтительно) или `chip0002_signMessage`**, подтверждено чтением исходников Sage сегодня. У этого маршрута ровно один внешний registration-пункт — **свой `projectId` WalletConnect/Reown Cloud** (см. §1.2, §5) — это его нужно завести Алексу, агент этого не делает сам (правило проекта об исходящих действиях). Sage Apps bridge подписывать умеет (`wallet.signMessage`, см. §1.3), но это **desktop-only рантайм внутри самого Sage** (`sage-app://…`), не подходит для обычного публичного сайта. Готового стандарта «Sign in with Chia» с challenge/nonce в природе не существует — эта часть (§4) полностью на нас, «предложение».

---

## 1. Как веб-приложение просит Sage подписать сообщение

### 1.1 WalletConnect v2 — точные имена методов и формы запроса/ответа

Sage подключается к дефолтному реле `wss://relay.walletconnect.org` через `@walletconnect/sign-client`, слушает `session_proposal`/`session_request` и диспетчерит по имени метода в свою собственную таблицу команд `walletConnectCommands` (`xch-dev/sage`, `src/walletconnect/commands.ts`, HEAD `main` = `f2ec89dd`, читано 2026-09-25). Два метода реально подписывают сообщение:

| Метод (namespace `chia`) | Откуда | Параметры (zod-схема, `commands.ts:148-154` / `:236-244`) | Возврат | `confirm` (модалка юзеру) | Rust-обработчик |
|---|---|---|---|---|---|
| `chip0002_signMessage` | CHIP-0002 (namespace дополнен WalletConnect-транспортом) | `{ message: string, publicKey: string }` | `string` (подпись, hex) | `true` | `sign_message_with_public_key` → `commands.signMessageWithPublicKey` (`handler` в `src/walletconnect/commands/chip0002.ts:96-107`) |
| `chia_signMessageByAddress` | Sage-специфичное расширение (не в тексте CHIP-0002) | `{ message: string, address: string }` | `{ publicKey: string, signature: string }` | `true` | `sign_message_by_address` (`src/walletconnect/commands/high-level.ts:79-88`) |

Rust-сторона (`crates/sage/src/endpoints/wallet_connect.rs:172-203` — `sign_message_with_public_key`, `:207-241` — `sign_message_by_address`, тот же HEAD) — оба обработчика делают буквально одно и то же (см. §2), различие только в том, чем идентифицируется ключ: публичным ключом напрямую или адресом (через который сервис сам находит `public_key` в БД кошелька, `wallet.db.public_key(p2_puzzle_hash)`).

**Для входа игрока лучше `chia_signMessageByAddress`**: одним запросом получаем и адрес (для отображения по D-15 — «ник + сокращённый адрес»), и `publicKey`, использованный для подписи, без отдельного вызова `chip0002_getPublicKeys`.

**Сессия WalletConnect.** Sage принимает предложение сессии только если в `requiredNamespaces.chia.chains` (или `optionalNamespaces`) есть `chia:testnet` или `chia:mainnet` — **не** `chia:testnet11`, разделения по testnet-сетям на уровне WC-namespace у Sage нет (`src/contexts/WalletConnectContext.tsx:174-176`). Идентификатор аккаунта, который Sage возвращает при `approve`, — `chia:${network.kind}:${wallet.fingerprint}` (`WalletConnectContext.tsx:187`), т.е. содержит **fingerprint** кошелька, не адрес. Реальную сеть (testnet11 vs другая testnet) и актуальный адрес нужно получать отдельными вызовами (`chip0002_chainId`, `chia_getAddress`) — намespace сам по себе это не гарантирует.

### 1.2 Project ID WalletConnect/Reown — да, отдельная регистрация нужна

Сам Sage инициализирует **свой** `SignClient` с собственным `projectId` (жёстко зашит в код):

```ts
// xch-dev/sage, src/contexts/WalletConnectContext.tsx:79-90
SignClient.init({
  projectId: '7a11dea2c7ab88dc4597d5d44eb79a18',
  relayUrl: 'wss://relay.walletconnect.org',
  metadata: { name: 'Sage Wallet', ... },
})
```

Это **не наш** projectId — у Sage свой. WalletConnect v2 требует, чтобы **каждая сторона** (и кошелёк, и dApp) инициализировала клиент со своим `projectId`, выданным Reown Cloud (бывший WalletConnect Cloud, `cloud.reown.com`), иначе `SignClient.init` не подключится к публичному реле. Это подтверждает и официальный тестовый dApp самой Chia-Network:

> «Note that the `.env` file contains `VITE_PROJECT_ID` and `VITE_CHAIN_ID` constants. The former is from WalletConnect and identifies your app inside of the wallet.»
> — `Chia-Network/walletconnect-rpcs-dapp`, `README.md` (HEAD `main` = `1de70270`, читано 2026-09-25)

и код там же: `Client.init({ logger: 'error', projectId, relayUrl, metadata })` (`src/contexts/WalletConnectContext.tsx:119-124`), где `projectId` приходит параметром снаружи (из `.env`).

**Вывод**: чтобы наш React-сайт мог открыть WalletConnect-сессию, нужен собственный `projectId` — то есть аккаунт на `cloud.reown.com` и создание там проекта (бесплатный тир есть, но это регистрация). По правилу проекта («исходящие действия… регистрации — только с явного разрешения Алекса», `the project plan` §9) агент это не делает сам — см. §5 «Что нужно от Алекса».

### 1.3 Sage Apps bridge — подписание есть в списке методов, но маршрут не годится для публичного сайта

`docs/research/sage_apps_research.md` (эта же сессия, тот же день) насчитал **37 `UserBridgeCapability`** и явно перечисляет группу «Wallet — write/sign» (5 методов, все `externally_observable` и per-request-approval-gated): `send_xch`, `send_xch_auto_submit`, `sign_coin_spends`, **`sign_message`**, `send_transaction` (`sage_apps_research.md`, раздел «2. Capabilities / bridge methods»). Подтверждено сегодня повторным чтением кода:

```rust
// xch-dev/sage, crates/sage-apps/src/bridge/methods/user/wallet/sign_message.rs:44-50
impl BridgeMethod for WalletSignMessage {
    fn name(&self) -> &'static str { "wallet.signMessage" }
    fn capability(&self) -> BridgeMethodCapability {
        BridgeMethodCapability::user(UserBridgeCapability::WalletSignMessage)
    }
```

Внутри `wallet.signMessage` вызывает тот же `sign_message_with_public_key`, что и WalletConnect (§1.1) — то есть криптографически это идентичный маршрут.

**Почему не используем для входа на публичный сайт**: Sage Apps — это рантайм **внутри Sage**, приложение обслуживается по кастомному протоколу `sage-app://<origin-id>/...` из собственного webview Tauri, и весь этот код регистрируется только `#[cfg(not(mobile))]`, т.е. **desktop-only** (`crates/sage-apps/src/security/protocol.rs:71-110`, `src-tauri/src/lib.rs:157-183` — обе ссылки уже проверены в `sage_apps_research.md`). Наш игровой сайт — обычная публичная веб-страница в обычном браузере; чтобы её страница входа шла через Apps bridge, пришлось бы упаковывать её как Sage-приложение, которое пользователь сам ставит внутрь Sage по URL (`crates/sage-apps/src/lifecycle/install/url.rs`) — то есть сама страница входа жила бы внутри Sage, а не на нашем сайте. Это не регистрация и не требует стороннего projectId, но меняет модель распространения (только desktop-Sage, никакого мобильного/браузерного захода без Sage) и не решает исходную задачу «человек открывает наш сайт → входит». Держим как запасной вариант, если WalletConnect route по каким-то причинам не пойдёт.

### 1.4 CHIP-0002 / `window.chia` (Goby) — не реализовано в Sage

CHIP-0002 (Status: **Final**, `Chia-Network/chips/CHIPs/chip-0002.md`, HEAD `main` = `25610296`, читано 2026-09-25) описывает инжект `window.chia` браузерным расширением-кошельком (`interface ChiaWindow extends Window { chia?: Wallet }`, `chip-0002.md:59-61`), а не WalletConnect. Референс-реализация, указанная в самом CHIP — **Goby** (`chip-0002.md:382`, «The Goby team has implemented the methods described above»), браузерное расширение, отдельное от Sage. Поиск по коду `xch-dev/sage` на `window.chia` сегодня дал **ноль совпадений** — Sage не инжектит `window.chia`, у него нет браузерного расширения; Sage реализует те же имена методов (`chip0002_*`), но поверх WalletConnect-транспорта, а не через `window.chia` (см. §1.1). Поскольку D-4 фиксирует именно Sage, ветка Goby/`window.chia` для нас неприменима — упомянута только по прямому пункту задания.

---

## 2. Точная схема подписи (что происходит внутри Sage)

Оба обработчика (`sign_message_with_public_key` и `sign_message_by_address`) делают одно и то же:

```rust
// xch-dev/sage, crates/sage/src/endpoints/wallet_connect.rs:197 (и идентично :236), HEAD f2ec89dd
let decoded_message = parse_signature_message(req.message)?;
let signature = sign(
    &secret_key,
    ("Chia Signed Message", decoded_message).tree_hash(),
);
```

т.е. подписывается **sha256tree** (CLVM tree-hash) пары `("Chia Signed Message", message_bytes)` секретным синтетическим ключом (`master_to_wallet_(un)hardened → derive_synthetic`), схемой **AugSchemeMPL** (BLS12-381, augmented — публичный ключ подмешивается в сообщение перед хешированием на кривую). Это буквально совпадает с описанием самого CHIP-0002:

> «The internal implementation in the wallet is `bls_sign(private_key, sha256tree(cons("Chia Signed Message", message))`. The scheme used in `bls_sign` is Augmented Scheme. To prevent replay attacks, dApps should add the current `networkId` and `timestamp` into the message.»
> — `Chia-Network/chips/CHIPs/chip-0002.md:236`

и с эталонной Python-реализацией в `chia-blockchain`:

```python
# Chia-Network/chia-blockchain, chia/wallet/util/signing.py, HEAD 38d4f8ce
CHIP_0002_SIGN_MESSAGE_PREFIX = "Chia Signed Message"   # chia/types/signing_mode.py
message_to_verify = Program.to((CHIP_0002_SIGN_MESSAGE_PREFIX, message)).get_tree_hash()
is_valid = AugSchemeMPL.verify(public_key, message_to_verify, signature)
```

Точный алгоритм BLS-верификации (`chia-bls`, часть `Chia-Network/chia_rs`, HEAD `main` = `7353dbbc`, крейт 975 176 загрузок на crates.io, обновлён 2026-09-23):

```rust
// crates/chia-bls/src/signature.rs:19, :348-370
const DST: &[u8] = b"BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_AUG_";   // фиксированный, не зависит от "режима"
pub fn verify(sig: &Signature, key: &PublicKey, msg: Msg) -> bool {
    let mut augmented_msg = key.to_bytes().to_vec();   // 48 байт compressed G1
    augmented_msg.extend_from_slice(msg.as_ref());
    // blst_core_verify_pk_in_g1(pubkey_affine, sig_affine, hash=true, augmented_msg, DST, …)
}
```

Важная деталь (проверена чтением кода, не документацией): `SigningMode` в Python (`chia/types/signing_mode.py`) перечисляет строки вида `"...AUG:CHIP-0002_"`, `"...AUG:utf8input_"` и т.д. — это **только описательные ярлыки режима** (какой префикс/wrap применён к сообщению до подписи), а не разные domain separation tag для hash-to-curve: реальный DST в коде BLS-примитива **один и тот же** для всех режимов (`chia-bls/src/signature.rs:19`, проверено — тестовый `DEFAULT_DST` в файле совпадает буква-в-букву). Верификатору не нужно ничего знать про «режимы» — только про предварительное оборачивание сообщения в `("Chia Signed Message", msg)` перед tree-hash.

**Как публичный ключ мапится на адрес** (нужно серверу, чтобы не доверять слепо связке address↔publicKey от клиента, см. §4): стандартная «синтетическая» деривация Chia-кошелька —

```python
# Chia-Network/chia-blockchain, chia/wallet/puzzles/p2_delegated_puzzle_or_hidden_puzzle.py:95-119
def calculate_synthetic_public_key(public_key, hidden_puzzle_hash) -> G1Element:
    synthetic_offset = PrivateKey.from_bytes(calculate_synthetic_offset(public_key, hidden_puzzle_hash)...)
    return public_key + synthetic_offset.get_g1()

def puzzle_hash_for_synthetic_public_key(synthetic_public_key) -> bytes32:
    return curry_and_treehash(QUOTED_MOD_HASH, shatree_atom(bytes(synthetic_public_key)))
```

с `hidden_puzzle_hash = DEFAULT_HIDDEN_PUZZLE_HASH` для обычных (не hardened-hidden) адресов; итоговый `puzzle_hash` кодируется bech32m с префиксом `txch` (testnet11) в адрес. В `chia_wallet_sdk` (Rust) та же формула инкапсулирована в `driver::StandardLayer` (уже используется внутри самого Sage, см. `wallet_connect.rs:90` — `StandardLayer::new(key).construct_puzzle(&mut ctx)`), поэтому Rust-верификатору (§3) не придётся реализовывать деривацию вручную — она уже есть в том же SDK, которым пользуется Sage.

**Формат `message` на входе — важная ловушка.** Sage сам решает, интерпретировать ли строку `message` как hex-байты или как UTF-8:

```rust
// xch-dev/sage, crates/sage/src/utils/parse.rs:118-130
pub fn parse_signature_message(input: String) -> Result<Bytes> {
    let stripped = input.strip_prefix("0x").unwrap_or(&input);
    if stripped.chars().all(|c| c.is_ascii_hexdigit()) && !stripped.is_empty() {
        Ok(Bytes::from(hex::decode(stripped)?))       // ⚠ интерпретируется как HEX
    } else {
        Ok(Bytes::from(input.as_bytes()))              // как UTF-8
    }
}
```

Если наш challenge-текст (см. §4) случайно **весь** состоит из hex-символов (`0-9a-fA-F`) — например, если это просто hex-нонс без обрамления — Sage подпишет не те байты, которые сервер ожидает как UTF-8-строку, а декодированные из hex, и подпись не сойдётся при обычной серверной проверке «сравни как строку». Решение — **предложение**: challenge-сообщение всегда начинается с фиксированного человекочитаемого префикса на нехексовых буквах (например `"Crowd vs Market login\n"`), так что вся строка целиком никогда не пройдёт тест `is_ascii_hexdigit()`. Дублируем это правило в реализации явным юнит-тестом на «весь текст — не hex».

---

## 3. Серверная верификация на Quarkus (Java 21)

Требуется реализовать: по `(publicKey, message, signature)` вернуть `bool`, используя схему AugSchemeMPL/BLS12-381 из §2 (фиксированный DST, augmented-подмешивание pubkey, `sha256tree(("Chia Signed Message", message))` как хешируемое сообщение).

### Вариант A — Rust-сайдкар поверх `chia_wallet_sdk`/`chia-bls` (предложение, рекомендуется)

Маленький Rust-бинарник (CLI, читает JSON из stdin/argv, пишет `{"valid": true/false}` в stdout; или процесс-сервис на `axum`, слушает только `127.0.0.1`, если частота вызовов оправдает постоянный процесс), который напрямую использует `chia_wallet_sdk::chia::bls::verify` (§2) и `driver::StandardLayer` (для проверки address↔publicKey) — тот же крейт и та же версия, которой пользуется сам Sage. Quarkus дёргает его через `ProcessBuilder`/HTTP на localhost.

- **За**: побайтовая гарантия совпадения с тем, что реально подписал Sage — ноль риска «наша реализация DST/augmented-схемы разошлась с оригиналом». Переиспользует Rust-стек, который неделя 1 уже поднимает для оракула (D-11, `the project plan` §3) — не новый язык в проекте, а тот же самый компонент.
- **Против**: отдельный процесс/бинарник рядом с Quarkus-JAR, кросс-платформенная сборка (dev — macOS, куда деплоить решается отдельно), IPC-обвязка.

### Вариант B — `org.dashj:dashj-bls` (Maven Central, чистый JVM-путь, не проверено на совместимость)

На Maven Central есть `org.dashj:dashj-bls` (последняя `1.0.1`, есть классификатор `-bundled.jar` — то есть нативные библиотеки под основные платформы упакованы в сам jar, ставить компилятор не нужно; `Apache-2.0`, репозиторий `dashpay/dashj-bls`, HEAD `master` = `9ab7f3eb`, читано 2026-09-25). Это JNI-обёртка (SWIG) поверх C++ `bls-signatures` — той же линии кода, от которой изначально произошла и Chia-эковская `bls-signatures` (общее происхождение, не идентичный форк). API один-в-один повторяет Chia-шный:

```java
// dashpay/dashj-bls, bls/src/main/java/org/dashj/bls/AugSchemeMPL.java:39,47,55
public class AugSchemeMPL extends CoreMPL {
    public static String getCIPHERSUITE_ID();  // геттер строки-ciphersuite, стоит свериться с "…AUG_"
    public G2Element sign(PrivateKey seckey, byte[] message);
    public boolean verify(byte[] pubkey, byte[] message, byte[] signature);
}
```

- **За**: остаёмся в JVM целиком, нет отдельного процесса; `-bundled` классификатор снимает боль со сборкой нативной части.
- **Против**: сторонняя (Dash, не Chia-Network) сборка; при переходе Chia с C++ `bls-signatures` на Rust `chia_rs`/`blst` в 2021 могли остаться нюансы (например, `getCIPHERSUITE_ID()` нужно явно сверить с `"BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_AUG_"`, а верификацию — прогнать на реальных тест-векторах, подписанных живым Sage, до того как доверять в проде). Это не сделано в рамках этого research — **открытый вопрос**, если вариант B выбирается вместо A.

### Вариант, отклонённый сразу — `blst` Java-биндинги напрямую

В `supranational/blst` есть каталог `bindings/java`, но там только `Android.mk`/`build.sh`/`runnable.java` — минимальная демонстрационная JNI-обвязка, **не публикуется на Maven Central** (поиск `search.maven.org` по `g:*blst*` ничего от supranational не нашёл). Пришлось бы самим писать и поддерживать JNI-слой поверх низкоуровневого `blst` — то же усилие, что вариант B, но без готового пакета и без Chia-specific логики (augmented-схема, DST, синтетические ключи) — всё равно пришлось бы реализовывать поверх примитивов вручную. Не годится как «готовое решение».

Eth2-ориентированные Java BLS-библиотеки (Teku/PegaSys) не рассматривались как альтернатива: они реализуют **POP**-схему (`…AUG:POP_`-DST для proof-of-possession), а не **Augmented**-схему, которую использует Chia — валидны напрямую только если библиотека позволяет подменить DST/scheme, что для «стандартных» Eth2-биндингов не типично; отдельно не проверялось (не нашли в Maven Central пакета `tech.pegasys:bls12-381` под этим именем при поиске сегодня — возможно, публикуется под другим `artifactId`, не потрачено время на уточнение, т.к. вариант A закрывает потребность с гарантированной точностью).

**Рекомендация (предложение, обоснование выше): вариант A** — Rust-сайдкар/сервис на `chia_wallet_sdk`. Причина в двух словах: нулевой крипто-риск (тот же код, что у Sage) + команда всё равно пишет Rust для оракула на этой же неделе, значит это не новый вид работы в проекте.

---

## 4. Challenge/nonce login-flow (SIWE-like)

Готового стандарта нет — ни в CHIP-0002, ни где-либо ещё найденном (см. §5). Ниже — предложение, минимально закрывающее replay-защиту, которую CHIP-0002 прямо требует делать на стороне dApp (`chip-0002.md:236`, цитата в §2), плюс ловушку из §2 (сообщение не должно быть «весь hex»).

```
1. React → POST /auth/challenge { address? }                      (address опционален,
                                                                     если UI сперва зовёт
                                                                     chia_getAddress)
   Backend: nonce = random(24 bytes, base64url); TTL = 5 мин.
   message = "Crowd vs Market — вход в игру\n"
           + "Домен: <наш-домен>\n"
           + "Адрес: <address или '?'>\n"
           + "Сеть: testnet11\n"
           + "Nonce: <nonce>\n"
           + "Выдано: <ISO8601 timestamp>\n"
           + "Истекает: <ISO8601 timestamp + 5 мин>"
   Backend сохраняет { nonce → { message, expiresAt, used=false, address? } }.
   Backend → React: { nonce, message }

2. React → WalletConnect session (если ещё не открыта: chip0002_connect/getPublicKeys
   или сразу session_proposal с namespace chia) → commands:
     chia_getAddress()                        → { address }
     chia_signMessageByAddress({ message, address }) → { publicKey, signature }
   (модалка Sage показывает message пользователю на подтверждение — confirm: true,
    см. §1.1 — это единственный шаг, где пользователь явно видит, что подписывает.)

3. React → POST /auth/verify { nonce, address, publicKey, signature }

   Backend:
   a) nonce найден, не использован, не просрочен, (если address был передан на шаге 1 —
      совпадает) → иначе 401.
   b) message пересобирается на сервере из сохранённых полей (nonce/address/timestamps) —
      НИКОГДА не берётся из тела запроса клиента (иначе клиент мог бы подсунуть другой текст,
      который сервер бы «проверил» как валидный).
   c) address↔publicKey: puzzle_hash_for_synthetic_public_key(publicKey) сравнивается
      с decode_puzzle_hash(address) (§2) — не доверяем слепо, что publicKey действительно
      соответствует address, даже если это обычно гарантирует сам Sage.
   d) verify(signature, publicKey, sha256tree(("Chia Signed Message", message_bytes)))
      через выбранный из §3 верификатор.
   e) nonce помечается used=true (одноразовый, дальше не работает — защита от replay).
   f) находим/создаём игрока по address (D-1/D-4); если это первый вход — начисляем
      стартовый пакет PLAY (D-2/D-12: 10 000 PLAY) и просим выбрать ник (D-15).
   g) выдаём серверную сессию (например, httpOnly-cookie с непрозрачным session id,
      либо JWT — конкретный механизм решить в неделю 1 как часть Quarkus-стека, D-20;
      не зафиксировано в docs/, помечаю предложением без выбора конкретной реализации
      здесь, т.к. это отдельный вопрос сессионного слоя, не входа).
```

Ключевое, чего криптографический слой **не** даёт сам по себе: у подписанного Chia-сообщения нет встроенного времени жизни или anti-replay — это просто BLS-подпись байтовой строки. Вся защита от replay целиком лежит на нас: одноразовый серверный nonce + TTL + пересборка `message` на сервере (шаг b), а не сверка текста, присланного клиентом.

---

## 5. Прежние реализации и что нужно от Алекса

**Найдено:**
- `Chia-Network/walletconnect-rpcs-dapp` — официальный демо-dApp от Chia-Network, тестирует WalletConnect-команды Chia (включая `SignMessageByAddress`), явно документирует необходимость своего `VITE_PROJECT_ID` (§1.2). Помечен авторами как «demonstration purposes only… not actively maintained» — годится как справочник по интеграции `@walletconnect/sign-client`, не как готовая либа для продакшена.
- Готового «Sign in with Chia» / SIWE-аналога для Chia в найденном не существует — поиск GitHub (`search/repositories` по `"sign in with chia"`) не дал результатов. Это значит, что весь §4 — наша собственная схема, не сверка с чужим стандартом.
- `DIG-Network` (`dig-sdk`, `dig-chrome-extension`, `dig-node`, `chia-provider`) — сторонний (не Chia-Network, не xch-dev) стек, активно использует те же имена методов `chip0002_*`/`chia_*` поверх WalletConnect и `window.chia`-подобного провайдера; не кошелёк, а инструментарий для dApp-разработчиков. Подтверждает, что имена методов из §1.1 — установившийся де-факто набор за пределами одного Sage, но самой логики входа/challenge там не нашли за отведённое время — не изучалось глубже.

**Что нужно от Алекса:**
- Завести аккаунт и проект на `cloud.reown.com` (WalletConnect Cloud) ради собственного `projectId` для нашего React-сайта — без этого `SignClient.init` на стороне dApp не подключится к реле WalletConnect (§1.2). Регистрация — по правилу проекта только с явного разрешения/руками Алекса, агент не делает этого сам.
- Ничего больше по этому пункту исследование не требует: сам Sage, чтобы подписывать наши challenge-сообщения, ничего специально настраивать не должен (WalletConnect в Sage включён по умолчанию, RPC отдельно поднимать не нужно — в отличие от Sage RPC-маршрута из `docs/research/week1_chain_access.md` §5, который для входа не используется).

---

## Реализация (2026-09-26, D-53/D-55/D-57 — веб-агент)

Закрывает task item 2 (реальный вход через Sage/WalletConnect в `web/`) и открытый вопрос 1 ниже
(D-57 фиксирует идентичность игрока — см. правку в самом конце этого раздела). Проверено сегодня
живым чтением исходников (`gh api`), не по памяти вчерашнего research — там, где факт совпал с
§1–§5 выше, это отдельно отмечено «подтверждено».

### Библиотека и её лицензия — `@walletconnect/sign-client`

`web/src/wallet/walletConnect.ts` использует `@walletconnect/sign-client@2.25.0` (npm, проверено
`npm view` 2026-09-26). **Важный факт, не проверенный вчера:** `npm view` показывает
`license: "SEE LICENSE IN LICENSE.md"` — это не MIT/Apache-2.0, а собственная лицензия Reown, Inc.
Прочитано целиком (`gh api repos/WalletConnect/walletconnect-monorepo/contents/packages/sign-client/LICENSE.md`,
2026-09-26, файл идентичен на тегах `v2.0` и `main` на момент чтения) — **«WALLETCONNECT COMMUNITY
LICENSE AGREEMENT»**, «Release Date: 20 August 2025»:

- **§3 «Limited commercial use»**: бесплатно (royalty-free) ниже **2 500 000 RPC/месяц или 500
  MAU/месяц**; выше — нужна отдельная коммерческая лицензия у Reown. Закрытая альфа (D-42: «5–10
  человек по ссылке») далеко ниже обоих порогов — пересмотреть при более широком открытии.
- **§2.b «Network Connection»**: обязательное условие лицензии — подключаться к «proprietary Reown
  gateway infrastructure» (их сети реле), не к самостоятельно поднятой инфраструктуре. Ровно это и
  делает модуль (`RELAY_URL = "wss://relay.walletconnect.org"`, тот же дефолт, что использует сам
  Sage, §1.2 выше) — не проблема, но explicit lock-in стоит знать.
- **§2.a «Distribution Requirements»**: обязательная строка атрибуции — **«Portions © 2025 Reown,
  Inc. All Rights Reserved»** — добавлена в `web/README.md`.
- **§1.c**: любые модификации/производные самого пакета переходят в собственность Reown (не
  относится к нашему собственному коду вокруг него — «your applications and wallets… are not
  modifications or derivative works», §1.c(ii)).

Наш собственный код остаётся Apache-2.0 (D-36) — это чужая зависимость на её собственных
условиях, как любая другая, просто впервые в этом проекте не MIT/Apache-2.0. Размер: пакет сам по
себе ~2,7 МБ unpacked (`npm view @walletconnect/sign-client dist.unpackedSize`) плюс дерево
зависимостей (86 пакетов при установке, `npm install`, 2026-09-26) — подгружается лениво
(`import()` внутри `walletConnect.ts`, тот же паттерн, что `chia-wallet-sdk-wasm` в
`testWallet.ts`), собранный чанк — ~132 КБ gzip (`npm run build`, вынесен отдельно от главного
чанка).

### QR-код — `qrcode-generator`

И адрес депозита (D-53, §8 `ux_spec.md`), и pairing-URI WalletConnect (ниже) показываются QR-кодом
через `qrcode-generator@2.0.4` (npm, MIT, ~15 КБ, без единой транзитивной зависимости — проверено
`npm view` 2026-09-26). Классический энкодер kazuhikoarase, встроенный в множество других
проектов. Рисуется как обычный inline-SVG по сетке модулей библиотеки (`web/src/wallet/QrCode.tsx`)
— не через собственный `createSvgTag()` пакета — чтобы получившийся SVG был обычными React-
элементами. Не вынесен в отдельный ленивый чанк — слишком маленький, чтобы это того стоило (в
отличие от `chia-wallet-sdk-wasm`/`@walletconnect/sign-client`).

### Точная схема — переподтверждено сегодня, не только вчера

`chia:testnet` (не `chia:testnet11`, §1.1 выше) — единственный namespace-чейн, который реально
запрашивается (`requiredNamespaces.chia.chains`). Методы — `chia_getAddress` и
`chia_signMessageByAddress`, оба перечитаны сегодня напрямую из `xch-dev/sage`,
`src/walletconnect/commands.ts`, HEAD `f2ec89dd` (тот же коммит, что вчера — репозиторий не
двигался):

```ts
// src/walletconnect/commands.ts:229-235 (HEAD f2ec89dd, читано 2026-09-26)
chia_getAddress: {
  paramsType: z.object({}),
  returnType: z.object({ address: z.string() }),
  confirm: false,   // ← нет модалки подтверждения в Sage для этого вызова
},
// src/walletconnect/commands.ts:236-245
chia_signMessageByAddress: {
  paramsType: z.object({ message: z.string(), address: z.string() }),
  returnType: z.object({ publicKey: z.string(), signature: z.string() }),
  confirm: true,
},
```

Это закрывает единственный факт вчерашнего §4, который был «предположением по аналогии с
референсным dApp», а не цитатой строки исходника — `chia_getAddress` действительно существует в
Sage именно с этой сигнатурой.

### Что реализовано (`web/src/wallet/walletConnect.ts`, `ConnectWalletModal.tsx`)

- Пейринг: `SignClient.connect({ requiredNamespaces })` → показ `uri` (QR + кнопка «Copy
  connection link» — одна и та же ссылка годится и для сканирования с телефона, и для вставки в
  сам Sage на том же устройстве, task item 2's «same-device mobile» случай) → `approval()`.
- Session persistence: `SignClient.init` без опции `storage` (тот же вызов, что у самого Sage,
  §1.2) — в браузере сохраняет сессии сама, между перезагрузками страницы; при следующем открытии
  модалки уже одобренная `chia`-сессия переиспользуется без нового QR (`ensureSession` в коде).
- Disconnect: подписка на `session_delete` (Sage отключился сам, либо сессия истекла) — очищает
  локально запомненный адрес, чтобы следующий коннект не тихо считал себя тем же игроком без новой
  подписи; `disconnectSage()` вызывается из общего `signOut()` приложения (лучшее усилие, никогда
  не блокирует сам выход).
- **Идентичность игрока (D-57, закрывает открытый вопрос 1 ниже):** решение принято главной
  сессией 26.09 после этого research — identity = адрес, которым подписана регистрация. Реализация:
  при первом коннекте адрес берётся через `chia_getAddress()`; при каждом следующем коннекте с
  этого же браузера используется **тот же** адрес (сохранён в `localStorage` после успешной
  `authVerify`), передаётся прямо в `chia_signMessageByAddress`, `chia_getAddress` уже не
  вызывается — даже если у Sage сменился активный адрес получения. `fingerprint` из id сессии
  WalletConnect нигде не используется как identity, только сама WalletConnect-сессия — ровно как
  D-57 и формулирует.
- Юнит-тесты (`walletConnect.test.ts`) — мок всего `@walletconnect/sign-client` (task item 2's
  «unit tests with a mocked SignClient»): проверены namespace/методы/порядок вызовов, обе ветки
  идентичности (новый игрок vs повторный вход), реакция на `session_delete`, `disconnectSage`. Ни
  один тест не касается реального реле/Sage — недостижимо из этой среды, как и вчера (§0).

### Не проверено (честно)

Ни один из шагов выше не прогонялся против **живого** Sage — в этой среде нет ни установленного
Sage, ни доступа к реальному WalletConnect-реле для сквозной проверки. Уверенность в точности
методов/схемы — из прямого чтения исходников Sage (тот же уровень доказательности, что и вчерашний
research), не из живого теста. Первая реальная проверка «QR → Sage → подписанный вход» произойдёт
только когда у Alex будет `projectId` и живой Sage под рукой (D-55: «Сначала Reown»).

---

## Открытые вопросы (не закрыты этим research, нужно решение Алекса/команды в неделю 1)

1. ~~**Идентификатор игрока в БД.**~~ **Закрыто D-57 (2026-09-26, `the decision log`)**:
   адрес, которым игрок подписал регистрацию; последующие входы подписываются тем же адресом.
   Реализация — см. «Реализация» выше. (Исходная формулировка вопроса сохранена для истории:
   WalletConnect-сессия отдаёт `fingerprint` кошелька, а вход по challenge — конкретный `(address,
   publicKey)`; адреса Sage ротируются, так что «текущий» `chia_getAddress()` при повторном входе
   мог бы дать другой адрес и дублирующегося игрока — отсюда и решение фиксировать identity на
   первом адресе, а не переспрашивать его каждый раз.)
2. **Механизм серверной сессии после верификации** (JWT vs httpOnly-cookie session id vs что-то в Quarkus-экосистеме) — сознательно не выбран в §4п.g, это отдельный вопрос сессионного слоя.
3. **Вариант A vs B из §3** (Rust-сайдкар vs `dashj-bls`) — предложен вариант A с обоснованием; если команда предпочтёт остаться целиком в JVM, `dashj-bls` требует отдельной проверки на тест-векторах реального Sage перед тем, как доверять ему в проде (не сделано в рамках этого research).
4. **`docker-compose.yml`'s `web` build не передаёт `VITE_WALLETCONNECT_PROJECT_ID`** — вне
   назначенных путей этой сессии (`web/Dockerfile` принимает его как необязательный `ARG` с
   пустым значением по умолчанию, готов принять `--build-arg`, но сам compose-файл ещё не даёт
   такой аргумент). Не блокирует: как только у сервера появится `serverInfo
   .walletConnectProjectId` (тоже пока не реализовано на `server/` — см. `contract.ts`'s заметка
   для следующего, кто коснётся `server/`), кнопка включится без пересборки образа вообще.
