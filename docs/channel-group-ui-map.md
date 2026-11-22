# Карта интерфейса каналов и групп в исходном Telegram-клиенте

Документ отвечает на три вопроса по каждому элементу: **где он на экране**, **где он в коде** (`файл:строка`)
и **что происходит при нажатии** (какой экран открывается, какой запрос уходит).

Все пути даны относительно `android-source/TMessagesProj/src/main/java/org/telegram/`, строки — по состоянию
архива `WaysGram-Android.zip.part1…part5` (коммит базы `95c11ad`). Миррор исходников и скрипт его сборки
описаны в [`android-source-mirror.md`](android-source-mirror.md). Номера строк не «вечные»: они верны для этого
среза и сдвинутся после правок, поэтому рядом с каждой цифрой назван класс и метод.

---

## 0. Как устроен UI в этом клиенте (важно для навигации)

| Что | Где | Следствие для поиска |
|---|---|---|
| Экраны | `ui/*.java` — наследники `BaseFragment`, каждый строит `fragmentView` кодом | разметку ищут в Java, а не в XML |
| Разметка | `res/layout/` — всего 22 файла (в основном служебные: виджеты, `Story/…`) | «экрана канала» в XML нет |
| Меню | `ActionBar.createMenu()` → `menu.addItem(id, drawable)`; вложенные пункты — `headerItem.lazilyAddSubItem(id, …)` | нет `res/menu/*.xml`, id пунктов — `int`-константы в самом классе |
| Обработчик меню | `actionBar.setActionBarMenuOnItemClick(...)` → `onItemClick(int id)`, `switch`/`if` по константам | от id к поведению ведёт один метод |
| Тексты | `LocaleController.getString(R.string.X)` → `res/values/strings.xml` (+ `values-ru/`, `values-de/` …) | подпись кнопки ищут по `R.string.*`, а не по литералу |
| Иконки | `R.drawable.*` → `res/drawable*` (векторные XML и `.webp` в архиве) | |

Меню, которое строится «лениво» (`lazilyAddSubItem`), создаётся один раз при открытии экрана, а видимость
отдельных пунктов потом переключается через `setSubItemShown` / `showSubItem` / `hideSubItem` — поэтому
условие «кому видно» часто лежит не рядом с `addItem`, а в `update…`-методах.

---

## 1. Вход в канал/группу

| Шаг | Код | Что происходит |
|---|---|---|
| Список чатов | `ui/DialogsActivity.java`, `listView.setOnItemClickListener` | обычный тап по диалогу |
| Открытие | `presentFragment(new ChatActivity(args))` (ветка для обычного тапа) | пуш экрана чата |
| Карточка рекомендованного канала в ленте | `ui/ChatActivity.java:41369` `didPressChannelRecommendation(ChatMessageCell, TLObject peer, boolean longPress)` | обычный тап → `presentFragment(new ChatActivity(args))` (41412); долгое нажатие → `presentFragmentAsPreviewWithMenu(...)` (41410) с popup-меню «Открыть» (`OpenChannel2`, 41386) и «Подписаться» (`ProfileJoinChannel`, 41397 → `MessagesController.addUserToChat`, 41407) |
| Экран чата | `ui/ChatActivity.java` (~50 000 строк) + обёртка `ui/ChatActivityContainer.java` | один класс на все типы чатов, режимы через `chatMode` |

`ChatActivity` — «монолит»: топбар, лента, нижняя панель, поиск, контекстное меню сообщений живут в нём.
Карточка профиля/канала — отдельный `ui/ProfileActivity.java` (~17 000 строк).

---

## 2. Топбар чата канала/группы (`ChatActivity`)

### 2.1 Константы пунктов

`ui/ChatActivity.java:1652-1725`:

```
chat_menu_search = -1      chat_menu_options = -2      chat_menu_edit_text_options = -3
clear_history = 15   delete_chat = 16   mute = 18   report = 21   edit = 23   add_shortcut = 24
auto_delete_timer = 26   change_colors = 27   tag_message = 28   boost_group = 29
call = 32   video_call = 33   search = 40   view_as_topics = 59   open_forum = 61
topic_close = 60   translate = 62   share = 69   open_direct = 70
waysgram_channel_inbox = 75   waysgram_group_topics = 76   waysgram_channel_giveaways = 77
waysgram_group_stories = 78   waysgram_group_identity = 79   chat_menu_topic_create = 73
```

### 2.2 Что именно попадает в меню «⋮» для канала/группы

Сборка — `createMenu()`, `ui/ChatActivity.java:4300+` (`ActionBarMenu menu = actionBar.createMenu()`):

| Пункт | Подпись (`R.string`) | Где добавлен | Видимость |
|---|---|---|---|
| Поиск (иконка) | `Search` | `4326` `menu.addItem(search, …)` | всегда, кроме служебных режимов |
| Звонок (иконка) | `Call` | `4356` (`lazilyAddItem(call, …)`) | только для пользователей |
| Уведомления (свайп-пункт) | `msg_mute` + `ChatNotificationsPopupWrapper` | `4401-4469` `muteItem = headerItem.lazilyAddSwipeBackItem(...)` | свайп раскрывает настройки: `toggleSound`, `muteFor`, `showCustomize` → `ProfileNotificationsActivity`, `toggleMute` |
| Открыть прямой чат | `ChannelOpenDirect` | `4478-4480` `open_direct` | канал с привязанной моногруппой |
| **WaysGram:** входящие канала | `WaysGramInboxTitle` / `WaysGramInboxWriteChannel` | `4482-4486` `waysgram_channel_inbox` | broadcast-канал WaysGram |
| **WaysGram:** розыгрыши | `WaysGramGiveaways` | `4483` `waysgram_channel_giveaways` | broadcast-канал WaysGram |
| **WaysGram:** истории группы / идентичность / темы | `WaysGramGroupStories`, `WaysGramGroupIdentity`, `WaysGramTopics` | `4488-4494` | обычная группа WaysGram |
| Звонок/видеозвонок | `Call`, `VideoCall` | `4496-4510` | пользователи |
| Поиск в подменю | `Search` | `4513-4515` | если есть `searchItem` |
| Бусты сообщества | `WaysGramCommunityBoosts` (WaysGram) или `BoostingBoostGroupMenu`/`BoostingBoostChannelMenu` | `4517-4526` `boost_group` | канал/группа |
| Перевод | `TranslateMessage` | `4527` `translate` | по настройке |
| Жалоба | `ReportChat` | `4529-4531` `report` | не создатель и не админ |
| Обои/цвета | `SetWallpapers` | `4534` `change_colors` | тема позволяет |
| Ярлык | `AddShortcut` | `4536` | у себя |
| Очистить историю | `ClearHistory` | `4540` `clear_history` | не тема |
| **Выйти** | `LeaveMegaMenu` / `LeaveChannelMenu` / `LeaveConversationMenu` | `4545-4554` `delete_chat` | подписан и не создатель |
| Удалить и выйти | `DeleteAndExit` | `4557` | обычная (неканальная) группа |
| Просмотр как темы | `TopicViewAsTopics` | `4531` `view_as_topics` | канал/группа, не тема |
| Все темы | `OpenAllTopics` | `4420-4426`, `4604` `open_forum` | форум |
| Закрыть тему | `CloseTopic` | `4607-4609` `topic_close` | есть права на тему |

### 2.3 Обработчик: `onItemClick(int id)` — `ui/ChatActivity.java:3755`

| id | Код | Действие |
|---|---|---|
| `mute` (18) | `3967-3968` → `toggleMute(false)` (`16897`) | переключает уведомления, показывает `BulletinFactory.createMuteBulletin` |
| `boost_group` (29) | `3975-3985` | WaysGram → `WaysGramPaidReactions.showCommunityBoosts`; иначе админ → `BoostsActivity`, гость → `NotificationCenter.openBoostForUsersDialog` |
| `report` (21) | `3986-3987` | `ReportBottomSheet.openChat(this)` |
| `edit` (23) | `3996-4019` | **правит не канал, а сообщение**: `startEditingMessageObject(...)` / `OPTION_EDIT_TODO` — это режим выделения сообщений |
| `search` (40) | `4043-4044` | `openSearchWithText(...)` |
| `translate` (62) | `4045-4050` | `TranslateController.toggleTranslatingDialog` |
| `call`/`video_call` | `4051-4055` | `VoIPHelper.startCall(...)` |
| `clear_history` / `delete_chat` / `auto_delete_timer` | `3901-3954` | `AlertsCreator.createClearOrDeleteDialogAlert(...)` — подтверждение и удаление истории/выход |
| `share` (69) | `3820+` | отправка чата в другой чат |
| `chat_menu_attach` (14) | `4028-4041` | открывает меню вложений (`openAttachMenu`) |

> Важное отличие от карточки профиля: в чате иконка «карандаш» отсутствует, редактирование канала/группы
> вызывается **только из карточки** (`ProfileActivity` → `ChatEditActivity`, см. §4).

---

## 3. Нижняя панель канала (`ChatActivity`)

Канал, в который нельзя писать, прячет поле ввода и показывает панель кнопок — `updateBottomOverlay(boolean animated)`
(`ui/ChatActivity.java:28128`, интересующая ветка — `28490-28520`):

Условие показа панели вместо поля ввода (`28497-28500`):

```java
} else if (chatMode == MODE_PINNED ||
        currentChat != null && (... (ChatObject.isNotInChat(currentChat) && ... || !ChatObject.canWriteToChat(currentChat)) ...)
```

| Элемент | Класс / код | При нажатии |
|---|---|---|
| Основная кнопка «Подписаться / Уведомления» | `bottomOverlayChatText`, текст ставится в `28224-28330` (`ChannelMuteNoCaps`, `ChannelUnmuteNoCaps`), клик — обработчик `bottomOverlayChat` ~`8600` | не подписан → `MessagesController.addUserToChat(...)` (`8683` для `join_request`, `8690` обычный путь); подписан → `toggleMute(true)`; не канал → `createClearOrDeleteDialogAlert` |
| Плавающие кнопки | `ui/Components/chat/layouts/ChatActivityChannelButtonsLayout.java`, слушатели — `8455-8515` | `BUTTON_SEARCH` → поиск (`8465`); `BUTTON_GIFT` → `GiftSheet` (`8472`); `BUTTON_DIRECT` → чат предложений `MODE_SUGGESTIONS` (`8476`); `BUTTON_GIGA_GROUP_INFO` → `UndoView` с `BroadcastGroupInfo` (`8468`) |
| Видимость кнопок | `28516-28519` `bottomChannelButtonsLayout.showButton(...)` | управляется флагами `showSearchButton`, `showSuggestButton`, `showGiftButton`, `showGigaGroupButton` |

---

## 4. Карточка канала/группы (`ProfileActivity`)

### 4.1 Меню топбара

`ui/ProfileActivity.java:4058-4111`:

| Пункт | id | Где добавлен |
|---|---|---|
| Поиск | `search_button = 32` | `4058` |
| Видеозвонок | `video_call_item = 16` | `4091` |
| Голосовой чат / звонок | `call_item = 15` | `4094`, `4101` |
| **«Карандаш» — редактирование** | `edit_channel = 12` (для чужого канала) / `edit_profile = 41` (свой профиль) | `4105`, `4108`; иконка `group_edit_profile`, `contentDescription = R.string.Edit` |
| «⋮» | `otherItem = menu.addItem(10, ic_ab_other)` | `4111` |

Константы — `ui/ProfileActivity.java:570-609` (`leave_group = 7`, `invite_to_group = 9`, `share = 10`,
`edit_channel = 12`, `statistics = 19`, `search_members = 17`, `add_member = 18`, `view_discussion = 22`,
`delete_topic = 23`, `report = 24`, `edit_info = 30`, `channel_stories = 39`, `edit_color = 40`,
`copy_link_profile = 42`, `delete_group = 45`, …).

### 4.2 Обработчик `onItemClick(int id)` — `ui/ProfileActivity.java:2599`

| id | Код | Что происходит |
|---|---|---|
| `edit_channel` (12) | `2705-2720` | **открывает `ChatEditActivity`** (`new ChatEditActivity(args)` + `fragment.setInfo(chatInfo)`), для темы — `TopicCreateFragment` |
| `edit_profile` (41) | `2721` | `UserInfoActivity` |
| `leave_group` (7) | `2654-2655` | `leaveChatPressed(false)` → `AlertsCreator` (выйти) |
| `delete_group` (45) | `2656-2657` | `leaveChatPressed(true)` → удалить канал/группу |
| `search_members` (17) | `2802-2809` | `ChatUsersActivity` с `type = TYPE_USERS`, `open_search = true` |
| `add_member` (18) | `2810-2811` | `openAddMember()` |
| `statistics` (19) | `2812-2814` | `StatisticActivity.create(chat, false)` |
| `view_discussion` (22) | `2815-2816` | `openDiscussion()` |
| `channel_stories` (39) | `2821-2827` | `MediaActivity` c `TYPE_ARCHIVED_CHANNEL_STORIES` |
| `gift_premium` (38) | `2817-2818` | `onGiftPermiumClicked()` |
| `share` (10) | `2782-2783` | `onShareClicked()` |
| `edit_color` (40) | `2873-2878` | `PeerColorActivity` |
| `copy_link_profile` (42) | `2879-2881` | ссылка в буфер обмена |

### 4.3 Состав меню «⋮» — `ui/ProfileActivity.java:12440-12560`

Для канала (`ChatObject.isChannel(chat)`, ветка `12461`):

| Пункт | Условие | Код |
|---|---|---|
| Начать голосовой чат / трансляцию | `canManageCalls` и звонка нет | `12472` |
| Статистика | `can_view_stats` / `can_view_revenue` / истории | `12480-12482` |
| Архив историй | есть права на истории | `12496-12499` |
| Поиск по участникам | мегагруппа, участники не скрыты | `12501-12505` |
| Удалить темы | тема + право на удаление | `12506-12508` |
| Ярлык | тема 0 | `12509-12511` |
| **Выйти / Удалить** | создатель → оба пункта с красным `delete`, иначе «Выйти» | `12512-12526` (мегагруппа), `12534-12552` (канал) |
| Поделиться | публичный канал | `12528-12531` |
| Подарить звонкие звёзды/подарок | `stargifts_available` | `12532-12536` |
| Открыть обсуждение | `linked_chat_id != 0` | `12537-12540` |

Для обычной группы (`12553+`) и мегагруппы набор тот же, плюс `Медленный режим` для групп WaysGram
(`12554-12557`, id `WAYSGRAM_SLOW_MODE`, подпись «Медленный режим»).

### 4.4 Строки карточки (переходы в списки участников)

| Строка | Код «нарисовать» | Код «нажать» | Открывается |
|---|---|---|---|
| Подписчики/Участники | `13993-14006` | `4567-4571` (`position == subscribersRow`) | `ChatUsersActivity` `TYPE_USERS` |
| Администраторы | `14015-14017` | `4580-4581` (`administratorsRow`) | `ChatUsersActivity` `TYPE_ADMIN` |
| Заблокированные | — | `4598-4599` (`blockedUsersRow`) | `ChatUsersActivity` `TYPE_BANNED` |
| «Присоединиться» (гость) | `13993` `ProfileJoinChannel` | `4567`-ветка | подписка |

---

## 5. Экран редактирования канала/группы (`ChatEditActivity`)

`ui/ChatEditActivity.java` — один экран на канал, мегагруппу, обычную группу и бота; заголовок всегда
`R.string.ChannelEdit` (`707`), кнопка «галочка» — id `done_button = 1` (`263`), обработчик `562-571`:

```java
} else if (id == done_button) {
    processDone();
```

### 5.1 Строки экрана и их обработчики

| Строка | Подпись | Добавление | Нажатие |
|---|---|---|---|
| Аватар + «Установить фото» | — | `843` `setAvatarCell` | `imageUpdater` → `MessagesController` (для канала `channels.editPhoto`, `16124`) |
| Имя | `ChannelName`-поле (`nameTextView`) | — | ввод, при сохранении — `changeChatTitle` |
| Описание | `DescriptionOptionalPlaceholder` (`863`) | — | ввод, при сохранении — `updateChatAbout` |
| Тип канала (публичный/приватный + ссылка) | `ChannelType` (`2469`) | `typeCell` | `958-962` → **`ChatEditTypeActivity`** |
| Пригласительные ссылки | `InviteLinks` (`2556`) | `inviteLinksCell` | `1299-1303` → **`ManageLinksActivity`** |
| Реакции | `Reactions` (`2715`) | `reactionsCell` | `1307-1315` → `ChatCustomReactionsEditActivity` (канал) / `ChatReactionsEditActivity` (группа) |
| Обсуждение | `Discussion` (`2412-2423`) | `linkedCell` | `1003` → привязанный чат |
| Скрыть прехисторию | `HiddenPrewHistory`-ветка (`1114`) | `historyCell` | переключатель, применяется в `processDone` |
| Форум/темы | `forumsCell` (`1189`) | `forumsCell` | переключатель → `toggleChannelForum` |
| Администраторы | `ChannelAdministrators` | `adminCell` | `1335-1342` → `ChatUsersActivity` `TYPE_ADMIN` |
| Участники | `ChannelMembers`/`ChannelSubscribers` | `membersCell` | `1346-1353` → `ChatUsersActivity` `TYPE_USERS` |
| Заявки на вступление | `MemberRequests` | `memberRequestsCell` | `1358` → `MemberRequestsActivity` |
| Журнал действий | `AdminLog`/`RecentActions` | `logCell` | `1376` → **`ChannelAdminLogActivity`** |
| Статистика и бусты | `StatisticsAndBoosts` | `1379-1386` | `StatisticActivity.create(...)` (в WaysGram скрыто — `!WaysGramTransport.ENABLED`) |
| Удалить канал/группу | `ChannelDelete`/`DeleteMega` | `1680-1685` `deleteCell` | `1685` → предупреждение → `MessagesController.deleteChat` |
| **WaysGram-строки** | `WaysGramPaidReactions`, `WaysGramChannelAnalytics`, `WaysGramSubscription`, `WaysGramDiscussions`, `WaysGramInbox`, «Скрытые участники», «Локация», «Аналитика группы», «Права на отправку» | `891-1000` | соответствующие `WaysGramPaidReactions.*`, `WaysGramDiscussions.settings`, `WaysGramChannelInbox.settings` |

### 5.2 «Галочка» → `processDone()` — `ui/ChatEditActivity.java:2155`

| Что сравнивается | Вызов | Строка |
|---|---|---|
| пустое имя | вибрация + `AndroidUtilities.shakeView(nameTextView)`, выход | `2157-2166` |
| бот | `TL_bots.setBotInfo` | `2168-2200` |
| обычная группа без канала | `MessagesController.convertToMegaGroup(...)`, затем повторный `processDone()` | `2204-2218` |
| скрытая прехистория | `MessagesController.toggleChannelInvitesHistory(chatId, historyHidden)` | `2225` |
| идёт загрузка аватара | ждёт `createAfterUpload` | `2229-2239` |
| изменилось имя | `MessagesController.changeChatTitle(chatId, name)` | `2242` |
| изменилось описание | `MessagesController.updateChatAbout(chatId, about, info)` | `2246` |
| включили форум | `MessagesController.toggleChannelForum(...)` + перестройка стека (`ChatActivity` → `TopicsFragment`) | `2250-2277` |

Сетевой результат: `changeChatTitle` → `channels.editTitle` (канал) или `messages.editChatTitle` (группа);
`updateChatAbout` → `messages.editChatAbout`; аватар → `channels.editPhoto` / `messages.editChatPhoto`.

---

## 6. Публичность канала (`ChatEditTypeActivity`)

`ui/ChatEditTypeActivity.java` — открывается из `typeCell` (§5.1).

| Элемент | Код | Поведение |
|---|---|---|
| Радио «Публичный» | `348-356` | `isPrivate = false`, `updatePrivatePublic()`; при лимите — `showPremiumIncreaseLimitDialog()` |
| Радио «Приватный» | `363-371` | `isPrivate = true` |
| Проверка username | `191`, `1507` `TL_channels_checkUsername` | асинхронная проверка занятости |
| Поле ссылки / «Управлять ссылками» | `581` `manageLinksTextView` → `ManageLinksActivity` | список пригласительных ссылок |
| Ограничение сохранения контента | `640` `saveRestrictCell` | `toggleChannelRestrictSaving`-аналог |
| Сохранение | `732` `processDone()`, `1336` `TL_channels_updateUsername` | смена адреса канала |

Заголовки: `ChannelInviteLinkTitle` / `ChannelLinkTitle` (`1394-1398`), подсказки
`ChannelPrivateLinkHelp` / `ChannelUsernameHelp`.

---

## 7. Участники, администраторы, права

### 7.1 `ChatUsersActivity`

| Что | Код |
|---|---|
| Типы списка | `237-241`: `TYPE_BANNED = 0`, `TYPE_ADMIN = 1`, `TYPE_USERS = 2`, `TYPE_KICKED = 3`, `TYPE_ADMIN_AND_USERS = 4` |
| Режимы выбора | `244+`: `SELECT_TYPE_ADMIN` («Добавить админа»), `SELECT_TYPE_BLOCK`, `SELECT_TYPE_EXCEPTION`, `SELECT_TYPE_MEMBERS` |
| Заголовки | `639-660`: `ChannelPermissions`, `ChannelBlacklist`, `ChannelAdministrators`, `ChannelSubscribers`/`ChannelMembers`, `ChannelAddAdmin`, `ChannelBlockUser`, `ChannelAddException` |
| Открыть права участника | `1053` `openRightsEdit(user.id, …, ChatRightsEditActivity.TYPE_ADMIN, …)` |
| Изменить роль/забанить | `2032` `TL_channels_editBanned`; `2001` `MessagesController.setUserAdminRole(...)` |
| Загрузка списка | `2395-2420`: `channels.getParticipants` с фильтром `channelParticipantsKicked` / `Admins` / `Recent` / `Contacts` |

### 7.2 `ChatRightsEditActivity`

| Что | Код |
|---|---|
| Типы | `114-115`: `TYPE_ADMIN = 0`, `TYPE_BANNED = 1` |
| Тумблеры прав | строки `893-975` (`post_messages`, `edit_messages`, `delete_messages`, `invite_users`, `pin_messages`, `ban_users`, `manage_call`, …), «Анонимность» — `anonymousRow` |
| Сохранение прав | `1619`, `1709` → `MessagesController.setUserAdminRole(...)` |
| Сетевой запрос | `messenger/MessagesController.java:8813-8826`: `TL_channels_editAdmin` (`channel`, `user_id`, `admin_rights`, `rank`) |
| Снятие админа | `713`: `setUserAdminRole(..., new TL_chatAdminRights(), ...)` |

---

## 8. Журнал действий (`ChannelAdminLogActivity`)

| Элемент | Код | Поведение |
|---|---|---|
| Открытие | `ui/ChatEditActivity.java:1376` `logCell` → `new ChannelAdminLogActivity(currentChat)` | — |
| Нижняя кнопка «Фильтр» | `ui/ChannelAdminLogActivity.java:1493-1500` `bottomOverlayChatText` | открывает `AdminLogFilterAlert2` (события + админы), применение → `setAdminLogFilterAlertDelegate` |
| Поиск/навигация по датам | `1546-1572` (`searchUpButton`, `searchDownButton`, `searchCalendarButton`) | переходы по результатам |
| Запрос | `channels.getAdminLog` (в этом классе) | — |

---

## 9. Пригласительные ссылки (`ManageLinksActivity`)

`ui/ManageLinksActivity.java` (класс `91`) — полноценный экран: список ссылок, создание, отзыв, импортёры.
Открывается из двух мест:

* `ChatEditActivity` → строка `InviteLinks` (`1299`);
* `ChatEditTypeActivity` → `manageLinksTextView` (`581`).

Запросы: `messages.exportChatInvite`, `messages.getExportedChatInvites`, `messages.editExportedChatInvite`,
`messages.deleteExportedChatInvite`, `messages.getChatInviteImporters`.

---

## 10. Создание канала и группы

| Экран | Код | Шаги |
|---|---|---|
| Канал, шаг 1 | `ui/ChannelCreateActivity.java` | имя + описание; «галочка» (`328-360`) → `MessagesController.createChat(..., ChatObject.CHAT_TYPE_CHANNEL, ...)` |
| Канал, шаг 2 (ссылка) | тот же класс, `currentStep == 1` | радио «Публичный/Приватный» (`734`, `759`), ссылка генерируется в `generateLink()` (`922`), сохранение → `channels.createChannel` + при необходимости `channels.updateUsername` |
| Группа, шаг 1 | `ui/GroupCreateActivity.java` | выбор контактов |
| Группа, шаг 2 | `ui/GroupCreateFinalActivity.java` | имя, аватар, автоудаление; FAB (`665-687`) → `MessagesController.createChat(name, selectedContacts, ...)` |

---

## 11. Куда уходят запросы (сводка)

| Действие в UI | Запрос TL |
|---|---|
| «Подписаться» (карточка, нижняя панель, превью) | `channels.joinChannel` (реализуется через `MessagesController.addUserToChat`) |
| «Выйти» | `channels.leaveChannel` / `messages.deleteChatUser` |
| «Удалить канал» | `channels.deleteChannel` |
| «Галочка» в редактировании (имя/описание) | `channels.editTitle`, `messages.editChatAbout` |
| Аватар канала/группы | `channels.editPhoto`, `messages.editChatPhoto` |
| Публичность и адрес | `channels.checkUsername`, `channels.updateUsername` |
| Права админа | `channels.editAdmin` |
| Кick/бан/исключение | `channels.editBanned` |
| Списки участников | `channels.getParticipants` |
| Журнал | `channels.getAdminLog` |
| Ссылки | `messages.*ChatInvite*` |
| Создание | `channels.createChannel`, `messages.createChat` |

---

## 12. Первый проход сравнения с нашей копией (WaysGram)

Основание: `WaysGramTransport.ENABLED = true` (`tgnet/WaysGramTransport.java:30`) — все TL-запросы
перехватываются и уходят в REST-бэкенд; всё, что не перехвачено, возвращает
`METHOD_NOT_SUPPORTED:<метод>` (`291-296`). Поэтому «есть ли кнопка» и «работает ли кнопка» — разные вопросы:
интерфейс часто остаётся телеграмовским, а обработчик либо подменён, либо скрыт по флагу.

| Место | В исходном Telegram | В нашей копии | Файл (наш) |
|---|---|---|---|
| Топбар чата | search, mute, clear, leave, report, boost, translate, colors | те же в стоковом порядке; 5 своих пунктов (inbox, giveaways, topics, stories, identity) добавлены **в конец** списка | `ui/ChatActivity.java`, блок перед `menu.setVisibility` |
| Нижняя панель канала | Join / Mute + Gift / Suggest / Search / Giga info | те же кнопки; «Подарок» ведёт в `GiftSheet`, «Предложить» — в моногруппу | `ui/ChatActivity.java:8465-8512` |
| Карточка: «карандаш» | `ChatEditActivity` | тот же экран, но часть строк скрыта флагами | `ui/ProfileActivity.java:4108`, `ui/ChatEditActivity.java:891-1000` |
| Статистика канала | `StatisticActivity` | стоковое условие (`can_view_stats` и т.п.); у WaysGram данных пока нет, своя аналитика — в конце экрана редактирования | `ui/ProfileActivity.java:12480-12482`, `ui/ChatEditActivity.java:976-980` |
| Голосовой чат / звонки | `canManageCalls` | стоковое условие без WaysGram-гейтов (`!waysGramBroadcast` убрано) | `ui/ProfileActivity.java:12469-12474` |
| Аватар канала | `channels.editPhoto` | **не перехвачен** → `METHOD_NOT_SUPPORTED`; сервер аватар не хранит | `tgnet/WaysGramTransport.java` (нет ветки), `messenger/MessagesController.java:16124` |
| Имя/описание канала | `channels.editTitle`, `messages.editChatAbout` | перехвачены, идут в `/api/channels/{id}` | `tgnet/WaysGramTransport.java:600-604`, `622-630` |
| Создание канала | `channels.createChannel` | `WaysGramProfile.createChannel` (`POST /api/channels`) | `tgnet/WaysGramTransport.java:594`, `tgnet/WaysGramProfile.java:433` |
| Права администраторов | полный набор `TL_chatAdminRights` | только 7 бит: post/edit/delete/pin/invite/ban/change_info; остальные → `CHAT_ADMIN_RIGHTS_UNSUPPORTED` | `tgnet/WaysGramChats.java:137-160` |
| Журнал действий | `channels.getAdminLog` | **не перехвачен** (экран открывается, запрос упадёт) | `ui/ChatEditActivity.java:1376` → `ChannelAdminLogActivity` |
| Пригласительные ссылки | `messages.*ChatInvite*` | перехвачены, реализованы в `WaysGramInvites` | `tgnet/WaysGramTransport.java:470-489` |
| Список участников | фильтры admins/recent/contacts | для WaysGram-канала всегда `channelParticipantsRecent` | `ui/ChatUsersActivity.java:2407` |
| Ярлык «подписан на канал» | хранится на сервере | хранится на устройстве (`waysgram_session`) | `tgnet/WaysGramChats.java:63-88` |
| Группы: «Медленный режим» | нет такого пункта | свой пункт `WAYSGRAM_SLOW_MODE`, перенесён в конец меню группы | `ui/ProfileActivity.java:12570-12575` |

Что из этого **уже проверено по коду** и что **нужно проверить отдельно**:

* проверено: перехват TL-методов (`WaysGramTransport`), ограничение прав (`WaysGramChats.editChannelAdmin`),
  состав меню и обработчиков (§2–§10), отсутствие ветки для `channels.editPhoto` и `channels.getAdminLog`;
* требует прогона (сборку не запускали): фактическое поведение `ChannelAdminLogActivity` и загрузки аватара
  на живом бэкенде — по коду они гарантированно получают `METHOD_NOT_SUPPORTED`, но экранную реакцию
  (тост/ошибка/молчание) нужно смотреть в рантайме.

---

## 12a. Что уже приведено к Telegram

Порядок кнопок и правила их включения приведены к стоковым в `ChatActivity`, `ProfileActivity` и
`ChatEditActivity`; подробности и список оставшихся серверных зависимостей —
[`channel-group-ui-parity.md`](channel-group-ui-parity.md).

## 13. Что дальше (глубокое сравнение)

Предлагаемый порядок, когда понадобится детальный разбор отличий:

1. **Карточка канала**: построить матрицу «телеграмовский пункт → виден ли в WaysGram-канале → работает ли»
   (условия видимости лежат в `ProfileActivity:12380-12560` и `ChatActivity:4400-4560`).
2. **Редактирование**: сверить `ChatEditActivity` построчно с `processDone` — какие поля реально сохраняются
   (`about` и аватар, по `WAYSGRAM_CHANGES.md`, сервер не хранит).
3. **Права**: сопоставить 7 поддерживаемых бит с UI-тумблерами `ChatRightsEditActivity` — часть тумблеров
   заведомо приведёт к отказу сервера.
4. **Журнал, статистика, бусты**: определить, скрывать их полностью или показывать свой экран
   (`WaysGramPaidReactions.showAnalytics` — уже пример такой замены).
