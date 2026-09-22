"""Static plugin metadata reader (AST-based, no module execution) — exteraless plugin SDK.

Pure Python: importable and testable on a host interpreter without Chaquopy.
"""

import ast
import copy
import json
import os
import re
import sys
from typing import Any, Dict, Optional


class PluginMetadataError(Exception):
    """Raised when a plugin's metadata block is missing or invalid."""


# Metadata keys recognized at the module top level of a plugin file.
_METADATA_KEYS = (
    "__id__", "__name__", "__description__", "__author__", "__version__",
    "__icon__", "__app_version__", "__sdk_version__", "__min_version__",
    "__beta__", "__requirements__", "__permissions__",
)

# Permission keys a plugin may declare in ``__permissions__``.
# Mirrors app.exteraless.plugins.PluginPermissions;
# the Python import hook in plugin_loader imports this tuple, so the two lists
# cannot drift apart.
PERMISSION_UI = "ui"
KNOWN_PERMISSIONS = (
    PERMISSION_UI,
    "messages.read",
    "messages.send",
    "network",
    "files",
    "intents",
    "settings",
    "hooks",
    "native",
)

_ID_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{1,31}$")


_metadata_cache: Dict[str, Any] = {}
_roots_cache: Dict[str, Any] = {}


def _file_key(path: str):
    try:
        st = os.stat(path)
    except OSError:
        return None
    return (st.st_size, st.st_mtime_ns)


def top_level_import_roots(nodes):
    for node in nodes:
        if isinstance(node, ast.Import):
            for alias in node.names:
                yield alias.name.partition(".")[0]
        elif isinstance(node, ast.ImportFrom):
            if node.level == 0 and node.module:
                yield node.module.partition(".")[0]
        elif not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            for name in ("body", "orelse", "finalbody", "handlers"):
                block = getattr(node, name, None)
                if isinstance(block, list):
                    yield from top_level_import_roots(block)


def import_roots(path: str):
    key = _file_key(path)
    cached = _roots_cache.get(path)
    if key is not None and cached is not None and cached[0] == key:
        return list(cached[1])
    with open(path, encoding="utf-8") as source:
        tree = ast.parse(source.read(), filename=path)
    roots = list(dict.fromkeys(top_level_import_roots(tree.body)))
    if key is not None:
        _roots_cache[path] = (key, tuple(roots))
    return roots


def _extract_constants(path: str) -> Dict[str, Any]:
    """Pull top-level literal assignments out of a plugin source without running it."""
    key = _file_key(path)
    try:
        with open(path, "r", encoding="utf-8") as handle:
            source = handle.read()
    except OSError as e:
        raise PluginMetadataError(f"cannot read plugin file {path!r}: {e}")

    try:
        tree = ast.parse(source, filename=path)
    except SyntaxError as e:
        raise PluginMetadataError(f"plugin file {path!r} has a syntax error: {e}")

    if key is not None:
        try:
            _roots_cache[path] = (key, tuple(dict.fromkeys(top_level_import_roots(tree.body))))
        except Exception:
            pass

    constants: Dict[str, Any] = {}
    for node in tree.body:
        target = None
        value_node = None
        if isinstance(node, ast.Assign) and len(node.targets) == 1 \
                and isinstance(node.targets[0], ast.Name):
            target = node.targets[0].id
            value_node = node.value
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) \
                and node.value is not None:
            target = node.target.id
            value_node = node.value
        if target is None or target not in _METADATA_KEYS or target in constants:
            continue
        try:
            constants[target] = ast.literal_eval(value_node)
        except (ValueError, SyntaxError, TypeError, MemoryError):
            # Dynamic / non-literal metadata is ignored by design.
            pass
    return constants


def _validate_plugin_id(plugin_id: Any) -> str:
    if plugin_id is None:
        raise PluginMetadataError("plugin metadata is missing the required __id__ constant")
    if not isinstance(plugin_id, str):
        raise PluginMetadataError(f"__id__ must be a string, got {type(plugin_id).__name__}")
    if not _ID_PATTERN.match(plugin_id):
        raise PluginMetadataError(
            f"invalid __id__ {plugin_id!r}: must be 2-32 characters, start with a letter "
            "and contain only Latin letters, digits, '_' or '-'"
        )
    if plugin_id in sys.stdlib_module_names:
        raise PluginMetadataError(
            f"__id__ {plugin_id!r} collides with a Python standard library module name"
        )
    # A plugin id that matches an importable module name is legal — the
    # reference allows it and plugin modules are loaded under their own name,
    # not merged into the global import namespace. Refusing it here rejected
    # real published plugins (e.g. "qrcode") for no gain.
    return plugin_id


def _split_bare(text: str) -> list:
    parts = []
    for chunk in text.replace("\n", ",").split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        if parts and chunk[0] in "<>=!~":
            parts[-1] = parts[-1] + "," + chunk
        else:
            parts.append(chunk)
    return parts


def _validate_permissions(declared: Any) -> list:
    """Normalise and validate ``__permissions__``.

    An unknown key is a metadata error on purpose: a typo must not silently turn
    into a missing permission.
    """
    if isinstance(declared, str):
        # Same leniency as __requirements__: a single permission is often written bare.
        declared = _split_bare(declared)
    if not isinstance(declared, (list, tuple, set, frozenset)):
        raise PluginMetadataError("__permissions__ must be a list of permission keys")
    out = []
    for item in declared:
        if not isinstance(item, str):
            raise PluginMetadataError(
                f"__permissions__ entries must be strings, got {type(item).__name__}")
        key = item.strip()
        if key not in KNOWN_PERMISSIONS:
            raise PluginMetadataError(
                f"unknown permission {item!r} in __permissions__; known keys: "
                + ", ".join(KNOWN_PERMISSIONS))
        if key not in out:
            out.append(key)
    return out


def read_metadata(path: str) -> Dict[str, Any]:
    """Read and validate plugin metadata from *path* without executing the module.

    Returns a dict with keys: id, name, description, author, version, icon,
    app_version, sdk_version, beta, requirements, permissions,
    permissions_declared.
    Raises PluginMetadataError with a human-readable message on any problem.
    """
    key = _file_key(path)
    cached = _metadata_cache.get(path)
    if key is not None and cached is not None and cached[0] == key:
        return copy.deepcopy(cached[1])
    meta = _read_metadata_uncached(path)
    if key is not None:
        _metadata_cache[path] = (key, copy.deepcopy(meta))
    return meta


def _read_metadata_uncached(path: str) -> Dict[str, Any]:
    constants = _extract_constants(path)

    plugin_id = _validate_plugin_id(constants.get("__id__"))

    name = constants.get("__name__")
    if name is None:
        raise PluginMetadataError("plugin metadata is missing the required __name__ constant")
    if not isinstance(name, str) or not name.strip():
        raise PluginMetadataError("__name__ must be a non-empty string")

    version = constants.get("__version__", "1.0")
    if not isinstance(version, str):
        version = str(version)

    app_version: Optional[str] = constants.get("__app_version__")
    min_version = constants.get("__min_version__")
    if app_version is None and min_version is not None:
        # Legacy alias: __min_version__ = "12.5.1" means __app_version__ = ">=12.5.1".
        app_version = f">={min_version}"

    requirements = constants.get("__requirements__") or []
    # Published plugins write both forms: a list, and a bare string for a single
    # dependency (``__requirements__ = "cachetools"``). Rejecting the string
    # would refuse the plugin outright, so normalise instead.
    if isinstance(requirements, str):
        requirements = _split_bare(requirements)
    if not isinstance(requirements, (list, tuple)) \
            or not all(isinstance(item, str) for item in requirements):
        raise PluginMetadataError("__requirements__ must be a string or a list of PEP 508 strings")

    declared_permissions = "__permissions__" in constants
    permissions = _validate_permissions(constants.get("__permissions__") or []) \
        if declared_permissions else []

    return {
        "id": plugin_id,
        "name": name,
        "description": constants.get("__description__") or "",
        "author": constants.get("__author__") or "",
        "version": version,
        "icon": constants.get("__icon__"),
        "app_version": app_version,
        "sdk_version": constants.get("__sdk_version__"),
        "beta": bool(constants.get("__beta__", False)),
        "requirements": list(requirements),
        # A plugin that declares nothing gets "ui" only — but only once the user has
        # consented at install time; the Java side keeps "declared nothing at all"
        # apart from "declared an empty list" through permissions_declared.
        "permissions": permissions,
        "permissions_declared": declared_permissions,
    }


def read_metadata_json(path: str) -> str:
    """Contract wrapper called from Java: always returns a JSON string."""
    try:
        meta = read_metadata(path)
        return json.dumps({"ok": True, "meta": meta}, ensure_ascii=False)
    except PluginMetadataError as e:
        return json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": f"{type(e).__name__}: {e}"},
                          ensure_ascii=False)
