"""Isolated import machinery for Elyx plugins.

Every loaded Elyx plugin gets its own module namespace:

    ElyxPlugins.<plugin_id>.*

A single sys.meta_path finder maps that prefix onto the plugin's extracted
directory (plus its extracted bundled-wheel directories). Isolation properties:

  - two plugins may ship identically named modules without colliding in
    sys.modules — names always live under the plugin's prefix;
  - *absolute* imports inside plugin code (`import helpers`,
    `from src.feature import x`) are redirected into the caller's namespace by
    a per-module __import__ override injected before execution — top-level
    names that are reserved SDK/Java roots or stdlib modules are never
    redirected, and nothing is ever added to the global sys.path;
  - relative imports (`from .helpers import x`) work through the normal import
    machinery because package names resolve under the prefix;
  - besides .py/.pyc modules and (namespace) packages, JSON/YAML/YML/TXT files
    can be imported as data modules when their stem is a valid module path
    (see PLUGINS-ELYX.md §6);
  - on unload, every module under the plugin's prefix is evicted from
    sys.modules and the namespace is unregistered.
"""

from __future__ import annotations

import importlib.abc
import importlib.machinery
import importlib.util
import json
import os
import sys
import threading
import types
from typing import Dict, List, Optional

import yaml

from .utils import lazy_wrap

NAMESPACE_ROOT = "ElyxPlugins"

DATA_FILE_EXTENSIONS = (".json", ".yaml", ".yml", ".txt")

# Top-level import roots that always stay with the ordinary runtime
# (PLUGINS-ELYX.md §6 "Зарезервированные и внешние top-level модули").
RESERVED_ROOTS = frozenset({
    "android", "androidx", "base_plugin", "client_utils", "com", "de",
    "elyx", "hook_utils", "importlib", "java", "org", "ui",
})

_ORIGINAL_IMPORT = __import__


# Registry

class PluginNamespace:
    """Search state of one loaded plugin."""

    __slots__ = ("plugin_id", "root_dir", "search_paths", "prefix")

    def __init__(self, plugin_id: str, root_dir: str, search_paths: List[str]):
        self.plugin_id = plugin_id
        self.root_dir = root_dir
        self.search_paths = list(search_paths)
        self.prefix = f"{NAMESPACE_ROOT}.{plugin_id}"

    def __repr__(self) -> str:  # pragma: no cover - debug helper
        return f"PluginNamespace({self.prefix!r}, paths={self.search_paths!r})"


_lock = threading.RLock()
_namespaces: Dict[str, PluginNamespace] = {}
_finder_installed = False


def get_namespace(plugin_id: str) -> Optional[PluginNamespace]:
    return _namespaces.get(plugin_id)


def register_namespace(plugin_id: str, root_dir: str,
                       search_paths: List[str]) -> PluginNamespace:
    global _finder_installed
    with _lock:
        if not _finder_installed:
            sys.meta_path.insert(0, _ElyxFinder())
            _finder_installed = True
        namespace = PluginNamespace(plugin_id, root_dir, search_paths)
        _namespaces[plugin_id] = namespace
        importlib.invalidate_caches()
        return namespace


def unregister_namespace(plugin_id: str) -> None:
    with _lock:
        _namespaces.pop(plugin_id, None)
        importlib.invalidate_caches()


def evict_modules(plugin_id: str) -> None:
    """Remove every sys.modules entry under ElyxPlugins.<plugin_id>(.*)."""
    prefix = f"{NAMESPACE_ROOT}.{plugin_id}"
    with _lock:
        doomed = [name for name in sys.modules
                  if name == prefix or name.startswith(prefix + ".")]
        for name in doomed:
            sys.modules.pop(name, None)
        if not _namespaces:
            sys.modules.pop(NAMESPACE_ROOT, None)
        importlib.invalidate_caches()


# Module resolution helpers

def _resolve_at(node_no_ext: str) -> Optional[str]:
    """What kind of importable thing lives at *node_no_ext*, if any."""
    for init_name in ("__init__.py", "__init__.pyc"):
        if os.path.isfile(os.path.join(node_no_ext, init_name)):
            return "package"
    if os.path.isfile(node_no_ext + ".py"):
        return "source"
    if os.path.isfile(node_no_ext + ".pyc"):
        return "bytecode"
    for ext in DATA_FILE_EXTENSIONS:
        if os.path.isfile(node_no_ext + ext):
            return "data"
    if os.path.isdir(node_no_ext):
        return "namespace"
    return None


def local_module_exists(namespace: PluginNamespace, dotted_name: str) -> bool:
    """True when *dotted_name* resolves to a module inside the plugin."""
    parts = dotted_name.split(".")
    if not all(parts):
        return False
    for base in namespace.search_paths:
        node = os.path.join(base, *parts)
        if os.path.isdir(os.path.dirname(node)) and _resolve_at(node) is not None:
            return True
    return False


def _redirectable(namespace: PluginNamespace, name: str) -> bool:
    top = name.partition(".")[0]
    if top in RESERVED_ROOTS or top in sys.stdlib_module_names:
        return False
    return local_module_exists(namespace, name)


def _make_plugin_import(namespace: PluginNamespace):
    """An __import__ override that resolves local top-level imports inside the
    caller plugin's namespace and delegates everything else unchanged."""

    redirectable = {}

    def plugin_import(name, globals=None, locals=None, fromlist=(), level=0):
        if level == 0 and isinstance(name, str) and name:
            local = redirectable.get(name)
            if local is None:
                local = _redirectable(namespace, name)
                if len(redirectable) < 1024:
                    redirectable[name] = local
        else:
            local = False
        if local:
            full = f"{namespace.prefix}.{name}"
            _ORIGINAL_IMPORT(full, globals, locals, fromlist, 0)
            if not fromlist:
                return sys.modules[f"{namespace.prefix}.{name.partition('.')[0]}"]
            return sys.modules[full]
        return _ORIGINAL_IMPORT(name, globals, locals, fromlist, level)

    return plugin_import


# Loaders

class _WrappingLoader(importlib.abc.Loader):
    """Delegates to a real file loader, injecting the plugin-local __import__."""

    def __init__(self, namespace: PluginNamespace,
                 real_loader: importlib.abc.Loader):
        self._namespace = namespace
        self._real = real_loader

    def create_module(self, spec):
        create = getattr(self._real, "create_module", None)
        return create(spec) if create is not None else None

    def exec_module(self, module):
        # CPython's IMPORT_NAME resolves __import__ from the frame's builtins,
        # not from its globals — so the plugin-local import override is
        # injected through a __builtins__ dict carrying the patched function.
        import builtins as _builtins

        patched_import = _make_plugin_import(self._namespace)
        module.__dict__["__builtins__"] = {
            **vars(_builtins), "__import__": patched_import,
        }
        self._real.exec_module(module)


class _EmptyPackageLoader(importlib.abc.Loader):
    """Synthetic package body (the ElyxPlugins root and namespace dirs)."""

    def create_module(self, spec):
        return None

    def exec_module(self, module):
        return None


class _DataModule(types.ModuleType):
    """Module object for imported JSON/YAML/TXT data files."""

    def __getitem__(self, key):
        return getattr(self, key)

    def get(self, key, default=None):
        return getattr(self, key, default)


class _DataModuleLoader(importlib.abc.Loader):
    """Loads .json/.yaml/.yml/.txt files as modules (PLUGINS-ELYX.md §6)."""

    def __init__(self, path: str, ext: str):
        self._path = path
        self._ext = ext

    def create_module(self, spec):
        return _DataModule(spec.name)

    def exec_module(self, module):
        with open(self._path, "rb") as handle:
            raw = handle.read()
        if self._ext == ".txt":
            module.content = raw.decode("utf-8")
            return
        text = raw.decode("utf-8")
        data = json.loads(text) if self._ext == ".json" else yaml.safe_load(text)
        module.content = lazy_wrap(data)
        if isinstance(data, dict):
            for key, value in data.items():
                setattr(module, str(key), lazy_wrap(value))


# The meta_path finder

class _ElyxFinder(importlib.abc.MetaPathFinder):
    """Maps the ElyxPlugins.<plugin_id>.* prefix onto plugin directories."""

    def find_spec(self, fullname, path=None, target=None):
        if fullname == NAMESPACE_ROOT:
            return self._package_spec(NAMESPACE_ROOT, _EmptyPackageLoader())

        parts = fullname.split(".")
        if len(parts) < 2 or parts[0] != NAMESPACE_ROOT:
            return None
        namespace = _namespaces.get(parts[1])
        if namespace is None:
            return None
        if len(parts) == 2:
            return self._plugin_package_spec(namespace)
        return self._submodule_spec(namespace, fullname, parts[2:])

    @staticmethod
    def _package_spec(fullname: str, loader,
                      origin: Optional[str] = None) -> importlib.machinery.ModuleSpec:
        spec = importlib.machinery.ModuleSpec(fullname, loader,
                                              origin=origin, is_package=True)
        # Empty search locations on purpose: children are resolved exclusively
        # by this finder from the namespace's own search paths, so the standard
        # PathFinder can never load plugin files outside our loader wrapper.
        spec.submodule_search_locations = []
        return spec

    def _plugin_package_spec(self, namespace: PluginNamespace):
        prefix = namespace.prefix
        for init_name, factory in (("__init__.py", importlib.machinery.SourceFileLoader),
                                   ("__init__.pyc", importlib.machinery.SourcelessFileLoader)):
            init_path = os.path.join(namespace.root_dir, init_name)
            if os.path.isfile(init_path):
                loader = _WrappingLoader(namespace, factory(prefix, init_path))
                return self._package_spec(prefix, loader, origin=init_path)
        return self._package_spec(prefix, _EmptyPackageLoader())

    def _submodule_spec(self, namespace: PluginNamespace, fullname: str,
                        tail: List[str]):
        rel = os.path.join(*tail)
        for base in namespace.search_paths:
            node = os.path.join(base, rel)
            kind = _resolve_at(node)
            if kind is None:
                continue
            if kind == "package":
                for init_name, factory in (
                        ("__init__.py", importlib.machinery.SourceFileLoader),
                        ("__init__.pyc", importlib.machinery.SourcelessFileLoader)):
                    init_path = os.path.join(node, init_name)
                    if os.path.isfile(init_path):
                        loader = _WrappingLoader(namespace, factory(fullname, init_path))
                        return self._package_spec(fullname, loader, origin=init_path)
            elif kind == "source":
                path = node + ".py"
                loader = _WrappingLoader(
                    namespace, importlib.machinery.SourceFileLoader(fullname, path))
                return importlib.machinery.ModuleSpec(fullname, loader, origin=path)
            elif kind == "bytecode":
                path = node + ".pyc"
                loader = _WrappingLoader(
                    namespace, importlib.machinery.SourcelessFileLoader(fullname, path))
                return importlib.machinery.ModuleSpec(fullname, loader, origin=path)
            elif kind == "data":
                for ext in DATA_FILE_EXTENSIONS:
                    path = node + ext
                    if os.path.isfile(path):
                        return importlib.machinery.ModuleSpec(
                            fullname, _DataModuleLoader(path, ext), origin=path)
            elif kind == "namespace":
                return self._package_spec(fullname, _EmptyPackageLoader(), origin=node)
        return None
