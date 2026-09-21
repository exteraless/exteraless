"""IntentsManager — global before/after handlers for links and intents
processed by the client (PLUGINS-API.md §8.1).

Matching happens on the Java side (IntentsDispatcher); only matching events
cross into Python. A before-handler returning True aborts the remaining
handlers and the original handling; after-handlers cannot abort.

    from intents import IntentsManager as IM

    handle = IM.new_global_before_handler(
        on_chat,                    # def on_chat(id): ... — the SDK inspects
        scheme="tg", host="chat",   # the signature and passes requested names
        required_path_args_names=["id"],
    )
    handle.unhandle()               # or IM.unhandle(handle.handler_id)
"""

import inspect
import json
import threading
from dataclasses import dataclass
from typing import Any, Callable, Dict, Optional

# Callback argument names passed by position when the handler uses *args.
_STANDARD_ORDER = ("intent", "scheme", "host", "path", "query_args",
                   "action", "flags", "type", "categories")

# filtersJson schema (consumed by the Java IntentsDispatcher):
#   schemes, hosts, path_template, query_args, action, type, categories,
#   whitelist_flags, blacklist_flags
_FILTER_KEYS = {
    "scheme": "schemes",
    "schemes": "schemes",
    "host": "hosts",
    "hosts": "hosts",
    "path": "path_template",
    "path_template": "path_template",
    "required_path_args_names": "query_args",
    "query_args": "query_args",
    "action": "action",
    "type": "type",
    "categories": "categories",
    "whitelist_flags": "whitelist_flags",
    "blacklist_flags": "blacklist_flags",
    "flags": "whitelist_flags",
}

_LIST_VALUED = {"schemes", "hosts", "query_args", "categories"}


def _build_filters(filters: Dict[str, Any]) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    for key, value in filters.items():
        target = _FILTER_KEYS.get(key)
        if target is None or value is None:
            continue
        if target in _LIST_VALUED and not isinstance(value, (list, tuple)):
            value = [value]
        elif isinstance(value, (list, tuple)):
            value = list(value)
        out[target] = value
    return out


def _context_to_dict(context) -> Dict[str, Any]:
    """Accept a java.util.Map (Chaquopy) or a plain dict; return a dict."""
    if context is None:
        return {}
    if isinstance(context, dict):
        return dict(context)
    try:  # java.util.Map
        return {str(key): context.get(key) for key in context.keySet()}
    except Exception:
        pass
    try:
        return dict(context)
    except Exception:
        return {}


def _flatten_context(context: Dict[str, Any]) -> Dict[str, Any]:
    """Flatten query args and path variables to top level (by name)."""
    flat = dict(context)
    for key in ("query_args", "path_args"):
        nested = _context_to_dict(context.get(key)) if context.get(key) is not None else {}
        for name, value in nested.items():
            flat.setdefault(str(name), value)
    return flat


def _invoke(fn: Callable, context: Dict[str, Any]):
    """Call *fn* with only the argument names it declares (docs §8.1)."""
    try:
        signature = inspect.signature(fn)
    except (TypeError, ValueError):
        return fn(context)
    params = signature.parameters
    if any(p.kind == p.VAR_KEYWORD for p in params.values()):
        return fn(**context)
    if any(p.kind == p.VAR_POSITIONAL for p in params.values()):
        return fn(*[context.get(name) for name in _STANDARD_ORDER])
    kwargs = {}
    for name, param in params.items():
        if name in context:
            kwargs[name] = context[name]
        elif param.default is inspect.Parameter.empty:
            kwargs[name] = None  # requested but absent from the context
    return fn(**kwargs)


class _IntentCallback:
    """PyObject handed to Java; called with the dispatch context Map."""

    def __init__(self, fn: Callable, before: bool):
        self._fn = fn
        self._before = before

    def __call__(self, context):
        result = _invoke(self._fn, _flatten_context(_context_to_dict(context)))
        if self._before:
            return bool(result)  # True aborts the remaining handling
        return None


def _plugin_services():
    try:
        from app.exteraless.plugins import PluginServices
        return PluginServices
    except Exception:
        from extera_utils.plugin_loader import engine_java_class
        return engine_java_class("app.exteraless.plugins.PluginServices")


class IntentsManager:
    """Registry of global intent/link handlers (see module docstring)."""

    class HandlerNotRegistered(Exception):
        """Raised by unhandle() for an unknown handler id."""

    @dataclass
    class HandlerHandle:
        handler_id: str
        plugin_id: Optional[str] = None

        def unhandle(self):
            IntentsManager.unhandle(self.handler_id)

    _lock = threading.RLock()
    _handlers: Dict[str, Dict[str, Any]] = {}  # handler_id -> record
    _by_plugin: Dict[str, set] = {}            # plugin_id -> {handler_id}

    # ---- registration ----

    @staticmethod
    def new_global_before_handler(fn: Optional[Callable] = None, *,
                                  priority: int = 0, **filters):
        """Register a before-handler; usable as a plain call or a decorator."""
        return IntentsManager._register(True, fn, priority, filters)

    @staticmethod
    def new_global_after_handler(fn: Optional[Callable] = None, *,
                                 priority: int = 0, **filters):
        """Register an after-handler; usable as a plain call or a decorator."""
        return IntentsManager._register(False, fn, priority, filters)

    @staticmethod
    def _register(before: bool, fn: Optional[Callable], priority: int,
                  filters: Dict[str, Any]):
        if fn is None:
            return lambda real_fn: IntentsManager._register(before, real_fn,
                                                            priority, filters)
        if not callable(fn):
            raise TypeError("handler must be callable")
        from extera_utils import plugin_loader
        plugin_id = plugin_loader.current_plugin_id()
        if not plugin_id:
            raise RuntimeError(
                "IntentsManager handlers must be registered from a plugin "
                "context (on_plugin_load / a hook callback)")
        PluginServices = _plugin_services()

        filters_json = json.dumps(_build_filters(filters), ensure_ascii=False)
        handler_id = PluginServices.registerIntentHandler(
            plugin_id, bool(before), filters_json, int(priority),
            _IntentCallback(fn, before))
        if handler_id is None:
            raise RuntimeError("registerIntentHandler failed on the Java side")
        handler_id = str(handler_id)
        with IntentsManager._lock:
            IntentsManager._handlers[handler_id] = {
                "plugin_id": plugin_id, "before": bool(before),
            }
            IntentsManager._by_plugin.setdefault(plugin_id, set()).add(handler_id)
        return IntentsManager.HandlerHandle(handler_id=handler_id,
                                            plugin_id=plugin_id)

    # ---- removal ----

    @staticmethod
    def unhandle(handler_id: str):
        handler_id = str(handler_id)
        with IntentsManager._lock:
            record = IntentsManager._handlers.pop(handler_id, None)
            if record is None:
                raise IntentsManager.HandlerNotRegistered(
                    f"no intent handler registered as {handler_id!r}")
            bucket = IntentsManager._by_plugin.get(record["plugin_id"])
            if bucket is not None:
                bucket.discard(handler_id)
        try:
            _plugin_services().unregisterIntentHandler(record["plugin_id"], handler_id)
        except Exception:
            pass

    # ---- URL parsing helper ----

    @staticmethod
    def parse(url: str) -> Dict[str, Any]:
        """Parse *url* into {"scheme", "host", "path", "query_args"}."""
        from urllib.parse import parse_qsl, urlsplit

        parts = urlsplit(str(url))
        return {
            "scheme": parts.scheme or None,
            "host": parts.netloc or None,
            "path": parts.path or None,
            "query_args": dict(parse_qsl(parts.query)),
        }


# Called from BasePlugin._cleanup_resources() on plugin unload.
def _unhandle_all_for_plugin(plugin_id: str):
    if not plugin_id:
        return
    with IntentsManager._lock:
        ids = list(IntentsManager._by_plugin.pop(plugin_id, ()))
    for handler_id in ids:
        IntentsManager._handlers.pop(handler_id, None)
    if not ids:
        return
    try:
        PluginServices = _plugin_services()
        for handler_id in ids:
            try:
                PluginServices.unregisterIntentHandler(plugin_id, handler_id)
            except Exception:
                pass
    except Exception:
        pass
