import importlib.util
import os
import sys
import threading
import types
from pathlib import Path

import pytest

import corpus


@pytest.fixture
def gate(monkeypatch, tmp_path):
    files = tmp_path / "files"
    plugins = files / "plugins"
    for path in (plugins / ".data" / "p", plugins / ".data" / "other", tmp_path / "shared_prefs",
                 tmp_path / "databases", plugins / "__pycache__", tmp_path / "cache"):
        path.mkdir(parents=True)

    file_utils = types.ModuleType("file_utils")
    file_utils.get_files_dir = lambda: str(files)

    def is_own_path(plugin_id, path):
        own = os.path.realpath(plugins / ".data" / plugin_id)
        target = os.path.realpath(path)
        return target == own or target.startswith(own + os.sep)

    file_utils._is_own_path = is_own_path
    monkeypatch.setitem(sys.modules, "file_utils", file_utils)

    path = Path(corpus.PYTHON_ROOT, "extera_utils", "audit_gate.py")
    spec = importlib.util.spec_from_file_location("audit_gate_under_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    granted = set()
    denials = []

    def require_permission(perm, what, detail=None, plugin_id=None):
        if perm not in granted:
            raise PermissionError(f"{plugin_id} lacks {perm}")

    loader = types.SimpleNamespace(
        plugin_frame_owner=lambda: "p",
        unsafe_mode=lambda: False,
        has_permission=lambda perm, plugin_id=None: perm in granted,
        require_permission=require_permission,
        log_denial=lambda *args: denials.append(args),
        _context_state=threading.local(),
        PERM_FILES="files", PERM_HOOKS="hooks", PERM_NATIVE="native", PERM_NETWORK="network",
    )
    return types.SimpleNamespace(module=module, loader=loader, granted=granted, root=tmp_path,
                                 plugins=plugins, denials=denials)


def check(gate, event, *args):
    gate.module._check(event, args, gate.loader)


def test_files_permission_does_not_open_settings_for_writing(gate):
    gate.granted.add("files")
    target = str(gate.root / "shared_prefs" / "exteraless_plugin_grants.xml")
    with pytest.raises(PermissionError):
        check(gate, "open", target, "w", os.O_WRONLY | os.O_CREAT)
    with pytest.raises(PermissionError):
        check(gate, "open", target, "r", os.O_RDONLY)


def test_session_files_need_hooks_to_read(gate):
    gate.granted.add("files")
    session = str(gate.root / "files" / "tgnet.dat")
    with pytest.raises(PermissionError):
        check(gate, "open", session, "rb", os.O_RDONLY)
    gate.granted.add("hooks")
    check(gate, "open", session, "rb", os.O_RDONLY)
    check(gate, "open", str(gate.root / "shared_prefs" / "userconfing.xml"), "r", os.O_RDONLY)


def test_rename_into_settings_is_refused(gate):
    gate.granted.add("files")
    source = str(gate.plugins / ".data" / "p" / "grants.xml")
    with pytest.raises(PermissionError):
        check(gate, "os.rename", source, str(gate.root / "shared_prefs" / "x.xml"), -1, -1)


def test_symlink_to_settings_does_not_bypass(gate):
    gate.granted.add("files")
    link = gate.plugins / ".data" / "p" / "prefs"
    os.symlink(gate.root / "shared_prefs", link)
    with pytest.raises(PermissionError):
        check(gate, "open", str(link / "x.xml"), "w", os.O_WRONLY)


def test_directory_fd_of_settings_cannot_be_opened(gate):
    gate.granted.add("files")
    with pytest.raises(PermissionError):
        check(gate, "open", str(gate.root / "shared_prefs"), None, os.O_RDONLY)


def test_plugin_code_and_foreign_data_are_sealed(gate):
    gate.granted.add("files")
    for target in (gate.plugins / "zwylib.py", gate.plugins / "x.plugin",
                   gate.plugins / "__pycache__" / "a.pyc", gate.plugins / ".data" / "other" / "s.json"):
        with pytest.raises(PermissionError):
            check(gate, "open", str(target), "w", os.O_WRONLY | os.O_CREAT)


def test_plugin_data_files_stay_writable(gate):
    gate.granted.add("files")
    check(gate, "open", str(gate.plugins / "deeplink_data" / "a.json"), "w", os.O_WRONLY | os.O_CREAT)
    check(gate, "open", str(gate.plugins / ".data" / "p" / "a.json"), "w", os.O_WRONLY | os.O_CREAT)
    check(gate, "open", str(gate.root / "cache" / "a.bin"), "wb", os.O_WRONLY | os.O_CREAT)


def test_trusted_plugin_may_install_code(gate):
    gate.granted.update({"files", "hooks"})
    check(gate, "open", str(gate.plugins / "new_plugin.py"), "w", os.O_WRONLY | os.O_CREAT)
    check(gate, "os.rename", str(gate.plugins / "a.tmp"), str(gate.plugins / "a.py"), -1, -1)


def test_unsafe_mode_lifts_the_seal(gate):
    gate.loader.unsafe_mode = lambda: True
    check(gate, "open", str(gate.root / "shared_prefs" / "x.xml"), "w", os.O_WRONLY)


def test_forged_sdk_filename_does_not_unseal(gate):
    gate.granted.add("files")
    code = compile("check(gate, 'open', target, 'w', os.O_WRONLY)", "/x/pip_controller.py", "exec")
    target = str(gate.root / "shared_prefs" / "x.xml")
    with pytest.raises(PermissionError):
        exec(code, {"check": check, "gate": gate, "target": target, "os": os})


def test_engine_dependency_install_may_write_shared_libs(gate):
    gate.granted.add("files")
    gate.loader._context_state.engine_write = True
    check(gate, "open", str(gate.plugins / "shared_libs" / "pkg" / "m.py"), "w", os.O_WRONLY | os.O_CREAT)


def _fake_importer(gate, monkeypatch):
    root = gate.root / "files" / "chaquopy" / "AssetFinder"
    importer = types.ModuleType("java.android.importer")

    class AssetFinder:
        pass

    class AssetZipFile:
        def extract_if_changed(self, member, target_dir):
            check(gate, "open", os.path.join(target_dir, member), "wb",
                  os.O_WRONLY | os.O_CREAT | os.O_TRUNC)

    importer.AssetFinder = AssetFinder
    importer.AssetZipFile = AssetZipFile
    importer.ASSET_PREFIX = str(root)
    genuine = AssetZipFile()
    finder = AssetFinder()
    finder.zip_files = [genuine]
    monkeypatch.setitem(sys.modules, "java.android.importer", importer)
    monkeypatch.setitem(sys.path_importer_cache, str(root / "stdlib"), finder)
    gate.loader._owner_of_frame = \
        lambda frame: "p" if frame.f_code.co_filename == "/plugins/p.py" else None
    gate.module._capture_extraction()
    monkeypatch.setattr(gate.module, "_runtime_prefixes", (os.path.realpath(root.parent),))
    return root, genuine, AssetZipFile


def test_runtime_extracts_native_module_imported_by_plugin(gate, monkeypatch):
    root, genuine, _ = _fake_importer(gate, monkeypatch)
    plugin = compile("archive.extract_if_changed('lib-dynload/_asyncio.so', target)",
                     "/plugins/p.py", "exec")
    exec(plugin, {"archive": genuine, "target": str(root / "stdlib")})


def test_plugin_cannot_write_into_runtime_extraction(gate, monkeypatch):
    root, genuine, archive_class = _fake_importer(gate, monkeypatch)
    plugin = compile("archive.extract_if_changed(member, target)", "/plugins/p.py", "exec")
    with pytest.raises(PermissionError):
        exec(plugin, {"archive": archive_class(), "member": "_asyncio.so",
                      "target": str(root / "stdlib")})
    with pytest.raises(PermissionError):
        exec(plugin, {"archive": genuine, "member": "x.xml",
                      "target": str(gate.root / "shared_prefs")})
    direct = compile("check(gate, 'open', path, 'wb', os.O_WRONLY)", "/plugins/p.py", "exec")
    with pytest.raises(PermissionError):
        exec(direct, {"check": check, "gate": gate, "os": os,
                      "path": str(root / "stdlib" / "_asyncio.so")})


def test_code_a_plugin_executes_from_its_own_unpacked_copy_is_attributed_to_it(gate):
    unpacked = gate.root / "files" / "pak" / "p" / "bundle"
    unpacked.mkdir(parents=True)
    main = unpacked / "main.py"
    main.write_text("x = 1\n")
    owners = {}
    gate.loader._resolve_owner = lambda path: owners.get(os.path.abspath(path))
    gate.loader._register_owner = lambda path, plugin_id: owners.__setitem__(os.path.abspath(path), plugin_id)
    check(gate, "exec", compile(main.read_text(), str(main), "exec"))
    assert owners == {str(main): "p"}


def test_executed_code_of_another_plugin_keeps_its_owner(gate):
    other = gate.plugins / "other.py"
    other.write_text("x = 1\n")
    registered = []
    gate.loader._resolve_owner = lambda path: "other"
    gate.loader._register_owner = lambda path, plugin_id: registered.append(path)
    with pytest.raises(PermissionError):
        check(gate, "exec", compile(other.read_text(), str(other), "exec"))
    assert registered == []


def test_session_files_under_a_runtime_path_still_need_files(gate, monkeypatch):
    files = str(gate.root / "files")
    monkeypatch.setattr(gate.module, "_runtime_prefixes", (os.path.realpath(files),))
    for name in ("cache4.db", "tgnet.dat", "accounts_map.json"):
        target = gate.root / "files" / name
        target.write_bytes(b"x")
        with pytest.raises(PermissionError):
            check(gate, "open", str(target), "rb", 0)


def test_settings_and_foreign_plugin_data_are_never_runtime_files(gate, monkeypatch):
    monkeypatch.setattr(gate.module, "_runtime_prefixes", (os.path.realpath(gate.root),))
    prefs = gate.root / "shared_prefs" / "exteraless_plugin_grants.xml"
    prefs.write_text("<map/>")
    foreign = gate.plugins / ".data" / "other" / "secret.json"
    foreign.write_text("{}")
    for target in (prefs, foreign):
        with pytest.raises(PermissionError):
            check(gate, "open", str(target), "rb", 0)


def test_runtime_modules_stay_readable(gate, monkeypatch):
    lib = gate.root / "files" / "chaquopy" / "lib"
    lib.mkdir(parents=True)
    module = lib / "json.py"
    module.write_text("")
    monkeypatch.setattr(gate.module, "_runtime_prefixes", (os.path.realpath(gate.root / "files" / "chaquopy"),))
    check(gate, "open", str(module), "rb", 0)
