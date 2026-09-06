"""Java-facing plugin loader: import, lifecycle, hook dispatch — exteraless plugin SDK.

The Java engine (app.exteraless.plugins.PythonPluginsEngine) calls the
module-level functions of this module via Chaquopy. All return values that
cross the bridge are JSON strings, plain strings or booleans.

User-code exceptions from hook callbacks propagate to Java intentionally —
the engine catches them and disables the offending plugin. PermissionError is
the one exception to that rule: a denied permission is not a broken plugin, so
it is logged and swallowed here.
"""

import contextlib
import hashlib
import importlib.machinery
import importlib.util
import inspect
import json
import os
import re
import sys
import threading
from collections import namedtuple
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

# Make sibling top-level modules (base_plugin, ui, ...) importable regardless
# of how the interpreter was started.
_SRC_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SRC_DIR not in sys.path:
    sys.path.insert(0, _SRC_DIR)

import client_utils
from base_plugin import AppEvent, BasePlugin, HookStrategy
from ui import settings as ui_settings

try:
    import pip_controller
    _pip_import_error = None
except Exception as _e:  # requests/packaging unavailable — pip support disabled
    pip_controller = None
    _pip_import_error = _e

from .metadata_parser import read_metadata  # noqa: F401
from .metadata_parser import read_metadata_json as _read_metadata_json_py


def read_metadata_json(path: str) -> str:
    """Java-facing metadata entry point. Routes structured Elyx archives
    (.elyx/.eaf) to elyx_runtime, plain .py/.plugin to the AST parser."""
    if str(path).endswith((".elyx", ".eaf")):
        try:
            import elyx_runtime
            return elyx_runtime.read_metadata_json(path)
        except ImportError:
            return '{"ok":false,"error":"elyx_runtime unavailable"}'
        except Exception as e:  # malformed archive etc.
            return '{"ok":false,"error":%s}' % __import__("json").dumps(str(e))
    return _read_metadata_json_py(path)

__all__ = [
    "read_metadata_json", "load_plugin", "unload_plugin", "uninstall_plugin",
    "call_app_event",
    "call_send_message_hook", "call_pre_request_hook", "call_post_request_hook",
    "call_update_hook", "call_updates_hook",
    "get_settings_json", "notify_setting_changed", "dispatch_setting_click",
    "is_loaded", "plugins", "PluginRecord", "start_dev_server",
    "plugin_context", "current_plugin_id",
    # песочница
    "caller_plugin_id", "has_permission", "require_permission", "plugin_files",
    "unsafe_mode", "set_unsafe_mode",
    "PERM_UI", "PERM_MESSAGES_READ", "PERM_MESSAGES_SEND", "PERM_NETWORK",
    "PERM_FILES", "PERM_INTENTS", "PERM_SETTINGS", "PERM_HOOKS", "PERM_NATIVE",
]


@dataclass
class PluginRecord:
    """Runtime state of a loaded plugin."""
    module: Any
    instance: BasePlugin
    path: str
    module_name: Optional[str] = None
    # Settings callback registry, rebuilt on every get_settings_json() call:
    click_callbacks: Dict[str, Callable] = field(default_factory=dict)  # callback_id -> on_click
    change_callbacks: Dict[str, Callable] = field(default_factory=dict)  # setting key -> on_change
    custom_views: Dict[str, Any] = field(default_factory=dict)  # view_id -> ui.settings.Custom


plugins: Dict[str, PluginRecord] = {}

_HookDispatchResult = namedtuple("_HookDispatchResult", ("strategy", "value"))
_VALID_STRATEGIES = frozenset({
    HookStrategy.DEFAULT, HookStrategy.CANCEL, HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL,
})


# Plugin context (which plugin's code is running on this thread)

_context_state = threading.local()


class ClassNotFoundError(Exception):
    """Плагину не дали класс. Текст совпадает с обычной ошибкой Chaquopy,
    чтобы плагин обработал это как «класса нет», а не как поломку."""

    def __init__(self, name):
        super().__init__(f"no class named {name}")


_RUNTIME_UNSET = object()
_java_runtime_class = _RUNTIME_UNSET


def _java_runtime():
    """app.exteraless.plugins.PluginRuntime или None (нет JVM / старая сборка)."""
    global _java_runtime_class
    if _java_runtime_class is _RUNTIME_UNSET:
        try:
            from app.exteraless.plugins import PluginRuntime as _Runtime
            _java_runtime_class = _Runtime
        except Exception:
            _java_runtime_class = None
    return _java_runtime_class


@contextlib.contextmanager
def java_runtime_mark(plugin_id: Optional[str]):
    """Пометить поток на Java-стороне: чей код сейчас исполняется.

    Нужно PluginSinkGate: в стеке JVM между плагином и стоком лежит Chaquopy,
    и по нему владельца не определить. Метку ставим там, где управление
    переходит к коду плагина.
    """
    runtime = _java_runtime()
    if runtime is None or plugin_id is None:
        yield
        return
    previous = None
    try:
        previous = runtime.enter(plugin_id)
    except Exception:
        yield
        return
    try:
        yield
    finally:
        try:
            runtime.exit(previous)
        except Exception:
            pass


def owner_of_function(fn) -> Optional[str]:
    """id плагина, которому принадлежит функция-колбэк (по файлу её кода)."""
    try:
        path = fn.__code__.co_filename
    except Exception:
        return None
    if not path or path.startswith("<"):
        return None
    if path in _path_owner_cache:
        return _path_owner_cache[path]
    try:
        owner = _resolve_owner(path)
    except Exception:
        owner = None
    _path_owner_cache[path] = owner
    return owner


@contextlib.contextmanager
def plugin_context(plugin_id: Optional[str]):
    """Mark *plugin_id* as the running plugin on this thread.

    FilesController / IntentsManager resolve their registering plugin from
    this; the loader sets it around load/unload and hook dispatch.
    """
    previous = getattr(_context_state, "plugin_id", None)
    _context_state.plugin_id = plugin_id
    try:
        with java_runtime_mark(plugin_id):
            yield plugin_id
    finally:
        _context_state.plugin_id = previous


def current_plugin_id() -> Optional[str]:
    return getattr(_context_state, "plugin_id", None)


def _ensure_plugins_dir_on_path() -> None:
    """Каталог плагинов — в конец sys.path.

    Оттуда импортируются и вспомогательные пакеты плагинов (zwylib пишет
    `zwylib_companion/__init__.py`), и сами плагины: установленный лежит как
    `<id>.py`, и соседи берут его по этому имени (`import zwylib`).
    Путь идёт в конец: модули SDK не должны перекрываться.
    """
    plugins_dir = _plugins_dir_path()
    if plugins_dir and plugins_dir not in sys.path:
        sys.path.append(plugins_dir)


def _plugins_dir_path() -> Optional[str]:
    try:
        import file_utils
        return file_utils.get_plugins_dir() or None
    except Exception:
        return None


def _sdk_module_names() -> frozenset:
    """Верхнеуровневые имена модулей SDK — их плагин перекрывать не вправе."""
    global _sdk_names_cache
    if _sdk_names_cache is not None:
        return _sdk_names_cache
    names = set()
    try:
        for entry in os.listdir(_SRC_DIR):
            if entry.endswith(".py"):
                names.add(entry[:-3])
            elif os.path.isdir(os.path.join(_SRC_DIR, entry)):
                names.add(entry)
    except Exception:
        pass
    _sdk_names_cache = frozenset(names)
    return _sdk_names_cache


_sdk_names_cache: Optional[frozenset] = None

_JAVA_ROOT_PACKAGES = frozenset({
    "java", "javax", "android", "androidx", "org", "com", "dalvik",
    "kotlin", "kotlinx",
})


def _module_name_for(plugin_id: str) -> str:
    """Имя плагина в sys.modules — его собственный id.

    Плагины каталога зависят друг от друга по id: material_settings_list
    начинается с `import zwylib`. Под искусственным именем такой импорт не
    находил бы уже загруженный модуль и поднимал бы из файла вторую копию —
    со своими глобалами, повторной регистрацией Java-фабрик, хуков и задач
    автообновления. Занятые имена (SDK, stdlib, пакеты Chaquopy, поставленные
    через requirements) не трогаем: id плагина не вправе подменить чужой модуль.
    """
    name = str(plugin_id)
    if not name.isidentifier():
        return "extera_plugin_" + re.sub(r"[^0-9A-Za-z_]", "_", name)
    if name in _JAVA_ROOT_PACKAGES or name in sys.stdlib_module_names \
            or name in _sdk_module_names():
        return "extera_plugin_" + name
    occupied = sys.modules.get(name)
    if occupied is not None and not _in_plugins_dir(getattr(occupied, "__file__", None)):
        return "extera_plugin_" + name
    return name


def _in_plugins_dir(path: Optional[str]) -> bool:
    root = _plugins_dir_path()
    if not path or not root:
        return False
    head = os.path.normcase(os.path.dirname(os.path.abspath(path)))
    return head == os.path.normcase(os.path.abspath(root))


def _same_file(a: Optional[str], b: Optional[str]) -> bool:
    if not a or not b:
        return False
    return os.path.normcase(os.path.abspath(a)) == os.path.normcase(os.path.abspath(b))


# Песочница: разрешения плагинов
#
# Граница честная и узкая: проверки ловят обращения ЧЕРЕЗ SDK и импорты из кода
# плагина. Плагин, который дёргает Java напрямую (`from java.lang import ...`,
# Chaquopy), проходит мимо — спецификация это признаёт («Честная граница
# применимости»). Задача — сделать намерения видимыми и ограничить случайный
# вред, а не построить границу против атакующего.

from .metadata_parser import KNOWN_PERMISSIONS, PERMISSION_UI  # noqa: F401

PERM_UI = PERMISSION_UI
PERM_MESSAGES_READ = "messages.read"
PERM_MESSAGES_SEND = "messages.send"
PERM_NETWORK = "network"
PERM_FILES = "files"
PERM_INTENTS = "intents"
PERM_SETTINGS = "settings"
PERM_HOOKS = "hooks"
PERM_NATIVE = "native"

_OUT_OF_SYNC = [p for p in (PERM_UI, PERM_MESSAGES_READ, PERM_MESSAGES_SEND,
                            PERM_NETWORK, PERM_FILES, PERM_INTENTS,
                            PERM_SETTINGS, PERM_HOOKS, PERM_NATIVE)
                if p not in KNOWN_PERMISSIONS]
if _OUT_OF_SYNC:  # набор разошёлся с metadata_parser/PluginPermissions — это баг
    print(f"[exteraless:plugin_loader] unknown permission keys {_OUT_OF_SYNC}",
          file=sys.stderr)

# Файл плагина -> его id. Заполняется ДО exec_module, иначе импорты верхнего
# уровня плагина (а их большинство) проверить было бы некому.
#
# Ключ — путь, а НЕ имя модуля: `__name__` у плагина занято метаданными
# (`__name__ = "Мой плагин"` есть у каждого второго в каталоге), так что
# f_globals["__name__"] к коду плагина отношения не имеет. co_filename кадра
# плагин переписать не может.
_owner_files: Dict[str, str] = {}
_path_owner_cache: Dict[str, Optional[str]] = {}

# Каталоги Elyx: <plugins_dir>/.elyx_extracted/<id>/<sha>/ и
# <plugins_dir>/elyx_local_libs/<id>/<wheel>/ (elyx_runtime/archive.py:38,39).
# Владелец вычисляется из самого пути — регистрировать его негде: модули
# Elyx-плагина исполняются внутри elyx_runtime.load_plugin_record, то есть
# раньше, чем управление вернётся к нам.
_ELYX_DIR_MARKERS = (".elyx_extracted", "elyx_local_libs")

# Кадры, которые НЕ считаются «тем, кто импортирует»: машинерия импорта, мы сами
# и per-module __import__ Elyx (elyx_runtime/namespace.py:_make_plugin_import) —
# он стоит между кодом плагина и нами, и без этого пропуска любой импорт
# Elyx-плагина выглядел бы как импорт из elyx_runtime.
_IMPORT_MACHINERY = frozenset({
    __name__, "importlib", "importlib._bootstrap", "importlib._bootstrap_external",
    "importlib.util", "importlib.machinery", "importlib.abc",
    "elyx_runtime.namespace",
})
_SELF_FILE = os.path.normcase(os.path.abspath(__file__))
_ELYX_NAMESPACE_FILE = os.path.normcase(os.path.join(
    _SRC_DIR, "elyx_runtime", "namespace.py"))

_MAX_FRAMES = 60

# Таблица импорт-хука (PLUGINS-SECURITY.md, «Python, импорт-хук»).
# Ключ — точный dotted-префикс: "urllib" целиком гейтить нельзя, urllib.parse
# это чистый разбор строк и им пользуется половина каталога.
# Значение None — не выдаётся никогда, ни при каком разрешении.
_IMPORT_RULES = {
    "subprocess": None,
    "_posixsubprocess": None,
    "ctypes": PERM_NATIVE,
    "_ctypes": PERM_NATIVE,
    "multiprocessing": None,
    "socket": PERM_NETWORK,
    "socketserver": PERM_NETWORK,
    "requests": PERM_NETWORK,
    "urllib.request": PERM_NETWORK,
    "urllib.error": PERM_NETWORK,
    "http.client": PERM_NETWORK,
    "http.server": PERM_NETWORK,
    "urllib3": PERM_NETWORK,
    "httpx": PERM_NETWORK,
    "aiohttp": PERM_NETWORK,
    "websocket": PERM_NETWORK,
    "websockets": PERM_NETWORK,
    "ftplib": PERM_NETWORK,
    "smtplib": PERM_NETWORK,
    "poplib": PERM_NETWORK,
    "imaplib": PERM_NETWORK,
    "telnetlib": PERM_NETWORK,
    "xmlrpc.client": PERM_NETWORK,
    "shutil": PERM_FILES,
}

# Дешёвый префильтр: враппер __import__ стоит на пути ВСЕХ импортов процесса,
# поэтому в общем случае он должен стоить один lookup в множестве.
_GATED_ROOTS = frozenset(key.partition(".")[0] for key in _IMPORT_RULES)

#: Внутренние модули движка: плагину они не отдаются вовсе.
#:
#: Импорты вообще-то не запрещаются (см. _guard_import: запрет ломал плагины
#: целиком), но здесь импорт и есть действие: `from extera_utils import
#: audit_gate; audit_gate._WATCHED.clear()` выключал весь гейт одной строкой.
#: Плагины каталога берут из пакета только classes и text_formatting.
_INTERNAL_MODULES = frozenset({
    "extera_utils.audit_gate",
    "extera_utils.plugin_loader",
    "extera_utils.class_aliases",
    "extera_utils.capability_scan",
    "extera_utils.sandbox_main",
    "extera_utils.metadata_parser",
    "dev_server",
})


def _deny_internal_import(name, fromlist=()) -> None:
    """Бросить ImportError, если плагин лезет во внутренний модуль движка."""
    if type(name) is not str or unsafe_mode():
        return
    candidates = [name]
    if fromlist:
        candidates.extend(f"{name}.{item}" for item in fromlist
                          if isinstance(item, str) and item != "*")
    for candidate in candidates:
        if candidate not in _INTERNAL_MODULES:
            continue
        try:
            pid = _direct_plugin_caller()
        except Exception:
            return  # сломанная атрибуция не должна ломать импорты процесса
        if pid is None:
            return
        _log_once(f"{pid}|internal|{candidate}",
                  f"plugin {pid!r}: module {candidate!r} is internal to the engine")
        raise ImportError(
            f"{candidate} is internal to the plugin engine and not available "
            f"to plugins")


#: Java-классы, за которыми стоит разрешение. Ключ — точное имя или префикс
#: с точкой на конце.
#:
#: Сюда попало только однозначное. MessagesController, например, здесь нет:
#: его 71 плагин из каталога зовёт ради имени пользователя по id, и отказ
#: сломал бы их, ничего не защитив, — за чтение переписки отвечает
#: MessagesStorage, он и закрыт.
_JAVA_CLASS_RULES = {
    "org.telegram.messenger.SendMessagesHelper": "messages.send",
    "org.telegram.messenger.MessagesStorage": "messages.read",
    "org.telegram.tgnet.ConnectionsManager": ("messages.read", "messages.send"),
    "android.content.ContentResolver": "files",
    "android.provider.MediaStore": "files",
    "de.robv.android.xposed.": "hooks",
    # Загрузка dex — произвольный Java-код в нашем процессе, та же власть,
    # что у хуков. 33 плагина каталога этим пользуются, поэтому запрет
    # «никогда» им не подходит: это разрешение hooks, а не отказ.
    "dalvik.system.DexClassLoader": "hooks",
    "dalvik.system.PathClassLoader": "hooks",
    "dalvik.system.InMemoryDexClassLoader": "hooks",
    "dalvik.system.BaseDexClassLoader": "hooks",
    "java.net.DatagramSocket": PERM_NETWORK,
    "java.net.MulticastSocket": PERM_NETWORK,
    "java.net.ServerSocket": PERM_NETWORK,
    "java.nio.channels.SocketChannel": PERM_NETWORK,
    "java.nio.channels.DatagramChannel": PERM_NETWORK,
    "java.nio.channels.ServerSocketChannel": PERM_NETWORK,
}

#: Java-классы, которые плагину не отдаются ни при каком разрешении.
#:
#: Это управляющий слой самого движка: он хранит уровни доверия, разрешения и
#: журнал. Раньше их не было ни в одном списке, поэтому плагин двумя вызовами
#: (`PluginTrustLevel.setLevel(id, 2)`, `PluginPermissions.grant(id, "native")`)
#: выдавал себе TRUSTED. Ни один плагин каталога эти классы не зовёт.
_JAVA_CLASS_DENIED = frozenset({
    "app.exteraless.plugins.PluginPermissions",
    "app.exteraless.plugins.PluginTrustLevel",
    "app.exteraless.plugins.PluginSinkGate",
    "app.exteraless.plugins.PluginsWatchdog",
    "app.exteraless.plugins.PluginRuntime",
    "app.exteraless.plugins.PluginServices",
    "app.exteraless.plugins.PluginAuditJournal",
    "app.exteraless.plugins.PluginDenialNotice",
    "app.exteraless.plugins.files.FilesControllerJava",
    "app.exteraless.plugins.intents.IntentsDispatcher",
    "app.exteraless.plugins.menus.MenusController",
    "java.lang.ProcessBuilder",
    "java.lang.Process",
})


def java_class_permission(name):
    """Разрешение, нужное для класса, или None."""
    if not name:
        return None
    exact = _JAVA_CLASS_RULES.get(name)
    if exact is not None:
        return exact
    for prefix, perm in _JAVA_CLASS_RULES.items():
        if prefix.endswith(".") and name.startswith(prefix):
            return perm
    return None


def guard_java_class(name):
    """Можно ли плагину получить этот Java-класс.

    Возвращает True/False, ничего не бросая: вызывающий (find_class и обёртка
    jclass) отдаёт None, а None у find_class — штатный ответ «класса нет», его
    проверяют 188 мест в каталоге. Отказ через исключение ломал бы плагины,
    которые до сих пор работали.

    Полной эту проверку считать нельзя: 210 плагинов каталога тянут
    org.telegram.* обычным импортом на верхнем уровне модуля, и туда она не
    достаёт. Настоящая граница — хуки на самих действиях
    (app.exteraless.plugins.PluginSinkGate), эта проверка лишь снимает самый
    ходовой путь: 178 плагинов зовут find_class, 94 — jclass.
    """
    if unsafe_mode():
        return True
    if name in _JAVA_CLASS_DENIED:
        pid = plugin_frame_owner()
        if pid is not None:
            _log_once(f"{pid}|jclass|{name}",
                      f"plugin {pid!r}: class {name!r} is never available to plugins")
            return False
        return True
    perm = java_class_permission(name)
    if perm is None:
        return True
    pid = plugin_frame_owner()
    if pid is None:
        return True
    wanted = perm if isinstance(perm, tuple) else (perm,)
    if any(has_permission(single, pid) for single in wanted):
        return True
    perm = " или ".join(wanted)
    _log_once(f"{pid}|jclass|{name}",
              f"plugin {pid!r}: class {name!r} refused, missing {perm!r}")
    try:
        from . import audit_gate
        audit_gate.note_denied_class(pid, name, perm)
    except Exception:
        pass
    return False


def _log(message: str) -> None:
    try:
        from android_utils import log as _android_log
        _android_log(message)
    except Exception:
        print(f"[exteraless:plugin_loader] {message}", file=sys.stderr)


_logged_denials = set()


def _log_once(mark: str, message: str) -> None:
    """Лог отказа один раз на процесс: отказ в цикле иначе зальёт лог."""
    if mark in _logged_denials:
        return
    if len(_logged_denials) > 256:
        # В ключе бывает путь к файлу — множество иначе растёт без границы.
        _logged_denials.clear()
    _logged_denials.add(mark)
    _log(message)


def log_denial(plugin_id: str, event: str, what: str) -> None:
    """Отказ audit-гейта в общий лог, один раз на (плагин, событие)."""
    _log_once(f"{plugin_id}|audit|{event}",
              f"plugin {plugin_id!r}: {what} refused ({event})")


def _register_owner(path: str, plugin_id: str) -> None:
    _owner_files[os.path.normcase(os.path.abspath(path))] = plugin_id
    _path_owner_cache.clear()


def _forget_owner(path: str) -> None:
    _owner_files.pop(os.path.normcase(os.path.abspath(path)), None)
    _path_owner_cache.clear()


def _installed_plugin_owner(full: str) -> Optional[str]:
    """`<plugins_dir>/<id>.py` — файл установленного плагина, владелец — <id>.

    Нужно, когда плагина подняли как зависимость раньше, чем движок дошёл до
    него сам: записи в _owner_files ещё нет, а его код уже исполняется. Без
    этого разрешения считались бы по импортёру — соседу, который про чужие
    хуки и файлы ничего не объявлял.
    """
    root = _plugins_dir_path()
    if not root:
        return None
    head, tail = os.path.split(full)
    if os.path.normcase(head) != os.path.normcase(os.path.abspath(root)):
        return None
    if not tail.endswith(".py"):
        return None
    name = tail[:-3]
    return name if name.isidentifier() else None


def _resolve_owner(path: str) -> Optional[str]:
    full = os.path.normcase(os.path.abspath(path))
    owner = _owner_files.get(full)
    if owner is not None:
        return owner
    owner = _installed_plugin_owner(full)
    if owner is not None:
        return owner
    parts = full.split(os.sep)
    for marker in _ELYX_DIR_MARKERS:
        try:
            index = parts.index(os.path.normcase(marker))
        except ValueError:
            continue
        if index + 1 < len(parts) and parts[index + 1]:
            return parts[index + 1]
    return None


def plugin_files(plugin_id: str):
    """Пути файлов самого плагина.

    Регистрация идёт до exec_module, поэтому список полон уже во время
    исполнения модуля плагина — а запись в ``plugins`` появляется только
    после него, и по ней «читаю сам себя» на загрузке выглядело бы как
    доступ наружу.
    """
    out = [path for path, owner in _owner_files.items() if owner == plugin_id]
    record = plugins.get(plugin_id)
    path = getattr(record, "path", None) if record is not None else None
    if path:
        out.append(os.path.normcase(os.path.abspath(path)))
    return out


def _owner_of_frame(frame) -> Optional[str]:
    """id плагина, чей файл исполняется в кадре, или None (SDK / чужой код)."""
    path = frame.f_code.co_filename
    if not path or path.startswith("<"):  # <string>, <frozen importlib._bootstrap>
        return None
    if path in _path_owner_cache:
        return _path_owner_cache[path]
    try:
        owner = _resolve_owner(path)
    except Exception:
        owner = None
    _path_owner_cache[path] = owner
    return owner


def _frame_is_machinery(frame) -> bool:
    """Кадр импортной машинерии, нашего враппера или import-шима Elyx."""
    if frame.f_globals.get("__name__") in _IMPORT_MACHINERY:
        return True
    path = frame.f_code.co_filename
    return bool(path) and (path.startswith("<frozen importlib")
                           or path == _SELF_FILE
                           or path == _ELYX_NAMESPACE_FILE)


def caller_plugin_id() -> Optional[str]:
    """Чей код привёл нас сюда: самый внутренний кадр плагина на стеке.

    Через стек, а не через plugin_context: контекст выставлен только вокруг
    load/unload и диспетчеризации хуков, а колбэки плагина прилетают ещё и из
    Java (OnClickListener, RequestDelegate, настройки) — там контекста нет,
    и проверка молча пропускала бы всё. Кадр плагина на стеке есть всегда,
    когда исполняется его код.
    """
    owner = plugin_frame_owner()
    return owner if owner is not None else current_plugin_id()


def plugin_frame_owner() -> Optional[str]:
    """Только по кадрам, без подстановки plugin_context.

    Для audit-гейта: контекст выставлен на всё время load/unload, а внутри
    загрузки SDK делает свою работу (распаковка Elyx-архива, pip, чтение
    метаданных). С подстановкой контекста эти операции выглядели бы как
    действия плагина и упирались бы в его разрешения ещё до того, как он
    вообще начал исполняться. Кадр плагина на стеке — признак того, что
    действие исходит именно от его кода.
    """
    try:
        frame = sys._getframe(1)
    except Exception:
        frame = None
    depth = 0
    while frame is not None and depth < _MAX_FRAMES:
        owner = _owner_of_frame(frame)
        if owner is not None:
            return owner
        frame = frame.f_back
        depth += 1
    return None


def _direct_plugin_caller() -> Optional[str]:
    """Кто вызвал напрямую: ПЕРВЫЙ кадр вне машинерии импорта и нас самих.

    Именно первый, а не любой кадр плагина на стеке. Для импорта: если socket
    тянет наш SDK или библиотека, поставленная плагином (requests тянет
    urllib3), импортирует не плагин, и запрещать нечего — гейтился сам
    `import requests` в коде плагина. Для open(): если файл открывает SDK по
    просьбе плагина, проверка уже стоит в file_utils. Так ни SDK, ни чужие
    плагины не задеваются.
    """
    try:
        frame = sys._getframe(1)
    except Exception:
        return None
    depth = 0
    while frame is not None and depth < _MAX_FRAMES:
        if not _frame_is_machinery(frame):
            return _owner_of_frame(frame)
        frame = frame.f_back
        depth += 1
    return None


_PERMISSIONS_UNSET = object()
_permissions_class = _PERMISSIONS_UNSET


def _permissions():
    global _permissions_class
    if _permissions_class is _PERMISSIONS_UNSET:
        try:
            from app.exteraless.plugins import PythonBridge
            _permissions_class = PythonBridge
        except Exception:
            return None
    return _permissions_class


def has_permission(perm: str, plugin_id: Optional[str] = None) -> bool:
    """Тихая проверка. Вне кода плагина и без JVM — True (гейтить нечего)."""
    if unsafe_mode():
        return True
    pid = plugin_id or caller_plugin_id()
    if pid is None:
        return True
    java = _permissions()
    if java is None:
        return True
    try:
        return bool(java.hasPermission(pid, perm))
    except Exception:
        return True


_unsafe_mode: Optional[bool] = None


def set_unsafe_mode(value) -> None:
    global _unsafe_mode
    _unsafe_mode = bool(value)


def unsafe_mode() -> bool:
    global _unsafe_mode
    if _unsafe_mode is None:
        java = _permissions()
        if java is None:
            return False
        try:
            _unsafe_mode = bool(java.isUnsafeMode())
        except Exception:
            return False
    return _unsafe_mode


def require_permission(perm: str, what: str, detail: Optional[str] = None,
                       plugin_id: Optional[str] = None) -> None:
    """Проверка в точке вызова; при отказе — PermissionError с внятным текстом.

    Отказ не роняет плагин: исключение ловит android_utils.safe_call на
    колбэках и _dispatch_hook на хуках, и уходит в лог, как любая другая
    ошибка плагина.

    *what* — короткое имя точки («send_text»), оно уходит в Java-лог и
    дедуплицируется там; *detail* (путь, имя модуля) идёт только в текст
    исключения, иначе множество дедупликации на Java-стороне росло бы
    на каждый новый путь.
    """
    if unsafe_mode():
        return
    pid = plugin_id or caller_plugin_id()
    if pid is None:
        return
    java = _permissions()
    if java is None:
        return
    try:
        allowed = bool(java.checkPermission(pid, perm, what))
    except Exception:
        return  # мост сломан — не мешаем работать
    if allowed:
        return
    where = what if detail is None else f"{what} ({detail})"
    raise PermissionError(
        f"plugin {pid!r} is not allowed to {where}: missing the {perm!r} "
        f"permission. Declare it in __permissions__ and grant it on the "
        f"plugin's screen."
    )


def _log_permission_error(plugin_id: Optional[str], exc: BaseException) -> None:
    _log_once(f"{plugin_id}|{exc}", f"permission denied: {exc}")


# ---- импорт-хук ----

def _gate_for(name: str):
    """(правило, разрешение) для модуля, или (None, None) если не гейтится."""
    parts = name.split(".")
    for i in range(len(parts), 0, -1):
        key = ".".join(parts[:i])
        if key in _IMPORT_RULES:
            return key, _IMPORT_RULES[key]
    return None, None


def _guard_import(name: str, fromlist=()) -> None:
    """Записать в лог интересный импорт плагина. Ничего не запрещает.

    Раньше отсюда летел PermissionError, и это ломало плагины целиком:
    `import requests` стоит в шапке модуля почти у каждого сетевого плагина,
    поэтому плагин без разрешения на сеть не загружался вовсе — не «терял
    сеть», а переставал существовать. У пользователя это выглядело так:
    выключаешь сеть плагину, и его команды перестают отвечать совсем.

    Сам по себе импорт ничего не делает. Запрещать надо действие, и оно
    запрещается в extera_utils/audit_gate.py — событием на уровне C, которое
    не обойти через sys.modules. Здесь остаётся только запись в лог: видно,
    что плагин собирался делать, и это не мешает ему работать в остальном.
    """
    candidates = [name]
    if fromlist:
        candidates.extend(f"{name}.{item}" for item in fromlist
                          if isinstance(item, str) and item != "*")
    for candidate in candidates:
        key, perm = _gate_for(candidate)
        if key is None:
            continue
        pid = _direct_plugin_caller()
        if pid is None:
            return  # импортирует не плагин
        _log_once(f"{pid}|import|{key}",
                  f"plugin {pid!r} imports {key!r}"
                  + ("" if perm is None else f" (needs {perm!r} to use it)"))
        return


class _PermissionFinder:
    """sys.meta_path-финдер: замечает импорт ещё не загруженных модулей.

    Ничего не находит сам (всегда None) и ничего не запрещает — только даёт
    записи в лог для модулей, которые загружаются впервые (враппер
    builtins.__import__ этот случай тоже видит, но не всегда: importlib
    ходит в машинерию напрямую).
    """

    def find_spec(self, fullname, path=None, target=None):
        if fullname.partition(".")[0] in _GATED_ROOTS:
            try:
                _guard_import(fullname)
            except Exception:
                pass  # запись в лог не должна ломать импорт
        return None


_original_import = None
_original_import_module = None


def _sandboxed_import_module(name, package=None):
    """Обёртка importlib.import_module.

    Отдельно от __import__: import_module идёт в машинерию напрямую, минуя
    builtins.__import__, а для уже загруженного модуля — ещё и минуя meta_path.
    """
    if package is None:
        _deny_internal_import(name)
    if package is None and type(name) is str \
            and name.partition(".")[0] in _GATED_ROOTS:
        try:
            _guard_import(name)
        except Exception:
            pass
    return _original_import_module(name, package)


_sandboxed_import_module._exteraless_sandbox = True


def _log_neighbour_import_failure(name, exc) -> None:
    """Плагин не смог импортировать соседа: настоящая причина — в лог.

    Плагины каталога ловят такой сбой сами и показывают своё «библиотека не
    установлена», подменяя причину. Без этой записи в логах не остаётся
    ничего: ни имени модуля, ни исключения.
    """
    try:
        pid = _direct_plugin_caller()
        if pid is None:
            return
        root = name.partition(".")[0]
        if root == pid:
            return
        plugins_dir = _plugins_dir_path()
        if not plugins_dir or not os.path.isfile(os.path.join(plugins_dir, root + ".py")):
            return
        _log_once(f"{pid}|neighbour|{root}|{type(exc).__name__}",
                  f"plugin {pid!r}: import {root!r} failed: "
                  f"{type(exc).__name__}: {exc}")
    except Exception:
        pass


def _sandboxed_import(name, globals=None, locals=None, fromlist=(), level=0):
    """Обёртка builtins.__import__.

    Одного meta_path-финдера мало: socket, http и urllib.request к моменту
    старта движка уже лежат в sys.modules, а закэшированный импорт до
    meta_path вообще не доходит. Враппер ловит именно этот случай — для лога.
    """
    if level == 0:
        _deny_internal_import(name, fromlist)
    if level == 0 and type(name) is str and name.partition(".")[0] in _GATED_ROOTS:
        try:
            _guard_import(name, fromlist)
        except Exception:
            pass
    if level != 0 or type(name) is not str \
            or name.partition(".")[0] not in _JAVA_ROOTS:
        try:
            return _original_import(name, globals, locals, fromlist, level)
        except Exception as exc:
            _log_neighbour_import_failure(name, exc)
            raise
    try:
        return _original_import(name, globals, locals, fromlist, level)
    except ModuleNotFoundError as exc:
        # Chaquopy отдаёт «No module named 'org'» — корень пакета, а не то, что
        # действительно не нашлось. Настоящий запрос знает только этот кадр.
        if getattr(exc, "_exteraless_java_import", None) is None:
            exc._exteraless_java_import = (name, tuple(fromlist or ()))
        raise


_sandboxed_import._exteraless_sandbox = True


# ---- прямой доступ к файлам из кода плагина ----

_original_open = None


def _sandboxed_open(file, mode="r", *args, **kwargs):
    """Обёртка builtins.open.

    Плагины пишут файлы через open(), а не через file_utils, — без этой
    обёртки разрешение "files" закрывало бы только парадный вход. Проверяется
    только прямой вызов из кода плагина: у SDK своя проверка в file_utils.
    """
    try:
        pid = _direct_plugin_caller()
        target = file if isinstance(file, (str, bytes, os.PathLike)) else None
        if pid is not None and target is not None:
            from file_utils import _is_own_path
            if not _is_own_path(pid, os.fsdecode(target)):
                require_permission(PERM_FILES, "open a file",
                                   detail=f"{os.fsdecode(target)} ({mode})",
                                   plugin_id=pid)
    except PermissionError:
        raise
    except Exception:
        pass  # сломанная проверка не должна ломать открытие файлов
    return _original_open(file, mode, *args, **kwargs)


_sandboxed_open._exteraless_sandbox = True


#: Функции os, работающие с уже открытым дескриптором или со ссылкой.
#:
#: Событий PEP 578 у них нет вовсе (проверено на CPython 3.12): плагин мог
#: читать чужой дескриптор через os.pread, не имея разрешения files. Своего
#: пути к чужому дескриптору у плагина быть не должно, поэтому правило простое:
#: прямой вызов из кода плагина требует files.
_FD_FUNCTIONS = ("pread", "preadv", "readlink", "dup", "dup2", "fdopen")

_original_fd_functions = {}


def _install_fd_guards() -> None:
    """Обернуть функции os без audit-событий. Идемпотентно, не бросает."""
    try:
        for name in _FD_FUNCTIONS:
            original = getattr(os, name, None)
            if original is None or getattr(original, "_exteraless_sandbox", False):
                continue
            _original_fd_functions[name] = original

            def make(func_name, func):
                def guarded(*args, **kwargs):
                    try:
                        pid = _direct_plugin_caller()
                        if pid is not None:
                            require_permission(PERM_FILES, f"call os.{func_name}",
                                               detail=None, plugin_id=pid)
                    except PermissionError:
                        raise
                    except Exception:
                        pass
                    return func(*args, **kwargs)

                guarded._exteraless_sandbox = True
                guarded.__name__ = func_name
                return guarded

            setattr(os, name, make(name, original))
    except Exception as e:
        print(f"[exteraless:plugin_loader] fd guards failed: {e}", file=sys.stderr)


def owner_of_object(obj) -> Optional[str]:
    """Владелец функции, метода или класса — по файлу его кода."""
    for candidate in (obj, getattr(obj, "__func__", None), getattr(obj, "run", None)):
        if candidate is None:
            continue
        owner = owner_of_function(candidate)
        if owner is not None:
            return owner
    return None


def _install_thread_marking() -> None:
    """Перенести метку владельца в потоки, которые плагин создаёт сам.

    Проверено на устройстве: без этого плагин, которому запрещена сеть,
    получал java.net.URL из собственного threading.Thread — метка потока там
    не стоит, и Java-гейт пропускал. Патчим Thread.run (он исполняется уже на
    новом потоке), а не start.

    Потоки приложения не задеты: если владельца нет, обёртка сразу зовёт
    оригинал.
    """
    try:
        import threading
        original_run = threading.Thread.run
        if getattr(original_run, "_exteraless_marked", False):
            return

        def run(self):
            owner = None
            try:
                owner = getattr(self, "_exteraless_owner", None)
                if owner is None:
                    owner = owner_of_object(getattr(self, "_target", None))
                if owner is None:
                    owner = owner_of_object(getattr(self, "function", None))
                if owner is None:
                    owner = owner_of_object(type(self))
            except Exception:
                owner = None
            if owner is None:
                return original_run(self)
            with java_runtime_mark(owner):
                return original_run(self)

        run._exteraless_marked = True
        threading.Thread.run = run

        original_start = threading.Thread.start
        if not getattr(original_start, "_exteraless_marked", False):
            def start(self):
                # Владельца берём в момент запуска, на стеке создателя: у
                # threading.Timer колбэк лежит в .function, а воркеры
                # ThreadPoolExecutor создаются с target из стандартной
                # библиотеки — по объекту потока владелец в обоих случаях
                # не определяется, и поток уходил без метки.
                try:
                    if getattr(self, "_exteraless_owner", None) is None:
                        self._exteraless_owner = plugin_frame_owner()
                except Exception:
                    pass
                return original_start(self)

            start._exteraless_marked = True
            threading.Thread.start = start
    except Exception as e:
        print(f"[exteraless:plugin_loader] thread marking failed: {e}", file=sys.stderr)


def _install_jclass_guard() -> None:
    """Обернуть java.jclass проверкой разрешений. Идемпотентно, не бросает.

    Плагины берут Java-классы по имени двумя способами: наш find_class и
    java.jclass напрямую (94 плагина каталога). Второй мимо SDK, поэтому
    оборачиваем сам jclass.
    """
    try:
        import java
        original = getattr(java, "jclass", None)
        if original is None or getattr(original, "_exteraless_guard", False):
            return

        def jclass(name, *args, **kwargs):
            try:
                from .class_aliases import resolve
                name = resolve(name)
            except Exception:
                pass
            try:
                if isinstance(name, str) and not guard_java_class(name):
                    needed = java_class_permission(name)
                    if isinstance(needed, tuple):
                        needed = " или ".join(needed)
                    raise ClassNotFoundError(
                        f"{name} — плагину не выдано разрешение {needed}"
                        if needed else name)
            except ClassNotFoundError:
                raise
            except Exception:
                pass  # сломанная проверка не должна ломать доступ к Java
            found = original(name, *args, **kwargs)
            try:
                from .class_aliases import adapt
                return adapt(name, found)
            except Exception:
                return found

        jclass._exteraless_guard = True
        java.jclass = jclass
    except Exception as e:
        print(f"[exteraless:plugin_loader] jclass guard failed: {e}", file=sys.stderr)


_PROXY_DEFAULTS = {
    "void": None,
    "boolean": False,
    "byte": 0,
    "short": 0,
    "int": 0,
    "long": 0,
    "float": 0.0,
    "double": 0.0,
    "char": "\0",
}


def _proxy_return_defaults(interfaces):
    defaults = {}
    for interface in interfaces:
        try:
            methods = interface.getClass().getMethods()
        except Exception:
            continue
        for index in range(len(methods)):
            try:
                method = methods[index]
                name = str(method.getName())
                if name in defaults:
                    continue
                defaults[name] = _PROXY_DEFAULTS.get(
                    str(method.getReturnType().getName()))
            except Exception:
                continue
    return defaults


def _guard_proxy_method(fn, default, owner):
    import functools

    @functools.wraps(fn)
    def guarded(*args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except PermissionError as e:
            print(f"[exteraless:plugin_loader] {owner}.{fn.__name__} denied: {e}",
                  file=sys.stderr)
            return default
        except Exception:
            import traceback
            print(f"[exteraless:plugin_loader] {owner}.{fn.__name__} "
                  f"raised into Java:\n{traceback.format_exc()}", file=sys.stderr)
            return default

    guarded._exteraless_guarded = True
    return guarded


def _guard_proxy_subclass(cls, defaults):
    for name, value in list(vars(cls).items()):
        if name.startswith("__") or not callable(value):
            continue
        if defaults and name not in defaults:
            continue
        if getattr(value, "_exteraless_guarded", False):
            continue
        if isinstance(value, (staticmethod, classmethod, type)):
            continue
        try:
            setattr(cls, name, _guard_proxy_method(value, defaults.get(name), cls.__name__))
        except Exception:
            continue


def _install_dynamic_proxy_guard() -> None:
    try:
        import java
        original = getattr(java, "dynamic_proxy", None)
        if original is None or getattr(original, "_exteraless_guard", False):
            return

        def dynamic_proxy(*interfaces, **kwargs):
            base = original(*interfaces, **kwargs)
            try:
                defaults = _proxy_return_defaults(interfaces)

                def __init_subclass__(cls, **subclass_kwargs):
                    _guard_proxy_subclass(cls, defaults)

                base.__init_subclass__ = classmethod(__init_subclass__)
            except Exception as e:
                print(f"[exteraless:plugin_loader] proxy guard skipped: {e}",
                      file=sys.stderr)
            return base

        dynamic_proxy._exteraless_guard = True
        java.dynamic_proxy = dynamic_proxy
    except Exception as e:
        print(f"[exteraless:plugin_loader] dynamic_proxy guard failed: {e}",
              file=sys.stderr)


def _install_sandbox() -> None:
    """Поставить финдер и врапперы импорта/open. Идемпотентно, не бросает."""
    global _original_import, _original_import_module, _original_open
    try:
        import builtins
        if _original_import is None:
            # Ровно один раз: если кто-то встанет поверх нас позже, повторный
            # захват сделал бы _original_import ссылкой на обёртку над нами —
            # то есть бесконечную рекурсию на первом же импорте.
            _original_import = builtins.__import__
            builtins.__import__ = _sandboxed_import
        if _original_import_module is None:
            import importlib as _importlib
            _original_import_module = _importlib.import_module
            _importlib.import_module = _sandboxed_import_module
        if _original_open is None:
            _original_open = builtins.open
            builtins.open = _sandboxed_open
        _install_fd_guards()
        # Audit hook (PEP 578) — принуждение уровня действия. Врапперы выше
        # остаются: они дают внятный текст ошибки в точке импорта, а гейт
        # ловит то, что мимо них проходит (sys.modules, importlib изнутри C).
        from . import audit_gate
        audit_gate.install(sys.modules[__name__])
        _install_thread_marking()
        _install_jclass_guard()
        _install_dynamic_proxy_guard()
        from . import class_aliases
        class_aliases.install_import_hook()
        if not any(isinstance(finder, _PermissionFinder) for finder in sys.meta_path):
            # В начало: elyx_runtime ставит свой финдер тоже в начало и может
            # оказаться перед нами — это безопасно, для не-ElyxPlugins имён он
            # возвращает None и передаёт очередь дальше.
            sys.meta_path.insert(0, _PermissionFinder())
    except Exception as e:
        print(f"[exteraless:plugin_loader] sandbox install failed: {e}",
              file=sys.stderr)


# Loading / unloading

#: Верхние пакеты, которые импортом достаёт Chaquopy, а не питон.
_JAVA_ROOTS = ("java", "javax", "org", "com", "android", "androidx", "dalvik", "kotlin")


def _failing_line(exc: BaseException) -> Optional[str]:
    """Последний кадр из файла плагина: «путь:строка: исходный код»."""
    import traceback
    frames = traceback.extract_tb(exc.__traceback__)
    if not frames:
        return None
    frame = frames[-1]
    for candidate in reversed(frames):
        if candidate.filename and not candidate.filename.startswith("<") \
                and not candidate.filename.endswith(".pxi") \
                and "extera_utils" not in candidate.filename \
                and "chaquopy" not in candidate.filename \
                and "importlib" not in candidate.filename:
            frame = candidate
            break
    name = os.path.basename(frame.filename or "?")
    line = (frame.line or "").strip()
    return f"{name}:{frame.lineno}" + (f": {line}" if line else "")


def _missing_java_classes(exc: BaseException) -> List[str]:
    """Какие именно классы не достались из запрошенного пакета.

    Имя запроса приходит из `_sandboxed_import`: сам ModuleNotFoundError знает
    только корень («org»), а плагину нужно увидеть полное имя класса —
    в форке половина классов эталона называется иначе или отсутствует.
    """
    requested = getattr(exc, "_exteraless_java_import", None)
    if not requested:
        return []
    module, fromlist = requested
    try:
        import java
    except Exception:
        return []
    candidates = [f"{module}.{item}" for item in fromlist if item and item != "*"]
    if not candidates:
        candidates = [module]
    missing = []
    for candidate in candidates:
        try:
            if java.jclass(candidate) is None:
                missing.append(candidate)
        except Exception:
            missing.append(candidate)
    return missing


def _java_import_hint(exc: BaseException) -> Optional[str]:
    """Настоящая причина отказа импорта java-пакета.

    Сообщение питона тут врёт: на `from org.telegram... import X` Chaquopy
    сначала пробует обычный импорт, ловит ModuleNotFoundError и только потом
    идёт за Java-классом. Не достался класс — наружу уходит исходная
    питоновская ошибка «No module named 'org'», в которой не видно ни имени
    класса, ни причины. Здесь причина добывается пробой.
    """
    if not isinstance(exc, ModuleNotFoundError):
        return None
    root = (getattr(exc, "name", "") or "").partition(".")[0]
    if root not in _JAVA_ROOTS:
        return None
    try:
        import java
    except Exception as e:
        return f"java bridge unavailable: {type(e).__name__}: {e}"
    probe = "org.telegram.messenger.ApplicationLoader"
    try:
        resolved = java.jclass(probe)
    except Exception as e:
        return f"Java classes are unreachable ({probe} -> {type(e).__name__}: {e})"
    if resolved is None:
        return f"Java classes are gated for this plugin ({probe} -> None)"
    missing = _missing_java_classes(exc)
    if missing:
        return "no such Java class: " + ", ".join(missing)
    import builtins
    hook = getattr(builtins.__import__, "__qualname__", "?")
    return (f"Java resolves ({probe} ok), so {root!r} lacks a specific class; "
            f"__import__ = {hook}")


def _error_report(exc: BaseException, summary: str) -> str:
    """Полный отчёт для кнопки «копировать»: он уедет в чужой чат, не в наш лог."""
    import platform
    import traceback
    lines = [summary, "", "traceback:"]
    lines.extend(traceback.format_exception(type(exc), exc, exc.__traceback__))
    lines.append("")
    lines.append(f"python: {platform.python_version()}")
    try:
        import java
        lines.append(f"android sdk: {java.jclass('android.os.Build$VERSION').SDK_INT}")
        build = java.jclass("org.telegram.messenger.BuildVars")
        lines.append(f"app: {build.BUILD_VERSION_STRING}")
    except Exception as e:
        lines.append(f"java bridge: {type(e).__name__}: {e}")
    return "\n".join(line.rstrip() for line in lines)


def _error_json(exc: BaseException) -> str:
    import traceback
    traceback.print_exception(type(exc), exc, exc.__traceback__, file=sys.stderr)
    parts = [f"{type(exc).__name__}: {exc}"]
    where = _failing_line(exc)
    if where:
        parts.append(where)
    hint = _java_import_hint(exc)
    if hint:
        parts.append(hint)
    summary = "\n".join(parts)
    # Иначе провал загрузки виден только в диалоге у пользователя, а по чужому
    # скриншоту не понять, какой класс не нашёлся.
    _log("plugin load failed: " + summary.replace("\n", " | "))
    try:
        debug = _error_report(exc, summary)
    except Exception:
        debug = summary
    return json.dumps({"ok": False, "error": summary, "debug": debug,
                       "has_settings": False}, ensure_ascii=False)


def _overrides(cls, name: str) -> bool:
    """True if *cls* provides its own implementation of a BasePlugin method."""
    return getattr(cls, name, None) is not getattr(BasePlugin, name, None)


def _import_module(path: str, plugin_id: str):
    _ensure_plugins_dir_on_path()
    module_name = _module_name_for(plugin_id)
    existing = sys.modules.get(module_name)
    if existing is not None and _same_file(getattr(existing, "__file__", None), path):
        _register_owner(path, plugin_id)
        return existing, module_name
    sys.modules.pop(module_name, None)
    # The loader must be explicit. spec_from_file_location() picks one by file
    # extension, and ".plugin" — the canonical extension for published plugins —
    # is not a registered source suffix, so it returns None and the import dies
    # with "cannot create a module spec". The file is plain Python source
    # whatever it is called, so name the loader instead of guessing.
    loader = importlib.machinery.SourceFileLoader(module_name, path)
    spec = importlib.util.spec_from_file_location(module_name, path, loader=loader)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot create a module spec for {path!r}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module  # needed by dataclasses/pickles inside the module
    # Владельца регистрируем ДО exec_module: импорт-хук определяет плагина по
    # имени модуля в кадре, а импорты верхнего уровня выполняются уже внутри
    # exec_module — после него регистрировать было бы поздно.
    _register_owner(path, plugin_id)
    try:
        # Контекст нужен и на верхнем уровне модуля: плагины каталога создают там
        # джавовые подклассы и трогают SDK, а проверки разрешений отказывают,
        # когда текущий плагин не установлен (pluginId == null).
        with plugin_context(plugin_id):
            spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(module_name, None)
        _forget_owner(path)
        raise
    return module, module_name


def _class_owner(obj) -> Optional[str]:
    source = sys.modules.get(getattr(obj, "__module__", "") or "")
    path = getattr(source, "__file__", None)
    if not path:
        return None
    try:
        return _resolve_owner(path)
    except Exception:
        return None


def _find_plugin_class(module, path: str, plugin_id: Optional[str] = None):
    fallback = None
    for obj in vars(module).values():
        if not isinstance(obj, type) or not issubclass(obj, BasePlugin) or obj is BasePlugin:
            continue
        if obj.__module__ == module.__name__:
            return obj
        if fallback is None:
            owner = _class_owner(obj)
            if owner is None or owner == plugin_id:
                fallback = obj
    if fallback is not None:
        return fallback
    raise RuntimeError(f"no BasePlugin subclass defined in {path!r}")


def _ensure_requirements(plugin_id: str, requirements) -> None:
    """Поставить зависимости плагина и убедиться, что они импортируются."""
    if pip_controller is None:
        raise RuntimeError(
            "механизм зависимостей недоступен, поставить "
            + ", ".join(str(r) for r in requirements)
            + " нечем" + (f": {_pip_import_error}" if _pip_import_error else ""))
    try:
        pip_controller.ensure_requirements(plugin_id, requirements)
    except Exception as e:
        raise RuntimeError(f"не удалось поставить зависимости плагина: {e}")

    import importlib.util
    for raw in requirements:
        name = re.split(r"[\s\[<>=!~;]", str(raw).strip(), 1)[0]
        if not name:
            continue
        if _dependency_available(name):
            continue
        raise RuntimeError(
            f"зависимость {name!r} поставлена, но не импортируется — "
            "проверьте, что пакет чисто питоновский")


def _dependency_available(name: str) -> bool:
    """Есть ли зависимость.

    Имя пакета и имя модуля совпадают далеко не всегда — pillow импортируется
    как PIL, beautifulsoup4 как bs4, — и проверка по имени модуля их не
    находила, хотя пакет стоит.
    """
    if pip_controller is not None:
        try:
            return pip_controller.is_provided(name)
        except Exception:
            pass
    import importlib.util
    try:
        return importlib.util.find_spec(name.replace("-", "_")) is not None
    except Exception:
        return False


def load_plugin(path: str, plugin_id: str) -> str:
    """Validate metadata, install requirements, import and start the plugin."""
    _install_sandbox()  # идемпотентно; на случай, если импорт модуля не прошёл
    if str(path).endswith((".elyx", ".eaf")):
        return _load_elyx_plugin(path, plugin_id)

    try:
        meta = read_metadata(path)  # validation; raises PluginMetadataError
    except Exception as e:
        return _error_json(e)

    try:
        if plugin_id in plugins:
            _unload_record(plugin_id, quiet=True)

        if meta.get("requirements"):
            _ensure_requirements(plugin_id, meta["requirements"])

        module, module_name = _import_module(path, plugin_id)
        plugin_class = _find_plugin_class(module, path, plugin_id)
        instance = plugin_class()
        instance._attach(plugin_id)
        plugins[plugin_id] = PluginRecord(module=module, instance=instance, path=path,
                                          module_name=module_name)

        try:
            if _overrides(plugin_class, "on_plugin_load"):
                with plugin_context(plugin_id):
                    instance.on_plugin_load()
        except Exception:
            _unload_record(plugin_id, quiet=True)
            raise

        has_settings = _overrides(plugin_class, "create_settings")
        return json.dumps({"ok": True, "error": None, "has_settings": has_settings},
                          ensure_ascii=False)
    except Exception as e:
        return _error_json(e)


def _load_elyx_plugin(path: str, plugin_id: str) -> str:
    """Structured .elyx/.eaf plugins delegate to elyx_runtime (owned elsewhere).

    SINGLE DISPATCH POINT: elyx_runtime.load_plugin_record(record, path)
    populates record.module / record.instance (and _attach()es). Lifecycle
    stays on this loader, per the elyx_runtime contract: we run
    on_plugin_load here, and _unload_record() routes namespace teardown
    back through elyx_runtime.unload_plugin_record().
    """
    try:
        import elyx_runtime
    except ImportError:
        return _error_json(RuntimeError("Elyx runtime unavailable"))
    try:
        if plugin_id in plugins:
            _unload_record(plugin_id, quiet=True)
        record = PluginRecord(module=None, instance=None, path=path)
        record.__dict__["_elyx"] = True
        with plugin_context(plugin_id):
            elyx_runtime.load_plugin_record(record, path)
        if record.instance is None:
            raise RuntimeError("elyx_runtime did not populate the plugin record")
        plugins[plugin_id] = record
        try:
            if _overrides(type(record.instance), "on_plugin_load"):
                with plugin_context(plugin_id):
                    record.instance.on_plugin_load()
        except Exception:
            _unload_record(plugin_id, quiet=True)
            raise
        has_settings = _overrides(type(record.instance), "create_settings")
        return json.dumps({"ok": True, "error": None, "has_settings": has_settings},
                          ensure_ascii=False)
    except Exception as e:
        return _error_json(e)


def _unload_record(plugin_id: str, quiet: bool):
    record = plugins.pop(plugin_id, None)
    if record is None:
        return
    instance = record.instance
    try:
        if instance is not None and not quiet \
                and _overrides(type(instance), "on_plugin_unload"):
            with plugin_context(plugin_id):
                instance.on_plugin_unload()
    finally:
        try:
            if instance is not None and hasattr(instance, "_cleanup_resources"):
                with plugin_context(plugin_id):
                    instance._cleanup_resources()
        except Exception:
            pass
        if getattr(record, "_elyx", False):
            # Elyx namespace teardown (module eviction etc.) is elyx_runtime's job.
            try:
                import elyx_runtime
                elyx_runtime.unload_plugin_record(record)
            except Exception:
                pass
        else:
            # Не по module.__name__: плагины переписывают его себе в шапке
            # (zwylib ставит "ZwyLib"), и запись в sys.modules пережила бы
            # выгрузку — следующая загрузка досталась бы старому модулю.
            name = getattr(record, "module_name", None)
            if name:
                sys.modules.pop(name, None)
        if getattr(record, "path", None):
            _forget_owner(record.path)


def unload_plugin(plugin_id: str) -> None:
    _unload_record(plugin_id, quiet=False)
    return None


def uninstall_plugin(plugin_id: str) -> None:
    """Full removal: unload plus dependency refcount cleanup.

    Called by the dev server's remove_plugin (the Java uninstall path should
    call this too when wired). File/prefs removal stays on the Java side.
    """
    _unload_record(plugin_id, quiet=False)
    if pip_controller is not None:
        try:
            pip_controller.remove_requirements(plugin_id)
        except Exception as e:
            print(f"[exteraless:plugin_loader] remove_requirements({plugin_id!r}) "
                  f"failed: {e}", file=sys.stderr)
    # Elyx: вычистить экстракции и локальные wheels (<plugins_dir>/.elyx_extracted/<id>).
    try:
        import elyx_runtime
        if hasattr(elyx_runtime, "purge_plugin"):
            from app.exteraless.plugins import PythonBridge
            elyx_runtime.purge_plugin(str(PythonBridge.getPluginsDir()), plugin_id)
    except Exception:
        pass
    return None


def is_loaded(plugin_id: str) -> bool:
    return plugin_id in plugins


def get_plugin_instance(plugin_id: str):
    record = plugins.get(plugin_id)
    return None if record is None else record.instance


# Event dispatch

def call_app_event(plugin_id: str, event: str) -> None:
    record = plugins.get(plugin_id)
    if record is None:
        return None
    instance = record.instance
    if not _overrides(type(instance), "on_app_event"):
        return None
    app_event = AppEvent.from_java(event)
    if app_event is None:
        instance.log(f"unknown app event {event!r}")
        return None
    try:
        instance.on_app_event(app_event)
    except PermissionError as e:  # отказ разрешения — не поломка плагина
        _log_permission_error(plugin_id, e)
    return None


def _strategy_of(result) -> str:
    """Extract a valid strategy string from a hook result (None -> DEFAULT)."""
    if result is None:
        return HookStrategy.DEFAULT
    strategy = getattr(result, "strategy", result)
    try:
        strategy = str(strategy)
    except Exception:
        return HookStrategy.DEFAULT
    return strategy if strategy in _VALID_STRATEGIES else HookStrategy.DEFAULT


def _dispatch_hook(plugin_id: str, account: int, fn, *args, result_field=None):
    """Вызвать хук в scope аккаунта и вернуть стратегию.

    PermissionError гасится здесь: движок трактует исключение из хука как
    поломку и отключает плагин целиком, а отказ в разрешении — не поломка
    (PLUGINS-SECURITY.md: «Отказ не роняет плагин»). Остальные исключения
    уходят в Java как раньше.
    """
    try:
        with client_utils.hook_scope(account), plugin_context(plugin_id):
            result = fn(*args)
            strategy = _strategy_of(result)
            if result_field is not None and strategy in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL):
                value = getattr(result, result_field, None)
                if value is None:
                    value = getattr(result, "result", None)
                if value is not None:
                    return _HookDispatchResult(strategy, value)
            return strategy
    except PermissionError as e:
        _log_permission_error(plugin_id, e)
        return HookStrategy.DEFAULT


def call_send_message_hook(plugin_id: str, account: int, params) -> Any:
    record = plugins.get(plugin_id)
    if record is None:
        return HookStrategy.DEFAULT
    instance = record.instance
    if not _overrides(type(instance), "on_send_message_hook"):
        return HookStrategy.DEFAULT
    return _dispatch_hook(plugin_id, account,
                          instance.on_send_message_hook, account, params, result_field="params")


def call_pre_request_hook(plugin_id: str, account: int, request_name: str, request) -> Any:
    record = plugins.get(plugin_id)
    if record is None:
        return HookStrategy.DEFAULT
    instance = record.instance
    if not _overrides(type(instance), "pre_request_hook"):
        return HookStrategy.DEFAULT
    return _dispatch_hook(plugin_id, account,
                          instance.pre_request_hook, request_name, account, request, result_field="request")


def call_post_request_hook(plugin_id: str, account: int, request_name: str,
                           response, error) -> Any:
    record = plugins.get(plugin_id)
    if record is None:
        return HookStrategy.DEFAULT
    instance = record.instance
    if not _overrides(type(instance), "post_request_hook"):
        return HookStrategy.DEFAULT
    return _dispatch_hook(plugin_id, account,
                          instance.post_request_hook, request_name, account, response, error, result_field="response")


def call_update_hook(plugin_id: str, account: int, update_name: str, update) -> Any:
    """Dispatch a single TL_update* to on_update_hook (Java routes by name)."""
    record = plugins.get(plugin_id)
    if record is None:
        return HookStrategy.DEFAULT
    instance = record.instance
    if not _overrides(type(instance), "on_update_hook"):
        return HookStrategy.DEFAULT
    return _dispatch_hook(plugin_id, account,
                          instance.on_update_hook, update_name, account, update, result_field="update")


def call_updates_hook(plugin_id: str, account: int, container_name: str, updates) -> Any:
    """Dispatch a TL_updates* container to on_updates_hook (Java routes by name)."""
    record = plugins.get(plugin_id)
    if record is None:
        return HookStrategy.DEFAULT
    instance = record.instance
    if not _overrides(type(instance), "on_updates_hook"):
        return HookStrategy.DEFAULT
    return _dispatch_hook(plugin_id, account,
                          instance.on_updates_hook, container_name, account, updates, result_field="updates")


# Settings serialization

def _put(data: dict, key: str, value):
    """Set an optional schema field; None-valued optionals are omitted."""
    if value is not None:
        data[key] = value


def _item_ident(item, scope: str, index: int) -> str:
    key = getattr(item, "key", None)
    alias = getattr(item, "link_alias", None)
    if key:
        name = ["key", key]
    elif alias:
        name = ["alias", alias]
    else:
        name = ["text", getattr(item, "text", None), getattr(item, "icon", None),
                bool(getattr(item, "create_sub_fragment", None))]
        if not any(name[1:]):
            name.append(index)
    return json.dumps([scope, type(item).__name__, name], ensure_ascii=False)


def _ident_hash(prefix: str, ident: str) -> str:
    return prefix + hashlib.sha1(ident.encode("utf-8")).hexdigest()[:12]


def _register_callbacks(item, record: PluginRecord, ident: str) -> Optional[str]:
    """Allocate a callback_id when the item declares on_change and/or on_click."""
    on_change = getattr(item, "on_change", None)
    on_click = getattr(item, "on_click", None)
    if on_change is None and on_click is None:
        return None
    callback_id = _ident_hash("cb_", ident)
    if on_click is not None:
        record.click_callbacks[callback_id] = on_click
    key = getattr(item, "key", None)
    if on_change is not None and key is not None:
        record.change_callbacks[key] = on_change
    return callback_id


def _register_long_click(item, record: PluginRecord, ident: str) -> Optional[str]:
    on_long_click = getattr(item, "on_long_click", None)
    if on_long_click is None:
        return None
    callback_id = _ident_hash("lc_", ident)
    record.click_callbacks[callback_id] = on_long_click
    return callback_id


def _attach_callbacks(data: dict, item, record: PluginRecord, ident: str) -> None:
    _put(data, "callback_id", _register_callbacks(item, record, ident))
    _put(data, "long_callback_id", _register_long_click(item, record, ident))


def _java_setting_kind(item) -> Optional[str]:
    if isinstance(item, (ui_settings.Header, ui_settings.Divider, ui_settings.Switch,
                         ui_settings.Selector, ui_settings.Input, ui_settings.Text,
                         ui_settings.EditText, ui_settings.Custom)):
        return None
    getter = getattr(item, "getType", None)
    if getter is None or getattr(item, "getClass", None) is None:
        return None
    try:
        kind = getter()
    except Exception:
        return None
    return kind if isinstance(kind, str) else None


def _java_get(item, name, fallback=None):
    getter = getattr(item, name, None)
    if getter is None:
        return fallback
    try:
        value = getter()
    except Exception:
        return fallback
    return fallback if value is None else value


def _from_java_setting(item, kind: str):
    s = ui_settings
    icon = _java_get(item, "getIcon")
    long_click = _java_get(item, "getOnLongClickCallback")
    alias = _java_get(item, "getLinkAlias")
    if kind == "header":
        return s.Header(text=_java_get(item, "getText", ""))
    if kind == "divider":
        return s.Divider(text=_java_get(item, "getText"))
    if kind == "switch":
        return s.Switch(key=_java_get(item, "getKey", ""),
                        text=_java_get(item, "getText", ""),
                        default=bool(_java_get(item, "getDefaultValue", False)),
                        subtext=_java_get(item, "getSubtext"), icon=icon,
                        on_change=_java_get(item, "getOnChangeCallback"),
                        on_long_click=long_click, link_alias=alias)
    if kind == "selector":
        items = _java_get(item, "getItems", [])
        return s.Selector(key=_java_get(item, "getKey", ""),
                          text=_java_get(item, "getText", ""),
                          default=int(_java_get(item, "getDefaultValue", 0)),
                          items=[str(entry) for entry in items],
                          subtext=_java_get(item, "getSubtext"), icon=icon,
                          on_change=_java_get(item, "getOnChangeCallback"),
                          on_long_click=long_click, link_alias=alias)
    if kind == "input":
        return s.Input(key=_java_get(item, "getKey", ""),
                       text=_java_get(item, "getText", ""),
                       default=_java_get(item, "getDefaultValue"),
                       subtext=_java_get(item, "getSubtext"), icon=icon,
                       on_change=_java_get(item, "getOnChangeCallback"),
                       on_long_click=long_click, link_alias=alias)
    if kind == "edit_text":
        max_length = _java_get(item, "getMaxLength", 0)
        return s.EditText(key=_java_get(item, "getKey", ""),
                          hint=_java_get(item, "getHint", ""),
                          default=_java_get(item, "getDefaultValue", ""),
                          multiline=bool(_java_get(item, "getMultiline", False)),
                          max_length=int(max_length) or None,
                          mask=_java_get(item, "getMask"),
                          on_change=_java_get(item, "getOnChangeCallback"))
    if kind == "text":
        return s.Text(text=_java_get(item, "getText", ""),
                      subtext=_java_get(item, "getSubtext"), icon=icon,
                      accent=bool(_java_get(item, "getAccent", False)),
                      red=bool(_java_get(item, "getRed", False)),
                      on_click=_java_get(item, "getOnClickCallback"),
                      on_long_click=long_click,
                      create_sub_fragment=_java_get(item, "getCreateSubFragmentCallback"),
                      link_alias=alias)
    if kind == "custom":
        return s.Custom(item=_java_get(item, "getItem"),
                        view=_java_get(item, "getView"),
                        factory=_java_get(item, "getFactory"),
                        on_click=_java_get(item, "getOnClickCallback"),
                        on_long_click=long_click,
                        create_sub_fragment=_java_get(item, "getCreateSubFragmentCallback"),
                        link_alias=alias)
    return None


def _serialize_setting_item(item, record: PluginRecord, scope: str = "",
                            index: int = 0, identities: Optional[dict] = None) -> Optional[dict]:
    kind = _java_setting_kind(item)
    if kind is not None:
        converted = _from_java_setting(item, kind)
        if converted is None:
            return None
        item = converted
    ident = _item_ident(item, scope, index)
    if identities is not None:
        occurrence = identities.get(ident, 0)
        identities[ident] = occurrence + 1
        ident = json.dumps([ident, occurrence], ensure_ascii=False)
    ident = hashlib.sha256(ident.encode("utf-8")).hexdigest()
    data = _serialize_setting_data(item, record, ident)
    if data is not None:
        data["row_id"] = ident
        _put(data, "link_alias", getattr(item, "link_alias", None))
    return data


def _serialize_setting_data(item, record: PluginRecord, ident: str) -> Optional[dict]:
    s = ui_settings
    instance = record.instance

    if isinstance(item, s.Header):
        return {"type": "header", "text": item.text}

    if isinstance(item, s.Divider):
        data = {"type": "divider"}
        _put(data, "text", item.text)
        return data

    if isinstance(item, s.Switch):
        data = {
            "type": "switch",
            "key": item.key,
            "text": item.text,
            "value": instance.get_setting(item.key, item.default),
        }
        _put(data, "subtext", item.subtext)
        _put(data, "icon", item.icon)
        _attach_callbacks(data, item, record, ident)
        return data

    if isinstance(item, s.Selector):
        data = {
            "type": "selector",
            "key": item.key,
            "text": item.text,
            "items": [str(entry) for entry in item.items],
            "value": instance.get_setting(item.key, item.default),
            "default": item.default,
        }
        _put(data, "subtext", item.subtext)
        _put(data, "icon", item.icon)
        _attach_callbacks(data, item, record, ident)
        return data

    if isinstance(item, s.Slider):
        data = {"type": "slider", "key": item.key, "text": item.text,
                "min": item.min, "max": item.max, "step": item.step,
                "integral": isinstance(item.normalize(item.default), int),
                "value": item.normalize(instance.get_setting(item.key, item.default))}
        _put(data, "subtext", item.subtext)
        _put(data, "icon", item.icon)
        _attach_callbacks(data, item, record, ident)
        return data

    if isinstance(item, s.Input):
        default = item.default if item.default is not None else ""
        data = {
            "type": "input",
            "key": item.key,
            "text": item.text,
            "value": instance.get_setting(item.key, default),
        }
        _put(data, "subtext", item.subtext)
        _put(data, "icon", item.icon)
        _attach_callbacks(data, item, record, ident)
        return data

    if isinstance(item, s.EditText):
        data = {
            "type": "edittext",
            "key": item.key,
            "hint": item.hint,
            "value": instance.get_setting(item.key, item.default),
            "multiline": bool(item.multiline),
        }
        _put(data, "max_length", item.max_length)
        _attach_callbacks(data, item, record, ident)
        return data

    if isinstance(item, s.Text):
        data = {
            "type": "text",
            "text": item.text,
            "accent": bool(item.accent),
            "red": bool(item.red),
        }
        _put(data, "subtext", item.subtext)
        _put(data, "icon", item.icon)
        _attach_callbacks(data, item, record, ident)
        if item.create_sub_fragment is not None:
            try:
                sub_items = item.create_sub_fragment()
            except Exception as e:
                instance.log(f"create_sub_fragment() failed: {type(e).__name__}: {e}")
                sub_items = None
            if sub_items:
                sub_page = []
                identities = {}
                for sub_index, sub_item in enumerate(sub_items):
                    entry = _serialize_setting_item(sub_item, record, ident, sub_index, identities)
                    if entry is not None:
                        sub_page.append(entry)
                if sub_page:
                    data["sub_page"] = sub_page
        return data

    if isinstance(item, s.Custom):
        view_id = _ident_hash("cv_", ident)
        record.custom_views[view_id] = item
        data = {"type": "custom", "view_id": view_id}
        _attach_callbacks(data, item, record, ident)
        if item.create_sub_fragment is not None:
            try:
                sub_items = item.create_sub_fragment()
            except Exception as e:
                instance.log(f"create_sub_fragment() failed: {type(e).__name__}: {e}")
                sub_items = None
            if sub_items:
                sub_page = []
                identities = {}
                for sub_index, sub_item in enumerate(sub_items):
                    entry = _serialize_setting_item(sub_item, record, ident, sub_index, identities)
                    if entry is not None:
                        sub_page.append(entry)
                if sub_page:
                    data["sub_page"] = sub_page
        return data

    return None  # unknown item type: skip defensively


def get_settings_json(plugin_id: str) -> str:
    """Serialize the plugin's create_settings() list into the Java JSON schema."""
    record = plugins.get(plugin_id)
    if record is None:
        return "null"
    instance = record.instance
    try:
        items = instance.create_settings()
    except Exception as e:
        summary = f"create_settings() failed: {type(e).__name__}: {e}"
        instance.log(summary)
        # Пустой экран не говорит пользователю ничего и выглядит как наша поломка.
        # Показываем причину прямо строкой на месте настроек.
        import traceback
        print(f"[{plugin_id}] {summary}\n{traceback.format_exc()}", file=sys.stderr)
        return json.dumps([{"type": "divider", "text": summary}], ensure_ascii=False)
    if items is None:
        return "null"

    # Реестры не обнуляем, а дополняем: id теперь выводятся из самой строки,
    # поэтому повторная сборка даёт те же самые, а строки, открытые на экране
    # до пересборки, продолжают попадать в свои колбэки.
    out = []
    identities = {}
    for index, item in enumerate(items):
        try:
            entry = _serialize_setting_item(item, record, "", index, identities)
        except Exception as e:
            instance.log(f"settings item skipped: {type(e).__name__}: {e}")
            continue
        if entry is not None:
            out.append(entry)
    return json.dumps(out, ensure_ascii=False)


# Settings callbacks

def _takes_positional_arg(fn) -> bool:
    try:
        signature = inspect.signature(fn)
    except (TypeError, ValueError):
        return True
    for param in signature.parameters.values():
        if param.kind in (param.POSITIONAL_ONLY, param.POSITIONAL_OR_KEYWORD,
                          param.VAR_POSITIONAL):
            return True
    return False


def _call_with_optional_arg(fn, arg):
    """Call fn(arg), or fn() when the callable declares no parameters."""
    if _takes_positional_arg(fn):
        fn(arg)
    else:
        fn()


def notify_setting_changed(plugin_id: str, key: str, json_value: str) -> None:
    """Persist a changed setting (no UI reload) and invoke its on_change callback."""
    record = plugins.get(plugin_id)
    if record is None:
        return None
    try:
        value = json.loads(json_value)
    except (ValueError, TypeError):
        value = None
    record.instance.set_setting(key, value)
    callback = record.change_callbacks.get(key)
    if callback is not None:
        try:
            _call_with_optional_arg(callback, value)
        except PermissionError as e:  # отказ разрешения — не поломка плагина
            _log_permission_error(plugin_id, e)
    return None


def dispatch_setting_click(plugin_id: str, callback_id: str, view=None) -> None:
    """Invoke the on_click callback registered under *callback_id*.

    Вьюха нажатой строки передаётся первым аргументом: у exteraGram колбэк
    зовётся как ``callback.call(view)``, и плагины привязывают к ней меню.
    """
    record = plugins.get(plugin_id)
    if record is None:
        return None
    callback = record.click_callbacks.get(callback_id)
    if callback is not None:
        try:
            _call_with_optional_arg(callback, view)
        except PermissionError as e:  # отказ разрешения — не поломка плагина
            _log_permission_error(plugin_id, e)
    return None


def _build_custom_view(item, context):
    """Готовая Android-вьюха элемента Custom или None.

    Три источника, в порядке того, как это пишут плагины: явная вьюха
    (`Custom(view=...)`), она же под именем `item`, и фабрика
    SimpleSettingFactory с `create_view`/`bind_view`.
    """
    view = getattr(item, "view", None)
    if view is None:
        view = getattr(item, "item", None)
    if view is not None:
        return view
    factory = getattr(item, "factory", None)
    if factory is None:
        return None
    build = getattr(factory, "build_view", None)
    if callable(build):
        return build(context, False)
    create = getattr(factory, "create_view", None)
    if not callable(create):
        return None
    try:
        view = create(context)
    except TypeError:
        view = create()
    if view is None:
        return None
    bind = getattr(factory, "bind_view", None)
    if callable(bind):
        try:
            bind(view, item, False)
        except TypeError:
            try:
                bind(view)
            except TypeError:
                pass
    return view


def get_custom_setting_view(plugin_id: str, view_id: str, context=None):
    """Вьюха для строки `{"type": "custom"}` — зовётся с UI-потока Java."""
    record = plugins.get(plugin_id)
    if record is None:
        return None
    item = record.custom_views.get(view_id)
    if item is None:
        return None
    try:
        with plugin_context(plugin_id):
            return _build_custom_view(item, context)
    except PermissionError as e:
        _log_permission_error(plugin_id, e)
    except Exception as e:
        record.instance.log(f"custom settings view failed: {type(e).__name__}: {e}")
    return None


# Dev server (port 42690; started by the engine in developer mode)

def scan_capabilities_json(path: str) -> str:
    """Что плагин может делать — по исходнику, без запуска (для диалога установки)."""
    try:
        from . import capability_scan
        return capability_scan.scan_json(path)
    except Exception as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)


def get_audit_journal_json(plugin_id: Optional[str] = None, limit: int = 100) -> str:
    """Последние наблюдения audit-гейта (для экрана «Что делал плагин»)."""
    try:
        from . import audit_gate
        return json.dumps(audit_gate.get_journal(plugin_id, limit), ensure_ascii=False)
    except Exception as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)


def get_audit_profile_json(plugin_id: Optional[str] = None) -> str:
    """Счётчики по категориям: что плагин делал по факту, а не по манифесту."""
    try:
        from . import audit_gate
        return json.dumps(audit_gate.get_profile(plugin_id), ensure_ascii=False)
    except Exception as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)


def forget_audit(plugin_id: str) -> None:
    try:
        from . import audit_gate
        audit_gate.clear_plugin(plugin_id)
    except Exception:
        pass


_dev_server_started = False


def start_dev_server() -> None:
    """Start the TCP/JSON dev server once (guarded, never raises)."""
    global _dev_server_started
    if _dev_server_started:
        return None
    _dev_server_started = True
    try:
        import dev_server
        dev_server.start()
    except Exception as e:
        print(f"[exteraless:plugin_loader] dev server start failed: {e}",
              file=sys.stderr)
    return None


# Песочницу ставим на импорте модуля: враппер builtins.__import__ должен
# оказаться в цепочке ДО того, как elyx_runtime.namespace захватит себе
# _ORIGINAL_IMPORT (elyx_runtime/namespace.py:53) — иначе импорты Elyx-плагинов
# пойдут мимо нас. plugin_loader импортируется движком раньше elyx_runtime.
_install_sandbox()

# Re-expose previously installed shared libs (pip_controller) at engine start.
if pip_controller is not None:
    try:
        pip_controller.restore_sys_path()
    except Exception as e:
        print(f"[exteraless:plugin_loader] restore_sys_path failed: {e}",
              file=sys.stderr)
