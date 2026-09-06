"""Load/unload orchestration for Elyx structured plugins.

Public contract consumed by extera_utils.plugin_loader (which owns the plugin
registry, lifecycle calls and error JSON):

    load_plugin_record(record, path)   - extract, parse, import, instantiate,
                                         attach module+instance to the record
    unload_plugin_record(record)       - evict ElyxPlugins.<id>.* modules,
                                         drop the namespace and environment
    is_available()                     - True

Load pipeline (per PLUGINS-ELYX.md):
  1. read refmap + metainfo + strings catalog straight from the ZIP;
  2. extract the archive content-addressed under
     <plugins_dir>/.elyx_extracted/<plugin_id>/<sha256[:16]>/;
  3. extract bundled wheels into <plugins_dir>/elyx_local_libs/<plugin_id>/;
  4. register the isolated namespace ElyxPlugins.<plugin_id> with search path
     [extracted archive] + [extracted wheels] (never global sys.path);
  5. register the plugin-bound elyx environment (settings/metainfo/refmap/
     assets/strings) — needed *before* the entry module executes;
  6. import the entry module as ElyxPlugins.<id>.<module path from refmap main>;
  7. instantiate the first BasePlugin subclass, _attach() it, store both on
     the record exactly like plugin_loader does for .py plugins.
"""

from __future__ import annotations

import importlib
import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from . import archive, facade, metadata, namespace
from .assets import Assets
from .errors import (
    ElyxError,
    MainModuleNotFoundError,
    PluginClassNotFoundError,
)
from .localization import Strings, load_strings_from_dir
from .settings import SettingsController


@dataclass
class ElyxPluginState:
    """Internal runtime state of one loaded Elyx plugin."""
    plugin_id: str
    archive_path: str
    plugins_dir: str
    digest: str
    extract_dir: str
    wheel_dirs: List[str]
    refmap: Dict[str, str]
    metainfo: Dict[str, Any]
    namespace: namespace.PluginNamespace
    environment: Dict[str, Any]
    module: Any = None


_states: Dict[str, ElyxPluginState] = {}


def get_state(plugin_id: str) -> Optional[ElyxPluginState]:
    return _states.get(plugin_id)


# Path resolution

def plugins_dir_for(path: str) -> str:
    """The host plugins dir: authoritative via PythonBridge, else the
    directory containing the plugin file (mirrors how Java passes paths)."""
    try:
        from app.exteraless.plugins import PythonBridge

        plugins_dir = PythonBridge.getPluginsDir()
        if plugins_dir:
            return str(plugins_dir)
    except Exception:
        pass
    return os.path.dirname(os.path.abspath(path))


# Record plumbing (defensive: record may be the PluginRecord dataclass, any
# attribute object, or a plain dict)

def _record_get(record, name: str) -> Any:
    if isinstance(record, dict):
        return record.get(name)
    return getattr(record, name, None)


def _record_set(record, name: str, value: Any) -> None:
    if isinstance(record, dict):
        record[name] = value
        return
    try:
        setattr(record, name, value)
    except Exception:
        pass


def _plugin_id_of(record) -> Optional[str]:
    instance = _record_get(record, "instance")
    if instance is not None:
        plugin_id = getattr(instance, "_plugin_id", None) \
            or getattr(instance, "plugin_id", None)
        if plugin_id:
            return str(plugin_id)
    module = _record_get(record, "module")
    module_name = getattr(module, "__name__", None)
    if isinstance(module_name, str) \
            and module_name.startswith(namespace.NAMESPACE_ROOT + "."):
        parts = module_name.split(".")
        if len(parts) >= 2:
            return parts[1]
    plugin_id = _record_get(record, "id") or _record_get(record, "plugin_id")
    return str(plugin_id) if plugin_id else None


# Entry module / plugin class

def _strings_declared(refmap: Dict[str, str]) -> Optional[str]:
    return refmap.get("strings") or refmap.get("locales")


def _entry_dirs(refmap: Dict[str, str], extract_dir: str) -> List[str]:
    """Каталог entry-модуля, если он лежит не в корне архива.

    Документация обещает точке входа «привычные импорты» соседних модулей
    (``from helpers import ...``). Для корневого main.py это даёт корень архива,
    а для вложенного (``main: plugin/src/main.py``) — иначе не нашлось бы ничего,
    кроме относительных импортов. Корень остаётся первым в списке.
    """
    main = refmap.get("main")
    if not main:
        return []
    main = archive.validate_relative_path(main, "refmap main")
    parts = main.split("/")
    if len(parts) < 2:
        return []
    entry_dir = archive.resolve_under(
        extract_dir, "/".join(parts[:-1]), "entry directory")
    if not os.path.isdir(entry_dir) or os.path.samefile(entry_dir, extract_dir):
        return []
    return [entry_dir]


def _entry_module_name(refmap: Dict[str, str], extract_dir: str) -> str:
    """Dotted module path (relative to the plugin root) of the entry module."""
    main = refmap.get("main")
    if not main:
        if os.path.isfile(os.path.join(extract_dir, "main.py")):
            main = "main.py"
        else:
            raise MainModuleNotFoundError(
                "no entry module: refmap has no `main` key and no root main.py exists"
            )
    relative = archive.validate_relative_path(main, "refmap main")
    for suffix in (".py", ".pyc"):
        if relative.endswith(suffix):
            relative = relative[: -len(suffix)]
            break
    parts = relative.split("/")
    if not parts:
        raise MainModuleNotFoundError(f"bad `main` pointer in refmap: {main!r}")
    entry_path = archive.resolve_under(extract_dir, "/".join(parts), "refmap main")
    if namespace._resolve_at(entry_path) not in ("source", "bytecode"):
        raise MainModuleNotFoundError(
            f"Main file not found: {main!r} (paths are relative to the archive "
            "root and case-sensitive)"
        )
    return ".".join(parts)


def _base_plugin_class():
    try:
        from base_plugin import BasePlugin

        return BasePlugin
    except Exception as e:
        raise ElyxError(
            f"cannot import base_plugin.BasePlugin: {type(e).__name__}: {e}"
        )


def _find_plugin_class(module) -> type:
    """The first concrete BasePlugin subclass of the entry module.

    Prefers classes defined in the entry module itself; falls back to the
    first subclass found in its namespace (spec: "only the first BasePlugin
    subclass found in the entry module is instantiated").
    """
    base_plugin = _base_plugin_class()
    imported_fallback = None
    for obj in vars(module).values():
        if isinstance(obj, type) and issubclass(obj, base_plugin) \
                and obj is not base_plugin:
            if obj.__module__ == module.__name__:
                return obj
            if imported_fallback is None:
                imported_fallback = obj
    if imported_fallback is not None:
        return imported_fallback
    raise PluginClassNotFoundError(
        f"no BasePlugin subclass defined in entry module {module.__name__!r}"
    )


# Environment construction

def _resolve_declared_dir(refmap: Dict[str, str], key: str, extract_dir: str,
                          *, autodetect: Optional[str] = None,
                          required_when_declared: bool = True) -> Optional[str]:
    declared = refmap.get(key)
    if not declared and autodetect:
        candidate = os.path.join(extract_dir, autodetect)
        if os.path.isdir(candidate):
            declared = autodetect
    if not declared:
        return None
    path = archive.resolve_under(extract_dir, declared, f"refmap {key}")
    if not os.path.isdir(path):
        if required_when_declared:
            raise archive.ElyxArchiveError(
                f"directory {declared!r} declared in refmap key {key!r} does not exist"
            )
        return None
    return path


def _build_environment(plugin_id: str, refmap: Dict[str, str],
                       metainfo: Dict[str, Any], extract_dir: str) -> Dict[str, Any]:
    environment: Dict[str, Any] = {
        "settings": SettingsController(plugin_id),
        "metainfo": metainfo,
        "refmap": dict(refmap),
    }

    assets_dir = _resolve_declared_dir(refmap, "assets", extract_dir, autodetect="assets")
    if assets_dir is not None:
        environment["assets"] = Assets(assets_dir)

    strings_declared = _strings_declared(refmap)
    if strings_declared:
        strings_path = archive.resolve_under(
            extract_dir, strings_declared, "refmap strings")
        if os.path.exists(strings_path):
            catalog = load_strings_from_dir(strings_path)
            if catalog:
                environment["strings"] = Strings(catalog)
    return environment


# Public contract

def is_available() -> bool:
    return True


def load_plugin_record(record, path: str) -> None:
    """Load an .elyx/.eaf plugin from *path* and attach it to *record*.

    Raises ElyxError subclasses with clear messages on malformed archives
    (missing refmap/metainfo, bad root pointer, missing plugin class).
    """
    path = os.path.abspath(path)
    plugins_dir = plugins_dir_for(path)

    # 1. Read everything needed for validation straight from the archive.
    with archive.open_archive(path) as zf:
        refmap = archive.load_refmap(zf)
        metainfo_member = archive.find_metainfo_member(zf, refmap)
        raw_meta = metadata.parse_mapping_file(
            metainfo_member, archive.read_member(zf, metainfo_member))
        catalog = None
        strings_declared = _strings_declared(refmap)
        if strings_declared:
            from .localization import load_strings_from_zip

            catalog = load_strings_from_zip(
                zf, archive.validate_relative_path(strings_declared, "refmap strings"))

    # 2. Normalize metadata; description placeholders use the archive strings.
    strings_for_meta = Strings(catalog) if catalog else None
    lookup = strings_for_meta.get if strings_for_meta is not None else None
    metainfo = metadata.build_metadata(raw_meta, lookup)
    plugin_id = metainfo["id"]

    # 3. Reload semantics: quietly drop a previous incarnation first.
    if plugin_id in _states or namespace.get_namespace(plugin_id) is not None:
        _teardown(plugin_id)

    # 4. Extract content-addressed; then bundled wheels.
    digest, extract_dir = archive.extract_archive(path, plugins_dir, plugin_id)
    wheel_dirs = archive.process_wheels(extract_dir, refmap, plugins_dir, plugin_id)

    # 5. Build the environment first (may fail on a bad declared directory,
    #    before anything is registered), then register namespace + environment
    #    — both must exist before the entry module executes.
    environment = _build_environment(plugin_id, refmap, metainfo, extract_dir)
    plugin_namespace = namespace.register_namespace(
        plugin_id, extract_dir,
        [extract_dir] + _entry_dirs(refmap, extract_dir) + list(wheel_dirs))
    facade.register_environment(plugin_id, environment)

    state = ElyxPluginState(
        plugin_id=plugin_id,
        archive_path=path,
        plugins_dir=plugins_dir,
        digest=digest,
        extract_dir=extract_dir,
        wheel_dirs=list(wheel_dirs),
        refmap=refmap,
        metainfo=metainfo,
        namespace=plugin_namespace,
        environment=environment,
    )
    _states[plugin_id] = state

    try:
        # 6. Import the entry module inside the isolated namespace.
        entry_name = _entry_module_name(refmap, extract_dir)
        module = importlib.import_module(f"{plugin_namespace.prefix}.{entry_name}")
        state.module = module

        # 7. Instantiate and bind the plugin class.
        plugin_class = _find_plugin_class(module)
        instance = plugin_class()
        if hasattr(instance, "_attach"):
            instance._attach(plugin_id)
    except Exception:
        _teardown(plugin_id)
        raise

    # Attach to the record exactly like plugin_loader does for .py plugins.
    _record_set(record, "module", module)
    _record_set(record, "instance", instance)
    _record_set(record, "path", path)
    _record_set(record, "metadata", metainfo)

    # Loading succeeded: drop extractions of previous content versions.
    archive.cleanup_stale_extractions(plugins_dir, plugin_id, digest)


def unload_plugin_record(record) -> None:
    """Teardown: evict ElyxPlugins.<id>.* modules, drop namespace + environment.

    Lifecycle calls (on_plugin_unload) and the plugins registry remain the
    host loader's job, exactly as for .py plugins.
    """
    plugin_id = _plugin_id_of(record)
    if plugin_id is None:
        return
    _teardown(plugin_id)


def _teardown(plugin_id: str) -> None:
    _states.pop(plugin_id, None)
    facade.unregister_environment(plugin_id)
    namespace.unregister_namespace(plugin_id)
    namespace.evict_modules(plugin_id)


def purge_plugin(plugins_dir: str, plugin_id: str) -> None:
    """Remove all on-disk artifacts of a plugin (for the integrator's uninstall)."""
    _teardown(plugin_id)
    archive.purge_plugin_dirs(plugins_dir, plugin_id)
