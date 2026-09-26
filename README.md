# tg-ws-proxy-phone

Android-версия [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy): локальный MTProto-прокси для Telegram, который
пускает трафик через WebSocket (`wss://kwsN.web.telegram.org/apiws`), то есть тем же путём, что и Telegram Web.
Там, где обычные подключения Telegram к дата-центрам режутся или замедляются, WebSocket-соединение до веб-версии часто
продолжает работать.

```
Telegram (Android) ──MTProto, dd-секрет──▶ 127.0.0.1:1443 (это приложение)
                                              │ перешифровка obfuscated2
                                              ▼
                        wss://kwsN.web.telegram.org/apiws  (TLS на 149.154.167.220)
                              ↓ если не вышло
                        CF Worker → Cloudflare-прокси → прямой TCP до DC
```

## Как пользоваться

1. Установите APK (см. «Сборка» ниже или артефакты GitHub Actions).
2. Откройте приложение и нажмите **«Запустить»**. Появится постоянное уведомление: это foreground-сервис, без него
   Android убьёт прокси в фоне.
3. Нажмите **«Добавить прокси в Telegram»**. Telegram покажет MTProto-прокси `127.0.0.1:1443`, нажмите «Подключить».
4. Рекомендуется нажать **«Отключить оптимизацию батареи»**, иначе на некоторых прошивках (Xiaomi, Huawei, Samsung)
   система может остановить прокси.

Прокси можно включать и выключать плиткой в шторке быстрых настроек («TG Прокси»). По желанию он запускается после
перезагрузки телефона.

Секрет генерируется один раз и сохраняется, поэтому добавлять прокси в Telegram нужно только один раз
(или после смены порта или секрета). Прокси слушает только `127.0.0.1`, так что из сети к нему не подключиться.

## Что перенесено из оригинала

| Возможность | Статус |
|---|---|
| MTProto obfuscated2 (abridged / intermediate / padded), dd-секрет | ✅ |
| Мост TCP → WebSocket с перешифровкой, по одному WS-кадру на MTProto-пакет | ✅ |
| DC → IP (по умолчанию `2` и `4` → `149.154.167.220`), домены `kwsN` / `kwsN-1` | ✅ |
| Чёрный список DC при редиректах, паузы после таймаутов | ✅ |
| Пул заранее открытых WS-соединений | ✅ (пополняется, только пока DC недавно использовался: бережёт батарею) |
| Запасной путь через Cloudflare-прокси (публичный список доменов с автообновлением или свои домены) | ✅ |
| Запасной путь через CF Worker | ✅ (без пула) |
| Прямой TCP до DC | ✅ |
| Тестовые DC (`10000+`, `--force-test-dc`) | ✅ |
| Fake TLS (ee-секрет), PROXY protocol | ❌ нужны только для публичного сервера, на телефоне не нужны |
| Domain fronting в пуле | ❌ пока нет |

iOS не поддерживается: там нельзя держать локальный сервер в фоне без Network Extension и платного
аккаунта разработчика.

## Устройство проекта

- `core/`: прокси на чистом Kotlin/JVM без зависимостей от Android: `ProxyServer`, `MtProto`, `AesCtr`, `RawWebSocket`,
  `MsgSplitter`, `WsPool`, `CfProxyDomains`. Покрыт тестами, в том числе векторами из оригинальной Python-реализации
  и end-to-end тестом с поддельным WebSocket-сервером Telegram.
- `app/`: Android-приложение (minSdk 26 / Android 8.0+): `ProxyService` (foreground service), `MainActivity`,
  плитка быстрых настроек, автозапуск.

## Сборка

Нужны JDK 17+ и Android SDK (или просто Android Studio).

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew -PcoreOnly :core:test       # тесты ядра, Android SDK не нужен
```

GitHub Actions на каждый push собирает debug- и release-APK и складывает их в артефакты, а на тег `v*` публикует
релиз. Чтобы release-APK подписывался постоянным ключом (и обновлялся поверх старой версии без удаления), добавьте
в секреты репозитория `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` и
`ANDROID_KEY_PASSWORD`. Без них release подписывается одноразовым debug-ключом.

### Ядро на компьютере

```bash
./gradlew -PcoreOnly :core:fatJar
java -jar core/build/libs/core-all.jar --port 1443 -v
```

Опции совпадают с оригиналом: `--dc-ip`, `--secret`, `--pool-size`, `--cfproxy-domain`, `--cfproxy-worker-domain`,
`--no-cfproxy`, `--no-secure`, `--force-test-dc`.

## Лицензия и благодарности

Логика прокси портирована из [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) (MIT). Список
Cloudflare-доменов для запасного пути берётся из того же проекта.
