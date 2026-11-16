"""Execute account-limit policy and production allocation code without Android.

Java/C++ harnesses compile production sources or verbatim source blocks. The
source checks below are complementary integration checks, not a policy model.
"""
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "TMessagesProj/src/main/java"
NATIVE = ROOT / "TMessagesProj/jni/tgnet"
HARNESSES = ROOT / "tests/account_limits"
USER_CONFIG = JAVA / "org/telegram/messenger/UserConfig.java"


def read(path):
    return path.read_text(encoding="utf-8")


def mask_comments_and_strings(source):
    """Preserve offsets/braces while ignoring literals and comments."""
    return re.sub(
        r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
        lambda match: "".join("\n" if c == "\n" else " " for c in match[0]),
        source,
        flags=re.DOTALL,
    )


def braced_block(source, opening):
    masked = mask_comments_and_strings(source)
    assert masked[opening] == "{"
    depth = 0
    for end in range(opening, len(masked)):
        if masked[end] == "{":
            depth += 1
        elif masked[end] == "}":
            depth -= 1
            if depth == 0:
                return source[opening:end + 1]
    raise AssertionError("Unterminated production block")


def method(source, name):
    declaration = re.search(
        rf"(?m)^\s*(?:public|protected|private)\s+"
        rf"(?:static\s+)?(?:synchronized\s+)?[\w<>\[\].]+\s+"
        rf"{re.escape(name)}\s*\([^;{{}}]*\)\s*\{{",
        mask_comments_and_strings(source),
    )
    assert declaration, f"Production method {name} not found"
    opening = declaration.end() - 1
    return source[declaration.start():opening] + braced_block(source, opening)


def run(command, *, cwd=ROOT):
    result = subprocess.run(
        [str(arg) for arg in command], cwd=cwd, capture_output=True,
        text=True, encoding="utf-8", errors="replace", timeout=90,
    )
    assert result.returncode == 0, (
        f"Command failed ({result.returncode}): {' '.join(map(str, command))}\n"
        f"{result.stdout}\n{result.stderr}"
    )
    return result.stdout


@pytest.fixture(scope="module")
def jdk():
    home = os.environ.get("JAVA_HOME")
    suffix = ".exe" if os.name == "nt" else ""
    if home:
        tools = tuple(Path(home) / "bin" / (name + suffix) for name in ("javac", "java"))
        assert all(tool.is_file() for tool in tools), f"Invalid JAVA_HOME: {home}"
        return tools
    tools = tuple(shutil.which(name) for name in ("javac", "java"))
    if not all(tools):
        pytest.skip("JDK required: set JAVA_HOME or put javac and java on PATH")
    return tools


def compile_java(jdk, output, sources):
    run([jdk[0], "-encoding", "UTF-8", "-d", output, *sources])


def user_config_constants():
    source = read(USER_CONFIG)
    declarations = []
    for name in ("MAX_ACCOUNT_DEFAULT_COUNT", "MAX_ACCOUNT_COUNT", "MAX_INSTANCE_COUNT"):
        match = re.search(
            rf"(?m)^\s*public\s+(?:final\s+static|static\s+final)\s+int\s+{name}\s*=[^;]+;",
            source,
        )
        assert match, f"{name} must remain an immutable Java constant"
        declarations.append(match[0])
    return "\n".join(declarations)


def policy_sources():
    return [JAVA / "app/exteraless/accounts/AccountLimits.java"]


@pytest.fixture(scope="module")
def policy_classes(jdk, tmp_path_factory):
    output = tmp_path_factory.mktemp("account-policy")
    compile_java(jdk, output, [*policy_sources(), HARNESSES / "AccountLimitsTest.java"])
    return output


@pytest.mark.parametrize("scenario", ["preferences", "allocation", "reduction", "matrix"])
def test_account_admission_policy(jdk, policy_classes, scenario):
    run([jdk[1], "-cp", policy_classes, "AccountLimitsTest", scenario])


@pytest.fixture(scope="module")
def config_classes(jdk, tmp_path_factory):
    output = tmp_path_factory.mktemp("account-config")
    production = read(USER_CONFIG)
    harness = read(HARNESSES / "UserConfigLimitsTest.java")
    harness = harness.replace("// USER_CONFIG_CONSTANTS", user_config_constants())
    for name in ("accountLimitSync", "pendingMaxAccountCount"):
        field = re.search(rf"(?m)^\s*private\s+static\s+[^;\n]*\b{name}\b[^;]*;", production)
        assert field, f"Preference state field {name} not found"
        harness = harness.replace(f"// {name}", field[0])
    names = (
        "getMaxAccountCount", "setMaxAccountCount", "getAvailableAccountCount",
        "findFreeAccount", "getActivatedAccountsCount", "isValidAccount", "getLoggedInAccountIds",
    )
    harness = harness.replace("// USER_CONFIG_METHODS", "\n".join(method(production, name) for name in names))
    source = output / "UserConfigLimitsTest.java"
    source.write_text(harness, encoding="utf-8")
    settings = read(JAVA / "app/exteraless/settings/OpenExteraGeneralActivity.java")
    settings_source = output / "SettingsAccountLimitTest.java"
    settings_source.write_text(
        read(HARNESSES / "SettingsAccountLimitTest.java").replace(
            "// SETTINGS_DIALOG", method(settings, "showMaxAccountCountDialog")
        ), encoding="utf-8",
    )
    compile_java(jdk, output, [*policy_sources(), source, settings_source])
    return output


@pytest.mark.parametrize("scenario", ["preferences", "early-context", "premium", "sessions"])
def test_user_config_limit_integration(jdk, config_classes, scenario):
    run([jdk[1], "-cp", config_classes, "UserConfigLimitsTest", scenario])


def test_settings_picker_applies_supported_limits(jdk, config_classes):
    run([jdk[1], "-cp", config_classes, "SettingsAccountLimitTest"])


def test_java_account_tables_address_slot_99_lazily(jdk, tmp_path):
    harness = read(HARNESSES / "AccountSlotsTest.java")
    harness = harness.replace("// USER_CONFIG_CONSTANTS", user_config_constants())
    for name in ("UserConfig", "AccountInstance", "NotificationCenter"):
        source = read(JAVA / f"org/telegram/messenger/{name}.java")
        field = re.search(
            rf"private\s+static\s+(?:volatile\s+|final\s+)?{name}\[\]\s+Instance\s*=[^;]+;",
            source,
        )
        assert field, f"{name} singleton table not found"
        harness = harness.replace(f"// {name.upper()}_TABLE", field[0])
        harness = harness.replace(f"// {name.upper()}_GET_INSTANCE", method(source, "getInstance"))
    source = tmp_path / "AccountSlotsTest.java"
    source.write_text(harness, encoding="utf-8")
    compile_java(jdk, tmp_path, [*policy_sources(), source])
    run([jdk[1], "-cp", tmp_path, "AccountSlotsTest"])


def test_native_account_slots_are_distinct_and_lazy(tmp_path):
    compiler = os.environ.get("CXX")
    if compiler:
        assert shutil.which(compiler), f"Configured CXX is unavailable: {compiler}"
    else:
        compiler = shutil.which("g++") or shutil.which("clang++")
    if not compiler:
        pytest.skip("C++ compiler required: set CXX or install g++/clang++")
    production = read(NATIVE / "ConnectionsManager.cpp")
    signature = re.search(
        r"ConnectionsManager\s*&\s*ConnectionsManager::getInstance\s*\(int32_t\s+instanceNum\)\s*\{",
        production,
    )
    assert signature, "Native account allocator not found"
    opening = signature.end() - 1
    allocator = production[signature.start():opening] + braced_block(production, opening)
    harness = read(HARNESSES / "NativeAccountSlotsTest.cpp")
    source = tmp_path / "NativeAccountSlotsTest.cpp"
    source.write_text(harness.replace("// NATIVE_ALLOCATOR", allocator), encoding="utf-8")
    executable = tmp_path / ("native-account-slots.exe" if os.name == "nt" else "native-account-slots")
    run([compiler, "-std=c++17", "-pthread", "-I", NATIVE, source, "-o", executable])
    run([executable])


def test_native_jni_table_uses_the_same_capacity():
    header = read(NATIVE / "Defines.h")
    capacity = re.search(r"(?m)^\s*#define\s+MAX_ACCOUNT_COUNT\s+(\d+)\b", header)
    assert capacity and int(capacity[1]) == 100, "Java and tgnet must both support 100 slots"
    for path in (NATIVE / "ConnectionsManager.h", NATIVE / "ConnectionsManager.cpp"):
        assert re.search(r"jniEnv\s*\[\s*MAX_ACCOUNT_COUNT\s*\]", read(path)), path


def test_native_capacity_query_has_matching_java_and_jni_signatures():
    java = read(JAVA / "org/telegram/tgnet/ConnectionsManager.java")
    wrapper = read(ROOT / "TMessagesProj/jni/TgNetWrapper.cpp")
    assert re.search(r"static\s+native\s+int\s+native_getAccountCapacity\s*\(\s*\)\s*;", java)
    registration = re.search(r'\{"native_getAccountCapacity",\s*"\(\)I",\s*\(void\s*\*\)\s*(\w+)\s*\}', wrapper)
    assert registration, "Capacity query must be registered as a no-argument JNI int method"
    function = re.search(rf"jint\s+{registration[1]}\s*\([^)]*\)\s*\{{", wrapper)
    assert function, "Registered capacity query implementation not found"
    body = braced_block(wrapper, function.end() - 1)
    assert re.search(r"return\s+MAX_ACCOUNT_COUNT\s*;", body), "Capacity query must report the actual native table size"


def test_settings_row_reads_and_writes_the_account_policy():
    source = read(JAVA / "app/exteraless/settings/OpenExteraGeneralActivity.java")
    row = re.search(r'(\w+Row)\s*=\s*addRow\("maxAccountCount",\s*"(\w+)"\)', source)
    assert row, "Account limit must be an addressable/searchable settings row"
    dialog = method(source, "showMaxAccountCountDialog")
    assert "UserConfig.getMaxAccountCount()" in dialog
    assert "UserConfig.setMaxAccountCount(" in dialog
    assert re.search(rf"if\s*\(position\s*==\s*{row[1]}\)\s*\{{\s*showMaxAccountCountDialog\(\)", source)
    assert re.search(rf"notifyItemChanged\(\s*{row[1]}\s*\)", dialog)
    binding = source[source.index("class ListAdapter"):]
    assert re.search(rf"position\s*==\s*{row[1]}.*?UserConfig\.getMaxAccountCount\(\)", binding, re.DOTALL)


@pytest.mark.parametrize("locale", ["values", "values-ru-rRU"])
def test_account_setting_resources_are_localized(locale):
    source = read(JAVA / "app/exteraless/settings/OpenExteraGeneralActivity.java")
    dialog = method(source, "showMaxAccountCountDialog")
    keys = set(re.findall(r"R\.string\.(OE\w+)", dialog))
    assert len(keys) >= 2, "Account limit needs a title and explanation"
    resources = ET.parse(ROOT / f"TMessagesProj/src/main/res/{locale}/strings_oe_general.xml").getroot()
    for key in keys:
        matches = resources.findall(f"string[@name='{key}']")
        assert len(matches) == 1, f"{locale}: missing/duplicate {key}"
        assert "".join(matches[0].itertext()).strip(), f"{locale}: empty {key}"
    info = "".join(resources.find("string[@name='OEGeneralMaxAccountCountInfo']").itertext())
    for value in ("3", "10", "100"):
        assert re.search(rf"\b{value}\b", info), f"{locale}: explanation has stale bounds/default"


@pytest.mark.parametrize("relative", [
    "app/exteraless/drawer/DrawerAccountPickerView.java",
    "org/telegram/ui/MainTabsActivity.java",
    "org/telegram/ui/UserInfoActivity.java",
    "org/telegram/ui/LogoutActivity.java",
])
def test_add_account_entry_points_use_shared_admission(relative):
    source = read(JAVA / relative)
    assert "UserConfig.getAvailableAccountCount()" in source, f"{relative}: stale add-account visibility"
    allocations = list(re.finditer(r"(?:final\s+)?int\s+(\w+)\s*=\s*UserConfig\.findFreeAccount\(\)\s*;", source))
    assert allocations, f"{relative}: missing shared allocation policy"
    for allocation in allocations:
        name = allocation[1]
        # Follow the actual chosen slot into login, checking its unavailable sentinel.
        tail = source[allocation.end():]
        login = re.search(rf"new\s+LoginActivity\(\s*{name}\s*\)", tail)
        assert login, f"{relative}: allocator result is not used by login"
        action = tail[:login.end()]
        assert re.search(rf"if\s*\(\s*{name}\s*>=\s*0\s*\)", action), f"{relative}: negative slot passed to login"
        assert "hasPremiumOnAccounts" not in action, f"{relative}: Premium changes account admission"
    assert "LoginActivity.showAccountLimitReached(" in source, f"{relative}: exhausted slots need a limit explanation"


def account_loops(source):
    loops = []
    masked = mask_comments_and_strings(source)
    for match in re.finditer(r"for\s*\(\s*int\s+a\s*=\s*0\s*;\s*a\s*<\s*UserConfig\.MAX_ACCOUNT_COUNT\s*;\s*a\+\+\s*\)\s*\{", masked):
        opening = match.end() - 1
        loops.append(source[match.start():opening] + braced_block(source, opening))
    return loops


def test_startup_and_network_changes_do_not_start_inactive_accounts(jdk, tmp_path):
    source = method(read(JAVA / "org/telegram/messenger/ApplicationLoader.java"), "postInitApplication")
    loops = account_loops(source)
    startup = [loop for loop in loops if ".loadConfig()" in loop or ".putUser(" in loop or ".checkAppAccount()" in loop]
    network = [loop for loop in loops if ".checkConnection()" in loop]
    assert len(startup) == 3 and len(network) == 1, "Startup loop locations changed; update extraction to keep executing them"
    harness = read(HARNESSES / "AccountStartupTest.java")
    harness = harness.replace("// STARTUP_LOOPS", "\n".join(startup))
    harness = harness.replace("// NETWORK_LOOP", network[0])
    java = tmp_path / "AccountStartupTest.java"
    java.write_text(harness, encoding="utf-8")
    compile_java(jdk, tmp_path, [java])
    run([jdk[1], "-cp", tmp_path, "AccountStartupTest"])
