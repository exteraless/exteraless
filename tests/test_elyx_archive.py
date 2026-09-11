import importlib
import os
import shutil
import sys
import tempfile
import zipfile

import pytest

import corpus

ARCHIVE_DIR = os.environ.get(
    "EXTERALESS_PLUGIN_ARCHIVES", "/home/coral/openExtera/plugin-corpus/archives")


@pytest.fixture(scope="module")
def archive_module():
    if corpus.PYTHON_ROOT not in sys.path:
        sys.path.insert(0, corpus.PYTHON_ROOT)
    try:
        return importlib.import_module("elyx_runtime.archive")
    except Exception as exc:
        pytest.skip(f"elyx_runtime.archive is not importable here: {exc}")


def archives_with_native():
    if not os.path.isdir(ARCHIVE_DIR):
        return []
    out = []
    for fn in sorted(os.listdir(ARCHIVE_DIR)):
        path = os.path.join(ARCHIVE_DIR, fn)
        if not (os.path.isfile(path) and zipfile.is_zipfile(path)):
            continue
        with zipfile.ZipFile(path) as z:
            libs = [n for n in z.namelist() if n.endswith(".so")]
        if libs:
            out.append((path, sorted(libs)))
    return out


def test_native_libraries_land_where_plugins_look(archive_module):
    found = archives_with_native()
    if not found:
        pytest.skip(f"no archive with native libraries in {ARCHIVE_DIR}")
    for path, libs in found:
        plugin_id = "test_" + os.path.basename(path).split(".")[0]
        root = tempfile.mkdtemp(prefix="elyx_archive_")
        try:
            plugins_dir = os.path.join(root, "plugins")
            os.makedirs(plugins_dir)
            digest, extract_dir = archive_module.extract_archive(
                path, plugins_dir, plugin_id)
            reference = archive_module.reference_dir(plugins_dir, plugin_id)
            assert os.path.isdir(reference), (
                f"{os.path.basename(path)}: nothing at {reference}; plugins build"
                f" native paths from <plugins>/ElyxPlugins/<id>, not from the"
                f" content-addressed dir {extract_dir}")
            for member in libs:
                target = os.path.join(reference, *member.split("/"))
                assert os.path.isfile(target), (
                    f"{os.path.basename(path)}: {member} missing at {target}")
            again_digest, again_dir = archive_module.extract_archive(
                path, plugins_dir, plugin_id)
            assert again_dir == extract_dir
            assert os.path.isfile(
                os.path.join(reference, *libs[0].split("/"))), (
                "the reference path must survive a reload of the same archive")
            archive_module.purge_plugin_dirs(plugins_dir, plugin_id)
            assert not os.path.exists(reference), (
                "uninstall must remove the reference path too")
        finally:
            shutil.rmtree(root, ignore_errors=True)


def test_refmap_rejects_paths_outside_archive(archive_module):
    payload = b"main: ../outside.py\nmetainfo: metainfo.yml\n"
    with pytest.raises(Exception, match="traversal"):
        archive_module.parse_refmap("refmap.yml", payload)


def test_archive_rejects_duplicate_casefolded_members(archive_module):
    root = tempfile.mkdtemp(prefix="elyx_archive_duplicate_")
    try:
        path = os.path.join(root, "duplicate.elyx")
        with zipfile.ZipFile(path, "w") as zf:
            zf.writestr("Main.py", "x = 1")
            zf.writestr("main.py", "x = 2")
        with pytest.raises(Exception, match="duplicate"):
            archive_module.open_archive(path)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_python_mapping_is_literal_only(archive_module, tmp_path):
    metadata = importlib.import_module("elyx_runtime.metadata")
    marker = tmp_path / "executed"
    source = (
        "id = 'safe_plugin'\n"
        "name: str = 'Safe plugin'\n"
        f"side_effect = __import__('pathlib').Path({str(marker)!r}).write_text('bad')\n"
    )
    with pytest.raises(Exception, match="must be a literal"):
        metadata.parse_mapping_file("metainfo.py", source.encode("utf-8"))
    assert not marker.exists()


def test_python_mapping_preserves_literal_assignments(archive_module):
    metadata = importlib.import_module("elyx_runtime.metadata")
    parsed = metadata.parse_mapping_file(
        "metainfo.py", b"id = 'safe_plugin'\nname: str = 'Safe plugin'\nbeta = False\n")
    assert parsed == {"id": "safe_plugin", "name": "Safe plugin", "beta": False}
