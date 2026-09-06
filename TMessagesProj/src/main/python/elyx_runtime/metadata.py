"""metainfo.yml parsing and normalization for Elyx plugins.

Produces the same metadata dict shape that extera_utils.metadata_parser yields
for plain .py plugins (id, name, description, author, version, icon,
app_version, sdk_version, beta, requirements) plus Elyx-specific extras
(min_version, requires). Also exposes read_metadata()/read_metadata_json() so
the host plugin loader can scan .elyx/.eaf files without executing anything.

Field rules of the .elyx metadata block:
  - id and name are required; id is 2-32 chars of [A-Za-z0-9_]
  - description defaults to "Description not provided", author to
    "Unknown author", version to "1.0.0"
  - legacy `min_version: X` maps to `app_version: ">=X"` when app_version is
    absent (same aliasing metadata_parser applies to __min_version__)
  - `requirements` is a comma-separated string (or YAML list) of PEP 508 specs
  - `requires` maps plugin ids (optionally with "(min_version)" suffix) to an
    optional download URL
  - description may contain {locale_key} placeholders resolved from the
    plugin's strings catalog (current locale, then English, then the key name)
  - dunder-wrapped keys (__name__) are normalized to their plain form
"""

from __future__ import annotations

import ast
import json
import math
import re
import zipfile
from typing import Any, Callable, Dict, Optional

import yaml

from .archive import find_metainfo_member, load_refmap, open_archive, read_member
from .errors import MetainfoError

_ID_PATTERN = re.compile(r"^[A-Za-z0-9_]{2,32}$")
_PLACEHOLDER_PATTERN = re.compile(r"\{([A-Za-z0-9_]+)\}")
_REQUIRES_KEY_PATTERN = re.compile(r"^\s*([A-Za-z0-9_]{2,32})(?:\s*\(([^)]*)\))?\s*$")

_METADATA_EXTENSIONS = (".yaml", ".yml", ".json", ".py")


# Parsing

def _normalize_keys(raw: Dict[str, Any]) -> Dict[str, Any]:
    """__name__ -> name, etc. Plain keys win when both forms are present."""
    normalized: Dict[str, Any] = {}
    for key, value in raw.items():
        if not isinstance(key, str):
            continue
        plain = key
        if key.startswith("__") and key.endswith("__") and len(key) > 4:
            plain = key[2:-2]
        if plain not in normalized:
            normalized[plain] = value
    return normalized


_MAX_PYTHON_MAPPING_NODES = 10_000


def _validate_literal(value: Any, *, depth: int = 0) -> Any:
    """Accept only bounded JSON-like literal values from legacy .py data files."""
    if depth > 32:
        raise MetainfoError("Python mapping literal is nested too deeply")
    if value is None or isinstance(value, (str, bool, int)):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise MetainfoError("Python mapping floats must be finite")
        return value
    if isinstance(value, (list, tuple)):
        if len(value) > 10_000:
            raise MetainfoError("Python mapping container is too large")
        return type(value)(_validate_literal(item, depth=depth + 1) for item in value)
    if isinstance(value, dict):
        if len(value) > 10_000:
            raise MetainfoError("Python mapping container is too large")
        out: Dict[str, Any] = {}
        for key, item in value.items():
            if not isinstance(key, str):
                raise MetainfoError("Python mapping keys must be strings")
            out[key] = _validate_literal(item, depth=depth + 1)
        return out
    raise MetainfoError(f"unsupported Python mapping value {type(value).__name__}")


def _parse_python_mapping(name: str, source: str) -> Dict[str, Any]:
    """Read top-level literal assignments without executing the data file."""
    try:
        tree = ast.parse(source, filename=name)
    except (SyntaxError, ValueError, MemoryError) as e:
        raise MetainfoError(f"cannot parse Python file {name}: {type(e).__name__}: {e}")
    if sum(1 for _ in ast.walk(tree)) > _MAX_PYTHON_MAPPING_NODES:
        raise MetainfoError(f"Python file {name} is too complex")
    collected: Dict[str, Any] = {}
    for statement in tree.body:
        target = None
        value_node = None
        if isinstance(statement, ast.Assign) and len(statement.targets) == 1 \
                and isinstance(statement.targets[0], ast.Name):
            target = statement.targets[0].id
            value_node = statement.value
        elif isinstance(statement, ast.AnnAssign) and isinstance(statement.target, ast.Name) \
                and statement.value is not None:
            target = statement.target.id
            value_node = statement.value
        if target is None or target.startswith("_"):
            continue
        try:
            value = ast.literal_eval(value_node)
        except (ValueError, SyntaxError, TypeError, MemoryError) as e:
            raise MetainfoError(
                f"Python mapping field {target!r} in {name} must be a literal: {e}")
        collected[target] = _validate_literal(value)
    return collected


def parse_mapping_file(name: str, data: bytes, *, what: str = "metainfo") -> Dict[str, Any]:
    """Parse a YAML/JSON/Python file into a plain mapping."""
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as e:
        raise MetainfoError(f"{what} file {name} is not valid UTF-8: {e}")
    lowered = name.lower()
    try:
        if lowered.endswith(".json"):
            parsed = json.loads(text)
        elif lowered.endswith(".py"):
            parsed = _parse_python_mapping(name, text)
        else:
            parsed = yaml.safe_load(text)
    except MetainfoError:
        raise
    except Exception as e:
        raise MetainfoError(f"cannot parse {what} file {name}: {type(e).__name__}: {e}")
    if not isinstance(parsed, dict) or not parsed:
        raise MetainfoError(f"{what} file {name} must contain a non-empty mapping")
    return parsed


# Validation / normalization

def validate_plugin_id(plugin_id: Any) -> str:
    if not isinstance(plugin_id, str) or not _ID_PATTERN.match(plugin_id):
        raise MetainfoError(
            f"invalid plugin id {plugin_id!r}: must be 2-32 characters of "
            "ASCII letters, digits and '_'"
        )
    return plugin_id


def _parse_requirements(value: Any) -> list:
    if value is None or value == "":
        return []
    if isinstance(value, str):
        return [item.strip() for item in value.split(",") if item.strip()]
    if isinstance(value, (list, tuple)) and all(isinstance(item, str) for item in value):
        return list(value)
    raise MetainfoError(
        "requirements must be a comma-separated string or a list of PEP 508 strings"
    )


def _parse_requires(value: Any) -> Dict[str, Dict[str, Optional[str]]]:
    if value in (None, "", {}):
        return {}
    if not isinstance(value, dict):
        raise MetainfoError("requires must be a mapping of plugin_id -> download URL")
    requires: Dict[str, Dict[str, Optional[str]]] = {}
    for raw_key, raw_url in value.items():
        match = _REQUIRES_KEY_PATTERN.match(str(raw_key))
        if not match:
            raise MetainfoError(f"invalid required plugin id {raw_key!r}")
        dep_id, dep_version = match.group(1), match.group(2)
        requires[dep_id] = {
            "min_version": dep_version.strip() if dep_version else None,
            "url": str(raw_url) if raw_url else None,
        }
    return requires


def resolve_placeholders(text: str, lookup: Optional[Callable[[str], Any]]) -> str:
    """Replace {key} placeholders using *lookup* (falls back to the key name)."""
    if lookup is None or "{" not in text:
        return text

    def _replace(match: "re.Match") -> str:
        value = lookup(match.group(1))
        return match.group(1) if value is None else str(value)

    return _PLACEHOLDER_PATTERN.sub(_replace, text)


def build_metadata(raw: Dict[str, Any],
                   string_lookup: Optional[Callable[[str], Any]] = None) -> Dict[str, Any]:
    """Normalize a parsed metainfo mapping into the engine metadata shape."""
    meta = _normalize_keys(raw)

    plugin_id = validate_plugin_id(meta.get("id"))

    name = meta.get("name")
    if not isinstance(name, str) or not name.strip():
        raise MetainfoError("metainfo is missing the required non-empty `name` field")

    version = meta.get("version", "1.0.0")
    if not isinstance(version, str):
        version = str(version)

    description = meta.get("description")
    if not description:
        description = "Description not provided"
    else:
        description = resolve_placeholders(str(description), string_lookup)

    author = meta.get("author") or "Unknown author"

    min_version = meta.get("min_version")
    app_version = meta.get("app_version")
    if app_version is None and min_version is not None:
        # Legacy alias, mirroring metadata_parser's __min_version__ handling.
        app_version = f">={min_version}"

    return {
        "id": plugin_id,
        "name": name.strip(),
        "description": description,
        "author": str(author),
        "version": version,
        "icon": meta.get("icon") or None,
        "app_version": str(app_version) if app_version is not None else None,
        "sdk_version": str(meta["sdk_version"]) if meta.get("sdk_version") is not None else None,
        "min_version": str(min_version) if min_version is not None else None,
        "beta": bool(meta.get("beta", False)),
        "requirements": _parse_requirements(meta.get("requirements")),
        "requires": _parse_requires(meta.get("requires")),
    }


# Archive-level metadata reading (no extraction, no code execution)

def read_metadata(path: str, string_lookup: Optional[Callable[[str], Any]] = None) -> Dict[str, Any]:
    """Read and normalize the metadata of an .elyx/.eaf archive.

    Reads only the refmap, the metainfo file and (when no explicit
    *string_lookup* is given) the declared localization files — directly from
    the ZIP, without extracting or executing plugin code.
    """
    with open_archive(path) as zf:
        refmap = load_refmap(zf)
        metainfo_member = find_metainfo_member(zf, refmap)
        raw = parse_mapping_file(metainfo_member, read_member(zf, metainfo_member))
        if string_lookup is None:
            string_lookup = _archive_string_lookup(zf, refmap)
    return build_metadata(raw, string_lookup)


def _archive_string_lookup(zf: zipfile.ZipFile, refmap: Dict[str, str]):
    """A key lookup over the archive's localization files (current locale)."""
    from .localization import Strings, load_strings_from_zip

    declared = refmap.get("strings") or refmap.get("locales")
    if not declared:
        return None
    from .archive import validate_relative_path
    catalog = load_strings_from_zip(zf, validate_relative_path(declared, "refmap strings"))
    if not catalog:
        return None
    # Strings.get() already falls back to the key itself when missing, which
    # matches the documented placeholder behavior for absent keys.
    return Strings(catalog).get


def read_metadata_json(path: str) -> str:
    """Same contract as extera_utils.metadata_parser.read_metadata_json."""
    try:
        meta = read_metadata(path)
        return json.dumps({"ok": True, "meta": meta}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": f"{type(e).__name__}: {e}"},
                          ensure_ascii=False)
