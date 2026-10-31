import importlib
import json
import sys

import pytest

import corpus


@pytest.fixture
def namespace(monkeypatch):
    monkeypatch.syspath_prepend(corpus.PYTHON_ROOT)
    try:
        return importlib.import_module("elyx_runtime.namespace")
    except Exception as exc:
        pytest.skip(f"elyx_runtime.namespace is not importable here: {exc}")


def _write(root, files):
    for name, text in files.items():
        path = root.joinpath(*name.split("/"))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)


def test_plugin_id_imports_the_plugin_root(namespace, tmp_path):
    plugin_id = "zwylibprobe"
    root = tmp_path / "extract"
    _write(root, {
        "__init__.py": f"if __name__ == {plugin_id!r}:\n    from .src.api import *\n",
        "src/api.py": "from .core import state\n__all__ = ['VALUE', 'state']\nVALUE = 42\n",
        "src/core/state.py": "loads = []\n",
        "src/main.py": (
            "import os, sys\n"
            f"from {plugin_id}.src.core import state as _state\n"
            f"import {plugin_id} as library\n"
            "package_dir = os.path.dirname(os.path.dirname(__file__))\n"
            "own = library.__file__.startswith(package_dir)\n"
            "_state.loads.append(1)\n"
        ),
    })
    namespace.register_namespace(plugin_id, str(root), [str(root)])
    try:
        main = importlib.import_module(f"{namespace.NAMESPACE_ROOT}.{plugin_id}.src.main")
        library = sys.modules[plugin_id]
        assert library.VALUE == 42
        assert main.own
        assert library.state is main._state
        assert main._state.loads == [1]
    finally:
        namespace.unregister_namespace(plugin_id)
        namespace.evict_modules(plugin_id)
    assert not [name for name in sys.modules
                if name.partition(".")[0] == plugin_id
                or name.startswith(f"{namespace.NAMESPACE_ROOT}.{plugin_id}")]
    with pytest.raises(ModuleNotFoundError):
        importlib.import_module(plugin_id)


def test_plugin_id_never_shadows_real_modules(namespace, tmp_path):
    root = tmp_path / "extract"
    _write(root, {"__init__.py": "HIJACKED = True\n"})
    namespace.register_namespace("json", str(root), [str(root)])
    try:
        sys.modules.pop("json", None)
        assert not hasattr(importlib.import_module("json"), "HIJACKED")
    finally:
        namespace.unregister_namespace("json")
        namespace.evict_modules("json")
        sys.modules["json"] = json
