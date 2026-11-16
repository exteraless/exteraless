from contextlib import contextmanager
import threading
from typing import Any, Callable, Dict, Optional

import sys


def _current_plugin_id() -> Optional[str]:
    try:
        import importlib
        return importlib.import_module("extera_utils.plugin_loader").caller_plugin_id()
    except Exception:
        return None


def _report_error(plugin_id: str, error: BaseException) -> None:
    try:
        from app.exteraless.plugins import PythonBridge
        PythonBridge.log(plugin_id, f"[ERROR] cleanup failed: {error!r}")
    except Exception:
        print(f"[plugin:{plugin_id}] cleanup failed: {error!r}", file=sys.stderr)


class PluginScope:
    def __init__(self, plugin_id: str):
        self.plugin_id = str(plugin_id or "plugin")
        self.stop_event = threading.Event()
        self._cleanups: list = []
        self._lock = threading.RLock()

    @property
    def stopped(self) -> bool:
        return self.stop_event.is_set()

    def register_cleanup(self, cleanup: Callable[[], Any]) -> Callable[[], None]:
        if not callable(cleanup):
            raise TypeError("cleanup must be callable")
        with self._lock:
            if self.stop_event.is_set():
                stopped_now = True
            else:
                stopped_now = False
                self._cleanups.append(cleanup)
        if stopped_now:
            try:
                cleanup()
            except Exception as e:
                _report_error(self.plugin_id, e)
            return lambda: None

        def unregister() -> None:
            with self._lock:
                try:
                    self._cleanups.remove(cleanup)
                except ValueError:
                    pass

        return unregister

    def close(self) -> None:
        with self._lock:
            self.stop_event.set()
            cleanups = list(reversed(self._cleanups))
            self._cleanups.clear()
        for fn in cleanups:
            try:
                fn()
            except Exception as e:
                _report_error(self.plugin_id, e)


class ManagedCallback:
    def __init__(self, fn: Callable, scope: Optional[PluginScope] = None, *,
                 once: bool = False, allow_stopped: bool = False, cleanup_task: bool = False):
        self.fn = fn
        self.scope = scope
        self.once = bool(once)
        self.allow_stopped = bool(allow_stopped)
        self.cleanup_task = bool(cleanup_task)
        self._cancelled = False
        self._cancel_fn: Optional[Callable[[], Any]] = None
        self._unreg: Optional[Callable[[], None]] = None
        if scope is not None:
            self._unreg = scope.register_cleanup(self.cancel)

    @property
    def cancelled(self) -> bool:
        if self._cancelled:
            return True
        if not self.allow_stopped and self.scope is not None and self.scope.stopped:
            return True
        return False

    def set_cancel(self, cancel: Optional[Callable[[], Any]]) -> None:
        self._cancel_fn = cancel
        if self._cancelled and callable(cancel):
            try:
                cancel()
            except Exception:
                pass

    def cancel(self) -> None:
        if self._cancelled:
            return
        self._cancelled = True
        if self._unreg is not None:
            self._unreg()
            self._unreg = None
        if callable(self._cancel_fn):
            fn, self._cancel_fn = self._cancel_fn, None
            try:
                fn()
            except Exception:
                pass

    def __call__(self, *args, **kwargs):
        if self.cancelled:
            return None
        if self.once:
            self._cancelled = True
            if self._unreg is not None:
                self._unreg()
                self._unreg = None
        return self.fn(*args, **kwargs)


_SCOPES: Dict[str, PluginScope] = {}
_LOCK = threading.RLock()
_ACTIVE = threading.local()


def begin(plugin_id: str) -> PluginScope:
    key = str(plugin_id or "plugin")
    with _LOCK:
        old = _SCOPES.get(key)
        if old is not None and not old.stopped:
            return old
        scope = PluginScope(key)
        _SCOPES[key] = scope
        return scope


def get_scope(plugin_id: Optional[str] = None) -> Optional[PluginScope]:
    cur = getattr(_ACTIVE, "scope", None)
    if plugin_id is None and cur is not None:
        return cur
    key = str(plugin_id or _current_plugin_id() or "")
    if not key:
        return cur
    with _LOCK:
        return _SCOPES.get(key)


def stop(plugin_id: str) -> None:
    key = str(plugin_id or "plugin")
    with _LOCK:
        scope = _SCOPES.pop(key, None)
    if scope is not None:
        scope.close()


def stop_all() -> None:
    with _LOCK:
        scopes = list(_SCOPES.values())
        _SCOPES.clear()
    for scope in scopes:
        scope.close()


def capture_scope(fn: Optional[Callable] = None) -> Optional[PluginScope]:
    return get_scope()


def bind(fn: Callable, *, once: bool = False) -> ManagedCallback:
    if isinstance(fn, ManagedCallback):
        return fn
    return ManagedCallback(fn, get_scope(), once=once)


def bind_task(fn: Callable, *, once: bool = False, scope: Optional[PluginScope] = None) -> ManagedCallback:
    if isinstance(fn, ManagedCallback):
        return fn
    return ManagedCallback(fn, scope if scope is not None else get_scope(), once=once, cleanup_task=True)


def bind_request(fn: Callable) -> ManagedCallback:
    if isinstance(fn, ManagedCallback):
        return fn
    return ManagedCallback(fn, get_scope(), once=True)


@contextmanager
def cleanup_context(scope: Optional[PluginScope]):
    prev = getattr(_ACTIVE, "scope", None)
    _ACTIVE.scope = scope
    try:
        yield scope
    finally:
        _ACTIVE.scope = prev
