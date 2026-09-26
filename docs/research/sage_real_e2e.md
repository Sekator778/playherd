# Настоящий Sage ↔ Herd — сквозная проверка (2026-09-26)

Проведена сессия вручную (adb + CDP) по прямому подтверждению Алекса. Всё — testnet11, локальный стенд (`docker compose -p herd`), эмулятор Android `herd-p3`.

| Шаг | Результат | Доказательство |
|---|---|---|
| Sage v0.13.1 для Android | `app-universal-release.apk`, sha256 `88ec6d705715037fe725c3010a3a3e738ae5ec1301803391426ca3460d118209` = digest релиза на GitHub | `gh api repos/xch-dev/sage/releases/tags/v0.13.1` |
| Новый кошелёк, сеть testnet11 | Settings → Network → Default Network → testnet11; адрес `txch14mg50pq4lftywhzmxktwtav0ugaf9zxvlj7ssna49g0syl6hev8ss7zzr7` | фраза-сид не читалась и не сохранялась |
| Пополнение 0,02 TXCH | с тестового `counterparty`, комиссия 500 000 000 mojo (меньшую testnet11 отвергает) | `c2 send … --push` → SUCCESS |
| Вход в Herd через WalletConnect | «Copy connection link» в Herd → Sage Settings → WalletConnect → вставить → Connect; Sage показал «Sign Message — From Herd» с текстом `Herd sign-in / Domain / Network: testnet11 / Address / Nonce / Issued / Expires` → Approve; сервер проверил подпись через chainsvc | игрок `369ccf81-…` `sage-real`, 10 000 PLAY |
| Вывод 150 PLAY в Sage | w-14 → CONFIRMED, монета `0xc13d49bb7b7bebcc0bad29e88c7a6e91d569b8aa9477f6867b254b6dee071e9b`, блок 4 738 927; в Sage: «Unknown CAT 150» | БД `withdrawal_requests`, экран Sage |
| Возврат 100 PLAY из Sage | Sage → Send CAT на адрес пополнения `txch1krsquruar7…s3eq5fqspn`, комиссия 0,0005; засчитано один раз: монета `0x947aecfeb435f9c2e42b3ec6e9ca8c6968132b86d68eef37cbc4b8f881b97078`, блок 4 738 936 | `deposit_confirmations`, 1 событие `DepositConfirmedOnChain`; баланс 9 850 → 9 950 |
| Выход и повторный вход | новая пара WalletConnect, подпись тем же адресом → тот же игрок, баланс 9 950, одно событие `PlayerRegistered` | Р-57 |

## Трение для реального игрока (для дизайнера)

1. Sage по умолчанию в mainnet — для testnet11 нужно переключить сеть в настройках (Settings → Network).
2. PLAY в Sage виден как «Unknown CAT» — нужна инструкция «переименовать/добавить токен» и asset id `4b88ce97…6080b70` на экране кошелька Herd.
3. Каждый вход — новая пара WalletConnect через копирование ссылки (на телефоне нет deep link в Sage); сессия после перезагрузки страницы Herd не переиспользуется.
4. В загруженном testnet11 перевод из Sage с комиссией 0 не проходит — в подсказке к пополнению указать рекомендуемую комиссию.
