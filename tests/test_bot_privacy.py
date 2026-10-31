"""Run the actual routing policy without requiring Android or a Telegram login."""
from pathlib import Path
import shutil
import subprocess
import pytest

ROOT = Path(__file__).resolve().parents[1]

@pytest.mark.parametrize("name", ["BotAccountPolicy", "BotStartDeduplicator"])
def test_bot_account_policy(tmp_path, name):
    if not shutil.which('javac'):
        pytest.skip('JDK required')
    subprocess.run(['javac', '-d', str(tmp_path),
        str(ROOT / f'TMessagesProj/src/main/java/app/exteraless/privacy/{name}.java'),
        str(ROOT / f'tests/bot_privacy/{name}Test.java')], check=True)
    subprocess.run(['java', '-cp', str(tmp_path),
        f'app.exteraless.privacy.{name}Test'], check=True)


@pytest.mark.parametrize('dialog,message,expected', [
    (None, None, False),  # Cached/resolved user only.
    ((0, 0, 0, 0), None, False),  # Empty dialog created by opening a chat.
    ((42, 0, 0, 0), None, True),
    ((42, 0, 0, 1), None, True),  # Archived history is still established.
    ((0, 42, 0, 1), None, True),  # Cleared history with retained read state.
    ((0, 0, 42, 0), None, True),
    (None, -1, True),  # Pending first outgoing message.
    (None, 42, True),
])
def test_local_conversation_evidence(dialog, message, expected):
    import re
    import sqlite3
    source = (ROOT / 'TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java').read_text()
    query = re.search(r'database.queryFinalized\("(SELECT 1 FROM dialogs[^"\n]+)"', source).group(1)
    with sqlite3.connect(':memory:') as db:
        db.execute('CREATE TABLE dialogs(did INTEGER, last_mid INTEGER, inbox_max INTEGER, outbox_max INTEGER, folder_id INTEGER)')
        db.execute('CREATE TABLE messages_v2(uid INTEGER, mid INTEGER)')
        # Another bot never counts as contact with this bot.
        db.execute('INSERT INTO dialogs VALUES (999, 10, 10, 10, 1)')
        if dialog is not None:
            db.execute('INSERT INTO dialogs VALUES (123, ?, ?, ?, ?)', dialog)
        if message is not None:
            db.execute('INSERT INTO messages_v2 VALUES (123, ?)', (message,))
        assert (db.execute(query, (123, 123)).fetchone() is not None) == expected
