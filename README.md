# WaysGram-New

Репозиторий подготовлен для исходного снимка из архива `Telegram-master.zip`.

## Структура проекта

В архиве Gradle-проект расположен в корне каталога `Telegram-master/`. Основные модули:
- `:TMessagesProj`
- `:TMessagesProj_App`
- `:TMessagesProj_AppHuawei`
- `:TMessagesProj_AppStandalone`
- `:TMessagesProj_AppHockeyApp`
- `:TMessagesProj_AppTests`

Архив содержит 14 086 файлов и `.gitmodules` с 19 внешними зависимостями. Каталоги зависимостей в ZIP пустые, поэтому CI должен получать их отдельно.

**Важно:** настройка репозитория и workflow ещё не означает, что полный снимок исходников уже импортирован. Для сборки файлы проекта должны находиться в корне репозитория рядом с `gradlew` и `settings.gradle`.

## Сборка APK

Используемая по конфигурации проекта задача:

```bash
./gradlew :TMessagesProj_App:assembleAfatRelease
```

Требуемые версии: JDK 17, Android SDK / Build Tools 36, NDK 27.2.12479018, CMake 3.22.1.

После импорта исходников workflow **Build Android APK** соберёт APK и прикрепит его к запуску Actions как artifact.

## WaysGram Android-функциональность

Перед сборкой workflow загружает overlay из [WaysGram-source](https://github.com/WeteroScript/WaysGram-source) на закреплённом коммите `ce8c1ce2db4832c6d1a29947ad64d9320927d53d` и встраивает его в распакованный Android-проект. Это включает отдельный вход WaysGram, каталог и инвентарь подарков, покупки за внутренние Stars, переводы, внутренний маркетплейс, детерминированные улучшения, подарки в профилях и личных чатах. Telegram Gift ID используется только как источник метаданных; внутренние операции не передают реальные Telegram NFT.
