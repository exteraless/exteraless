import importlib.util
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import corpus

PYTHON_ROOT = os.environ.get("EXTERALESS_PYTHON_ROOT", corpus.PYTHON_ROOT)
SCANNER = os.path.join(PYTHON_ROOT, "extera_utils", "capability_scan.py")


@pytest.fixture(scope="module")
def scanner():
    if not os.path.isfile(SCANNER):
        pytest.skip(f"missing {SCANNER}")
    spec = importlib.util.spec_from_file_location("exteraless_capability_scan", SCANNER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _scan(scanner, tmp_path, source):
    path = tmp_path / "sample.plugin"
    path.write_text(source, encoding="utf-8")
    return scanner.scan(str(path))


def test_send_request_offers_every_permission_its_gates_check(scanner, tmp_path):
    found = _scan(scanner, tmp_path, (
        "from client_utils import send_request\n"
        "def search(request, done):\n"
        "    send_request(request, done)\n"
    ))
    assert "network" in found
    assert "messages.read" in found


def test_direct_send_request_offers_message_access(scanner, tmp_path):
    found = _scan(scanner, tmp_path, (
        "from client_utils import get_connections_manager\n"
        "def search(request, delegate):\n"
        "    get_connections_manager().sendRequest(request, delegate)\n"
    ))
    assert "messages.read" in found


def test_request_hook_registration_offers_message_access(scanner, tmp_path):
    found = _scan(scanner, tmp_path, (
        "from base_plugin import BasePlugin\n"
        "class P(BasePlugin):\n"
        "    def on_plugin_load(self):\n"
        "        self.add_hook('updateProfile', match_substring=True)\n"
    ))
    assert "messages.read" in found


def test_ui_only_plugin_is_offered_nothing(scanner, tmp_path):
    found = _scan(scanner, tmp_path, (
        "from ui.bulletin import BulletinHelper\n"
        "def greet():\n"
        "    BulletinHelper.show_info('hi')\n"
    ))
    assert found == {}


def test_app_files_dir_offers_files(scanner, tmp_path):
    found = _scan(scanner, tmp_path, (
        "import os\n"
        "from file_utils import get_files_dir, ensure_dir_exists\n"
        "def data_dir():\n"
        "    return ensure_dir_exists(os.path.join(get_files_dir(), 'cache'))\n"
    ))
    assert "files" in found


def test_overlay_window_offers_hooks(scanner, tmp_path):
    found = _scan(scanner, tmp_path, (
        "from android.view import WindowManager\n"
        "def params():\n"
        "    p = WindowManager.LayoutParams()\n"
        "    p.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY\n"
        "    return p\n"
    ))
    assert "hooks" in found


def test_own_plugin_dir_is_offered_nothing(scanner, tmp_path):
    found = _scan(scanner, tmp_path, (
        "from file_utils import get_plugin_dir, get_plugin_cache_dir\n"
        "def dirs():\n"
        "    return get_plugin_dir(), get_plugin_cache_dir()\n"
    ))
    assert found == {}



_STUB_DEX = """\
# __DEX_BEGIN__
# eNpVk89rE0EUx9/sbtKmaht7EQLisujJJJsfuzFJRSxUpBDpIaV4EONkd5Jsu9lds5t0QSx6Vrzo
# QfAq9CL05smjF/+AHoQKIl68ePAu+CY7gTXhk5l57/vem2S/sVm8UqmbcPJm92Xx4+1mZfzbDE5/
# nBzmj5XLf749fCsDBAAQ7xnrIF7fJYA1SOJZ5AOCIfiMEOQMUZCf4qzgR4SCq7gGSIy8QF4hr5Fj
# 5BT5hfxFllFbQK4gGnINuS4lM3jfjJi7hJxDZHGvEUnOGXGWUL0i7nB+fiYiTuDCvG5xTjSLPsti
# /YKJrxLPkXn+AUnmB3muXJ3XcR6RpKYAOSgQAo9VgDzmM0gOVuPFDAXf2ZuO50S34OLWnfvq2Len
# LlNdn9rMBrIN8nanA+sd6tkT37H1aeS4escfbkCxY/ljncURm9DAnQ4dL9RD5tm9MHKsAzbp0bDn
# H3r6Pep4G9hhn86o7lJvqO/095kV/R/rRhPHw645Li/zOOS72K2bNNsMdw49kHc37wLZA2KDwm8I
# 78jR0VbzidanKPJsra3ZLNaKGt4scFwaOb5Xwm/E5on+dIipEQ1L1ohZB+F0HGrtAXVDVtTGjlei
# gaO1q0UtHNFSFSsqTYM2W9Vag7KK2WpYZqtVNer2wLxBG6bRMBp0UGvWq3VsOmOTEGdhUatcKxsl
# m820p/iAuGvI82fKe4nkPknkUo7/7mviWZLUuvCulPKvnPKwkvJxJuXlbMrPRE303NOy2HN/kHyi
# 4V6UVDGLB9Qkzj2VEXvuLVnoz3gDsef/r3/qWpi1
# __DEX_END__
"""


def test_unused_embedded_dex_is_reported_without_permissions(scanner, tmp_path):
    found = _scan(scanner, tmp_path, "print('hi')\r\n" + _STUB_DEX.replace("\n", "\r\n"))
    assert found["dex"] == ["size:920", "loaded:no",
                            "class:com.exteraplugins.send_sticker_as_own.Main"]
    assert set(found) == {"dex"}


def test_loaded_embedded_dex_adds_what_it_calls(scanner, tmp_path, monkeypatch):
    symbols = (
        {"Lcom/example/Core;"},
        ["Lcom/example/Core;", "Ljava/net/HttpURLConnection;", "Lde/robv/android/xposed/XposedBridge;"],
        [("Ljava/net/HttpURLConnection;", "connect"), ("Ljava/lang/Runtime;", "exec")],
    )
    monkeypatch.setattr(scanner, "_dex_symbols", lambda data: symbols)
    found = _scan(scanner, tmp_path,
                  "from java import jclass\n"
                  "loader = jclass('dalvik.system.InMemoryDexClassLoader')\n" + _STUB_DEX)
    assert found["dex"][:3] == ["size:920", "loaded:yes", "class:com.example.Core"]
    assert "DEX: HttpURLConnection" in found["network"]
    assert "DEX: Xposed" in found["hooks"]
    assert "DEX: Runtime.exec" in found["native"]


def test_dex_in_base64_literal_adds_what_it_calls(scanner, tmp_path, monkeypatch):
    symbols = (
        {"Lcom/example/Core;"},
        ["Lcom/example/Core;", "Landroid/webkit/WebView;"],
        [("Landroid/webkit/WebView;", "loadUrl")],
    )
    monkeypatch.setattr(scanner, "_dex_symbols", lambda data: symbols)
    payload = "\n".join(line[2:] for line in _STUB_DEX.splitlines()[1:-1])
    found = _scan(scanner, tmp_path,
                  "from dalvik.system import InMemoryDexClassLoader\n"
                  f'DEX_B64 = """{payload}"""\n')
    assert found["dex"][:3] == ["size:920", "loaded:yes", "class:com.example.Core"]
    assert "DEX: WebView" in found["network"]


def test_plain_base64_literal_is_not_a_dex(scanner, tmp_path):
    found = _scan(scanner, tmp_path, f'ICON = "{"QUJD" * 300}"\n')
    assert "dex" not in found
