"""Localization for Elyx plugins (PLUGINS-ELYX.md §8).

Locale files live at the refmap `strings` path (a single file or a directory).
The locale is the part after the last "_" in the file stem; a stem without an
underscore means English:

    strings_en.yml -> en      strings_ru.json -> ru
    ui_de.yaml     -> de      strings.yml     -> en

Supported formats: .yaml, .yml, .json and .py (a Python locale file is executed
and its simple, non-callable public values are collected).

Lookup fallback chain: selected locale -> English ("en") -> the key itself.
The selected locale is the Elyx language setting when set (integrators and the
dev server can override it via set_locale_override), otherwise the Android
system locale, otherwise the host locale.
"""

from __future__ import annotations

import json
import os
import zipfile
from pathlib import Path
from typing import Any, Dict, Optional, Union

import yaml

from .archive import ElyxArchiveError

SUPPORTED_EXTENSIONS = (".yaml", ".yml", ".json", ".py")
DEFAULT_LOCALE = "en"

_MISSING = object()


# Current-locale resolution

_locale_override: Optional[str] = None


def set_locale_override(locale: Optional[str]) -> None:
    """Integrator hook: force the Elyx language setting (None = system)."""
    global _locale_override
    _locale_override = locale or None


def get_current_locale() -> str:
    if _locale_override:
        return _locale_override
    # Android system locale through Chaquopy.
    try:
        from java import jclass

        language = jclass("java.util.Locale").getDefault().getLanguage()
        if language:
            return str(language)
    except Exception:
        pass
    # Host fallback.
    try:
        import locale as locale_module

        loc = locale_module.getlocale()[0] or locale_module.getdefaultlocale()[0]
        if loc:
            return str(loc).split("_")[0].split("-")[0]
    except Exception:
        pass
    return DEFAULT_LOCALE


# Loading locale files

def locale_from_filename(filename: str) -> str:
    stem = os.path.splitext(os.path.basename(filename))[0]
    if "_" in stem:
        tail = stem.rsplit("_", 1)[1]
        if tail:
            return tail
    return DEFAULT_LOCALE


def _parse_locale_bytes(name: str, data: bytes) -> Dict[str, Any]:
    from .metadata import parse_mapping_file

    return parse_mapping_file(name, data, what="localization")


def _load_locale_file(catalog: Dict[str, Dict[str, Any]], name: str, data: bytes) -> None:
    parsed = _parse_locale_bytes(name, data)
    locale = locale_from_filename(name)
    catalog.setdefault(locale, {}).update(parsed)


def load_strings_from_dir(path: Union[str, Path]) -> Dict[str, Dict[str, Any]]:
    """Load a strings file or directory from the filesystem."""
    catalog: Dict[str, Dict[str, Any]] = {}
    target = Path(path)
    if target.is_file():
        if target.suffix.lower() in SUPPORTED_EXTENSIONS:
            _load_locale_file(catalog, target.name, target.read_bytes())
        return catalog
    if not target.is_dir():
        raise ElyxArchiveError(f"strings path {path} does not exist")
    for child in sorted(target.iterdir()):
        if child.is_file() and child.suffix.lower() in SUPPORTED_EXTENSIONS:
            _load_locale_file(catalog, child.name, child.read_bytes())
    return catalog


def load_strings_from_zip(zf: zipfile.ZipFile, prefix: str) -> Optional[Dict[str, Dict[str, Any]]]:
    """Load the strings catalog directly from an open archive (scan time).

    Returns None when the declared path matches nothing (the environment then
    simply omits `strings`).
    """
    from .archive import read_member, validate_relative_path

    prefix = validate_relative_path(prefix, "refmap strings")
    catalog: Dict[str, Dict[str, Any]] = {}
    names = zf.namelist()
    if prefix in names:  # single file
        if Path(prefix).suffix.lower() in SUPPORTED_EXTENSIONS:
            _load_locale_file(catalog, os.path.basename(prefix), read_member(zf, prefix))
        return catalog or None
    dir_prefix = prefix + "/"
    for name in sorted(names):
        if not name.startswith(dir_prefix):
            continue
        rest = name[len(dir_prefix):]
        if "/" in rest or not rest:  # direct children only
            continue
        if Path(rest).suffix.lower() in SUPPORTED_EXTENSIONS:
            _load_locale_file(catalog, rest, read_member(zf, name))
    return catalog or None


# Strings

class Strings:
    """Locale-aware read-only string catalog.

    strings["title"] / strings.title / strings.get("title") / strings("title")
    are equivalent; calling also formats: strings("hello", name="Alice") and
    positional placeholders strings("coordinates", 10, 20).
    """

    def __init__(self, all_strings: Dict[str, Dict[str, Any]]):
        self._all_strings: Dict[str, Dict[str, Any]] = {
            str(locale): dict(values) for locale, values in (all_strings or {}).items()
            if isinstance(values, dict)
        }

    def __len__(self) -> int:
        return sum(len(values) for values in self._all_strings.values())

    def __contains__(self, key) -> bool:
        return any(key in values for values in self._all_strings.values())

    @property
    def locales(self):
        return tuple(sorted(self._all_strings))

    def _lookup(self, key: str, locale: Optional[str], default: Any = _MISSING) -> Any:
        candidates = (locale or get_current_locale(), DEFAULT_LOCALE)
        for candidate in candidates:
            values = self._all_strings.get(candidate)
            if values is not None and key in values:
                return values[key]
        if default is not _MISSING:
            return default
        return key  # final fallback: the key itself

    def get(self, key: str, default: Any = _MISSING) -> Any:
        return self._lookup(key, None, default)

    def get_with_locale(self, key: str, locale: Optional[str] = None,
                        default: Any = _MISSING) -> Any:
        return self._lookup(key, locale, default)

    def __getitem__(self, key: str) -> Any:
        return self._lookup(key, None)

    def __getattr__(self, name: str) -> Any:
        if name.startswith("_"):
            raise AttributeError(name)
        return self._lookup(name, None)

    def __call__(self, key: str, *args, default: Any = _MISSING,
                 locale: Optional[str] = None, **kwargs) -> Any:
        value = self._lookup(key, locale, default)
        if (args or kwargs) and isinstance(value, str):
            return value.format(*args, **kwargs)
        return value

    def pluralize(self, count: int, key: str, locale: Optional[str] = None) -> str:
        """Three-form (Slavic) pluralization: "<count> <selected form>"."""
        forms = self._lookup(key, locale)
        if isinstance(forms, str):
            forms = [forms, forms, forms]
        if not isinstance(forms, (list, tuple)) or not forms:
            forms = [key, key, key]
        n = abs(int(count))
        if n % 10 == 1 and n % 100 != 11:
            form = 0
        elif 2 <= n % 10 <= 4 and not 10 <= n % 100 <= 19:
            form = 1
        else:
            form = 2
        return f"{count} {forms[min(form, len(forms) - 1)]}"
