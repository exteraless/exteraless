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


def test_ui_only_plugin_is_offered_nothing(scanner, tmp_path):
    found = _scan(scanner, tmp_path, (
        "from ui.bulletin import BulletinHelper\n"
        "def greet():\n"
        "    BulletinHelper.show_info('hi')\n"
    ))
    assert found == {}
