<div align="center">

<img src="assets/logo.png" width="128" alt="exteraless">

# exteraless

Открытая версия exteraGram — с моделью разрешений для плагинов

[![Канал](https://img.shields.io/badge/Telegram-@exteraless-2CA5E0?logo=telegram&logoColor=white)](https://t.me/exteraless)

</div>

---

## Что это

exteraless — форк [NagramX](https://github.com/risin42/NagramX), в котором возможности
exteraGram реализованы с открытым исходным кодом. Основа — Telegram для Android 13.1.1.

Отдельный упор — **безопасность плагинов**. В exteraGram плагин не ограничен ни в чём;
здесь у него есть модель разрешений: плагин объявляет, что ему нужно, это
видно на экране установки, и получает он только то, что вы разрешили.

Проект **в бете**: часть возможностей exteraGram ещё не перенесена. Если заметили, что
каких-то функций не хватает, — откройте issue или напишите в [канал](https://t.me/exteraless?direct).

* Имя пакета: `com.exteraless.app`
* Только 64-битные устройства (`arm64-v8a`, `x86_64`)

### Что внутри

**Плагины**

* Совместимость с плагинами exteraGram: одиночные `.plugin`, архивы `.elyx`/`.eaf`,
  плагины с DEX и хуки Java-методов (Xposed API)
* Разрешения: сеть, файлы, чтение и отправка сообщений, интенты, настройки, хуки,
  нативные библиотеки. Запрос без разрешения тихо отклоняется и попадает в журнал
* Журнал активности плагина с поиском и экспортом
* Защита от падений: плагин, который раз за разом роняет приложение, отключается;
  после нескольких падений на старте включается безопасный режим
* Экспорт и импорт всех плагинов вместе с настройками
* Python 3.11 на своей сборке [Chaquopy](https://github.com/exteraless/chaquopy)

**Клиент**

* Настройки exteraGram: оформление, чаты, иконпаки, боковое меню, полоса пилюль,
  поиск по настройкам
* Плеер в стиле Material 3 с настраиваемой обложкой, мини-плеером, очередью
  и синхронизированными текстами песен
* Режим призрака и сохранение удалённых и отредактированных сообщений
  (с исключениями по чатам)
* Своя ИИ-служба вместо встроенных в Telegram редактора и пересказов
* Темы Monet, включая стиль Inugram
* Вход на другие устройства по QR из бокового меню

### Ссылки

* Канал: [@exteraless](https://t.me/exteraless)
* Исходники: [github.com/exteraless/exteraless](https://github.com/exteraless/exteraless)

### Сборка

Понадобятся JDK 21, Android SDK с NDK и CMake, Python 3.11 на машине сборки.

1. Склонировать репозиторий вместе с подмодулями:

    ```bash
    git clone --recursive --shallow-submodules https://github.com/exteraless/exteraless.git exteraless
    ```

    Если репозиторий уже склонирован без подмодулей:

    ```bash
    git submodule update --init --recursive --depth=1
    ```

2. Получить `TELEGRAM_APP_ID` и `TELEGRAM_APP_HASH` на [my.telegram.org](https://my.telegram.org/auth)
   и создать `local.properties` в корне проекта:

   ```properties
   TELEGRAM_APP_ID=<ваш_app_id>
   TELEGRAM_APP_HASH=<ваш_app_hash>
   ```

   Если `python3` в системе не 3.11, указать нужный интерпретатор:

   ```properties
   CHAQUOPY_BUILD_PYTHON=/usr/bin/python3.11
   ```

3. Для подписи APK положить свой `TMessagesProj/release.keystore` и дописать в `local.properties`:

   ```properties
   KEYSTORE_PASS=<пароль_хранилища>
   ALIAS_NAME=<имя_ключа>
   ALIAS_PASS=<пароль_ключа>
   ```

   Ключа в репозитории нет намеренно. Без него сборка не падает — APK подписывается
   отладочным ключом Android.

4. Для push-уведомлений заменить `TMessagesProj/google-services.json` своим
   (Firebase, имя пакета `com.exteraless.app`).

5. Необязательно: свои ключи в `local.properties` — `MAPS_API_KEY` (Google Maps; ключ
   привязан к пакету и подписи, поэтому чужой показывает пустую карту) и `GLYPH_API_KEY`
   (Nothing Glyph). Для канала метаданных — его числовой id без префикса `-100`
   в `BaseRemoteHelper.CHANNEL_METADATA_ID`.

6. Собрать: `./gradlew :TMessagesProj:assembleDebug` или открыть проект в Android Studio.
   Среда выполнения Python собирается из подмодуля `TMessagesProj/chaquopy` во время
   сборки; Cython для этого ставится сам.

**Про ABI.** Переменная `NATIVE_TARGET` задаёт цель: `arm64-v8a` или `x86_64` (один ABI,
быстрее), пусто или `universal` (один APK с обоими ABI), `SKIP` (без нативной части —
только Java и ресурсы). 32-битные ABI не собираются.

### Сборка через GitHub Actions

Нужны два секрета репозитория:

* `LOCAL_PROPERTIES` — содержимое `local.properties` в base64:

  ```bash
  base64 -w0 local.properties
  ```

* `RELEASE_KEYSTORE` — файл ключа в base64:

  ```bash
  base64 -w0 TMessagesProj/release.keystore
  ```

Дальше запустить workflow **Release Build**. Готовый APK лежит в артефактах прогона.

### Авторы дизайна

Иконки и оформление, унаследованные от exteraGram, созданы его дизайнером —
[@the8055u](https://t.me/the8055u) и студией [@BlueprintDsgn](https://t.me/BlueprintDsgn).
Права на эти материалы принадлежат авторам.

### Благодарности

- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [Dr4iv3rNope](https://github.com/Dr4iv3rNope/NotSoAndroidAyuGram)
- [exteraGram](https://github.com/exteraSquad/exteraGram)
- [Inugram](https://github.com/teidesu/inugram)
- [Nagram](https://github.com/NextAlone/Nagram)
- [NagramX](https://github.com/risin42/NagramX)
- [Nekogram](https://github.com/Nekogram/Nekogram)
- [OctoGram](https://github.com/OctoGramApp/OctoGram)

---

## English

### What this is

exteraless is a fork of [NagramX](https://github.com/risin42/NagramX) that implements
exteraGram's features open-source. It is based on Telegram for Android 13.1.1.

The special focus is **plugin security**. In exteraGram a plugin is not restricted in
any way; here it comes with a permission model: a plugin declares what it needs, you
see that on the install sheet, and it gets only what you allow.

The project is **in beta**: some exteraGram features have not been ported yet. If you
notice a missing feature, open an issue or write to the [channel](https://t.me/exteraless?direct).

* Package name: `com.exteraless.app`
* 64-bit devices only (`arm64-v8a`, `x86_64`)

### What's inside

* exteraGram plugins: single-file `.plugin`, `.elyx`/`.eaf` archives, DEX plugins and
  Java method hooks (Xposed API)
* Permissions for network, files, reading and sending messages, intents, settings, hooks
  and native libraries; denied calls fail quietly and go to the plugin's activity log
* Crash protection: a plugin that keeps crashing the app is disabled, repeated launch
  crashes bring up safe mode
* Export and import of all plugins with their settings
* Python 3.11 on our own [Chaquopy](https://github.com/exteraless/chaquopy) build
* exteraGram settings, a Material 3 music player with synced lyrics, ghost mode, saved
  deleted and edited messages, Monet themes, QR login for other devices

### Building

You need JDK 21, the Android SDK with NDK and CMake, and Python 3.11 on the build machine.

1. Clone with submodules:

    ```bash
    git clone --recursive --shallow-submodules https://github.com/exteraless/exteraless.git exteraless
    ```

2. Get `TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH` from [my.telegram.org](https://my.telegram.org/auth)
   and put them into `local.properties` in the project root. If `python3` is not 3.11,
   set `CHAQUOPY_BUILD_PYTHON` there to a 3.11 interpreter.

3. For release signing, drop your own `TMessagesProj/release.keystore` and add
   `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS` to `local.properties`. No keystore is
   shipped with the repository; without one the build is signed with the Android debug key.

4. For push notifications, replace `TMessagesProj/google-services.json` with your own
   Firebase config for `com.exteraless.app`.

5. Optional: `MAPS_API_KEY` and `GLYPH_API_KEY` in `local.properties`, and your metadata
   channel id (without `-100`) in `BaseRemoteHelper.CHANNEL_METADATA_ID`.

6. Build with `./gradlew :TMessagesProj:assembleDebug` or from Android Studio. The Python
   runtime is built from the `TMessagesProj/chaquopy` submodule during the build.

`NATIVE_TARGET` selects the ABI: `arm64-v8a` or `x86_64` (one ABI, faster), empty or
`universal` (one APK with both), `SKIP` (no native part). 32-bit ABIs are not built.

For CI, set two repository secrets — `LOCAL_PROPERTIES` and `RELEASE_KEYSTORE`, both
base64-encoded — and run the **Release Build** workflow. The APK ends up in the run
artifacts.

### Design credits

Icons and visual design inherited from exteraGram are the work of its designer,
[@the8055u](https://t.me/the8055u), and the [@BlueprintDsgn](https://t.me/BlueprintDsgn)
studio. Rights to those materials belong to their authors.
