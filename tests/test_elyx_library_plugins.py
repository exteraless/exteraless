import ast
import importlib
import importlib.util
import os
import sys
import typing
import zipfile
from pathlib import Path

import pytest

import corpus

LIBRARY_ID = "zwystackprobe"

LIBRARY_FILES = {
    "refmap.yml": "metainfo: metainfo.yml\nmain: src/main.py\n",
    "metainfo.yml": (
        f"id: {LIBRARY_ID}\n"
        "name: Stack probe\n"
        "description: probe\n"
        'author: "@probe"\n'
        'version: "3.0.0"\n'
    ),
    "__init__.py": f"if __name__ == {LIBRARY_ID!r}:\n    from .src.api import *\n",
    "src/__init__.py": "",
    "src/api.py": (
        "import os\n"
        "import sys\n"
        "PLUGINS_DIR = None\n"
        f"LIBRARY_ID = {LIBRARY_ID!r}\n"
        "def caller_id():\n"
        "    index = 0\n"
        "    while True:\n"
        "        index += 1\n"
        "        try:\n"
        "            path = sys._getframe(index).f_code.co_filename\n"
        "        except ValueError:\n"
        "            return None\n"
        "        if PLUGINS_DIR not in path:\n"
        "            continue\n"
        "        if 'ElyxPlugins' in path:\n"
        "            plugin_id = path.split('ElyxPlugins/')[1].split('/')[0]\n"
        "        else:\n"
        "            plugin_id = os.path.splitext(path)[0].split('/')[-1]\n"
        "        if plugin_id == LIBRARY_ID:\n"
        "            continue\n"
        "        return plugin_id\n"
        "def register():\n"
        "    return caller_id()\n"
    ),
    "src/main.py": (
        "from base_plugin import BasePlugin\n"
        f"from {LIBRARY_ID}.src import api\n"
        "class Probe(BasePlugin):\n"
        "    pass\n"
    ),
}


@pytest.fixture
def elyx(monkeypatch):
    monkeypatch.syspath_prepend(corpus.PYTHON_ROOT)
    try:
        runtime = importlib.import_module("elyx_runtime")
        importlib.import_module("base_plugin")
    except Exception as exc:
        pytest.skip(f"elyx_runtime is not importable here: {exc}")
    return runtime


def _write_archive(path, files):
    with zipfile.ZipFile(path, "w") as archive:
        for name, text in files.items():
            archive.writestr(name, text)


def test_library_frames_are_skipped_by_stack_based_plugin_ids(elyx, tmp_path):
    plugins_dir = tmp_path / "plugins"
    plugins_dir.mkdir()
    archive_path = plugins_dir / f"{LIBRARY_ID}.elyx"
    _write_archive(archive_path, LIBRARY_FILES)
    record = {}
    elyx.load_plugin_record(record, str(archive_path))
    try:
        library = sys.modules[LIBRARY_ID]
        reference = os.path.join(str(plugins_dir), "ElyxPlugins", LIBRARY_ID)
        assert library.__file__.startswith(reference + os.sep)
        assert record["module"].__file__.startswith(reference + os.sep)
        library.src.api.PLUGINS_DIR = str(plugins_dir)
        dependent = plugins_dir / "dependent_probe.py"
        dependent.write_text(f"import {LIBRARY_ID}\nRESULT = {LIBRARY_ID}.register()\n")
        spec = importlib.util.spec_from_file_location("dependent_probe", dependent)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        assert module.RESULT == "dependent_probe"
    finally:
        elyx.unload_plugin_record(record)
    assert LIBRARY_ID not in sys.modules


def _owner_resolver(plugins_dir):
    path = Path(corpus.PYTHON_ROOT, "extera_utils", "plugin_loader.py")
    tree = ast.parse(path.read_text(encoding="utf-8"))
    wanted = {"_resolve_owner", "_installed_plugin_owner", "_ELYX_DIR_MARKERS"}
    body = []
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name in wanted:
            body.append(node)
        elif isinstance(node, ast.Assign) and any(
                isinstance(target, ast.Name) and target.id in wanted for target in node.targets):
            body.append(node)
    namespace = {
        "os": os,
        "Optional": typing.Optional,
        "_owner_files": {},
        "_plugins_dir_path": lambda: plugins_dir,
    }
    exec(compile(ast.Module(body=body, type_ignores=[]), str(path), "exec"), namespace)
    return namespace["_resolve_owner"]


def test_code_under_the_elyx_reference_dir_belongs_to_its_plugin(tmp_path):
    plugins_dir = str(tmp_path / "plugins")
    resolve = _owner_resolver(plugins_dir)
    assert resolve(os.path.join(plugins_dir, "ElyxPlugins", "zwylib", "src", "main.py")) == "zwylib"
    assert resolve(os.path.join(plugins_dir, ".elyx_extracted", "zwylib", "0f", "src", "main.py")) == "zwylib"
    assert resolve(os.path.join(plugins_dir, "LocalEdictor.py")) == "LocalEdictor"
    assert resolve(os.path.join(str(tmp_path), "site", "json", "__init__.py")) is None
