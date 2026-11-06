# WaysGram-New

Этот репозиторий подключает Android-исходники из архива как Git-подмодуль `Telegram` и запускает сборку APK через GitHub Actions.

## Зафиксированный исходный код

Подмодуль `Telegram` закреплён на коммите [DrKLO/Telegram@3de37c8](https://github.com/DrKLO/Telegram/commit/3de37c8cfb039452be7398909461da6288babf15) от 25 октября 2026 года (версия 13.1.1, build 7237). Контрольные файлы архива совпали с файлами этого коммита. Код подмодуля не изменялся. В `WaysGram-New` хранится Git-ссылка на этот коммит, а не дублируются все исходные файлы; при клонировании с `--recurse-submodules` они загружаются из указанного репозитория.

Для получения исходников вместе со всеми вложенными подмодулями:

```bash
git clone --recurse-submodules https://github.com/WeteroScript/WaysGram-New.git
```

## Сборка APK

Откройте **Actions → Build Android APK → Run workflow**. Сборка использует JDK 17, Android SDK 36, NDK 27.2.12479018 и CMake 3.22.1.

Задача Gradle: `:TMessagesProj_App:assembleAfatRelease`.

Готовый APK появится в артефактах успешного запуска workflow. Исходные файлы внутри подмодуля остаются неизменёнными.
