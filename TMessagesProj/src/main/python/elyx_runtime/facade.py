"""The `elyx` plugin-facing facade (PLUGINS-ELYX.md §12 Public API).

Plugin code imports its environment from one stable public module:

    from elyx import assets, metainfo, refmap, settings, strings

The module object installed into sys.modules as "elyx" resolves those
plugin-bound values to the *calling* plugin (identified by walking the Python
stack for a frame whose module lives under ElyxPlugins.<id>). Static symbols
(Asset, Assets, Strings, SettingsController, LazyDict, exceptions, gen/gen2,
mvel_execute, import_module, get_environment) behave like ordinary module
attributes. Java callback proxies are built lazily so the facade imports fine
on a host interpreter.
"""

from __future__ import annotations

import sys
import traceback
import types
from typing import Any, Callable, Dict, Optional

from . import namespace as _namespace
from .assets import (
    Asset,
    AssetNotFoundException,
    Assets,
    AssetsDirNotFoundException,
)
from .localization import Strings
from .settings import SettingsController
from .utils import LazyDict

# Plugin-bound environments (owned by loader.py, keyed by plugin id)

_environments: Dict[str, Dict[str, Any]] = {}


def register_environment(plugin_id: str, environment: Dict[str, Any]) -> None:
    _environments[plugin_id] = environment


def unregister_environment(plugin_id: str) -> None:
    _environments.pop(plugin_id, None)


def environment_for(plugin_id: str) -> Optional[Dict[str, Any]]:
    return _environments.get(plugin_id)


def _calling_plugin_id() -> Optional[str]:
    """The id of the Elyx plugin whose code is on the current Python stack."""
    prefix = _namespace.NAMESPACE_ROOT + "."
    frame = sys._getframe(1)
    while frame is not None:
        module_name = frame.f_globals.get("__name__", "")
        if isinstance(module_name, str) and module_name.startswith(prefix):
            parts = module_name.split(".")
            if len(parts) >= 2 and parts[1]:
                return parts[1]
        frame = frame.f_back
    return None


# Public helpers with caller resolution

def get_environment() -> Dict[str, Any]:
    """The full environment dict of the calling plugin.

    Raises RuntimeError when called outside Elyx plugin code.
    """
    plugin_id = _calling_plugin_id()
    if plugin_id is None:
        raise RuntimeError(
            "elyx.get_environment() must be called from an Elyx plugin module"
        )
    environment = _environments.get(plugin_id)
    if environment is None:
        raise RuntimeError(f"no Elyx environment registered for plugin {plugin_id!r}")
    return environment


def import_module(name: str, package: Optional[str] = None):
    """Import *name* relative to the calling plugin; fall back to plain import."""
    plugin_id = _calling_plugin_id()
    if plugin_id is not None and name and not name.startswith("."):
        plugin_namespace = _namespace.get_namespace(plugin_id)
        if plugin_namespace is not None:
            top = name.partition(".")[0]
            if top not in _namespace.RESERVED_ROOTS \
                    and top not in sys.stdlib_module_names \
                    and _namespace.local_module_exists(plugin_namespace, name):
                import importlib

                return importlib.import_module(f"{plugin_namespace.prefix}.{name}")
    import importlib

    return importlib.import_module(name, package)


# Java callback proxies (gen / gen2 / prebuilt SAM proxies)

_proxy_cache: Dict[Any, type] = {}


def _dynamic_proxy_base(java_class):
    try:
        from java import dynamic_proxy
    except Exception:
        raise RuntimeError(
            "Java callback proxies require the Android/Chaquopy runtime"
        )
    if java_class is None:
        raise TypeError(
            "gen(): interface not found — find_class returned None. "
            "Check the class name (and that the plugin has the permission for it)."
        )
    return dynamic_proxy(java_class)


def _log_proxy_error() -> None:
    # Exceptions must not cross the Java callback boundary; log them instead.
    traceback.print_exc()


def gen(java_class, method_name: str, return_value: bool = False,
        default_value: Any = None) -> type:
    """Create (and cache) a proxy class for a single-method Java interface.

    Instances take the Python callback plus optional extra args appended to the
    Java callback arguments. Callback exceptions are logged and swallowed;
    *default_value* is returned on failure when return_value=True.
    """
    cache_key = (str(java_class), method_name, bool(return_value))
    cached = _proxy_cache.get(cache_key)
    if cached is not None:
        return cached
    base = _dynamic_proxy_base(java_class)

    def __init__(self, callback: Callable, *extra_args):
        super(proxy_class, self).__init__()
        self.__dict__["_elyx_callback"] = callback
        self.__dict__["_elyx_extra"] = extra_args

    def _invoke(self, *args):
        callback = self.__dict__["_elyx_callback"]
        extra = self.__dict__["_elyx_extra"]
        try:
            result = callback(*args, *extra)
            return result if return_value else None
        except Exception:
            _log_proxy_error()
            return default_value if return_value else None

    class_name = f"ElyxProxy_{method_name}"
    proxy_class = type(class_name, (base,), {"__init__": __init__, method_name: _invoke})
    _proxy_cache[cache_key] = proxy_class
    return proxy_class


def gen2(java_class, return_value: bool = False, **methods) -> type:
    """Create a proxy class for a multi-method Java interface."""
    base = _dynamic_proxy_base(java_class)

    def __init__(self):
        super(proxy_class, self).__init__()

    body: Dict[str, Any] = {"__init__": __init__}
    for method_name, implementation in methods.items():
        def _make(fn):
            def _method(self, *args):
                try:
                    result = fn(*args)
                    return result if return_value else None
                except Exception:
                    _log_proxy_error()
                    return None
            return _method

        body[method_name] = _make(implementation)
    proxy_class = type("ElyxProxy_multi", (base,), body)
    return proxy_class


def _jclass(binary_name: str):
    try:
        from java import jclass
    except Exception:
        raise RuntimeError(
            "Java callback proxies require the Android/Chaquopy runtime"
        )
    return jclass(binary_name)


# Prebuilt proxies: name -> (binary interface name, method, returns value)
_PROXY_SPECS = {
    "OnClickListener": ("android.view.View$OnClickListener", "onClick", False),
    "Runnable": ("java.lang.Runnable", "run", False),
    "Callback": ("org.telegram.messenger.Utilities$Callback", "run", False),
    "Callback2": ("org.telegram.messenger.Utilities$Callback2", "run", False),
    "Callback3": ("org.telegram.messenger.Utilities$Callback3", "run", False),
    "CallbackReturn": ("org.telegram.messenger.Utilities$CallbackReturn", "run", True),
}


# MVEL

_mvel_cache: Dict[str, Any] = {}


def mvel_execute(script: str, data, to_type=None, java_instance=None):
    """Compile (cached) and run an MVEL expression against a dict/HashMap."""
    try:
        from java import jclass
    except Exception:
        raise RuntimeError("mvel_execute requires the Android/Chaquopy runtime")
    mvel = jclass("org.mvel2.MVEL")
    compiled = _mvel_cache.get(script)
    if compiled is None:
        compiled = mvel.compileExpression(script)
        _mvel_cache[script] = compiled
    if isinstance(data, dict):
        hash_map = jclass("java.util.HashMap")()
        for key, value in data.items():
            hash_map.put(str(key), value)
        data = hash_map
    if java_instance is not None:
        if to_type is not None:
            return mvel.executeExpression(compiled, java_instance, data, to_type)
        return mvel.executeExpression(compiled, java_instance, data)
    if to_type is not None:
        return mvel.executeExpression(compiled, data, to_type)
    return mvel.executeExpression(compiled, data)


# The facade module object

_BOUND_NAMES = frozenset({"settings", "metainfo", "refmap", "assets", "strings"})

_SPEC_ALL = (
    "Asset",
    "AssetNotFoundException",
    "Assets",
    "AssetsDirNotFoundException",
    "Callback",
    "Callback2",
    "Callback3",
    "CallbackReturn",
    "LazyDict",
    "OnClickListener",
    "Runnable",
    "SettingsController",
    "Strings",
    "gen",
    "gen2",
    "get_environment",
    "import_module",
    "mvel_execute",
)


class _ElyxModule(types.ModuleType):
    """sys.modules['elyx']: static API + caller-resolved plugin-bound values."""

    def __getattr__(self, item: str):
        if item in _BOUND_NAMES:
            plugin_id = _calling_plugin_id()
            environment = _environments.get(plugin_id) if plugin_id else None
            if environment is not None and item in environment:
                return environment[item]
            if plugin_id is None:
                raise AttributeError(
                    f"elyx.{item} is only available from Elyx plugin code"
                )
            raise AttributeError(
                f"plugin {plugin_id!r} has no {item!r} in its Elyx environment "
                "(check the corresponding refmap key and files)"
            )
        spec = _PROXY_SPECS.get(item)
        if spec is not None:
            binary_name, method, returns_value = spec
            proxy = gen(_jclass(binary_name), method, return_value=returns_value)
            self.__dict__[item] = proxy  # cache on the module
            return proxy
        raise AttributeError(f"module 'elyx' has no attribute {item!r}")


def install_facade() -> types.ModuleType:
    """Install (or return) the `elyx` module object in sys.modules."""
    existing = sys.modules.get("elyx")
    if isinstance(existing, _ElyxModule):
        return existing
    module = _ElyxModule("elyx")
    module.__doc__ = __doc__
    module.__all__ = list(_SPEC_ALL)
    for name in (
        "Asset", "AssetNotFoundException", "Assets", "AssetsDirNotFoundException",
        "LazyDict", "SettingsController", "Strings",
        "gen", "gen2", "get_environment", "import_module", "mvel_execute",
    ):
        setattr(module, name, globals()[name])
    sys.modules["elyx"] = module
    return module
