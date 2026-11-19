from __future__ import annotations

import asyncio
import importlib.util
import os
from pathlib import Path
from types import SimpleNamespace

import pytest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "create_gift_user_session.py"
spec = importlib.util.spec_from_file_location("gift_session_setup", SCRIPT)
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


def test_creates_private_session_without_printing_secret(tmp_path, monkeypatch, capsys):
    class FakeClient:
        def __init__(self, *args):
            self.session = SimpleNamespace(save=lambda: "test-secret")
            self.disconnected = False

        async def start(self):
            pass

        async def get_me(self):
            return SimpleNamespace(bot=False)

        async def disconnect(self):
            self.disconnected = True

    monkeypatch.setattr(setup, "TelegramClient", FakeClient)
    output = tmp_path / "gift.session"
    asyncio.run(setup.create(output, 123, "hash"))
    assert output.read_text() == "test-secret\n"
    assert output.stat().st_mode & 0o777 == 0o600
    assert "test-secret" not in capsys.readouterr().out
    with pytest.raises(FileExistsError):
        asyncio.run(setup.create(output, 123, "hash"))
    assert output.read_text() == "test-secret\n"


def test_session_setup_rejects_bot_and_removes_partial_file(tmp_path, monkeypatch):
    class FakeClient:
        def __init__(self, *args):
            pass

        async def start(self):
            pass

        async def get_me(self):
            return SimpleNamespace(bot=True)

        async def disconnect(self):
            pass

    monkeypatch.setattr(setup, "TelegramClient", FakeClient)
    output = tmp_path / "gift.session"
    with pytest.raises(RuntimeError):
        asyncio.run(setup.create(output, 123, "hash"))
    assert not output.exists()


def test_pydroid_session_file_and_cleanup(tmp_path, monkeypatch):
    script = SCRIPT.with_name("pydroid3_gift_session.py")
    spec = importlib.util.spec_from_file_location("pydroid_gift_session", script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    class FakeClient:
        bot = False
        disconnected = False

        def __init__(self, *args):
            self.session = SimpleNamespace(save=lambda: "session-secret")

        async def start(self):
            pass

        async def get_me(self):
            return SimpleNamespace(bot=self.bot)

        async def disconnect(self):
            type(self).disconnected = True

    monkeypatch.setattr(module, "TelegramClient", FakeClient)
    output = tmp_path / "waysgram-gift.session"
    asyncio.run(module.create_session(123, "hash", output))
    assert output.read_text() == "session-secret\n"
    assert FakeClient.disconnected
    with pytest.raises(FileExistsError):
        asyncio.run(module.create_session(123, "hash", output))
    assert output.read_text() == "session-secret\n"

    FakeClient.bot = True
    rejected = tmp_path / "bot.session"
    with pytest.raises(ValueError):
        asyncio.run(module.create_session(123, "hash", rejected))
    assert not rejected.exists()
