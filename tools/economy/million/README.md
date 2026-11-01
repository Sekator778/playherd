# tools/economy/million

Скрипты расчёта `docs/research/economy_1m_players_2y_2026-10-17.md` («1 000 000 игроков, 1 и 2 года», D-99): методы из `docs/research/economy_literature_2026-10-17.md`, библиотеки `arch` (стационарный бутстрэп, длина блока, GARCH) и `scipy` (GPD). Скрипты прежних расчётов `tools/economy/scale/` не менялись; из них взяты только идеи (мультипликативная казна, правило выключателя) и константы.

## Данные

Свечи 1 минута (гитигнорированы): `.local/econ-candles/{BTC,ETH,SOL}_1m.csv`, 730 суток, качает `bots/scripts/download_candles.py --days 730 --out .local/econ-candles`. Если файлов нет, `mcommon.candles_dir()` берёт `.local/bots-candles` (14 суток) — прогоны тогда помечены «14 суток». Переменная `CANDLES_DIR` переопределяет путь.
Рынки без собственных свечей (DOGE, XRP, HYPE, акции, нефть, золото, индексы) — прокси из свечей BTC/SOL (описание в документе, раздел «Входы и пробелы», и в `mcommon.build_rows`). Кэш построенных рядов и промежуточных результатов — `.local/million-cache/`.

## Запуск

```
python3 -m venv $V && $V/bin/pip install numpy scipy arch pandas
cd tools/economy/million
$V/bin/python -E -s m1_tails_blocks.py     > out_m1.txt   # длина блока (arch.optimal_block_length), POT/GPD (scipy.stats.genpareto)
$V/bin/python -E -s m3_supply.py           > out_m3.txt   # денежная масса за 24 месяца, 1 млн игроков
$V/bin/python -E -s m4_wille_lundberg.py   > out_m4.txt   # граница Вилля, неравенство Лундберга
$V/bin/python -E -s m5_informed.py         > out_m5.txt   # безубыточная точность, информированные
$V/bin/python -E -s m6_units.py            > out_m6.txt   # единицы, RTP с округлением комиссии, N <= kappa*T/пакет
$V/bin/python -E -s m7_farms.py            > out_m7.txt   # фермы кошельков, парная ставка
$V/bin/python -E -s m8_garch_check.py      > out_m8.txt   # сверка бутстрэпа с GARCH(1,1)-t
$V/bin/python -E -s m10_skill.py           > out_m10.txt  # меньшинство с навыком
$V/bin/python -E -s m11_today.py           > out_m11.txt  # что ломается первым при сегодняшних правилах
$V/bin/python -E -s m9_grid.py             > out_m9.txt   # развёртка T x q x lambda x комиссии x регистрации x удержание (самый долгий, ~50 мин)
$V/bin/python -E -s m12_sens.py            > out_m12.txt  # чувствительность: блок, множитель sigma, плечо адверсария
$V/bin/python -E -s m13_kappa_scan.py      > out_m13.txt  # мелкая развёртка по kappa = N*пакет/T
$V/bin/python -E -s m14_fork.py            > out_m14.txt  # стресс на лимите, комиссии в HERD: S2 и информированная толпа
```

`-I` не используется (ломает импорт соседних модулей), `python -E -s`. `m1_tails_blocks.py` пишет длину блока в кэш для `m2_risk.py`/`m9_grid.py`/`m8_garch_check.py`; запускать первым.
Время на Mac (15 ядер): m1 — секунды, m3 — 20 с, m8 — 1–2 мин на 730 сутках, m9 — десятки минут (кэш `grid_cache.json` позволяет продолжить).

## Файлы

- `mcommon.py` — константы с file:line, загрузка свечей, построение рядов (15-минутные строки: пять 3-минутных и одна 15-минутная доходность по 12 рынкам), стационарный бутстрэп на сэмплере `arch`.
- `m2_risk.py` — движок риска казны (мультипликативная казна, выключатель как в `LedgerBook.java:837-857`, потоки в казну и из неё).
- остальные `m*.py` — по списку выше; `out_m*.txt` — выводы последних прогонов.
