"""Slot-reuse regression using production preference methods without Android."""
import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest


JAVA = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/java"
SOURCE = JAVA / "tw/nekomimi/nekogram/helpers/PasscodeHelper.java"


def method(source, name):
    match = re.search(rf"public static \w+ {name}\([^)]*\)\s*\{{", source)
    assert match, f"Production method {name} not found"
    depth = 1
    end = match.end()
    while depth and end < len(source):
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    assert depth == 0, f"Unterminated production method {name}"
    return source[match.start():end]


@pytest.fixture(scope="module")
def passcode_harness(tmp_path_factory):
    home = os.environ.get("JAVA_HOME")
    suffix = ".exe" if os.name == "nt" else ""
    if home:
        tools = [Path(home) / "bin" / (name + suffix) for name in ("javac", "java")]
        assert all(tool.is_file() for tool in tools), f"Invalid JAVA_HOME: {home}"
    else:
        tools = [shutil.which(name) for name in ("javac", "java")]
        if not all(tools):
            pytest.skip("JDK required: set JAVA_HOME or add javac/java to PATH")
    source = SOURCE.read_text(encoding="utf-8")
    methods = "\n".join(method(source, name) for name in (
        "removePasscodeForAccount", "isAccountAllowPanic",
        "hasPasscodeForAccount", "isAccountHidden",
    ))
    harness = """
import java.util.HashMap;
import java.util.Map;

class AccountPasscodeTest {
    static final Preferences preferences = new Preferences();

    static class Preferences {
        final Map<String, Object> values = new HashMap<>();
        boolean contains(String key) { return values.containsKey(key); }
        boolean getBoolean(String key, boolean fallback) {
            return (Boolean) values.getOrDefault(key, fallback);
        }
        Editor edit() { return new Editor(); }
        class Editor {
            final Map<String, Object> pending = new HashMap<>(values);
            Editor remove(String key) { pending.remove(key); return this; }
            void apply() { values.clear(); values.putAll(pending); }
        }
    }

    // PRODUCTION_METHODS

    public static void main(String[] args) {
        int account = Integer.parseInt(args[0]);
        String[] fields = {"passcodeHash", "passcodeSalt", "hide", "allowPanic"};
        for (int slot : new int[] {0, 1, 10, 99, Integer.MAX_VALUE}) {
            preferences.values.put("passcodeHash" + slot, "hash");
            preferences.values.put("passcodeSalt" + slot, "salt");
            preferences.values.put("hide" + slot, true);
            preferences.values.put("allowPanic" + slot, false);
        }
        preferences.values.put("hideSettings", true);
        preferences.values.put("settingsHash", "settings-key");
        Map<String, Object> expected = new HashMap<>(preferences.values);
        for (String field : fields) expected.remove(field + account);

        if (isAccountAllowPanic(account) || !isAccountHidden(account))
            throw new AssertionError("Fixture must start hidden and panic-exempt");
        removePasscodeForAccount(account);
        if (!isAccountAllowPanic(account))
            throw new AssertionError("Reused slot inherited the previous identity's panic exemption");
        if (hasPasscodeForAccount(account) || isAccountHidden(account))
            throw new AssertionError("Account protection was not removed");
        if (!preferences.values.equals(expected))
            throw new AssertionError("Cleanup changed another slot or global settings");
        removePasscodeForAccount(account);
        if (!preferences.values.equals(expected))
            throw new AssertionError("Cleanup must be idempotent");
    }
}
""".replace("// PRODUCTION_METHODS", methods)
    output = tmp_path_factory.mktemp("account-passcode")
    path = output / "AccountPasscodeTest.java"
    path.write_text(harness, encoding="utf-8")
    subprocess.run(
        [str(tools[0]), "-encoding", "UTF-8", "-d", str(output), str(path)],
        check=True, capture_output=True, text=True, timeout=30,
    )
    return str(tools[1]), str(output)


@pytest.mark.parametrize("account", [0, 1, 10, 99])
def test_reused_slot_resets_panic_exemption_without_touching_other_accounts(passcode_harness, account):
    java, output = passcode_harness
    result = subprocess.run(
        [java, "-cp", output, "AccountPasscodeTest", str(account)],
        capture_output=True, text=True, timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def test_login_clears_slot_protection_before_assigning_new_identity():
    source = (JAVA / "org/telegram/ui/LoginActivity.java").read_text(encoding="utf-8")
    start = source.index("private void onAuthSuccess(TLRPC.TL_auth_authorization res, boolean afterSignup)")
    reset = source.index("PasscodeHelper.removePasscodeForAccount(currentAccount)", start)
    identity = source.index("setCurrentUser(res.user)", start)
    assert reset < identity


def test_passcode_account_scans_use_full_capacity():
    source = SOURCE.read_text(encoding="utf-8")
    for name, count in (("checkPasscode", 2), ("switchToVisibleAccount", 1)):
        loops = re.findall(r"for\s*\(([^)]*)\)", method(source, name))
        assert len(loops) == count
        assert all(re.search(r"a\s*<\s*UserConfig\.MAX_ACCOUNT_COUNT\b", loop) for loop in loops)
