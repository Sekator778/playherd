# Implementation plan — building game feel into `web/`

Дата: 2026-09-25. Как реализовать `direction.md`/`result-moment.md`/`live-round.md` в текущем
React 19 + TypeScript + Vite стеке (`web/package.json`) с минимумом зависимостей, per
`game-designer.md`: «дизайн-токены (CSS-переменные), компоненты анимаций, без тяжёлых
зависимостей». Эта работа не входит в текущий design-only шаг (см. системное сообщение задачи —
another engineer правит `web/` прямо сейчас) — документ фиксирует план для следующего шага.

## 1. Технический выбор: CSS/WAA + один маленький канвас-модуль, без новых npm-пакетов для эффектов

Три варианта, per задание («CSS keyframes + Web Animations API vs a tiny canvas particle system
vs a small library — pick and justify»):

| Вариант | За | Против |
|---|---|---|
| Только CSS keyframes + WAA | Zero-dep, полный доступ к существующим токенам, лучший перф на трансформах/opacity | Не даёт частицы (монеты по дуге, конфетти, осколки) — DOM-based «частицы» через сотни элементов дороги по layout/paint |
| `canvas-confetti` (MIT, ~3KB gzip) | Готовый, лёгкий, разрешительная лицензия | Даёт только радиальный взрыв конфетти «из коробки» — не умеет монету, летящую по конкретной дуге в конкретный DOM-элемент (пилюля баланса), не умеет спрайт «осколок»/«трещина»; пришлось бы писать свой канвас-код для монет/осколков ВСЁ РАВНО поверх этой зависимости — она закрывает только один из трёх нужных типов частиц |
| `tsparticles` (несколько сотен KB с пресетами) | Богатый конфиг | На телефонный бюджет (§5) избыточен, config-driven API плохо ложится на choreographed «монета летит по дуге именно в шапку», не то, что нужно |
| **Свой канвас-модуль** (выбор) | Один файл, три вида спрайта (coin/spark/shard) на общей простой физике (гравитация + опциональная безье-дуга к DOM-цели), точный контроль дуги в баланс, 0 новых зависимостей, лицензия — своя (Apache-2.0 проекта) | Пишем и поддерживаем сами — оправдано объёмом (см. ниже) |

Решение: **hand-rolled `web/src/fx/particles.ts`**, ориентир <150 строк / ~2-3KB minified,
общий движок для WIN-монет, WIN-искр/конфетти, LOSS/LIQUIDATION-осколков — три спрайта, разные
наборы параметров из тир-таблиц `result-moment.md`/`live-round.md`, не три отдельных модуля.
Всё остальное (backdrop fade, вход/выход карточки, тряска экрана, draw-in трещины, glow-пульсы) —
CSS `@keyframes` + `element.animate()` (WAA) там, где нужна JS-оркестрация точных таймингов
(например, синхронизация конца count-up с началом dismiss).

## 2. Компоненты

Все новые файлы — под `web/src/fx/` (новая директория рядом с `web/src/components/`), кроме двух
переиспользуемых элементов бет-слипа, которые остаются в `web/src/components/` рядом с
`BetSlip.tsx`:

| Файл | Роль |
|---|---|
| `web/src/fx/resultEventBus.ts` | Мини pub/sub (`subscribe`/`publish`) — модульный массив подписчиков + React Context, без Redux/Zustand (масштаб задачи этого не требует) |
| `web/src/fx/useSettlementWatcher.ts` | Диф-детект (§3) — собственный `usePoll` на `api.home()`, не привязан к текущей странице |
| `web/src/fx/resultEvent.ts` | Чистые функции: `(before, after) → ResultEvent[]`, тир-расчёт (`result-moment.md` §2) — вынесены отдельно от React ради unit-тестов (§6) |
| `web/src/fx/resultQueue.ts` | FIFO + схлопывание >2 (`result-moment.md` §1) — тоже чистая логика, без React |
| `web/src/fx/ResultMomentHost.tsx` | Портал-хост, подписан на bus и очередь, монтируется в `Layout.tsx` рядом с `<ConnectWalletModal />`, вне `<Outlet/>` |
| `web/src/fx/ResultCard.tsx` | Разметка карточки, ветвится по `kind`/`tier` на CSS-классы (`.result-card--win`, `--loss`, `--liquidation`, `--even`) |
| `web/src/fx/particles.ts` | Канвас-движок (§1) |
| `web/src/fx/RollingDigits.tsx` | Одометр-компонент числа — переиспользуется `ResultCard` (заголовок) И `Layout.tsx`'s `BalancePill` (HUD-баланс крутится на любое изменение, не только при показе момента — прямой ответ на «чтобы сумма выигрыша или проигрыша добавлялись или отнимались... сразу это проявлялось») |
| `web/src/fx/haptics.ts` | `impact(style)`/`notification(type)`/`vibrate(pattern)` — внутри: `Capacitor.isNativePlatform()` → динамический `import("@capacitor/haptics")`, иначе `navigator.vibrate(...)` напрямую (веб/PWA не платит бандлом за нативный плагин, см. §5) |
| `web/src/fx/sound.ts` | WebAudio-синтез (§4), ленивый `AudioContext` (резюмируется по первому жесту пользователя — политика автоплея браузеров) |
| `web/src/fx/fxSettings.ts` | `herd:sfxEnabled`(default `false`)/`herd:hapticsEnabled`(default `true`) в `localStorage` + хук `useFxSettings()`; тумблеры — маленькая иконка в `Header` (`Layout.tsx`) и повторно в `ResultCard` |
| `web/src/components/LeverageDial.tsx` | Стилизованный `<input type=range>` (`live-round.md` §1) — замена голого слайдера в `BetSlip.tsx` |
| `web/src/components/LiquidationBar.tsx` | Шкала дистанции (`live-round.md` §2), используется и в bet slip (статично), и в `RoundParts.tsx`'s `CrowdPositionCard`/позиции игрока (живо, когда появится `currentPrice`) |

CSS: новые токены/keyframes — новый файл `web/src/fx/fx.css`, импортируется один раз из
`main.tsx` рядом с существующим `import "./index.css"` (аддитивно, ничего в `index.css` не
удаляется — только пара классов типа `.direction-btn`/`.balance-pill` получают точечные
переопределения под новые токены, тоже через доп. правила, не переписывание файла).

## 3. Источник событий итога: диф поверх существующего поллинга

Никакого WebSocket, никакого нового backend-эндпоинта для самого момента итога — используется то
же самое, что уже опрашивается (`web/src/api/usePoll.ts`, 2000ms, пока вкладка видима).

`useSettlementWatcher` держит собственный `usePoll(() => api.home(), 2000, [])`, **независимо**
от того, какая страница смонтирована — необходимо, чтобы момент срабатывал «видно с любой
страницы» (`game-designer.md`), даже если игрок сейчас на `/wallet` или `/ratings`, откуда сам
`home()` иначе не запрашивался бы. Это единственный намеренный лишний сетевой вызов, который
добавляет эта фича (одна дешёвая GET раз в 2с поверх того, что уже опрашивает посещаемая
страница) — предложение: если профилирование позже покажет, что это заметно, вынести общий кэш
поллинга на уровень `ApiContext`, чтобы `home()` запрашивался один раз, а не дважды (watcher +
страница); не требуется для MVP-ощущения.

На каждый тик, для `home.hourly` и `home.daily` (`RoundsPairDto.running`/`.betting`):

1. Держим `Map<RoundId, RoundDto>` (последнее увиденное состояние) и `Set<RoundId>` уже
   отыгранных `(roundId:playerId)` (§`result-moment.md` §1 дедуп-ключ) в `useRef`.
2. Для раунда, где у текущего игрока есть/была позиция (`myBet` в `BETTING`, либо
   `positions.find(p => p.playerId === player.playerId)` в `RUNNING`/`SETTLED`):
   - Если на этом тике позиция `liquidated === true`, а на прошлом тике (или в состоянии до
     раскрытия) — не была, и `(roundId:playerId)` ещё не в `Set` → `publish({kind:
     "LIQUIDATION", tier: leverageTier(leverage), ...})`, добавить в `Set`.
   - Если фаза стала `SETTLED` впервые для этого раунда, позиция не `liquidated`, и
     `(roundId:playerId)` ещё не в `Set` → вычислить `net = Number(payout) − Number(stake)`,
     `publish({kind: net>0?"WIN":net===0?"EVEN":"LOSS", tier: ...})`, добавить в `Set`. На момент
     написания в рабочей копии (параллельная правка `web/`, не эта задача) уже появляются
     `web/src/api/outcome.ts` (`netFromPayoutAndStake`, `outcomeFromNet`, `positionStakeStr` —
     достаёт точный стейк из аддитивного `PositionDto.stake`, с лоссовым фолбэком через
     `initialMargin` для бэкенда без этого поля) и `PublicPositionDto.stake?: PlayAmountStr` в
     `contract.ts` — ровно то, что нужно этому шагу: `resultEvent.ts` должен переиспользовать эти
     функции, а не пересчитывать net заново вторым способом (одна логика «net, не payout» на весь
     фронт, не две).
   - Если фаза стала `SETTLED`, но `(roundId:playerId)` уже в `Set` (значит был обработан как
     `LIQUIDATION` мид-раунда) — ничего не публикуем (дедуп из `result-moment.md` §1).
3. Обновить `Map` текущим состоянием раунда для следующего тика.

Тиры считаются в `resultEvent.ts` по формулам из `result-moment.md` §2 (`m = payout/stake` для
WIN, `stakeShare = stake/balanceBefore` для LOSS, `leverage`-бакеты для LIQUIDATION, кикер
`leverage ≥ 30 → tier+1`).

`ResultMomentHost` подписан на `resultEventBus`, кладёт новые события в `resultQueue`, рендерит
`ResultCard` для головы очереди, снимает по завершении/tap.

## 4. Звук — синтез WebAudio, без аудиофайлов

Решение: **синтезировать, не грузить сэмплы** — ноль веса в бандле, ноль вопросов лицензии,
тайминги/громкость программно завязаны на тир (`result-moment.md`/`live-round.md` таблицы) без
второго набора файлов на каждый тир. `web/src/fx/sound.ts`, один `AudioContext`, простые
осцилляторы + фильтрованный шум:

- **Монетный джингл (WIN)** — N коротких «блипов» (N по тиру, `result-moment.md` §2) treble
  triangle/square-осциллятор, база 1800–2600Hz со случайной детюнировкой ±15% на блип, каждый
  ~40ms (быстрый attack/decay envelope через `GainNode`), смешан с коротким шумовым «звоном»
  (белый шум через `BiquadFilterNode` bandpass ~3–5kHz) — «клик» монеты.
- **Тад (LOSS)** — одиночный низкий sine/triangle burst 80–120Hz, быстрый attack, decay
  250–400ms по тиру, подмешан lowpass-фильтрованный шум для «глухости».
- **Трещина (LIQUIDATION)** — короткий highpass-шум (2–6kHz), очень быстрый attack, 2–4
  рандомизированных микро-щелчка за ~80ms («крак»), сразу за ним — тот же «тад».
- **UI-тик** (leverage-haptic-tick, secondbounce, EVEN) — очень короткий (15–25ms) синус ~600Hz,
  низкий gain.

Опциональный апгрейд позже (не блокер, не делать сейчас): если понадобится более «богатый»
звук, чем синтез — источники записанных SFX для замены: Kenney.nl аудио-паки (заявлены как CC0 на
сайте — **UNVERIFIED**, в проектных источниках не зафиксировано, проверить актуальный текст
лицензии на kenney.nl перед использованием) или freesound.org, фильтруя строго по CC0 на
конкретный файл (лицензия там задаётся по каждому файлу отдельно, не по сайту в целом — проверять
при каждом скачивании). До этого апгрейда — только синтез.

Все звуки — `if (!fxSettings.sfxEnabled) return;` в начале каждой функции, тумблер по умолчанию
`false` (`direction.md` §6).

## 5. Hidden `/lab` route — «Design lab»

`web/src/pages/LabPage.tsx`, маршрут `/lab` регистрируется в `App.tsx` рядом с существующими
(`/`, `/round/:id`, …), **не добавляется** в `NAV_LINKS` (`Layout.tsx`) и не требует сессии —
ничего денежного не триггерит, только локальные фейковые события в `resultEventBus`, так что
скрытость через отсутствие в навигации достаточна (не auth-gate).

Содержимое:
- Кнопки на каждый `kind × tier` из `result-moment.md` §2 с реалистичными фейковыми суммами
  (WIN T1..T4, LOSS T1..T4, LIQUIDATION T1..T4 на repräsentative leverage 5/15/25/38, EVEN).
- Слайдер, предпросматривающий `LeverageDial` в изоляции по всему диапазону x2–x40
  (`live-round.md` §1).
- Слайдер фейковой «текущей цены», предпросматривающий `LiquidationBar` по всем зонам
  (`live-round.md` §2).
- Чекбокс «force reduced motion» (эмулировать `prefers-reduced-motion` без похода в настройки ОС
  — переопределяет CSS через атрибут на `<html>`, который `fx.css`-медиа-подобные правила читают
  вместо реального `@media`, только на этой странице).
- Чекбокс «force sound/haptics on» — только для превью на этой странице, не трогает реальный
  `fxSettings` в `localStorage`.

Прямое назначение — то, что просит задача: Алекс пробует эффекты на эмуляторах/десктопе без
необходимости реально проигрывать/проигрывать раунды.

## 6. Перформанс-бюджет: 60fps на среднем Android

- Канвас-частицы — один `<canvas position:fixed>` слой, `devicePixelRatio` захардкожен потолком
  `2` (не безусловные 3x на высоком DPI — ограничивает fill-rate), число спрайтов — строго по
  тир-таблицам (максимум T4 WIN: 42 монеты + 90 искр + 40 конфетти ≈ 170 простых залитых
  кругов/путей без per-particle `shadowBlur` — свечение делается ОДИН раз на уровне
  CSS-карточки, не на канвасе на каждой частице).
- Пул объектов частиц (переиспользуемый массив фиксированного размера, не `push`/`splice` в
  rAF-цикле) — избегает GC-пауз при частых T3/T4.
- Анимируются **только** `transform`/`opacity` (тряска, вход/выход карточки, пульсы) — никогда
  `width`/`height`/`top`/`left`, чтобы остаться на compositor-потоке.
- `will-change: transform` добавляется на старте конкретной анимации и снимается по её
  завершении — не висит постоянно (не «forever-promoted» слои).
- Канвас/оверлей полностью размонтируются (не `display:none`) в простое — 0 стоимости, когда
  момента нет на экране.
- `prefers-reduced-motion` — канвас-модуль вообще не импортируется/не вызывается на этом пути
  (самый дешёвый вариант по построению, не «отключенная, но всё ещё загруженная» ветка).
- Проверка на практике — как часть визуальной проверки (§7): захват `adb shell dumpsys gfxinfo`
  или Chrome DevTools Performance во время T4 WIN на эмуляторе, цель — без dropped frames
  (кадровый бюджет ≤16.6ms) на протяжении всплеска частиц.
- **Найдено ревью 2026-09-25**: T4 WIN на эмуляторе — 6/91 janky frames (~200ms пик) при
  синхронном измерении rect + `burst()` искр/конфетти + `spawnCoinArc()` в одном mount-эффекте.
  Фикс — искры и старт coin-arc отложены на 1 `requestAnimationFrame` (после первой отрисовки
  карточки), конфетти — ещё на один кадр дальше (`ResultCard.tsx`'s mount effect); LOSS/LIQUIDATION
  их `burst()` получили тот же rAF-сдвиг для единообразия. Не переизмерено после фикса — следующий
  проход визуальной проверки (§7) должен повторить `dumpsys gfxinfo` и подтвердить исчезновение
  janky-фреймов.
- Шрифты: решение `direction.md` §2 (не грузить веб-шрифт для базовой реализации) само по себе —
  часть этого бюджета, не отдельная задача: 0 дополнительных сетевых запросов на типографику.
  `@capacitor/haptics` — только динамический импорт под `Capacitor.isNativePlatform()`, веб/PWA
  бандл его не получает вовсе.

## 7. Test plan

Обязательный зелёный прогон перед сдачей (per `game-designer.md`): `npm run typecheck`, `lint`,
`test -- --run`, `build`. **Не трогать** `web/src/api/liquidation.test.ts` (golden-vector тесты
ликвидации) — эта фича только читает уже посчитанные `liquidated`/`liquidationPrice`, никогда не
пересчитывает их заново.

**Unit-тесты** (Vitest, тот же паттерн, что существующие `BetSlip.test.tsx`/
`testWallet.test.ts`):

- `resultEvent.test.ts` — чистая функция `(before, after) → ResultEvent[]`: границы множителя WIN
  (1.49×/1.5×, 2.99×/3×, 7.99×/8×), границы `stakeShare` LOSS (4.9%/5%, 14.9%/15%, 39.9%/40%),
  границы leverage LIQUIDATION (9/10, 19/20, 29/30, 39/40), правило кикера (leverage≥30 → tier+1,
  потолок T4), `EVEN` при `net===0`, дедуп (раунд, уже отмеченный LIQUIDATION, не даёт второго
  события на SETTLED), одновременное срабатывание hourly+daily в одном тике → два независимых
  события.
- `resultQueue.test.ts` — FIFO-порядок, схлопывание в сводную карточку начиная с 3-го элемента,
  tap-to-skip переходит к следующему немедленно, инвариант «один момент на экране».
- `RollingDigits.test.tsx` — синхронный рендер финального значения при
  `prefers-reduced-motion: reduce` (мок `matchMedia`, как для существующих `@media
  (prefers-reduced-motion: reduce)` CSS-правил, но на уровне JS-хука `usePrefersReducedMotion`,
  которого сейчас в кодовой базе нет — этот компонент его и вводит).
- `fxSettings.test.ts` — round-trip через `localStorage`, дефолты `sfxEnabled=false`/
  `hapticsEnabled=true`.

**Визуальная проверка** (per `game-designer.md`: десктоп headless Chrome + Android-эмуляторы,
состояния выигрыш/проигрыш/ликвидация/живой раунд): скриншоты `/lab`, запускающего WIN-T2,
WIN-T4, LOSS-T3, LIQUIDATION-T4, EVEN; `LeverageDial` на всём диапазоне; `LiquidationBar` по всем
зонам — desktop (headless Chrome) и три Android-эмулятора (`adb exec-out screencap`, тот же
метод, что уже использован для скриншотов в scratchpad этой сессии — `p1-after-fix.png`,
`now-emulator-*.png`), до/после против текущих скриншотов как базовой линии.

**Ручная/перцептивная проверка** — минимум один полный прогон T4 WIN и один LIQUIDATION на
реальном Android-эмуляторе с включённым frame-rate оверлеем или подключённым DevTools
Performance трейсом, сверка с бюджетом §6.
