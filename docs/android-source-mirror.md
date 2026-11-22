# Зеркало Android-исходников (`android-source/`)

`android-source/` — распакованная копия Android-проекта из `WaysGram-Android.zip.part1…part5`, чтобы код можно
было читать и искать обычными инструментами (`grep`, `diff`, IDE, агент), не разворачивая архив каждый раз.

## Что является источником правды

| Артефакт | Роль |
|---|---|
| `WaysGram-Android.zip.part1…part5` | **источник правды для сборки.** `.github/workflows/build-apk.yml` склеивает части, распаковывает их и запускает Gradle |
| `android-source/` | **только для чтения.** Миррор исходников; сборка его не использует |
| `scripts/extract_android_source.py` | пересобирает миррор из частей архива |

Правило прежнее: правки Android-кода вносятся в архив (распаковать → поправить → склеить), а не в
`android-source/`. Если править миррор, изменения не попадут в APK и потеряются при следующей распаковке.

## Что внутри

```
android-source/
├── TMessagesProj/            # основной модуль: java, res, assets-текст, конфиги
│   └── src/main/java/org/telegram/
│       ├── ui/               # экраны (ChatActivity, ProfileActivity, ChatEditActivity, ...)
│       ├── ui/Components/    # вью-компоненты (в т.ч. chat/layouts/ChatActivityChannelButtonsLayout)
│       ├── messenger/        # MessagesController, SendMessagesHelper, ImageUpdater, ...
│       ├── messenger/waysgram/  # собственная функциональность WaysGram (34 класса)
│       ├── tgnet/            # TLRPC + WaysGramTransport/WaysGramChats/WaysGramProfile (REST-шим)
│       └── androidx/, com/, me/  # локальные копии библиотечных классов
├── TMessagesProj_App*/       # варианты приложения (afat, standalone, huawei, hockeyapp)
├── buildSrc/, gradle/, build.gradle, settings.gradle, gradlew
├── WAYSGRAM_CHANGES.md       # перечень отличий от исходного Telegram
└── README.md, LICENSE, Tools/
```

Итог: **3 170 файлов, ~80 МБ** (java — 2 326 файлов, xml — 662).

## Что намеренно не распаковано

| Исключено | Объём | Почему |
|---|---|---|
| `TMessagesProj/jni/**` | 274 МБ | нативный C/C++ и third-party (submodules подтягиваются workflow'ом) |
| `*.a`, `*.so` | 215 МБ | прекомпилированные библиотеки (`libwallet_engine.a`, `libcrypto.a`, …) пересобираются в CI |
| `res/raw/*.json` | 17,7 МБ | анимации реакций (данные, не код) |
| `assets/*.pack`, `*.dat`, `assets/fonts/**`, `assets/models/**`, `*.attheme` | ~12 МБ | бинарные ресурсы |
| `*.webp`, `*.png`, `*.jpg` | ~3 МБ, ~4 300 файлов | растровые ресурсы; экономят лимит по числу файлов |

Полная распаковка (381 МБ, 13 242 файла) доступна флагом `--full`.

## Как пересобрать

```bash
python3 scripts/extract_android_source.py            # обновить android-source/ (нужные файлы перезапишутся)
python3 scripts/extract_android_source.py --clean    # удалить каталог и распаковать заново
python3 scripts/extract_android_source.py --with-images  # добавить растровые ресурсы
python3 scripts/extract_android_source.py --full     # распаковать архив целиком
```

Скрипт сам склеивает пять частей во `/tmp/WaysGram-Android.zip`, проверяет архив (`ZipFile.testzip()`) и
пишет файлы только внутрь репозитория.

## Проверка целостности

SHA-256 файлов миррора совпадают с содержимым архива — выборочная проверка:

```bash
python3 - <<'PY'
import hashlib, pathlib, zipfile
z = zipfile.ZipFile('/tmp/WaysGram-Android.zip')
for name in ['TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java',
             'TMessagesProj/src/main/AndroidManifest.xml']:
    a = hashlib.sha256(z.read('WaysGram-Android/' + name)).hexdigest()
    b = hashlib.sha256((pathlib.Path('android-source') / name).read_bytes()).hexdigest()
    print('OK' if a == b else 'FAIL', name)
PY
```

## Куда смотреть дальше

* [`channel-group-ui-map.md`](channel-group-ui-map.md) — карта интерфейса каналов и групп: экраны, пути,
  обработчики нажатий, сетевые запросы и первый проход сравнения с WaysGram.
* `android-source/WAYSGRAM_CHANGES.md` — перечень отличий от исходного Telegram по функциональности.
