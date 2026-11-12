from dataclasses import fields, is_dataclass
from enum import IntEnum
import os
import sys
import threading
import time
import traceback
from typing import Any, Callable, Dict, Optional, Union


ELYX_PLUGIN_NAMESPACE = "ElyxPlugins"
__all__ = ["LogLevel", "LogRecord", "Logger", "get_logger", "dump"]


class LogLevel(IntEnum):
    VERBOSE = 2
    DEBUG = 3
    INFO = 4
    WARNING = 5
    ERROR = 6
    OFF = 100


def _coerce_level(value: Union[LogLevel, int, str]) -> LogLevel:
    if isinstance(value, LogLevel):
        return value
    if isinstance(value, bool):
        raise TypeError(f"Invalid log level type: {type(value).__name__}")
    if isinstance(value, int):
        try:
            return LogLevel(value)
        except ValueError:
            raise ValueError(f"Unknown log level: {value!r}") from None
    if isinstance(value, str):
        key = value.strip().upper()
        if key in LogLevel.__members__:
            return LogLevel[key]
        raise ValueError(f"Unknown log level: {value!r}")
    raise TypeError(f"Invalid log level type: {type(value).__name__}")


def _plugin_loader():
    import importlib
    return importlib.import_module("extera_utils.plugin_loader")


def _plugin_id() -> Optional[str]:
    try:
        return _plugin_loader().caller_plugin_id()
    except Exception:
        return None


def _is_java(obj: Any) -> bool:
    return type(obj).__module__.startswith("java") or hasattr(obj, "getClass") and hasattr(type(obj), "__javaclass__")


_PRIORITIES = {
    LogLevel.VERBOSE: "v",
    LogLevel.DEBUG: "d",
    LogLevel.INFO: "i",
    LogLevel.WARNING: "w",
    LogLevel.ERROR: "e",
}


def _write(rec: "LogRecord") -> None:
    text = rec.text
    plugin_id = None
    if rec.tag.startswith("plugin."):
        plugin_id = rec.tag[len("plugin."):].split(".", 1)[0]
    try:
        from app.exteraless.plugins import PythonBridge
        if plugin_id:
            PythonBridge.log(plugin_id, f"[{rec.level.name}] {text}")
            return
    except Exception:
        pass
    try:
        from android.util import Log
        getattr(Log, _PRIORITIES.get(rec.level, "i"))(rec.tag[:23], text)
    except Exception:
        print(f"{rec.level.name[0]}/{rec.tag}: {text}", file=sys.stderr)


class LogRecord:
    def __init__(self, level: LogLevel, tag: str, message: str, location: Optional[str],
                 tb: Optional[str], created: float):
        self.level = _coerce_level(level)
        self.tag = str(tag)
        self.message = str(message)
        self.location = location
        self.traceback = tb
        self.created = float(created)

    @property
    def text(self) -> str:
        base = f"[{self.location}] {self.message}" if self.location else self.message
        return f"{base}\n{self.traceback}" if self.traceback else base

    def __repr__(self) -> str:
        return f"<LogRecord {self.level.name} {self.tag}: {self.message!r}>"


def _format_message(msg: Any, args: tuple) -> str:
    text = str(msg)
    if not args:
        return text
    try:
        if len(args) == 1 and isinstance(args[0], dict) and args[0]:
            return text % args[0]
        return text % args
    except Exception as e:
        return f"{text} (formatting failed: {e})"


def _format_exc(exc_info: Any) -> Optional[str]:
    if not exc_info:
        return None
    if isinstance(exc_info, BaseException):
        return "".join(traceback.format_exception(type(exc_info), exc_info, exc_info.__traceback__)).rstrip()
    if isinstance(exc_info, tuple) and len(exc_info) == 3:
        if exc_info[0] is None:
            return None
        return "".join(traceback.format_exception(*exc_info)).rstrip()
    ei = sys.exc_info()
    if ei[0] is None:
        return None
    return "".join(traceback.format_exception(*ei)).rstrip()


def _caller_location(stacklevel: int) -> Optional[str]:
    try:
        frame = sys._getframe(max(1, int(stacklevel)))
        fn = os.path.basename(frame.f_code.co_filename or "?")
        return f"{fn}:{frame.f_lineno}"
    except Exception:
        return None


class _Timer:
    def __init__(self, logger: "Logger", label: str, level: LogLevel):
        self._logger = logger
        self._label = label
        self._level = level
        self._t0 = 0.0

    def __enter__(self) -> "_Timer":
        self._t0 = time.perf_counter()
        return self

    def __exit__(self, exc_type, exc, tb) -> bool:
        ms = (time.perf_counter() - self._t0) * 1000.0
        if exc_type is None:
            self._logger.log(self._level, "%s took %.1f ms", self._label, ms, stacklevel=2)
        else:
            self._logger.log(self._level, "%s failed after %.1f ms", self._label, ms,
                             exc_info=(exc_type, exc, tb), stacklevel=2)
        return False


_IN_HANDLER = threading.local()


class Logger:
    def __init__(self, tag: str, *, show_location: bool = True):
        self._tag = str(tag)
        self._show_location = bool(show_location)
        self._level: Optional[LogLevel] = None
        self._parent: Optional["Logger"] = None
        self._children: Dict[str, "Logger"] = {}
        self._handlers: list = []
        self._lock = threading.RLock()

    @property
    def tag(self) -> str:
        return self._tag

    @property
    def level(self) -> Optional[LogLevel]:
        return self._level

    @level.setter
    def level(self, value: Optional[Union[LogLevel, int, str]]) -> None:
        self._level = None if value is None else _coerce_level(value)

    @property
    def effective_level(self) -> LogLevel:
        cur: Optional[Logger] = self
        while cur is not None:
            if cur._level is not None:
                return cur._level
            cur = cur._parent
        return LogLevel.DEBUG

    def is_enabled_for(self, level: Union[LogLevel, int, str]) -> bool:
        lvl = _coerce_level(level)
        eff = self.effective_level
        return eff < LogLevel.OFF and lvl >= eff

    def child(self, name: str) -> "Logger":
        parts = [p for p in str(name).strip(".").split(".") if p]
        cur = self
        for part in parts:
            with cur._lock:
                ch = cur._children.get(part)
                if ch is None:
                    ch = Logger(f"{cur._tag}.{part}" if cur._tag else part, show_location=cur._show_location)
                    ch._parent = cur
                    cur._children[part] = ch
                cur = ch
        return cur

    def add_handler(self, handler: Callable[[LogRecord], Any]) -> None:
        if not callable(handler):
            raise TypeError("handler must be callable")
        with self._lock:
            if handler not in self._handlers:
                self._handlers.append(handler)

    def remove_handler(self, handler: Callable[[LogRecord], Any]) -> bool:
        with self._lock:
            if handler in self._handlers:
                self._handlers.remove(handler)
                return True
        return False

    def _emit(self, lvl: LogLevel, msg: Any, args: tuple, exc_info: Any = None, stacklevel: int = 1) -> None:
        if lvl >= LogLevel.OFF or not self.is_enabled_for(lvl):
            return
        message = _format_message(msg, args)
        tb = _format_exc(exc_info)
        loc = _caller_location(int(stacklevel) + 2) if self._show_location else None
        rec = LogRecord(lvl, self._tag, message, loc, tb, time.time())
        _write(rec)
        if getattr(_IN_HANDLER, "active", False):
            return
        _IN_HANDLER.active = True
        try:
            cur: Optional[Logger] = self
            while cur is not None:
                with cur._lock:
                    handlers = list(cur._handlers)
                for h in handlers:
                    try:
                        h(rec)
                    except Exception:
                        pass
                cur = cur._parent
        finally:
            _IN_HANDLER.active = False

    def verbose(self, msg: Any, *args, exc_info: Any = None, stacklevel: int = 1) -> None:
        self._emit(LogLevel.VERBOSE, msg, args, exc_info, stacklevel)

    def debug(self, msg: Any, *args, exc_info: Any = None, stacklevel: int = 1) -> None:
        self._emit(LogLevel.DEBUG, msg, args, exc_info, stacklevel)

    def info(self, msg: Any, *args, exc_info: Any = None, stacklevel: int = 1) -> None:
        self._emit(LogLevel.INFO, msg, args, exc_info, stacklevel)

    def warning(self, msg: Any, *args, exc_info: Any = None, stacklevel: int = 1) -> None:
        self._emit(LogLevel.WARNING, msg, args, exc_info, stacklevel)

    warn = warning

    def error(self, msg: Any, *args, exc_info: Any = None, stacklevel: int = 1) -> None:
        self._emit(LogLevel.ERROR, msg, args, exc_info, stacklevel)

    def exception(self, msg: Any, *args, exc_info: Any = True, stacklevel: int = 1) -> None:
        self._emit(LogLevel.ERROR, msg, args, exc_info, stacklevel)

    def log(self, level: Union[LogLevel, int, str], msg: Any, *args,
            exc_info: Any = None, stacklevel: int = 1) -> None:
        self._emit(_coerce_level(level), msg, args, exc_info, stacklevel)

    def dump(self, obj: Any, label: Optional[str] = None, *,
             level: Union[LogLevel, int, str] = LogLevel.DEBUG,
             depth: int = 2, max_items: int = 20, stacklevel: int = 1) -> None:
        rendered = dump(obj, depth=depth, max_items=max_items)
        text = f"{label}: {rendered}" if label else rendered
        self._emit(_coerce_level(level), "%s", (text,), None, stacklevel)

    def timed(self, label: str, level: Union[LogLevel, int, str] = LogLevel.DEBUG) -> _Timer:
        return _Timer(self, str(label), _coerce_level(level))

    def __repr__(self) -> str:
        return f"<Logger {self._tag} level={self.effective_level.name}>"


def _dump_inner(obj: Any, depth: int, max_items: int, seen: set) -> str:
    if obj is None or isinstance(obj, (bool, int, float)):
        return repr(obj)
    if isinstance(obj, (str, bytes, bytearray)):
        r = repr(obj)
        return r if len(r) <= 200 else r[:197] + "..."
    if _is_java(obj):
        try:
            return f"<{obj.getClass().getName()}: {str(obj)[:160]}>"
        except Exception:
            return f"<{type(obj).__name__}>"
    oid = id(obj)
    if oid in seen:
        return "<...>"
    if depth <= 0:
        return f"<{type(obj).__name__}>"
    seen.add(oid)
    try:
        if isinstance(obj, dict):
            items = list(obj.items())
            parts = [f"{_dump_inner(k, depth - 1, max_items, seen)}: {_dump_inner(v, depth - 1, max_items, seen)}"
                     for k, v in items[:max_items]]
            if len(items) > max_items:
                parts.append(f"... +{len(items) - max_items}")
            return "{" + ", ".join(parts) + "}"
        if isinstance(obj, (list, tuple, set, frozenset)):
            seq = list(obj)
            parts = [_dump_inner(v, depth - 1, max_items, seen) for v in seq[:max_items]]
            if len(seq) > max_items:
                parts.append(f"... +{len(seq) - max_items}")
            open_b, close_b = ("(", ")") if isinstance(obj, tuple) else ("[", "]") if isinstance(obj, list) else ("{", "}")
            return open_b + ", ".join(parts) + close_b
        if is_dataclass(obj) and not isinstance(obj, type):
            flds = fields(obj)
            parts = [f"{f.name}={_dump_inner(getattr(obj, f.name, None), depth - 1, max_items, seen)}"
                     for f in flds[:max_items]]
            return f"{type(obj).__name__}(" + ", ".join(parts) + ")"
        d = getattr(obj, "__dict__", None)
        if isinstance(d, dict):
            items = [(k, v) for k, v in d.items() if not str(k).startswith("_")]
            parts = [f"{k}={_dump_inner(v, depth - 1, max_items, seen)}" for k, v in items[:max_items]]
            if len(items) > max_items:
                parts.append(f"... +{len(items) - max_items}")
            return f"{type(obj).__name__}(" + ", ".join(parts) + ")"
        return repr(obj)[:200]
    finally:
        seen.discard(oid)


def dump(obj: Any, depth: int = 2, max_items: int = 20) -> str:
    return _dump_inner(obj, max(0, int(depth)), max(1, int(max_items)), set())


_PLUGIN_LOGGERS: Dict[str, Logger] = {}
_LOGGERS_LOCK = threading.RLock()


def _plugin_logger(plugin_id: str) -> Logger:
    key = str(plugin_id or "plugin").strip() or "plugin"
    with _LOGGERS_LOCK:
        lg = _PLUGIN_LOGGERS.get(key)
        if lg is None:
            lg = Logger(f"plugin.{key}")
            _PLUGIN_LOGGERS[key] = lg
        return lg


def _reset_plugin_logger(plugin_id: str) -> None:
    key = str(plugin_id or "plugin").strip() or "plugin"
    with _LOGGERS_LOCK:
        _PLUGIN_LOGGERS.pop(key, None)


def get_logger(name: Optional[str] = None) -> Logger:
    pid = _plugin_id()
    if pid:
        base = _plugin_logger(pid)
        return base.child(name) if name else base
    return Logger(str(name).strip(".") if name else "python")
