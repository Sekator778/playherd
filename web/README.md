# Crowd vs Market — фронтенд

Vite + React + TypeScript (strict) + react-router. Собран против `src/api/contract.ts`
(TS-зеркало будущего Quarkus API, Р-20/Р-35). Два `Api`: `MockApi` (`src/api/mockApi.ts`,
детерминированные тестовые данные, без сети) и `RealApi` (`src/api/realApi.ts`, HTTP поверх
FIXED INTERFACE (1) — `POST /api/rpc/<method>`). Выбор — переменной сборки `VITE_API_MODE`
(`src/api/apiMode.ts`): `mock` по умолчанию (`npm run dev`), `real` в Docker-сборке
(`Dockerfile`). Ни один компонент не знает, какой из двух активен (в этом и была цель
контракта) — все читают через `useApi()`/`ApiContext.tsx`.

Источники: `docs/product/decisions.md` (Р-1…Р-48), `docs/product/ux_spec.md`,
`docs/research/hl_liquidation_math.md`, `docs/research/round_engine_design.md`,
`docs/research/week1_wallet_auth.md`. UI-копирайт — на английском (Р-24), этот файл — на
русском, код и комментарии — на английском.

## Стек и почему

Vite/React/TS(strict)/react-router заданы условием задачи. Остальной выбор — ниже, зависимостей
по минимуму; лицензии в основном MIT/Apache-2.0, кроме `@walletconnect/sign-client` (см. ниже —
собственная лицензия Reown, не OSI, использована как есть, наш код остаётся Apache-2.0, Р-36):

- **decimal.js** (MIT) — BigDecimal-эквивалент. `src/api/liquidation.ts` — точный TS-порт
  `LiquidationCalculator.java`/`PositionSizing.java`, формула в формулу, с той же схемой
  округления (`MathContext.DECIMAL128` ⇒ `precision: 50` + `ROUND_HALF_UP` везде,
  количество знаков — как в Java: 2dp для денег, 5dp для размера BTC). `liquidation.test.ts`
  воспроизводит все 20 строк золотой таблицы `hl_liquidation_math.md` §3 (маржа, цена
  ликвидации, payout) плюс выборку исторических сценариев §5 — так TS и Java физически не
  могут разойтись незамеченными.
- **recharts** (MIT) — график «толпа vs BTC vs анти-толпа» (§5/§3 `ux_spec.md`: три линии,
  легенда, переключатель диапазона, отметки границ сезонов). **Предложение**: recharts взят за
  минимальную обвязку с React (декларативные `<Line>`, готовый `ResponsiveContainer`) при MIT
  лицензии; рассматривались `visx` (гибче, но многословнее для одного line-chart) и
  `lightweight-charts` (Apache-2.0, заточен под свечи/книгу ордеров — избыточен для трёх линий
  с легендой). Вынесен в отдельный ленивый чанк (`React.lazy`, `LazyCrowdChart.tsx`), чтобы
  экраны без графика не платили за его вес.
- **Стилизация — обычный CSS**, без библиотек: один `src/index.css` с CSS-переменными
  (токены цвета/радиуса/тени). **Предложение**: ноль зависимостей, полный контроль над
  контрастом (WCAG AA, см. ниже) и над «игровым» стилем Р-33 (крупные UP/DOWN, таймер, яркие,
  но проверенные на контраст цвета — тёмная тема в духе Twitch Predictions, светлой темы нет:
  ни один источник её не требует, а для «игрового» экрана одна выверенная тема читается лучше
  переключателя).
- **vitest + @testing-library/react + jsdom** — тесты (дано условием).
- **eslint + typescript-eslint** (flat config) — линт (дано условием); `eslint-plugin-react-hooks`
  в этой версии тянет и строгие «React Compiler»-правила (`set-state-in-effect`, `purity`,
  `refs`) — весь фетчинг данных в эффектах и рефы (`usePoll.ts`) написаны так, чтобы им
  соответствовать (см. код: паттерн «сброс состояния при смене входных данных — во время
  рендера, не в эффекте»; «обновление рефа — в собственном безусловном эффекте, не во время
  рендера»).
- **chia-wallet-sdk-wasm** (Apache-2.0, официальный `xch-dev/chia-wallet-sdk`) — браузерный
  тестовый кошелёк (Р-45, `src/wallet/testWallet.ts`): настоящий BLS-ключ и подпись прямо в
  браузере, той же схемой, что использует Sage (`docs/research/week1_wallet_auth.md` §2).
  Экспорты проверены чтением `.d.ts` пакета (не документацией) — `SecretKey.sign`,
  `deriveUnhardenedPath`/`deriveSynthetic`, `standardPuzzleHash`, `Address`,
  `Clvm.pair`/`Program.treeHash()` — всё нужное присутствует, альтернативный пакет не
  понадобился. Подгружается лениво (`import()` только при открытии окна входа) — его wasm сам
  по себе ~2 МБ gzip, весь пакет (NFT/DID/офферы/…), не только используемый нами срез подписи,
  так что он не должен утяжелять первую загрузку.
- **vite-plugin-wasm** — учит Vite импортировать `.wasm` как ES-модуль (wasm-bindgen'овский
  "bundler"-таргет `chia-wallet-sdk-wasm`). `vite-plugin-top-level-await` (обычная вторая
  половина этой пары) пробовалась и не подошла — её `generateBundle`-хук ожидает форму вывода
  классического Rollup/esbuild и падает на Vite 8's Rolldown-бандлере («missing field `type`»
  из внутреннего SWC re-print шага); не понадобилась — вывод Rolldown уже поддерживает
  top-level await нативно, `npm run build` тому доказательство.
- **@walletconnect/sign-client** (`2.25.0`, проверено 2026-09-25/26) — реальный вход через Sage
  (Р-55, `src/wallet/walletConnect.ts`): пейринг-сессия WalletConnect v2, `chia_getAddress`/
  `chia_signMessageByAddress` (`docs/research/week1_wallet_auth.md` §1.1, «Реализация»).
  **Лицензия — НЕ MIT/Apache-2.0**: собственная «WalletConnect Community License Agreement»
  Reown, Inc. (`github.com/WalletConnect/walletconnect-monorepo`,
  `packages/sign-client/LICENSE.md`, «Release Date: 20 August 2025», прочитано `gh api` 2026-09-26)
  — бесплатно ниже 2 500 000 RPC/мес или 500 MAU/мес (закрытая альфа Р-42, 5–10 тестеров, далеко
  ниже порога), обязательно подключение к сети реле Reown (не self-hosted — ровно то, что делает
  этот модуль), обязательная строка атрибуции (см. ниже). Наш собственный код остаётся
  Apache-2.0 (Р-36) — это чужая зависимость на её собственных условиях, не наш код.
  **Portions © 2025 Reown, Inc. All Rights Reserved.** Подгружается лениво (`import()` внутри
  `wallet/walletConnect.ts`, ~2,7 МБ unpacked по данным `npm view`) — не в главном чанке.
- **qrcode-generator** (`2.0.4`, MIT, ~15 КБ, без своих зависимостей) — QR-код адреса депозита
  (Р-53) и QR-код WalletConnect-пейринга (Р-55), `src/wallet/QrCode.tsx`: рисует обычными SVG
  `<rect>`/`<path>` по сетке модулей библиотеки, не её собственным `createSvgTag()`. Достаточно
  маленький, чтобы не выносить в отдельный ленивый чанк — в отличие от двух пакетов выше.

Помимо перечисленного выше зависимостей не добавлялось сверх нужд задачи; `contract.ts` —
только аддитивные правки: `serverInfo()` (+ `walletConnectProjectId?`), `PlayerSummaryDto.isBot?`,
`resultsHash?` на разрешённом/отменённом раунде, и, для Р-53, `myDepositAddress()`/
`DepositAddressDto` и `WithdrawalRequestDto`'s `toAddress`/`chainState`/`coinId`/`height`/
`failureReason` (сверено с реальными полями `server/`, которые строила параллельно другая
сессия по той же задаче) — старый самоотчётный `requestDeposit(amount)`/`deposit(requestId)`
убран из `Api` целиком (эксплойт критики 2026-09-26, никогда не подключался из UI и не
возвращается ни в каком виде).

## Архитектура

```
src/api/
  contract.ts        — контракт (не мой файл, только использован)
  liquidation.ts      — точный TS-порт LiquidationCalculator/PositionSizing + золотые векторы
  clock.ts             — симулированные часы + детерминированный сид-путь цены BTC
  season.ts            — общая календарная арифметика сезона (UTC-месяц, Р-10/Р-16)
  rng.ts                — детерминированный PRNG (mulberry32) — вся "случайность" мока сидирована
  mockPlayers.ts        — ~40 NPC-игроков + их история/сезонные записи/рейтинги
  mockRounds.ts          — раунд любой фазы из сидированного набора ставок
  mockCrowdHistory.ts     — длинная история счёта толпы (crowd/BTC-hold/анти-толпа)
  mockApi.ts               — единственная реализация Api; единственное реальное состояние —
                              сессия вошедшего игрока (баланс, леджер, свои ставки)
  ApiContext.tsx / useClock.ts — React-обвязка (контекст Api/сессии, тик часов)
src/components/         — общие компоненты (BetSlip, CrowdChart, Layout, статусы, состояния)
src/pages/                — семь экранов по ux_spec.md §3–9
```

### Симулированные часы (`clock.ts`)

Реальные раунды идут часами/сутками (Р-19) — ждать час ради демо неудобно. Часы приложения
стартуют от фиксированного якоря `SIM_BASE_MS` («сегодня») и идут в `SIM_SPEED = 60` раз
быстрее реального времени (1 реальная секунда = 1 симулированная минута), так что цикл
часового раунда BETTING → RUNNING → SETTLED целиком проигрывается примерно за минуту реального
времени. **Предложение** — скорость не зафиксирована ни одним источником, подобрана для
демонстрации и вынесена в одну константу.

Цена BTC — детерминированный сид-путь (`btcPriceAt`, дневной блуждающий процесс + часовой шум),
не `Math.random`: одна и та же точка времени всегда даёт одну и ту же цену, поэтому открытие
раунда, история графика и превью ликвидации никогда не расходятся между вызовами.

### Честные упрощения мока (не путать с механикой игры — она не менялась)

- «Идущий» и «открытый» раунды каждой серии не хранятся отдельно — детерминированно
  генерируются заново из сида `roundId` при каждом запросе; пока час/сутки не сменились,
  результат тот же самый.
- Ликвидация RUNNING-раунда считается по уже известному (детерминированному) закрытию свечи, а
  не по «прожитому» тик-пути — мок не хранит потоковую цену, только формулу; отмечено в коде
  `mockRounds.ts`. Реальный движок (`PositionSimulator.java`) считает иначе (по факту пройденного
  пути), это не переносится на бэкенд.
- История счёта толпы — дневная случайная модель (`mockCrowdHistory.ts`), не буквальный повтор
  всех часовых раундов с первого дня — иначе пришлось бы материализовать тысячи точек ради
  графика, который и так рисуется по дням.
- Состояние вошедшего игрока живёт только в памяти вкладки (`mockApi.ts` — модульный синглтон);
  обновление страницы сбрасывает сессию. Каждое новое подключение кошелька — заведомо новый
  игрок (`isNewPlayer: true`), чтобы сценарий первого входа (§10 `ux_spec.md`) был виден всегда,
  а не только один раз.

## Маршруты

Предложение — URL-схему не фиксирует ни один источник, только состав экранов (`ux_spec.md` §1).

| Маршрут | Экран |
|---|---|
| `/` | Home |
| `/round/:roundId` | Round |
| `/crowd` | Crowd |
| `/ratings` | Ratings |
| `/profile/:playerId`, `/profile/me` | Profile |
| `/wallet` | Wallet (desktop и mobile, Р-48 заменяет Р-31) |
| `/how-it-works` | How it works |

## Команды

```
npm install
npm run dev         # http://localhost:5173
npm run typecheck   # tsc -b --noEmit
npm run lint        # eslint .
npm test -- --run   # vitest --run
npm run build        # tsc -b && vite build
npm run preview       # собранный dist/
```

Все пять команд (`typecheck`/`lint`/`test`/`build`, плюс сам `install`) проверены и проходят
чисто на момент последней сдачи (2026-09-26, Р-53/Р-55): 239 тестов, 0 ошибок линта (8
некритичных warning про fast-refresh в файлах с несколькими экспортами — контекст/константы
рядом с компонентом), сборка — несколько чанков, самые большие: `chia-wallet-sdk-wasm` (~2,0 МБ
gzip, тестовый кошелёк) и WalletConnect (~132 КБ gzip) — оба вынесены отдельно и грузятся только
при реальном открытии окна входа, ни один не в главном чанке (`index-*.js`, ~132 КБ gzip).

## Доступность

Клавиатурная достижимость и видимый фокус (`:focus-visible`, толстая обводка) везде; направление
UP/DOWN всегда парой иконка+текст, ни одного чисто цветового сигнала (тексты "Won"/"Lost"/
"Liquidated" тоже, не только цвет); формы — с `<label>`; модалка входа — `role="dialog"`,
`aria-modal`, возврат фокуса при закрытии, закрытие по Esc. Публичные страницы проверены на
375px (Р-31).

## Android (Capacitor, Р-48)

`android/` — нативный Android-проект, добавленный Capacitor'ом (`@capacitor/core`/`@capacitor/cli`/
`@capacitor/android`, MIT). Это сборка **под локальный стенд**, не под магазин: `capacitor.config.ts`
указывает WebView прямо на поднятый Docker-стенд (`server.url: "http://10.0.2.2:8480"` — с эмулятора
`10.0.2.2` это `localhost` Mac'а; `server.cleartext: true`, иначе Android ≥ API 28 блокирует http),
поэтому cookie-сессия `/api/rpc` работает без переделок — тот же ориджин, что и в браузере.
Подробности запуска на нескольких эмуляторах — `docs/product/local_stand.md` §9.

**Открытый пункт (не сделано здесь, отдельное решение):** сборка для Google Play/App Store не может
указывать `server.url` на чей-то ноутбук — она вместо этого упакует `dist/` как локальные ассеты
(обычный `webDir` без `server.*`) и будет ходить на задеплоенный бэкенд через API base URL, а
cookie-сессию придётся заменить на токен — cookie действует только для того ориджина, что её
выставил, а `file://`/`capacitor://` ориджин ассетов — не он.

```
npm run cap:sync      # npx cap sync android — подхватить dist/ + конфиг в android/
npm run android:debug # sync + ./gradlew assembleDebug → android/app/build/outputs/apk/debug/
```

WebView remote debugging (`chrome://inspect`) включён только в debug-сборке
(`MainActivity.java`, `BuildConfig.DEBUG`).

## Демо-баннер

Пока приложение работает на моковых данных, на каждом экране закреплена нескрываемая полоса:
**"DEMO — PLAY is toy money on a test network. Nothing here is real funds."** (`ux_spec.md` §2).
