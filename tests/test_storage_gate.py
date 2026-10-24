import importlib.util
import ntpath
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


@pytest.fixture
def descriptor(gate, monkeypatch):
    """Test permission policy without requiring the host's descriptor API."""
    paths = {}
    monkeypatch.setattr(gate.module, "_fd_path", paths.get)

    def register(path):
        fd = 100 + len(paths)
        paths[fd] = os.fspath(path)
        return fd

    return register


def test_files_permission_does_not_open_settings_for_writing(gate):
    gate.granted.add("files")
    target = str(gate.root / "shared_prefs" / "exteraless_plugin_grants.xml")
    with pytest.raises(PermissionError):
        check(gate, "open", target, "w", os.O_WRONLY | os.O_CREAT)
    check(gate, "open", target, "r", os.O_RDONLY)


def test_rename_into_settings_is_refused(gate):
    gate.granted.add("files")
    source = str(gate.plugins / ".data" / "p" / "grants.xml")
    with pytest.raises(PermissionError):
        check(gate, "os.rename", source, str(gate.root / "shared_prefs" / "x.xml"), -1, -1)


def test_symlink_to_settings_does_not_bypass(gate):
    gate.granted.add("files")
    link = gate.plugins / ".data" / "p" / "prefs"
    try:
        os.symlink(gate.root / "shared_prefs", link, target_is_directory=True)
    except OSError as error:
        if getattr(error, "winerror", None) == 1314:
            pytest.skip("Windows does not grant symlink creation to this process")
        raise
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


@pytest.mark.parametrize("source_path", [
    "shared_prefs/exteraless_plugin_grants.xml",
    "databases/account.db",
    "files/plugins/victim.py",
    "files/plugins/.data/other/settings.json",
])
def test_hardlink_cannot_alias_protected_files(gate, source_path):
    gate.granted.add("files")
    source = gate.root / source_path
    source.write_text("protected", encoding="utf-8")
    alias = gate.plugins / ".data" / "p" / "alias.txt"

    # realpath resolves symlinks, not hard links: block the alias at creation.
    with pytest.raises(PermissionError):
        check(gate, "os.link", str(source), str(alias), -1, -1)
        os.link(source, alias)
        check(gate, "open", str(alias), "w", os.O_WRONLY | os.O_TRUNC)
        alias.write_text("overwritten", encoding="utf-8")

    assert source.read_text(encoding="utf-8") == "protected"
    assert not alias.exists()


def test_hardlink_between_own_data_files_stays_allowed(gate):
    source = gate.plugins / ".data" / "p" / "source.txt"
    alias = source.with_name("alias.txt")
    source.write_text("own data", encoding="utf-8")
    check(gate, "os.link", str(source), str(alias), -1, -1)
    os.link(source, alias)
    assert os.path.samefile(source, alias)


@pytest.mark.parametrize("unsafe", [False, True])
def test_trusted_hardlink_policy_is_preserved(gate, unsafe):
    gate.granted.add("files")
    if unsafe:
        gate.loader.unsafe_mode = lambda: True
    else:
        gate.granted.add("hooks")
    source = str(gate.root / "shared_prefs" / "settings.xml")
    alias = str(gate.plugins / ".data" / "p" / "alias.txt")
    check(gate, "os.link", source, alias, -1, -1)


@pytest.mark.parametrize("event,tail", [
    ("open", ("w", os.O_WRONLY)),
    ("os.truncate", (0,)),
    ("os.chmod", (0o600, -1)),
    ("os.chown", (-1, -1, -1)),
    ("os.utime", (None, None, -1)),
])
def test_descriptor_mutations_obey_storage_seal(gate, descriptor, event, tail):
    gate.granted.add("files")
    target = gate.root / "shared_prefs" / "settings.xml"
    target.write_text("settings", encoding="utf-8")
    with pytest.raises(PermissionError, match="app settings and databases"):
        check(gate, event, descriptor(target), *tail)


@pytest.mark.parametrize("event", ["open", "os.listdir", "os.scandir"])
def test_descriptor_reads_require_files_outside_own_data(gate, descriptor, event):
    fd = descriptor(gate.root / "cache")
    tail = ("r", os.O_RDONLY) if event == "open" else ()
    with pytest.raises(PermissionError, match="lacks files"):
        check(gate, event, fd, *tail)
    gate.granted.add("files")
    check(gate, event, fd, *tail)


@pytest.mark.parametrize("event,tail", [
    ("open", ("w", os.O_WRONLY)), ("os.truncate", (0,)),
    ("os.chmod", (0o600, -1)), ("os.utime", (None, None, -1)),
])
def test_own_file_descriptors_stay_allowed(gate, descriptor, event, tail):
    target = gate.plugins / ".data" / "p" / "data.txt"
    target.write_text("data", encoding="utf-8")
    check(gate, event, descriptor(target), *tail)


@pytest.mark.parametrize("event", ["os.remove", "os.rmdir", "os.mkdir", "os.chmod", "os.chown", "os.utime"])
def test_relative_dir_fd_targets_obey_storage_seal(gate, descriptor, event):
    gate.granted.add("files")
    fd = descriptor(gate.plugins / ".data" / "p")
    path = "../other/settings.json"
    tails = {
        "os.remove": (fd,), "os.rmdir": (fd,), "os.mkdir": (0o700, fd),
        "os.chmod": (0o600, fd), "os.chown": (-1, -1, fd),
        "os.utime": (None, None, fd),
    }
    with pytest.raises(PermissionError, match="data of other plugins"):
        check(gate, event, path, *tails[event])


@pytest.mark.parametrize("event", ["os.rename", "os.link"])
@pytest.mark.parametrize("protected_index", [0, 1])
def test_both_dir_fd_targets_obey_storage_seal(gate, descriptor, event, protected_index):
    gate.granted.add("files")
    fd = descriptor(gate.plugins / ".data" / "p")
    paths = ["source.txt", "destination.txt"]
    paths[protected_index] = "../other/settings.json"
    with pytest.raises(PermissionError, match="data of other plugins"):
        check(gate, event, *paths, fd, fd)


def test_symlink_dir_fd_destination_obeys_storage_seal(gate, descriptor):
    gate.granted.add("files")
    fd = descriptor(gate.plugins / ".data" / "p")
    with pytest.raises(PermissionError, match="data of other plugins"):
        check(gate, "os.symlink", "data.txt", "../other/alias.txt", fd)


def test_relative_dir_fd_targets_require_files(gate, descriptor):
    fd = descriptor(gate.plugins / ".data" / "p")
    with pytest.raises(PermissionError, match="lacks files"):
        check(gate, "os.remove", "../../../../cache/data.txt", fd)
    check(gate, "os.remove", "data.txt", fd)


def test_absolute_path_ignores_dir_fd(gate, descriptor):
    fd = descriptor(gate.root / "shared_prefs")
    check(gate, "os.remove", str(gate.plugins / ".data" / "p" / "data.txt"), fd)


@pytest.mark.skipif(os.name != "posix" or not Path("/proc/self/fd").is_dir(),
                    reason="requires Linux/Android procfs")
@pytest.mark.parametrize("own", [False, True])
def test_procfs_real_file_descriptor_policy(gate, own):
    target = (gate.plugins / ".data" / "p" / "data.txt" if own
              else gate.root / "shared_prefs" / "settings.xml")
    target.write_text("data", encoding="utf-8")
    gate.granted.add("files")
    with target.open("rb") as stream:
        assert os.path.samefile(gate.module._fd_path(stream.fileno()), target)
        if own:
            check(gate, "open", stream.fileno(), "w", os.O_WRONLY)
        else:
            with pytest.raises(PermissionError, match="app settings and databases"):
                check(gate, "open", stream.fileno(), "w", os.O_WRONLY)


@pytest.mark.skipif(os.name != "posix" or not Path("/proc/self/fd").is_dir(),
                    reason="requires Linux/Android procfs")
def test_procfs_real_directory_descriptor_policy(gate):
    directory = gate.plugins / ".data" / "p"
    fd = os.open(directory, os.O_RDONLY)
    try:
        assert os.path.samefile(gate.module._fd_path(fd), directory)
        check(gate, "os.remove", "data.txt", fd)
        gate.granted.add("files")
        with pytest.raises(PermissionError, match="data of other plugins"):
            check(gate, "os.remove", "../other/data.txt", fd)
    finally:
        os.close(fd)


@pytest.mark.parametrize("event,args,allowed", [
    ("open", (101, "w", os.O_WRONLY), True),
    ("open", (200, "w", os.O_WRONLY), False),
    ("os.remove", ("data.txt", 100), True),
    ("os.remove", (r"..\other\data.txt", 100), False),
    ("os.rename", ("data.txt", r"..\other\data.txt", 100, 100), False),
])
def test_descriptor_policy_with_windows_path_semantics(gate, monkeypatch, event, args, allowed):
    # Exercise drive letters and backslashes on every host, without changing
    # Python's process-wide os.path or relying on Windows directory handles.
    windows_os = types.SimpleNamespace(**vars(os))
    windows_os.path = ntpath
    windows_os.sep = "\\"
    monkeypatch.setattr(gate.module, "os", windows_os)
    plugins = r"C:\app\files\plugins"
    own = plugins + r"\.data\p"
    sealed = r"C:\app\shared_prefs"
    monkeypatch.setattr(gate.module, "_storage_paths", lambda: ((sealed,), plugins))
    monkeypatch.setattr(gate.module, "_fd_path", {
        100: own, 101: own + r"\data.txt", 200: sealed + r"\settings.xml",
    }.get)
    monkeypatch.setattr(gate.module, "_is_runtime_file", lambda path: False)
    monkeypatch.setattr(gate.module, "_is_own_file", lambda pid, path:
                        ntpath.normpath(path) == own or
                        ntpath.normpath(path).startswith(own + "\\"))
    if allowed:
        check(gate, event, *args)
    else:
        gate.granted.add("files")
        with pytest.raises(PermissionError):
            check(gate, event, *args)


@pytest.mark.parametrize("event", ["shutil.copymode", "shutil.copystat", "shutil.unpack_archive"])
def test_high_level_destination_requires_files(gate, event):
    source = str(gate.plugins / ".data" / "p" / "source.zip")
    target = str(gate.root / "cache" / "destination")
    with pytest.raises(PermissionError):
        check(gate, event, source, target)
    gate.granted.add("files")
    check(gate, event, source, target)


@pytest.mark.parametrize("event", ["shutil.copymode", "shutil.copystat", "shutil.unpack_archive"])
def test_high_level_destination_obeys_storage_seal(gate, event):
    gate.granted.add("files")
    with pytest.raises(PermissionError):
        check(gate, event, str(gate.plugins / ".data" / "p" / "source.zip"),
              str(gate.root / "shared_prefs"))


def test_archive_default_destination_obeys_storage_seal(gate, monkeypatch):
    gate.granted.add("files")
    monkeypatch.chdir(gate.root / "shared_prefs")
    with pytest.raises(PermissionError):
        check(gate, "shutil.unpack_archive", str(gate.plugins / ".data" / "p" / "data.zip"), None, None)


@pytest.mark.parametrize("event", ["os.listdir", "os.scandir"])
def test_default_directory_requires_files(gate, monkeypatch, event):
    monkeypatch.chdir(gate.root / "cache")
    with pytest.raises(PermissionError):
        check(gate, event, None)
    monkeypatch.chdir(gate.plugins / ".data" / "p")
    check(gate, event, None)


@pytest.mark.parametrize("event", ["os.rename", "os.rmdir", "shutil.move", "shutil.rmtree"])
@pytest.mark.parametrize("relative", [".", "files", "files/plugins"])
def test_structural_mutations_protect_storage_ancestors(gate, event, relative):
    gate.granted.add("files")
    target = str(gate.root / relative)
    with pytest.raises(PermissionError):
        check(gate, event, target, str(gate.root / "cache" / "destination"), -1, -1)


@pytest.mark.parametrize("event", ["os.rename", "shutil.move", "shutil.rmtree"])
def test_own_directory_mutations_stay_allowed(gate, event):
    own = gate.plugins / ".data" / "p"
    check(gate, event, str(own / "source"), str(own / "destination"), -1, -1)


@pytest.mark.parametrize("uri_kind", ["absolute", "encoded", "relative", "localhost"])
def test_database_uri_obeys_storage_seal(gate, monkeypatch, uri_kind):
    gate.granted.add("files")
    target = str(gate.root / "databases" / "account.db")
    uris = {
        "absolute": "file:" + target + "?mode=rwc",
        "encoded": "file:" + target.replace("databases", "%64atabases") + "?mode=rwc",
        "relative": "file:databases/account.db?mode=rwc",
        "localhost": "file://localhost" + target + "?mode=rwc",
    }
    monkeypatch.chdir(gate.root)
    with pytest.raises(PermissionError):
        check(gate, "sqlite3.connect", uris[uri_kind])


def test_database_uri_literal_filename_is_also_checked(gate, monkeypatch):
    monkeypatch.chdir(gate.root / "shared_prefs")
    own = gate.plugins / ".data" / "p" / "data.db"
    gate.granted.add("files")
    with pytest.raises(PermissionError):
        check(gate, "sqlite3.connect", "file:" + str(own))


def test_own_database_and_handle_stay_allowed(gate):
    check(gate, "sqlite3.connect", str(gate.plugins / ".data" / "p" / "data.db"))
    check(gate, "sqlite3.connect/handle", object())


@pytest.mark.parametrize("event,args", [
    ("open", (123, "w", os.O_WRONLY)),
    ("os.remove", ("data.txt", 123)),
    ("sqlite3.connect", ("file://[/data.db",)),
])
def test_unverifiable_targets_fail_closed_through_hook(gate, monkeypatch, event, args):
    monkeypatch.setattr(gate.module, "_fd_path", lambda fd: None)
    with pytest.raises(PermissionError):
        gate.module._hook(event, args, _loader_ref=gate.loader)
    assert not gate.module._local.busy


@pytest.mark.parametrize("unsafe", [False, True])
def test_trusted_directory_policy_is_preserved(gate, unsafe):
    gate.granted.add("files")
    if unsafe:
        gate.loader.unsafe_mode = lambda: True
    else:
        gate.granted.add("hooks")
    check(gate, "os.rename", str(gate.plugins), str(gate.root / "cache" / "plugins"), -1, -1)


@pytest.mark.parametrize("character", ["\t", "\n", "\r"])
def test_database_uri_keeps_filename_characters(gate, character):
    filename = str(gate.plugins / ".data" / "p" / ("a" + character + "b.db"))
    uri = "file:" + filename + "?mode=rwc"
    assert gate.module._database_paths(uri) == (uri, filename)


def test_database_uri_rejects_encoded_nul(gate):
    with pytest.raises(PermissionError):
        check(gate, "sqlite3.connect", "file:data.db%00ignored")
