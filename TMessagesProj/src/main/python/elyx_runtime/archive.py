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
import re
import shutil
import stat
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

MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_ARCHIVE_ENTRIES = 4096
MAX_MEMBER_BYTES = 64 * 1024 * 1024
MAX_EXPANDED_BYTES = 256 * 1024 * 1024
MAX_DATA_MEMBER_BYTES = 1024 * 1024
MAX_PATH_LENGTH = 512

_PATH_REFMAP_KEYS = frozenset({"main", "metainfo", "strings", "locales", "assets", "wheels"})


# ZIP basics

def open_archive(path: str) -> zipfile.ZipFile:
    """Open *path* as a ZIP, raising a clear error for non-archives."""
    try:
        if os.path.getsize(path) > MAX_ARCHIVE_BYTES:
            raise ElyxArchiveError(
                f"archive exceeds the {MAX_ARCHIVE_BYTES // (1024 * 1024)} MiB limit")
        archive = zipfile.ZipFile(path)
        try:
            _validate_archive(archive)
        except Exception:
            archive.close()
            raise
        return archive
    except zipfile.BadZipFile as e:
        raise ElyxArchiveError(f"{path!r} is not a valid ZIP/Elyx archive: {e}")
    except OSError as e:
        raise ElyxArchiveError(f"cannot read archive {path!r}: {e}")


def archive_digest(path: str) -> str:
    """SHA-256 of the archive file; used as the content-addressed stamp."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_member(zf: zipfile.ZipFile, name: str,
                max_bytes: int = MAX_DATA_MEMBER_BYTES) -> bytes:
    try:
        info = zf.getinfo(name)
    except KeyError:
        raise ElyxArchiveError(f"archive member {name!r} not found")
    if info.file_size > max_bytes:
        raise ElyxArchiveError(
            f"archive member {name!r} exceeds the {max_bytes}-byte data limit")
    with zf.open(info) as handle:
        data = handle.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise ElyxArchiveError(
            f"archive member {name!r} exceeds the {max_bytes}-byte data limit")
    return data


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
        name = validate_relative_path(str(declared), "refmap metainfo")
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
        key_text = str(key)
        if key_text in _PATH_REFMAP_KEYS:
            value = validate_relative_path(value, f"refmap {key_text}")
        refmap[key_text] = value
    return refmap


def load_refmap(zf: zipfile.ZipFile) -> Dict[str, str]:
    member = find_refmap_member(zf)
    return parse_refmap(member, read_member(zf, member))


# Safe extraction

def validate_relative_path(value: str, what: str = "path") -> str:
    """Return a normalized POSIX-relative path or reject unsafe pointers."""
    if not isinstance(value, str) or not value or "\x00" in value:
        raise ElyxArchiveError(f"invalid {what}: {value!r}")
    normalized = value.replace("\\", "/")
    if len(normalized) > MAX_PATH_LENGTH:
        raise ElyxArchiveError(f"{what} is too long")
    if normalized.startswith("/") or normalized.startswith("//") \
            or re.match(r"^[A-Za-z]:", normalized):
        raise ElyxArchiveError(f"unsafe absolute {what}: {value!r}")
    parts = normalized.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ElyxArchiveError(f"unsafe traversal in {what}: {value!r}")
    return "/".join(parts)


def resolve_under(root: str, value: str, what: str = "path") -> str:
    """Resolve a validated pointer and prove it remains under *root*."""
    relative = validate_relative_path(value, what)
    base = os.path.realpath(root)
    candidate = os.path.realpath(os.path.join(base, *relative.split("/")))
    try:
        contained = os.path.commonpath((base, candidate)) == base
    except ValueError:
        contained = False
    if not contained:
        raise ElyxArchiveError(f"{what} escapes the plugin root: {value!r}")
    return candidate


def _check_member_safety(name: str) -> None:
    """Reject absolute paths and path traversal (zip-slip) in member names."""
    validate_relative_path(name.rstrip("/"), "archive member")


def _validate_archive(zf: zipfile.ZipFile) -> None:
    infos = zf.infolist()
    if len(infos) > MAX_ARCHIVE_ENTRIES:
        raise ElyxArchiveError(f"archive contains more than {MAX_ARCHIVE_ENTRIES} entries")
    total = 0
    seen = set()
    for info in infos:
        _check_member_safety(info.filename)
        normalized = info.filename.replace("\\", "/").rstrip("/").casefold()
        if normalized in seen:
            raise ElyxArchiveError(f"duplicate archive member: {info.filename!r}")
        seen.add(normalized)
        if info.flag_bits & 0x1:
            raise ElyxArchiveError(f"encrypted archive member is unsupported: {info.filename!r}")
        mode = (info.external_attr >> 16) & 0xFFFF
        file_type = stat.S_IFMT(mode)
        if file_type and not (stat.S_ISREG(mode) or stat.S_ISDIR(mode)):
            raise ElyxArchiveError(f"special archive member is unsupported: {info.filename!r}")
        if info.file_size > MAX_MEMBER_BYTES:
            raise ElyxArchiveError(f"archive member is too large: {info.filename!r}")
        total += info.file_size
        if total > MAX_EXPANDED_BYTES:
            raise ElyxArchiveError(
                f"archive expands beyond {MAX_EXPANDED_BYTES // (1024 * 1024)} MiB")


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
        wheels_dir = resolve_under(extract_dir, declared, "refmap wheels")
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
