"""Elyx archive handling: refmap discovery, safe extraction, bundled wheels.

Archive layout of the .elyx container:
  - ZIP-compatible file (.elyx / .eaf / .elyx.zip / .eaf.zip)
  - refmap.yaml | refmap.yml | refmap.json at the archive root (first match wins)
  - all refmap paths are relative to the archive root

Installed layout (host plugins dir):
  <plugins_dir>/.elyx_extracted/<plugin_id>/<content-sha256[:16]>/   extracted archive
  <plugins_dir>/elyx_local_libs/<plugin_id>/<wheel-stem>/             extracted wheels

The content-addressed extraction subdir makes reloads safe: a changed archive
extracts to a fresh directory while the old one is removed after a successful
load.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import zipfile
from typing import Dict, List, Optional, Tuple

import yaml

from .errors import (
    ElyxArchiveError,
    MetainfoNotFoundError,
    RefmapError,
    RefmapNotFoundError,
)

REFMAP_CANDIDATES = ("refmap.yaml", "refmap.yml", "refmap.json")
METAINFO_CANDIDATES = ("metainfo.yaml", "metainfo.yml", "metainfo.json")

EXTRACTED_DIRNAME = ".elyx_extracted"
REFERENCE_DIRNAME = "ElyxPlugins"
LOCAL_LIBS_DIRNAME = "elyx_local_libs"
_COMPLETE_MARKER = ".elyx_complete"
_WHEEL_MARKER = ".elyx_wheel_complete"

# Extraction limits are intentionally generous for plugins (including bundled
# native libraries), but finite. ZIP metadata is checked before any member is
# read or written so a tiny archive cannot expand until it fills app storage.
MAX_ARCHIVE_MEMBERS = 4096
MAX_ARCHIVE_UNCOMPRESSED_BYTES = 128 * 1024 * 1024
MAX_ARCHIVE_MEMBER_BYTES = 64 * 1024 * 1024


# ZIP basics

def _validate_archive(zf: zipfile.ZipFile) -> None:
    infos = zf.infolist()
    if len(infos) > MAX_ARCHIVE_MEMBERS:
        raise ElyxArchiveError(
            f"archive has {len(infos)} entries, over the "
            f"{MAX_ARCHIVE_MEMBERS} entry limit"
        )

    total_size = 0
    for info in infos:
        _check_member_safety(info.filename)
        if info.is_dir():
            continue
        if info.file_size > MAX_ARCHIVE_MEMBER_BYTES:
            raise ElyxArchiveError(
                f"archive member {info.filename!r} expands to "
                f"{info.file_size} bytes, over the "
                f"{MAX_ARCHIVE_MEMBER_BYTES} byte per-file limit"
            )
        total_size += info.file_size
        if total_size > MAX_ARCHIVE_UNCOMPRESSED_BYTES:
            raise ElyxArchiveError(
                f"archive expands to more than "
                f"{MAX_ARCHIVE_UNCOMPRESSED_BYTES} bytes"
            )


def open_archive(path: str) -> zipfile.ZipFile:
    """Open *path* as a ZIP, raising a clear error for non-archives."""
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as e:
        raise ElyxArchiveError(f"{path!r} is not a valid ZIP/Elyx archive: {e}")
    except OSError as e:
        raise ElyxArchiveError(f"cannot read archive {path!r}: {e}")
    try:
        _validate_archive(archive)
    except Exception:
        archive.close()
        raise
    return archive


def archive_digest(path: str) -> str:
    """SHA-256 of the archive file; used as the content-addressed stamp."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_member(zf: zipfile.ZipFile, name: str) -> bytes:
    try:
        return zf.read(name)
    except KeyError:
        raise ElyxArchiveError(f"archive member {name!r} not found")


def _root_names(zf: zipfile.ZipFile) -> set:
    """Names living directly at the archive root."""
    names = set()
    for info in zf.infolist():
        entry = info.filename
        if "/" not in entry.strip("/"):
            names.add(entry.strip("/"))
    return names


def find_refmap_member(zf: zipfile.ZipFile) -> str:
    roots = _root_names(zf)
    for candidate in REFMAP_CANDIDATES:
        if candidate in roots:
            return candidate
    raise RefmapNotFoundError(
        "refmap not found: the archive root must contain one of "
        + ", ".join(REFMAP_CANDIDATES)
        + " (zip the project *contents*, not a wrapper directory)"
    )


def find_metainfo_member(zf: zipfile.ZipFile, refmap: Dict[str, str]) -> str:
    """Metainfo location: explicit refmap pointer, else root auto-discovery."""
    declared = refmap.get("metainfo")
    if declared:
        name = str(declared).strip("/")
        if name in zf.namelist():
            return name
        raise ElyxArchiveError(
            f"metainfo file {declared!r} declared in refmap does not exist in the archive"
        )
    roots = _root_names(zf)
    for candidate in METAINFO_CANDIDATES:
        if candidate in roots:
            return candidate
    raise MetainfoNotFoundError(
        "metainfo not found: declare `metainfo: <path>` in the refmap or place "
        + "/".join(METAINFO_CANDIDATES)
        + " at the archive root"
    )


# refmap parsing

def parse_refmap(name: str, data: bytes) -> Dict[str, str]:
    """Parse a refmap file (YAML or JSON by extension) into a str->str mapping."""
    text = data.decode("utf-8")
    try:
        if name.endswith(".json"):
            parsed = json.loads(text)
        else:
            parsed = yaml.safe_load(text)
    except Exception as e:
        raise RefmapError(f"cannot parse {name}: {type(e).__name__}: {e}")
    if not isinstance(parsed, dict) or not parsed:
        raise RefmapError(f"{name} must contain a non-empty mapping of paths")
    refmap: Dict[str, str] = {}
    for key, value in parsed.items():
        if value is None:
            continue
        if not isinstance(value, str):
            # Builder-only keys may carry non-string values in the future;
            # keep them stringified so the runtime mapping stays simple.
            value = str(value)
        refmap[str(key)] = value
    return refmap


def load_refmap(zf: zipfile.ZipFile) -> Dict[str, str]:
    member = find_refmap_member(zf)
    return parse_refmap(member, read_member(zf, member))


# Safe extraction

def _check_member_safety(name: str) -> None:
    """Reject absolute paths and path traversal (zip-slip) in member names."""
    normalized = name.replace("\\", "/")
    if normalized.startswith("/") or (len(normalized) > 1 and normalized[1] == ":"):
        raise ElyxArchiveError(f"unsafe absolute path in archive: {name!r}")
    if any(part == ".." for part in normalized.split("/")):
        raise ElyxArchiveError(f"path traversal in archive member: {name!r}")


def _safe_extract(zf: zipfile.ZipFile, dest: str) -> None:
    _validate_archive(zf)
    zf.extractall(dest)


def extract_archive(path: str, plugins_dir: str, plugin_id: str) -> Tuple[str, str]:
    """Extract the archive content-addressed; returns (digest, extract_dir).

    Reuses an existing complete extraction of identical content, so reloads of
    an unchanged archive are cheap.
    """
    digest = archive_digest(path)
    stamp = digest[:16]
    base = os.path.join(plugins_dir, EXTRACTED_DIRNAME, plugin_id)
    dest = os.path.join(base, stamp)
    marker = os.path.join(dest, _COMPLETE_MARKER)
    if os.path.isfile(marker):
        _link_reference_dir(plugins_dir, plugin_id, dest)
        return digest, dest

    os.makedirs(base, exist_ok=True)
    tmp = os.path.join(base, f".{stamp}.tmp{os.getpid()}")
    shutil.rmtree(tmp, ignore_errors=True)
    shutil.rmtree(dest, ignore_errors=True)  # drop a partial previous attempt
    os.makedirs(tmp, exist_ok=True)
    try:
        with open_archive(path) as zf:
            _safe_extract(zf, tmp)
        with open(os.path.join(tmp, _COMPLETE_MARKER), "w") as handle:
            handle.write(digest)
        os.replace(tmp, dest)
    except Exception:
        shutil.rmtree(tmp, ignore_errors=True)
        raise
    _link_reference_dir(plugins_dir, plugin_id, dest)
    return digest, dest


def reference_dir(plugins_dir: str, plugin_id: str) -> str:
    return os.path.join(plugins_dir, REFERENCE_DIRNAME, plugin_id)


def module_root(plugins_dir: str, plugin_id: str, extract_dir: str) -> str:
    link = reference_dir(plugins_dir, plugin_id)
    try:
        if os.path.realpath(link) == os.path.realpath(extract_dir):
            return link
    except OSError:
        pass
    return extract_dir


def _link_reference_dir(plugins_dir: str, plugin_id: str, dest: str) -> None:
    link = reference_dir(plugins_dir, plugin_id)
    try:
        os.makedirs(os.path.dirname(link), exist_ok=True)
        if os.path.realpath(link) == os.path.realpath(dest):
            return
        tmp_link = link + f".tmp{os.getpid()}"
        _drop_path(tmp_link)
        os.symlink(dest, tmp_link)
        os.replace(tmp_link, link)
    except OSError:
        _drop_path(link)
        try:
            shutil.copytree(dest, link)
        except OSError:
            pass


def _drop_path(path: str) -> None:
    if os.path.islink(path) or os.path.isfile(path):
        try:
            os.unlink(path)
        except OSError:
            pass
    else:
        shutil.rmtree(path, ignore_errors=True)


def cleanup_stale_extractions(plugins_dir: str, plugin_id: str, keep_digest: str) -> None:
    """Remove extraction dirs of previous content versions (after a reload)."""
    base = os.path.join(plugins_dir, EXTRACTED_DIRNAME, plugin_id)
    keep = keep_digest[:16]
    if not os.path.isdir(base):
        return
    for entry in os.listdir(base):
        if entry != keep:
            shutil.rmtree(os.path.join(base, entry), ignore_errors=True)


def purge_plugin_dirs(plugins_dir: str, plugin_id: str) -> None:
    """Remove every on-disk artifact of a plugin (used by uninstall)."""
    shutil.rmtree(os.path.join(plugins_dir, EXTRACTED_DIRNAME, plugin_id),
                  ignore_errors=True)
    shutil.rmtree(os.path.join(plugins_dir, LOCAL_LIBS_DIRNAME, plugin_id),
                  ignore_errors=True)
    _drop_path(reference_dir(plugins_dir, plugin_id))


# Bundled wheels

def process_wheels(extract_dir: str, refmap: Dict[str, str],
                   plugins_dir: str, plugin_id: str) -> List[str]:
    """Extract bundled .whl files into plugin-local lib storage.

    Per the spec: only direct .whl children of the refmap `wheels` directory are
    considered; a wheel already extracted under the same filename stem is
    skipped; stale extracted dirs absent from the current release are removed.

    Returns the list of extracted wheel directories (to be appended to the
    plugin's namespace search path — never to global sys.path).
    """
    declared = refmap.get("wheels")
    if not declared:
        wheels: List[str] = []
    else:
        wheels_dir = os.path.join(extract_dir, declared.strip("/"))
        if not os.path.isdir(wheels_dir):
            raise ElyxArchiveError(
                f"wheels directory {declared!r} declared in refmap does not exist"
            )
        wheels = sorted(
            os.path.join(wheels_dir, name)
            for name in os.listdir(wheels_dir)
            if name.endswith(".whl")
            and os.path.isfile(os.path.join(wheels_dir, name))
        )

    libs_root = os.path.join(plugins_dir, LOCAL_LIBS_DIRNAME, plugin_id)
    expected_stems = {os.path.splitext(os.path.basename(w))[0] for w in wheels}
    extracted: List[str] = []

    if wheels:
        os.makedirs(libs_root, exist_ok=True)
    for wheel_path in wheels:
        stem = os.path.splitext(os.path.basename(wheel_path))[0]
        dest = os.path.join(libs_root, stem)
        marker = os.path.join(dest, _WHEEL_MARKER)
        if not os.path.isfile(marker):
            tmp = dest + f".tmp{os.getpid()}"
            shutil.rmtree(tmp, ignore_errors=True)
            shutil.rmtree(dest, ignore_errors=True)
            os.makedirs(tmp, exist_ok=True)
            try:
                with zipfile.ZipFile(wheel_path) as zf:
                    _safe_extract(zf, tmp)  # wheels get the same traversal guard
                with open(os.path.join(tmp, _WHEEL_MARKER), "w") as handle:
                    handle.write(stem)
                os.replace(tmp, dest)
            except Exception:
                shutil.rmtree(tmp, ignore_errors=True)
                raise ElyxArchiveError(
                    f"cannot extract bundled wheel {os.path.basename(wheel_path)!r}"
                )
        extracted.append(dest)

    # Remove stale extractions from previous releases of this plugin.
    if os.path.isdir(libs_root):
        for entry in os.listdir(libs_root):
            if entry not in expected_stems:
                shutil.rmtree(os.path.join(libs_root, entry), ignore_errors=True)

    return extracted
