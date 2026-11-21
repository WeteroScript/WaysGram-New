import asyncio
import time
from pathlib import Path
from types import SimpleNamespace

import pytest
from cryptography.fernet import Fernet

import bot as bot_module
import db_split
import messenger_api
from messenger_api import init_messenger_storage

ADMIN = 424242


def run(coro):
    return asyncio.run(coro)


class Msg:
    def __init__(self, text=None, user_id=ADMIN):
        self.text = text
        self.from_user = SimpleNamespace(id=user_id, username=None)
        self.chat = SimpleNamespace(id=user_id, type="private")
        self.sent: list[tuple[str, object]] = []

    async def answer(self, text, reply_markup=None):
        self.sent.append((text, reply_markup))


class Cb:
    def __init__(self, data, message):
        self.data = data
        self.message = message
        self.from_user = SimpleNamespace(id=ADMIN)

    async def answer(self, text=None, show_alert=False):
        pass


@pytest.fixture
def panel(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    path = tmp_path / "waysgram.sqlite3"
    monkeypatch.setattr(bot_module, "DATABASE_PATH", path)
    monkeypatch.setattr(bot_module, "ADMIN_ID", ADMIN)
    store = bot_module.Store(path)
    store.configure_otp_encryption(Fernet.generate_key().decode("ascii"))
    monkeypatch.setattr(bot_module, "store", store)
    run(store.init())
    run(bot_module.init_admin_storage())
    run(init_messenger_storage(path))
    bot_module.ADMIN_INPUT.clear()
    yield path, store
    bot_module.ADMIN_INPUT.clear()


def press(data: str) -> Msg:
    message = Msg()
    run(bot_module.admin_callback(Cb(data, message)))
    return message


def type_text(text: str) -> Msg:
    message = Msg(text)
    run(bot_module.admin_text_input(message))
    return message


def query(path: Path, sql: str, args=()):
    conn = db_split.sqlite_connect(path)
    try:
        return conn.execute(sql, args).fetchall()
    finally:
        conn.close()


def test_admin_creates_account_with_number_stars_and_grams(panel) -> None:
    path, store = panel
    press("wgadm:newacct")
    assert "номер" in type_text("not a number").sent[-1][0]
    assert "Stars" in type_text("555 123 4567").sent[-1][0]
    assert "Gram" in type_text("700").sent[-1][0]
    done = type_text("12")
    assert "Аккаунт создан" in done.sent[0][0]

    rows = query(path, "SELECT telegram_id,virtual_number,stars,grams FROM accounts WHERE virtual_number='+15551234567'")
    assert len(rows) == 1 and rows[0][2:] == (700, 12)
    account_id = rows[0][0]
    assert account_id > 9_000_000_000_000
    assert query(path, "SELECT COUNT(*) FROM economy_transactions WHERE telegram_id=?", (account_id,))[0][0] == 2
    # Login codes for the new number are delivered to the administrator's chat.
    owner = run(store.resolve_number("+15551234567"))
    assert owner is not None and owner["chat_id"] == ADMIN and owner["verified"] == 1

    # The same number cannot be used twice.
    press("wgadm:newacct")
    assert "уже занят" in type_text("+1 555 123 4567").sent[-1][0]
    # A second account gets its own id.
    press("wgadm:newacct")
    type_text("5559990000")
    type_text("0")
    type_text("0")
    ids = {r[0] for r in query(path, "SELECT telegram_id FROM accounts")}
    assert len(ids) == 2


def test_account_range_is_validated(panel) -> None:
    path, store = panel
    press("wgadm:newacct")
    type_text("5551110000")
    assert "диапазон" in type_text("-5").sent[-1][0].lower()
    assert "целое число" in type_text("abc").sent[-1][0]
    assert query(path, "SELECT COUNT(*) FROM accounts")[0][0] == 0


def test_admin_creates_username_channel_for_an_account(panel) -> None:
    path, store = panel
    run(store.upsert_user(77, 77))
    run(bot_module.ensure_account(77, "+15550007777", "someone", "Some One"))

    press("wgadm:newuname")
    assert "от 2 до 32" in type_text("1bad").sent[-1][0]
    assert "от 2 до 32" in type_text("a").sent[-1][0]
    assert "от 2 до 32" in type_text("x" * 33).sent[-1][0]
    assert "Telegram ID" in type_text("Ab").sent[-1][0]
    assert "не найден" in type_text("999999").sent[-1][0]
    done = type_text("77")
    assert "создан канал @ab" in done.sent[0][0]
    rows = query(path, "SELECT username,title,owner_telegram_id FROM channels")
    assert rows == [("ab", "ab", 77)]

    # By phone number, and the long end of the length range.
    press("wgadm:newuname")
    long_name = "n" + "x" * 31
    assert len(long_name) == 32
    type_text(long_name)
    type_text("+1 555 000 7777")
    assert query(path, "SELECT owner_telegram_id FROM channels WHERE username=?", (long_name,)) == [(77,)]

    # A name that is already used by a channel or an account is refused.
    press("wgadm:newuname")
    assert "занят" in type_text("ab").sent[-1][0]
    press("wgadm:newuname")
    assert "занят" in type_text("someone").sent[-1][0]


def test_only_admin_can_use_the_new_functions(panel) -> None:
    path, store = panel
    stranger = Msg(user_id=1)
    callback = Cb("wgadm:newacct", stranger)
    callback.from_user = SimpleNamespace(id=1)
    run(bot_module.admin_callback(callback))
    assert bot_module.ADMIN_INPUT == {}
    run(bot_module.admin_text_input(Msg("5551234567", user_id=1)))
    assert query(path, "SELECT COUNT(*) FROM accounts")[0][0] == 0


def test_admin_creates_reusable_labels_and_assigns_accounts_and_channels(panel) -> None:
    path, store = panel
    run(store.upsert_user(770801, 770801))
    run(bot_module.ensure_account(770801, "+15550770801", "label_account", "Label Account"))
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        cur = db.execute(
            "INSERT INTO channels(username,title,owner_telegram_id,created_at) VALUES(?,?,?,?)",
            ("label_channel", "Label Channel", 770801, now),
        )
        channel_id = int(cur.lastrowid)
        cur = db.execute(
            """INSERT INTO chat_groups(username,title,owner_telegram_id,created_at)
               VALUES(?,?,?,?)""",
            ("label_group", "Label Group", 770801, now),
        )
        group_id = int(cur.lastrowid)
        db.commit()

    menu = press("wgadm:labels")
    assert "шаблонов" in menu.sent[0][0].lower()
    press("wgadm:labelnew")
    assert "цвет метки" in type_text("Official").sent[0][0].lower()
    created = type_text("#1769AA")
    assert "Шаблон метки сохранён" in created.sent[0][0]
    assert query(path, "SELECT label_text,color FROM waysgram_labels") == [("Official", "#1769AA")]
    label_id = query(path, "SELECT id FROM waysgram_labels")[0][0]

    press(f"wgadm:labelassign:{label_id}")
    press(f"wgadm:labeltarget:account:{label_id}")
    assigned_account = type_text("770801")
    assert "метка выдана аккаунту" in assigned_account.sent[0][0].lower()

    press(f"wgadm:labelassign:{label_id}")
    press(f"wgadm:labeltarget:channel:{label_id}")
    assigned_channel = type_text("@label_channel")
    assert "метка выдана каналу" in assigned_channel.sent[0][0].lower()

    # Groups carry marks too: the client shows them next to the group name.
    who = press(f"wgadm:labelassign:{label_id}")
    assert "Группе" in str(who.sent[-1][1])
    press(f"wgadm:labeltarget:group:{label_id}")
    assigned_group = type_text("@label_group")
    assert "метка выдана группе" in assigned_group.sent[0][0].lower()
    assert "Группа не найден" not in assigned_group.sent[0][0]

    assignments = query(
        path,
        "SELECT target_type,target_id FROM waysgram_label_assignments WHERE label_id=? ORDER BY target_type",
        (label_id,),
    )
    assert assignments == [("account", 770801), ("channel", channel_id), ("group", group_id)]
    assert run(bot_module.admin_assign_label(label_id, "group", group_id)) is False

    # The mark is edited only here: the card lists who got it and can take it back.
    card = press(f"wgadm:label:{label_id}")
    assert "Кому выдана (3)" in str(card.sent[-1][1])
    listed = press(f"wgadm:labeltargets:{label_id}")
    assert "Кому выдана метка" in listed.sent[0][0]
    assert "@label_group" in listed.sent[0][0]
    assert f"wgadm:labelunassign:{label_id}:group:{group_id}" in str(listed.sent[-1][1])

    removed = press(f"wgadm:labelunassign:{label_id}:group:{group_id}")
    assert "Метка снята" in removed.sent[0][0]
    assert query(
        path,
        "SELECT COUNT(*) FROM waysgram_label_assignments WHERE target_type='group'",
    )[0][0] == 0
    assert run(bot_module.admin_unassign_label(label_id, "group", group_id)) is False
    assert "label_unassign" in str(query(path, "SELECT action FROM admin_logs"))

    # Four marks next to one name do not fit: the panel stops at three.
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        for text in ("Second", "Third", "Fourth"):
            cur = db.execute(
                "INSERT INTO waysgram_labels(label_text,color,created_by,created_at) VALUES(?,?,?,?)",
                (text, "#334455", bot_module.ADMIN_ID, now),
            )
            db.execute(
                """INSERT INTO waysgram_label_assignments
                   (label_id,target_type,target_id,assigned_by,assigned_at)
                   VALUES(?,?,?,?,?)""",
                (int(cur.lastrowid), "account", 770801, bot_module.ADMIN_ID, now),
            )
        db.commit()
    press(f"wgadm:labelassign:{label_id}")
    press(f"wgadm:labeltarget:account:{label_id}")
    full = type_text("770801")
    assert "уже 3 метки" in full.sent[0][0]
    # Assigning the same saved template twice is idempotent.
    assert run(bot_module.admin_assign_label(label_id, "account", 770801)) is False


def test_admin_gift_display_name_uses_sticker_set_name() -> None:
    assert bot_module.telegram_gift_display_name("Telegram Gift 123", "PlushPepe", "123") == "Plush Pepe"
    assert bot_module.telegram_gift_display_name("Telegram Gift 123", "plush_pepe", "5936013938331222567") == "Plush Pepe"
    assert bot_module.telegram_gift_display_name("", "GoldenDuck", "456") == "Golden Duck"
    assert bot_module.telegram_gift_display_name("Rare Gift", "SomeSet", "789") == "Rare Gift"
    # A placeholder written by an older catalog sync is not the name of a gift.
    assert bot_module.telegram_gift_display_name("Подарок Telegram", "GoldenDuck", "456") == "Golden Duck"
    assert bot_module.telegram_gift_display_name("Подарок Телеграмм", "", "500123") == "Подарок №500123"
    assert (
        bot_module.telegram_gift_display_name("", "", "500123", "🐸") == "Подарок 🐸 №500123"
    )


def test_admin_edits_telegram_gift_prices_from_the_bot(panel) -> None:
    path, store = panel
    gifts = [
        {"id": "g1", "sticker": {"file_id": "f1", "file_unique_id": "u1", "type": "regular", "width": 512,
                                 "height": 512, "is_animated": False, "is_video": False, "emoji": "🎁",
                                 "mime_type": "image/webp"}, "star_count": 25},
        {"id": "g2", "sticker": {"file_id": "f2", "file_unique_id": "u2", "type": "regular", "width": 512,
                                 "height": 512, "is_animated": False, "is_video": False, "emoji": "🧸",
                                 "mime_type": "image/webp"}, "star_count": 50},
    ]
    run(messenger_api.store_telegram_gifts(path, gifts, int(time.time())))

    listing = press("wgadm:tggifts")
    assert "всего 2" in listing.sent[0][0]
    assert "⛔" in str(listing.sent[0][1])  # nothing is for sale yet

    press("wgadm:tggift:g1")
    assert "введите цену" in press("wgadm:tgprice:g1").sent[0][0].lower()
    assert "диапазон" in type_text("-1").sent[-1][0].lower()
    assert "Сохранено" in type_text("30").sent[0][0]
    press("wgadm:tgsupply:g1")
    saved = type_text("4")
    assert "Остаток" in saved.sent[0][0] or "Осталось: 4" in saved.sent[0][0]
    assert query(path, "SELECT price_stars,supply,sold FROM telegram_gift_prices WHERE gift_id='g1'") == [(30, 4, 0)]

    # The listing now puts the gift that is on sale first, and hiding resets both values.
    assert "✅" in press("wgadm:tggifts").sent[0][0] + str(press("wgadm:tggifts").sent[0][1])
    press("wgadm:tghide:g1")
    assert query(path, "SELECT price_stars,supply FROM telegram_gift_prices WHERE gift_id='g1'") == [(0, 0)]


def test_admin_balance_credit_sends_system_message(panel) -> None:
    path, store = panel
    user_id = 770077
    run(bot_module.ensure_system_account())
    run(bot_module.ensure_account(user_id, "+15550770077", "credit_test", "Credit Test"))

    press(f"wgadm:credit:stars:{user_id}")
    completed = type_text("125")
    assert "Начислено 125 STARS" in completed.sent[0][0]

    messages = query(
        path,
        "SELECT body FROM messages WHERE sender_telegram_id=? AND recipient_telegram_id=? ORDER BY id",
        (bot_module.SYSTEM_ACCOUNT_ID, user_id),
    )
    assert len(messages) == 2
    assert "Начислено: +125 Stars" in messages[-1][0]
    assert "Текущий баланс: 5125 Stars" in messages[-1][0]



def test_admin_adds_telegram_gift_by_id(panel, monkeypatch: pytest.MonkeyPatch) -> None:
    path, store = panel

    class FakeMTProtoCatalog:
        def __init__(self):
            self.sync_calls = 0

        async def sync(self, database_path):
            self.sync_calls += 1
            return {"gift_count": 2, "source": "telegram_mtproto"}

    mtproto_catalog = FakeMTProtoCatalog()
    monkeypatch.setattr(bot_module, "mtproto_gift_catalog", mtproto_catalog)
    gifts = [
        {"id": "g1", "sticker": {"file_id": "f1", "file_unique_id": "u1", "type": "regular",
                                 "width": 512, "height": 512, "is_animated": False, "is_video": False,
                                 "emoji": "🎁", "mime_type": "image/webp"}, "star_count": 25},
        {"id": "g2", "sticker": {"file_id": "f2", "file_unique_id": "u2", "type": "regular",
                                 "width": 512, "height": 512, "is_animated": False, "is_video": False,
                                 "emoji": "🧸", "mime_type": "image/webp"}, "star_count": 50},
    ]
    run(messenger_api.store_telegram_gifts(path, gifts, int(time.time())))

    prompt = press("wgadm:tgadd")
    assert "Telegram Gift ID" in prompt.sent[0][0]
    added_unknown = type_text("unknown-id")
    assert "добавлен в управление WaysGram" in added_unknown.sent[0][0]
    assert "ID: unknown-id" in added_unknown.sent[0][0]
    assert "метаданные Telegram не найдены" in added_unknown.sent[0][0]
    assert "Подарок · unknown-id" in added_unknown.sent[0][0]
    assert "Telegram Gift unknown-id" not in added_unknown.sent[0][0]
    assert query(path, "SELECT price_stars,supply FROM telegram_gift_prices WHERE gift_id='unknown-id'") == [(0, 0)]

    press("wgadm:tgadd")
    added = type_text("g2")
    assert "добавлен в управление" in added.sent[0][0]
    assert "ID: g2" in added.sent[0][0]
    assert query(path, "SELECT price_stars,supply FROM telegram_gift_prices WHERE gift_id='g2'") == [(0, 0)]
    assert mtproto_catalog.sync_calls == 2



def test_admin_database_account_controls(panel) -> None:
    path, store = panel
    user_id = 770701
    run(store.upsert_user(user_id, user_id))
    run(store.set_verified(user_id, True))
    number = run(store.allocate_number(user_id))
    run(bot_module.ensure_account(user_id, number, "db_user", "Database User"))
    run(bot_module.ensure_system_account())

    conn = db_split.sqlite_connect(path)
    try:
        conn.execute(
            """INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at)
               VALUES('WG-DBTEST','Тестовый подарок',?,?)""",
            (user_id, int(time.time())),
        )
        conn.execute(
            "INSERT INTO channels(username,title,owner_telegram_id,created_at) VALUES('dbtest','DB test',?,?)",
            (user_id, int(time.time())),
        )
        conn.commit()
    finally:
        conn.close()

    assert "Управление БД" in str(press("wgadm:home").sent[0][1])
    press("wgadm:db")
    users = press("wgadm:dbaccounts")
    assert "Пользователи" in users.sent[0][0]
    assert "@db_user (1)" in str(users.sent[0][1])
    owner = press(f"wgadm:dbowner:{user_id}")
    assert number in str(owner.sent[0][1])
    card = press(f"wgadm:dbaccount:{user_id}")
    assert "Stars" in card.sent[0][0] and "Gram" in card.sent[0][0]

    press(f"wgadm:dbdo:gifts:{user_id}")
    assert query(path, "SELECT COUNT(*) FROM user_gifts WHERE owner_telegram_id=?", (user_id,))[0][0] == 0

    press(f"wgadm:dbdo:stars:{user_id}")
    press(f"wgadm:dbdo:grams:{user_id}")
    assert query(path, "SELECT stars,grams FROM accounts WHERE telegram_id=?", (user_id,)) == [(0, 0)]
    assert query(path, "SELECT COUNT(*) FROM economy_transactions WHERE telegram_id=? AND amount<0", (user_id,))[0][0] == 2

    press(f"wgadm:dbdo:channels:{user_id}")
    assert query(path, "SELECT COUNT(*) FROM channels WHERE owner_telegram_id=?", (user_id,))[0][0] == 0

    press(f"wgadm:dbdo:account:{user_id}")
    assert query(path, "SELECT COUNT(*) FROM accounts WHERE telegram_id=?", (user_id,))[0][0] == 0
    assert query(path, "SELECT COUNT(*) FROM numbers WHERE telegram_id=?", (user_id,))[0][0] == 0


def test_admin_bulk_gift_import_and_hide_preserve_inventory(panel, monkeypatch: pytest.MonkeyPatch) -> None:
    path, store = panel
    class FakeCatalog:
        async def sync(self, database_path):
            return {"gift_count": 2, "source": "telegram_mtproto"}

    monkeypatch.setattr(bot_module, "mtproto_gift_catalog", FakeCatalog())
    conn = db_split.sqlite_connect(path)
    try:
        now = int(time.time())
        conn.execute(
            "INSERT INTO telegram_gift_sync_state(id,last_synced_at,gift_count,source) VALUES(1,?,?,?)",
            (now, 2, "telegram_mtproto"),
        )
        conn.execute(
            """INSERT INTO telegram_mtproto_gift_catalog
               (gift_id,title,stars,limited,sold_out,availability_remains,
                is_available,source_payload,position,synced_at)
               VALUES('limited','Limited gift',25,1,0,4,1,'{}',0,?)""",
            (now,),
        )
        conn.execute(
            """INSERT INTO telegram_mtproto_gift_catalog
               (gift_id,title,stars,limited,sold_out,availability_remains,
                is_available,source_payload,position,synced_at)
               VALUES('unlimited','Unlimited gift',50,0,0,NULL,1,'{}',1,?)""",
            (now,),
        )
        conn.execute(
            """INSERT INTO telegram_gift_prices(gift_id,price_stars,supply,sold,updated_at)
               VALUES('limited',25,7,3,?)""",
            (now,),
        )
        conn.execute(
            """INSERT INTO user_gifts(serial_number,gift_name,telegram_gift_id,owner_telegram_id,acquired_at)
               VALUES('WG-KEEP','Keep me','limited',123,?)""",
            (now,),
        )
        conn.commit()
    finally:
        conn.close()

    prompt = press("wgadm:dbgiftaddall")
    assert "от 1 до 2" in prompt.sent[0][0]
    assert query(path, "SELECT COUNT(*) FROM telegram_gift_prices WHERE gift_id='unlimited'") == [(0,)]
    added = type_text("2")
    assert "Добавлено/обновлено первых подарков: 2" in added.sent[0][0]
    assert query(path, "SELECT price_stars,supply,sold FROM telegram_gift_prices WHERE gift_id='limited'") == [(25, 7, 3)]
    assert query(path, "SELECT price_stars,supply,sold FROM telegram_gift_prices WHERE gift_id='unlimited'") == [
        (50, bot_module.UNLIMITED_GIFT_SUPPLY, 0)
    ]

    press("wgadm:dbgifthideall")
    assert query(path, "SELECT COUNT(*) FROM telegram_gift_prices WHERE price_stars=0 AND supply=0")[0][0] == 2
    assert query(path, "SELECT COUNT(*) FROM user_gifts WHERE serial_number='WG-KEEP'")[0][0] == 1


def press_as(data: str, user_id: int, chat_type: str = "private") -> Msg:
    message = Msg(user_id=user_id)
    message.chat.type = chat_type
    cb = Cb(data, message)
    cb.from_user = SimpleNamespace(id=user_id)
    run(bot_module.admin_callback(cb))
    return message


def type_as(text: str, user_id: int) -> Msg:
    message = Msg(text, user_id=user_id)
    run(bot_module.admin_text_input(message))
    return message


def test_admin_grant_username_confirmation_persistence_and_revoke(panel) -> None:
    path, store = panel
    run(store.upsert_user(77, 77, "Real_Admin"))
    assert "Добавить админа" in str(bot_module.admin_home_keyboard(ADMIN))
    assert "Добавить админа" not in str(bot_module.admin_home_keyboard(77))
    assert not run(bot_module.is_authbot_admin(77))
    press("wgadm:addadmin")
    candidate = type_text("@REAL_ADMIN")
    assert "Telegram ID 77" in candidate.sent[0][0]
    assert not run(bot_module.is_authbot_admin(77))
    press("wgadm:addadminconfirm:77")
    assert query(path, "SELECT telegram_id,added_by FROM authbot_admins") == [(77, ADMIN)]
    assert run(bot_module.is_authbot_admin(77))
    run(bot_module.init_admin_storage())  # startup migration must keep delegated access
    assert run(bot_module.is_authbot_admin(77))

    panel_msg = Msg(user_id=77)
    run(bot_module.admin_handler(panel_msg))
    assert "Панель администратора" in panel_msg.sent[0][0]
    assert "Добавить админа" not in str(panel_msg.sent[0][1])
    assert "Только главный" in press_as("wgadm:addadmin", 77).sent[0][0]
    assert "Только главный" in press_as("wgadm:addadminconfirm:88", 77).sent[0][0]
    assert "Только главный" in press_as(f"wgadm:adminremove:{ADMIN}", 77).sent[0][0]

    # Another admin's input state and audit identity are independent of the owner.
    press("wgadm:labelnew")
    press_as("wgadm:labelnew", 77)
    type_as("Метка второго", 77)
    type_as("#123456", 77)
    assert bot_module.ADMIN_INPUT[ADMIN][0] == "label_text"
    assert query(path, "SELECT admin_id FROM admin_logs WHERE action='label_create'") == [(77,)]

    assert "77" in str(press("wgadm:admins").sent[0][1])
    press("wgadm:adminremoveask:77")
    press("wgadm:adminremove:77")
    assert not run(bot_module.is_authbot_admin(77))
    denied = Msg(user_id=77)
    run(bot_module.admin_handler(denied))
    assert "Доступ запрещён" in denied.sent[0][0]
    assert query(path, "SELECT action,admin_id,target FROM admin_logs WHERE action IN ('admin_add','admin_remove') ORDER BY id") == [
        ("admin_add", ADMIN, "77"), ("admin_remove", ADMIN, "77")]


def test_admin_add_id_validation_cancellation_and_private_scope(panel) -> None:
    path, store = panel
    # The owner must have a current confirmation, and ordinary users cannot forge one.
    press("wgadm:addadminconfirm:100")
    assert not run(bot_module.is_authbot_admin(100))
    press_as("wgadm:addadminconfirm:100", 100)
    assert not run(bot_module.is_authbot_admin(100))
    assert not press_as("wgadm:addadmin", ADMIN, chat_type="group").sent
    press("wgadm:addadmin")
    assert "Некорректный" in type_text("9" * 100).sent[0][0]
    assert "username не найден" in type_text("@nobody_found").sent[0][0].lower()
    type_text("100")
    press("wgadm:admincancel")
    press("wgadm:addadminconfirm:100")
    assert not run(bot_module.is_authbot_admin(100))
    press("wgadm:addadmin")
    type_text("100")
    press("wgadm:addadminconfirm:100")
    assert run(bot_module.is_authbot_admin(100))
    assert query(path, "SELECT COUNT(*) FROM authbot_admins WHERE telegram_id=100") == [(1,)]
    press("wgadm:addadminconfirm:100")
    assert query(path, "SELECT COUNT(*) FROM authbot_admins WHERE telegram_id=100") == [(1,)]
    press(f"wgadm:adminremove:{ADMIN}")
    assert run(bot_module.is_authbot_admin(ADMIN))


def test_duplicate_telegram_usernames_require_explicit_id(panel) -> None:
    path, store = panel
    run(store.upsert_user(77, 77, "same_name"))
    run(store.upsert_user(78, 78, "same_name"))
    press("wgadm:addadmin")
    assert "неоднозначен" in type_text("@same_name").sent[0][0]
    assert query(path, "SELECT COUNT(*) FROM authbot_admins") == [(0,)]


def test_bot_admin_grant_does_not_grant_waysgram_admin_status(panel) -> None:
    from fastapi import FastAPI
    from test_messenger_api import FakeBot, add_session, client_for

    path, store = panel
    run(store.upsert_user(77, 77, "apiadmin"))
    run(store.set_verified(77, True))
    number = run(store.allocate_number(77))
    run(bot_module.ensure_account(77, number, "apiadmin", "API Admin"))
    add_session(path, 77, "admin-session-token")
    app = FastAPI()
    app.include_router(messenger_api.build_messenger_router(
        path, store, lambda: FakeBot(), bot_module.account_is_banned, admin_id=ADMIN,
    ))

    async def get_flag():
        async with await client_for(app) as client:
            response = await client.get("/api/me", headers={"Authorization": "Bearer admin-session-token"})
            assert response.status_code == 200
            return response.json()["is_admin"]

    assert run(get_flag()) is False
    press("wgadm:addadmin")
    type_text("77")
    press("wgadm:addadminconfirm:77")
    assert run(bot_module.is_authbot_admin(77)) is True
    assert run(get_flag()) is False
    press("wgadm:adminremove:77")
    assert run(get_flag()) is False


def test_delegated_admin_created_account_is_owned_and_audited_by_actor(panel) -> None:
    path, store = panel
    press("wgadm:addadmin")
    type_text("88")
    press("wgadm:addadminconfirm:88")
    press_as("wgadm:newacct", 88)
    type_as("5552220000", 88)
    type_as("15", 88)
    type_as("2", 88)
    row = query(path, "SELECT telegram_id,owner_telegram_id FROM numbers WHERE number=?", ("+15552220000",))[0]
    assert row[1] == 88
    assert run(store.resolve_number("+15552220000"))["chat_id"] == 88
    assert query(path, "SELECT admin_id FROM admin_logs WHERE action='create_account'") == [(88,)]
    assert query(path, "SELECT DISTINCT admin_id FROM economy_transactions WHERE reason='admin_create_account'") == [(88,)]


def test_add_admin_confirmation_expires(panel, monkeypatch) -> None:
    path, store = panel
    press("wgadm:addadmin")
    type_text("900")
    monkeypatch.setattr(bot_module.time, "time", lambda: 2_000_000_000)
    result = press("wgadm:addadminconfirm:900")
    assert "устарело" in result.sent[0][0]
    assert not run(bot_module.is_authbot_admin(900))


@pytest.mark.parametrize("source", ["telegram_mtproto", "telegram_bot_api"])
def test_bulk_gifts_import_first_n_in_source_order(panel, monkeypatch, source) -> None:
    path, _ = panel
    # The refresh is mocked; both catalogues exercise the actual selection and writes.
    class FakeCatalog:
        async def sync(self, database_path):
            return {"gift_count": 40, "source": source}

    async def fake_bot_sync(database_path, bot):
        return {"gift_count": 40, "source": source}

    monkeypatch.setattr(bot_module, "mtproto_gift_catalog", FakeCatalog() if source == "telegram_mtproto" else None)
    monkeypatch.setattr(bot_module, "bot", None if source == "telegram_mtproto" else object())
    monkeypatch.setattr(bot_module, "sync_telegram_gifts", fake_bot_sync)
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        db.execute("INSERT INTO telegram_gift_sync_state(id,last_synced_at,gift_count,source) VALUES(1,?,?,?)", (now, 40, source))
        # Gift IDs descend while positions ascend, so ordering by ID would select the wrong items.
        for position in reversed(range(41)):
            gift_id = str(1000 - position)
            active = int(position != 40)
            if source == "telegram_mtproto":
                db.execute("""INSERT INTO telegram_mtproto_gift_catalog
                    (gift_id,title,stars,limited,sold_out,availability_remains,is_available,source_payload,position,synced_at)
                    VALUES(?,'Gift',25,1,0,4,?,'{}',?,?)""", (gift_id, active, position, now))
            else:
                db.execute("""INSERT INTO telegram_gift_catalog
                    (gift_id,title,sticker_file_id,star_count,total_count,remaining_count,is_available,source_payload,position,synced_at)
                    VALUES(?,'Gift','file',25,10,4,?,'{}',?,?)""", (gift_id, active, position, now))
        # An unrelated existing listing must not be hidden or overwritten.
        db.execute("INSERT INTO telegram_gift_prices(gift_id,price_stars,supply,sold,updated_at) VALUES('970',99,20,1,?)", (now,))
        db.commit()

    prompt = press("wgadm:dbgiftaddall")
    assert "от 1 до 40" in prompt.sent[0][0]
    assert query(path, "SELECT COUNT(*) FROM telegram_gift_prices") == [(1,)]
    for invalid in ("0", "-1", "1.5", "abc", "9" * 100):
        assert "Введите" in type_text(invalid).sent[0][0]
    assert "от 1 до 40" in type_text("41").sent[0][0]
    assert query(path, "SELECT COUNT(*) FROM telegram_gift_prices") == [(1,)]

    result = type_text("30")
    assert "подарков: 30" in result.sent[0][0]
    assert {row[0] for row in query(path, "SELECT gift_id FROM telegram_gift_prices WHERE gift_id!='970'")} == {
        str(1000 - i) for i in range(30)
    }
    assert query(path, "SELECT price_stars,supply,sold FROM telegram_gift_prices WHERE gift_id='970'") == [(99, 20, 1)]
    assert query(path, "SELECT COUNT(*) FROM admin_logs WHERE action='db_gifts_add_all'") == [(1,)]
    assert ADMIN not in bot_module.ADMIN_INPUT

    # Full range remains available and includes the previously unselected entries.
    press("wgadm:dbgiftaddall")
    assert "подарков: 40" in type_text("40").sent[0][0]
    assert query(path, "SELECT COUNT(*) FROM telegram_gift_prices") == [(40,)]
    assert query(path, "SELECT price_stars,supply,sold FROM telegram_gift_prices WHERE gift_id='970'") == [(25, 5, 1)]


def test_bulk_gift_prompt_cancel_and_catalog_change(panel, monkeypatch) -> None:
    path, _ = panel
    class FakeCatalog:
        async def sync(self, database_path):
            return {"gift_count": 2}
    monkeypatch.setattr(bot_module, "mtproto_gift_catalog", FakeCatalog())
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        db.execute("INSERT INTO telegram_gift_sync_state(id,last_synced_at,gift_count,source) VALUES(1,?,2,'telegram_mtproto')", (now,))
        for pos in range(2):
            db.execute("""INSERT INTO telegram_mtproto_gift_catalog
                (gift_id,title,stars,source_payload,position,synced_at) VALUES(?,'Gift',25,'{}',?,?)""", (str(pos), pos, now))
        db.commit()

    press("wgadm:dbgiftaddall")
    press("wgadm:dbgifts")
    assert ADMIN not in bot_module.ADMIN_INPUT
    type_text("1")
    assert query(path, "SELECT COUNT(*) FROM telegram_gift_prices") == [(0,)]

    press("wgadm:dbgiftaddall")
    with db_split.sqlite_connect(path) as db:
        db.execute("UPDATE telegram_mtproto_gift_catalog SET is_available=0 WHERE gift_id='1'")
        db.commit()
    assert "от 1 до 1" in type_text("2").sent[0][0]
    assert query(path, "SELECT COUNT(*) FROM telegram_gift_prices") == [(0,)]
    assert "подарков: 1" in type_text("1").sent[0][0]
    assert query(path, "SELECT gift_id FROM telegram_gift_prices") == [("0",)]


def test_bulk_gift_input_is_consumed_once_after_concurrent_authentication(panel, monkeypatch) -> None:
    path, _ = panel
    class FakeCatalog:
        async def sync(self, database_path):
            return {"gift_count": 1}
    monkeypatch.setattr(bot_module, "mtproto_gift_catalog", FakeCatalog())
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        db.execute("INSERT INTO telegram_gift_sync_state(id,last_synced_at,gift_count,source) VALUES(1,?,1,'telegram_mtproto')", (now,))
        db.execute("""INSERT INTO telegram_mtproto_gift_catalog
            (gift_id,title,stars,source_payload,position,synced_at) VALUES('one','Gift',25,'{}',0,?)""", (now,))
        db.commit()
    press("wgadm:dbgiftaddall")

    async def simultaneous_messages():
        gate = asyncio.Event()
        count = 0
        async def slow_auth(actor_id):
            nonlocal count
            count += 1
            if count == 2:
                gate.set()
            await gate.wait()
            return True
        monkeypatch.setattr(bot_module, "is_authbot_admin", slow_auth)
        first, second = Msg("1"), Msg("1")
        await asyncio.gather(bot_module.admin_text_input(first), bot_module.admin_text_input(second))
        return first, second

    first, second = run(simultaneous_messages())
    assert len(first.sent) + len(second.sent) == 1
    assert query(path, "SELECT COUNT(*) FROM admin_logs WHERE action='db_gifts_add_all'") == [(1,)]
    assert query(path, "SELECT gift_id FROM telegram_gift_prices") == [("one",)]


def test_bulk_gift_sync_failure_does_not_import_or_wait_for_input(panel, monkeypatch) -> None:
    path, _ = panel
    class FailingCatalog:
        async def sync(self, database_path):
            raise RuntimeError("offline")
    monkeypatch.setattr(bot_module, "mtproto_gift_catalog", FailingCatalog())
    message = press("wgadm:dbgiftaddall")
    assert "Не удалось обновить" in message.sent[0][0]
    assert ADMIN not in bot_module.ADMIN_INPUT
    assert query(path, "SELECT COUNT(*) FROM telegram_gift_prices") == [(0,)]


def test_admin_names_use_known_ids_and_better_secondary_catalog(panel) -> None:
    path, _ = panel
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        db.execute("INSERT INTO telegram_gift_sync_state(id,last_synced_at,gift_count,source) VALUES(1,?,2,'telegram_mtproto')", (now,))
        db.execute("""INSERT INTO telegram_mtproto_gift_catalog
            (gift_id,title,sticker_set_name,source_payload,position,synced_at)
            VALUES('5167939598143193218','s','TelegramGifts','{}',0,?)""", (now,))
        db.execute("""INSERT INTO telegram_mtproto_gift_catalog
            (gift_id,title,sticker_set_name,source_payload,position,synced_at)
            VALUES('98765','Подарок Telegram','','{}',1,?)""", (now,))
        db.execute("""INSERT INTO telegram_gift_catalog
            (gift_id,title,sticker_file_id,source_payload,position,synced_at)
            VALUES('98765','Real Name','file','{}',0,?)""", (now,))
        db.commit()
    rows = {row["id"]: row for row in run(bot_module.telegram_gift_rows())}
    assert rows["5167939598143193218"]["title"] == "Sakura Flower"
    assert rows["98765"]["title"] == "Real Name"
    assert rows["98765"]["catalog_source"] == "telegram_mtproto"
    assert bot_module.telegram_gift_display_name("", "TelegramGifts", "123") == "Подарок №123"
    assert bot_module.telegram_gift_display_name("s", "", "123") == "Подарок №123"


def test_find_waysgram_account_and_grant_telegram_gifts(panel) -> None:
    from fastapi import FastAPI
    from test_messenger_api import FakeBot, add_session, client_for

    path, store = panel
    user_id = 770077
    run(bot_module.ensure_system_account())
    run(bot_module.ensure_account(user_id, "+15550770077", "WaysGram_Name", "Example"))
    gifts = [
        {"id": f"gift{i}", "title": f"Gift {i}",
         "sticker": {"file_id": f"file{i}", "file_unique_id": f"uniq{i}",
                     "type": "regular", "width": 512, "height": 512,
                     "is_animated": False, "is_video": False, "emoji": "🎁", "mime_type": "image/webp"},
         "star_count": 25, "upgrade_star_count": 10}
        for i in range(13)
    ]
    run(messenger_api.store_telegram_gifts(path, gifts, int(time.time())))
    run(bot_module.set_telegram_gift_values("gift0", price=25, supply=2))
    assert "@username" in press("wgadm:accounts").sent[0][0]
    assert "не найден" in type_text("@missing").sent[0][0]
    found = type_text("@wAYSGRAM_nAME")
    assert f"wgadm:user:{user_id}" in str(found.sent[0][1])
    press("wgadm:accounts")
    assert f"wgadm:user:{user_id}" in str(type_text("waysgram_name").sent[0][1])
    press("wgadm:accounts")
    assert f"wgadm:user:{user_id}" in str(type_text("+15550770077").sent[0][1])
    press("wgadm:accounts")
    assert f"wgadm:user:{user_id}" in str(type_text("xamp").sent[0][1])
    press("wgadm:accounts")
    assert f"wgadm:user:{user_id}" in str(type_text(str(user_id)).sent[0][1])
    assert "Выдать подарок" in str(press(f"wgadm:user:{user_id}").sent[0][1])

    page = press(f"wgadm:grant:{user_id}")
    assert "всего 13" in page.sent[0][0] and "не меняет остатки" in page.sent[0][0]
    state = bot_module.ADMIN_INPUT[ADMIN][1]
    token = state["token"]
    assert "Вперёд" in str(page.sent[0][1])
    assert "страница 2" in press(f"wgadm:gpage:{token}:1").sent[0][0]
    chosen = next(i for i, gift in enumerate(state["gifts"]) if gift["id"] == "gift0")
    assert "Введите количество" in press(f"wgadm:gsel:{token}:{chosen}").sent[0][0]
    assert "целое число" in type_text("0").sent[0][0]
    assert "целое число" in type_text("101").sent[0][0]
    assert "Выдано подарков: 3" in type_text("3").sent[0][0]
    assert query(path, "SELECT COUNT(*),COUNT(DISTINCT serial_number) FROM user_gifts WHERE owner_telegram_id=? AND telegram_gift_id='gift0'", (user_id,)) == [(3, 3)]
    assert query(path, "SELECT supply,sold FROM telegram_gift_prices WHERE gift_id='gift0'") == [(2, 0)]
    assert query(path, "SELECT COUNT(*) FROM gift_transactions WHERE event_type='admin_grant' AND to_telegram_id=?", (user_id,)) == [(3,)]
    assert query(path, "SELECT admin_id,action FROM admin_logs WHERE action='gift_grant'") == [(ADMIN, 'gift_grant')]
    assert "× 3" in query(path, "SELECT body FROM messages WHERE recipient_telegram_id=? ORDER BY id DESC LIMIT 1", (user_id,))[0][0]
    assert query(path, "SELECT COUNT(*) FROM message_events WHERE actor_id=? AND user_b=?", (bot_module.SYSTEM_ACCOUNT_ID, max(bot_module.SYSTEM_ACCOUNT_ID, user_id))) == [(1,)]
    assert "устарел" in press(f"wgadm:gsel:{token}:{chosen}").sent[0][0]
    assert not type_text("3").sent

    add_session(path, user_id, "grant-inventory-session")
    app = FastAPI()
    app.include_router(messenger_api.build_messenger_router(path, store, lambda: FakeBot(), bot_module.account_is_banned, admin_id=ADMIN))
    async def inventory():
        async with await client_for(app) as client:
            response = await client.get("/api/me/gifts", headers={"Authorization": "Bearer grant-inventory-session"})
            assert response.status_code == 200
            return response.json()["gifts"]
    received = run(inventory())
    assert len(received) == 3
    assert all(gift["telegram_gift_id"] == "gift0" and gift["telegram_gift"]["sticker_available"] for gift in received)
    assert all(gift["purchase_price"] == 0 for gift in received)


def test_grant_cancellation_stale_and_removed_gift_are_safe(panel) -> None:
    path, _ = panel
    run(bot_module.ensure_system_account())
    run(bot_module.ensure_account(770077, "+15550770077", "target", "Target"))
    run(messenger_api.store_telegram_gifts(path, [{"id": "gift1", "sticker": {"file_id": "f1", "file_unique_id": "u1", "type": "regular", "width": 512, "height": 512}, "star_count": 10}], int(time.time())))
    press("wgadm:grant:770077")
    token = bot_module.ADMIN_INPUT[ADMIN][1]["token"]
    press(f"wgadm:gsel:{token}:0")
    press("wgadm:user:770077")
    assert not type_text("5").sent
    assert query(path, "SELECT COUNT(*) FROM user_gifts WHERE owner_telegram_id=770077") == [(0,)]
    assert "устарел" in press(f"wgadm:gsel:{token}:0").sent[0][0]
    press("wgadm:grant:770077")
    token = bot_module.ADMIN_INPUT[ADMIN][1]["token"]
    press(f"wgadm:gsel:{token}:0")
    with db_split.sqlite_connect(path) as db:
        db.execute("DELETE FROM telegram_gift_catalog WHERE gift_id='gift1'")
    assert "больше не найден" in type_text("1").sent[0][0]
    assert query(path, "SELECT COUNT(*) FROM user_gifts WHERE owner_telegram_id=770077") == [(0,)]
    assert query(path, "SELECT COUNT(*) FROM admin_logs WHERE action='gift_grant'") == [(0,)]


def test_grant_duplicate_inputs_and_failed_batch_are_atomic(panel, monkeypatch) -> None:
    path, _ = panel
    run(bot_module.ensure_system_account())
    run(bot_module.ensure_account(770077, "+15550770077", "target", "Target"))
    run(messenger_api.store_telegram_gifts(path, [{"id": "gift1", "sticker": {"file_id": "f1", "file_unique_id": "u1", "type": "regular", "width": 512, "height": 512}, "star_count": 10}], int(time.time())))
    press("wgadm:grant:770077")
    token = bot_module.ADMIN_INPUT[ADMIN][1]["token"]
    press(f"wgadm:gsel:{token}:0")
    first, second = Msg("3"), Msg("3")
    async def concurrent():
        await asyncio.gather(bot_module.admin_text_input(first), bot_module.admin_text_input(second))
    run(concurrent())
    assert query(path, "SELECT COUNT(*) FROM user_gifts WHERE owner_telegram_id=770077") == [(3,)]
    assert query(path, "SELECT COUNT(*) FROM admin_logs WHERE action='gift_grant'") == [(1,)]

    press("wgadm:grant:770077")
    token = bot_module.ADMIN_INPUT[ADMIN][1]["token"]
    press(f"wgadm:gsel:{token}:0")
    monkeypatch.setattr(bot_module.secrets, "token_hex", lambda n: "a" * (n * 2))
    failed = type_text("2")  # duplicate serial in the same transaction
    assert "Не удалось" in failed.sent[0][0]
    assert query(path, "SELECT COUNT(*) FROM user_gifts WHERE owner_telegram_id=770077") == [(3,)]
    assert query(path, "SELECT COUNT(*) FROM admin_logs WHERE action='gift_grant'") == [(1,)]


def test_grant_buttons_require_admin_and_current_selection(panel) -> None:
    path, _ = panel
    run(bot_module.ensure_account(770077, "+15550770077", "target", "Target"))
    with db_split.sqlite_connect(path) as db:
        db.execute("INSERT INTO gift_catalog(name,price_grams,supply,created_at) VALUES('Legacy',1,3,?)", (int(time.time()),))
        db.execute("INSERT INTO telegram_gift_sync_state(id,last_synced_at,gift_count,source) VALUES(1,?,0,'telegram_bot_api')", (int(time.time()),))
    press("wgadm:grant:770077")
    token = bot_module.ADMIN_INPUT[ADMIN][1]["token"]
    press_as(f"wgadm:gsel:{token}:0", 999)
    assert bot_module.ADMIN_INPUT[ADMIN][0] == "grant_select"
    assert not type_as("3", 999).sent
    assert "устарела" in press("wgadm:grantgift:770077:1").sent[0][0]
    assert query(path, "SELECT COUNT(*) FROM user_gifts WHERE owner_telegram_id=770077") == [(0,)]


@pytest.mark.parametrize("source", ["telegram_mtproto", "telegram_bot_api"])
def test_bulk_import_all_151_are_visible_in_store_including_telegram_sold_out(panel, monkeypatch, source):
    from fastapi import FastAPI
    from test_messenger_api import FakeBot, add_session, client_for

    path, store = panel
    class FakeCatalog:
        async def sync(self, database_path):
            return {"gift_count": 151, "source": source}
    async def fake_bot_sync(database_path, bot):
        return {"gift_count": 151, "source": source}
    monkeypatch.setattr(bot_module, "mtproto_gift_catalog", FakeCatalog() if source == "telegram_mtproto" else None)
    monkeypatch.setattr(bot_module, "bot", None if source == "telegram_mtproto" else object())
    monkeypatch.setattr(bot_module, "sync_telegram_gifts", fake_bot_sync)
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        db.execute("INSERT INTO telegram_gift_sync_state(id,last_synced_at,gift_count,source) VALUES(1,?,151,?)", (now, source))
        for position in range(151):
            gift_id = str(5000 + position)
            available = position < 11
            if source == "telegram_mtproto":
                db.execute("""INSERT INTO telegram_mtproto_gift_catalog
                    (gift_id,title,stars,limited,sold_out,availability_remains,is_available,source_payload,position,synced_at)
                    VALUES(?,?,25,1,?,?,1,'{}',?,?)""", (gift_id, f"Gift {position}", int(not available), 3 if available else 0, position, now))
            else:
                db.execute("""INSERT INTO telegram_gift_catalog
                    (gift_id,title,sticker_file_id,star_count,total_count,remaining_count,is_available,source_payload,position,synced_at)
                    VALUES(?,?, 'file',25,100,?,1,'{}',?,?)""", (gift_id, f"Gift {position}", 3 if available else 0, position, now))
        db.execute("INSERT INTO telegram_gift_prices(gift_id,price_stars,supply,sold,updated_at) VALUES('5011',25,6,6,?)", (now,))
    run(bot_module.ensure_account(770077, "+15550770077", "target", "Target"))
    add_session(path, 770077, "bulk-gift-session")
    assert "до 151" in press("wgadm:dbgiftaddall").sent[0][0]
    assert "подарков: 151" in type_text("151").sent[0][0]
    assert query(path, "SELECT COUNT(*) FROM telegram_gift_prices WHERE price_stars>0 AND supply>sold") == [(151,)]
    assert query(path, "SELECT sold FROM telegram_gift_prices WHERE gift_id='5011'") == [(6,)]

    app = FastAPI()
    app.include_router(messenger_api.build_messenger_router(path, store, lambda: FakeBot(), bot_module.account_is_banned, admin_id=ADMIN))
    async def shop():
        async with await client_for(app) as client:
            headers = {"Authorization": "Bearer bulk-gift-session"}
            response = await client.get("/api/telegram/gifts", headers=headers)
            assert response.status_code == 200, response.text
            gifts = response.json()["gifts"]
            assert len(gifts) == 151
            exhausted = next(g for g in gifts if g["id"] == "5011")
            assert exhausted["for_sale"] and exhausted["unlimited_supply"]
            bought = await client.post("/api/telegram/gifts/5011/purchase", headers=headers, json={})
            assert bought.status_code == 201, bought.text
    run(shop())


def test_bulk_import_missing_price_prompts_before_atomic_write_and_can_cancel(panel, monkeypatch):
    path, _ = panel
    class FakeCatalog:
        async def sync(self, database_path):
            return {"gift_count": 3}
    monkeypatch.setattr(bot_module, "mtproto_gift_catalog", FakeCatalog())
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        db.execute("INSERT INTO telegram_gift_sync_state(id,last_synced_at,gift_count,source) VALUES(1,?,3,'telegram_mtproto')", (now,))
        for pos, price in enumerate((25, 0, 0)):
            db.execute("""INSERT INTO telegram_mtproto_gift_catalog
                (gift_id,title,stars,limited,sold_out,availability_remains,source_payload,position,synced_at)
                VALUES(?,'Gift',?,1,1,0,'{}',?,?)""", (str(pos), price, pos, now))
        db.execute("INSERT INTO telegram_gift_prices(gift_id,price_stars,supply,sold,updated_at) VALUES('2',40,0,2,?)", (now,))
    press("wgadm:dbgiftaddall")
    assert "У 1 подарков нет цены" in type_text("3").sent[0][0]
    assert query(path, "SELECT gift_id,price_stars,supply FROM telegram_gift_prices") == [("2", 40, 0)]
    assert "положительное" in type_text("0").sent[0][0]
    assert "цену от 1" in type_text("1000000001").sent[0][0]
    press("wgadm:dbgifts")
    assert ADMIN not in bot_module.ADMIN_INPUT
    assert not type_text("50").sent
    assert query(path, "SELECT COUNT(*) FROM telegram_gift_prices") == [(1,)]

    press("wgadm:dbgiftaddall")
    type_text("3")
    assert "подарков: 3" in type_text("50").sent[0][0]
    assert query(path, "SELECT gift_id,price_stars FROM telegram_gift_prices ORDER BY gift_id") == [("0", 25), ("1", 50), ("2", 40)]
    assert query(path, "SELECT COUNT(*) FROM telegram_gift_prices WHERE supply>sold") == [(3,)]


def test_admin_grants_one_nft_with_selected_traits(panel):
    import json

    path, _ = panel
    uid = 770078
    gift_id = '5936013938331222567'
    run(bot_module.ensure_system_account())
    run(bot_module.ensure_account(uid, '+15550770078', 'nft_recipient', 'NFT recipient'))
    run(messenger_api.store_telegram_gifts(path, [{
        'id': gift_id, 'title': 'Plush Pepe', 'star_count': 100,
        'upgrade_star_count': 25, 'sticker': {'file_id': 'nft-file', 'file_unique_id': 'nft-id'},
    }], int(time.time())))
    run(bot_module.set_telegram_gift_values(gift_id, price=100, supply=2))
    assert 'Выдать NFT-подарок' in str(press(f'wgadm:user:{uid}').sent[0][1])
    press(f'wgadm:nftgrant:{uid}')
    state = bot_module.ADMIN_INPUT[ADMIN][1]
    token = state['token']
    index = next(i for i, g in enumerate(state['gifts']) if g['id'] == gift_id)
    models = press(f'wgadm:nft:{token}:gift:s{index}')
    assert '%' in str(models.sent[0][1])
    for slot in bot_module.NFT_SLOTS:
        response = press(f'wgadm:nft:{token}:{slot}:s0')
    assert 'Фон + узор' in response.sent[0][0]
    assert 'выдан' in press(f'wgadm:nft:{token}:confirm:go').sent[0][0]
    assert 'устарел' in press(f'wgadm:nft:{token}:confirm:go').sent[0][0]
    serial, level, raw, paid = query(path, 'SELECT serial_number,upgrade_level,attributes_json,purchase_price FROM user_gifts WHERE owner_telegram_id=?', (uid,))[0]
    assert (level, paid) == (1, 0)
    traits = json.loads(raw)['collectible_traits']
    assert all(traits[slot] == state['selected'][slot] for slot in bot_module.NFT_SLOTS)
    assert query(path, 'SELECT unique_num FROM gift_unique_numbers WHERE serial_number=?', (serial,)) == [(1,)]
    assert query(path, 'SELECT supply,sold FROM telegram_gift_prices WHERE gift_id=?', (gift_id,)) == [(2, 0)]
    assert query(path, "SELECT COUNT(*) FROM admin_logs WHERE action='nft_gift_grant'") == [(1,)]
