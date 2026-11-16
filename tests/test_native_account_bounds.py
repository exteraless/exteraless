"""Execute the production JNI account boundary without Android or a JVM.

Set CXX to a g++/clang++ executable, or put either compiler on PATH. The compiler's
directory is added to child PATH on Windows for the MSYS runtime DLLs. Allocation
itself is covered by test_account_limits.py; this harness observes JNI dispatch.
"""
import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
WRAPPER = ROOT / "TMessagesProj/jni/TgNetWrapper.cpp"
DEFINES = ROOT / "TMessagesProj/jni/tgnet/Defines.h"
POLICY = ROOT / "TMessagesProj/src/main/java/app/exteraless/accounts/AccountLimits.java"
HARNESS = ROOT / "tests/account_limits/NativeJniBoundsTest.cpp"


def read(path):
    return path.read_text(encoding="utf-8")


def mask_comments_and_strings(source):
    return re.sub(
        r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
        lambda match: "".join("\n" if c == "\n" else " " for c in match[0]),
        source,
        flags=re.DOTALL,
    )


def functions(source):
    """Extract complete definitions, preserving production text and nested lambdas."""
    masked = mask_comments_and_strings(source)
    declarations = re.finditer(
        r"(?m)^[ \t]*(?:static\s+)?(?:void|bool|jint|jlong|jboolean)\s+"
        r"(?P<name>\w+)\s*\((?P<parameters>[^;{}]*)\)\s*\{",
        masked,
    )
    for declaration in declarations:
        opening = declaration.end() - 1
        depth = 0
        for end in range(opening, len(masked)):
            if masked[end] == "{":
                depth += 1
            elif masked[end] == "}":
                depth -= 1
                if depth == 0:
                    yield (
                        declaration["name"], declaration["parameters"],
                        source[declaration.start():end + 1], masked[opening + 1:end],
                    )
                    break
        else:
            raise AssertionError(f"Unterminated production function: {declaration['name']}")


def run(command, *, env):
    result = subprocess.run(
        [str(arg) for arg in command], cwd=ROOT, env=env, capture_output=True,
        text=True, encoding="utf-8", errors="replace", timeout=90,
    )
    assert result.returncode == 0, (
        f"Command failed ({result.returncode}): {' '.join(map(str, command))}\n"
        f"{result.stdout}\n{result.stderr}"
    )
    return result.stdout


@pytest.fixture(scope="module")
def native_jni_harness(tmp_path_factory):
    configured = os.environ.get("CXX")
    compiler = shutil.which(configured) if configured else (
        shutil.which("g++") or shutil.which("clang++")
    )
    if configured:
        assert compiler, f"Configured CXX is unavailable: {configured}"
    if not compiler:
        pytest.skip("C++17 compiler required: set CXX or install g++/clang++")
    env = os.environ.copy()
    if os.name == "nt":
        env["PATH"] = str(Path(compiler).parent) + os.pathsep + env.get("PATH", "")

    capacity = re.search(r"(?m)^\s*#define\s+MAX_ACCOUNT_COUNT\s+(\d+)\b", read(DEFINES))
    java_capacity = re.search(r"\bMAX_LIMIT\s*=\s*(\d+)\s*;", mask_comments_and_strings(read(POLICY)))
    assert capacity, "Native capacity definition not found"
    assert java_capacity, "Java account capacity definition not found"
    source = read(WRAPPER)
    definitions = {name: definition for name, _, definition, _ in functions(source)}
    required = (
        "checkAccountIndex", "getAccountCapacity", "getCurrentPingTime",
        "setLangCode", "setJava", "init",
    )
    assert set(required) <= definitions.keys(), "Production JNI entry point missing"
    harness = read(HARNESS)
    replacements = {
        "// NATIVE_CAPACITY": capacity[0].strip(),
        "// JAVA_CAPACITY": f"constexpr jint JAVA_ACCOUNT_CAPACITY = {java_capacity[1]};",
        "// PRODUCTION_JNI_FUNCTIONS": "\n\n".join(definitions[name] for name in required),
    }
    for marker, replacement in replacements.items():
        assert harness.count(marker) == 1, f"Harness marker missing or duplicated: {marker}"
        harness = harness.replace(marker, replacement)
    output = tmp_path_factory.mktemp("native-jni-bounds")
    generated = output / HARNESS.name
    generated.write_text(harness, encoding="utf-8")
    executable = output / ("native-jni-bounds.exe" if os.name == "nt" else "native-jni-bounds")
    run([compiler, "-std=c++17", "-pthread", "-O0", "-g", "-UNDEBUG", generated, "-o", executable], env=env)
    return executable, env


@pytest.mark.parametrize("scenario", [
    "capacity", "guard-range", "guard-class-lookup-failure", "invalid-wrappers",
    "valid-wrappers", "set-java", "init-repeat", "init-concurrent", "init-concurrent-slots",
])
def test_native_jni_account_boundary(native_jni_harness, scenario):
    executable, env = native_jni_harness
    # Each process gets fresh production initFlags; no reset/reimplementation of call_once.
    assert run([executable, scenario], env=env).strip() == f"PASS {scenario}"


def test_account_jni_entry_points_guard_before_side_effects():
    guarded = []
    for name, parameters, _, body in functions(read(WRAPPER)):
        if name == "checkAccountIndex" or not re.search(r"\bJNIEnv\s*\*\s*env\b", parameters):
            continue
        if not re.search(r"\bjint\s+instanceNum\b", parameters):
            continue
        guarded.append(name)
        assert re.match(
            r"\s*if\s*\(\s*!\s*checkAccountIndex\s*\(\s*env\s*,\s*instanceNum\s*\)\s*\)"
            r"\s*(?:\{\s*)?return(?:\s+(?:0|false|nullptr))?\s*;",
            body,
        ), f"{name} must reject invalid accounts before JNI/native side effects"
    assert {"init", "sendRequest", "checkProxy", "getCurrentPingTime", "setLangCode"} <= set(guarded)


def test_native_capacity_query_is_registered():
    assert re.search(
        r'\{\s*"native_getAccountCapacity"\s*,\s*"\(\)I"\s*,\s*'
        r'\(\s*void\s*\*\s*\)\s*getAccountCapacity\s*\}',
        read(WRAPPER),
    ), "The Java capacity handshake must be registered as native_getAccountCapacity()I"
