# Сборка exteraless

## Подготовка

1. Склонировать репозиторий вместе с подмодулями:
```bash
git clone --recursive --shallow-submodules https://github.com/exteraless/exteraless.git exteraless
```

Если репозиторий уже склонирован без подмодулей:

```bash
git submodule update --init --recursive --depth=1
```

2. Получить `TELEGRAM_APP_ID` и `TELEGRAM_APP_HASH` на [my.telegram.org](https://my.telegram.org/auth) и создать `local.properties` в корне проекта:

```properties
TELEGRAM_APP_ID=<ваш_app_id>
TELEGRAM_APP_HASH=<ваш_app_hash>
```

3. Для подписи APK положить свой `TMessagesProj/release.keystore` и дописать в `local.properties`:

```properties
KEYSTORE_PASS=<пароль_хранилища>
ALIAS_NAME=<имя_ключа>
ALIAS_PASS=<пароль_ключа>
```

Ключа в репозитории нет намеренно. Без него сборка не падает — APK подписывается отладочным ключом Android.

4. Для push-уведомлений положить свой `TMessagesProj/google-services.json` (Firebase, имя пакета `com.exteraless.app`).

5. Заменить метаданные проекта:
   - ключ Google Maps в записи `com.google.android.maps.v2.API_KEY` в `TMessagesProj/src/main/AndroidManifest.xml`
   - `BaseRemoteHelper.CHANNEL_METADATA_ID` — числовой id вашего канала метаданных, без префикса `-100`.

## Сборка

Собрать: `./gradlew :TMessagesProj:assembleDebug` или открыть проект в Android Studio.

## ABI

Собираются только 64-битные `arm64-v8a` и `x86_64`: Chaquopy собирает Python 3.12 лишь под них, и на `armeabi-v7a` конфигурация обрывается. Переменная `NATIVE_TARGET` задаёт цель: `arm64-v8a` (один ABI, быстрее), `universal` (оба), `SKIP` (без нативной части — только Java и ресурсы).

## Сборка через GitHub Actions

Нужны два секрета репозитория:

- `LOCAL_PROPERTIES` — содержимое `local.properties` в base64:

```bash
base64 -w0 local.properties
```

- `RELEASE_KEYSTORE` — файл ключа в base64:

```bash
base64 -w0 TMessagesProj/release.keystore
```

Дальше запустить workflow **Release Build**. Готовый APK лежит в артефактах прогона.
