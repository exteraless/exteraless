"""Гейт на audit hooks (PEP 578) — принуждение уровня «действие», а не «имя импорта».

Зачем он, если уже есть врапперы ``__import__``/``open`` в plugin_loader
--------------------------------------------------------------------------
Врапперы стоят на именах: они видят ``import socket`` и запрещают его. Но
модуль, который УЖЕ загружен приложением, достаётся мимо них в одну строку —
``sys.modules["socket"]`` не зовёт ни ``__import__``, ни meta_path-финдер.
Проверено на Pixel 7 (build 37, раунд 2): плагин без разрешения ``network``
получил рабочий ``socket`` и достижимый ``os.system`` при полностью живом
импорт-гейте.

Audit hooks закрывают именно это. Событие возбуждается в C-коде CPython в
точке действия (``socket.connect``, ``os.system``, ``open``), а не в имени,
которое написал плагин, — поэтому способ добычи модуля роли не играет.

Что проверено на устройстве (CPython 3.12.12, Chaquopy 17), раунды 1-4:

* ``sys.addaudithook`` есть и работает; ``sys._clear_audit_hooks`` в этой
  сборке ОТСУТСТВУЕТ — снять хук из Python нечем;
* исключение из хука реально отменяет операцию (``socket.connect``,
  ``os.system``, ``open`` — все три);
* установка чужого хука видна как событие ``sys.addaudithook`` — плагин не
  может тихо повесить свой;
* хук наследуется потоками, созданными после установки;
* атрибуция по стеку работает: в кадрах видно ``audit_probe.plugin``;
* цена: 3000 ``open`` — 220 мкс/шт без хука и 214-216 мкс/шт с хуком, то
  есть в пределах шума при 6000 пойманных событий.

Чего гейт НЕ закрывает
----------------------
Java-сторону. ``Class.forName("java.lang.Runtime")`` + ``getMethod`` +
``invoke`` отработал в том же тесте и породил ровно ноль событий сверх
импорта ``java.lang``: Chaquopy отдаёт живые Java-объекты, а дальше всё
происходит в JVM, где Python-аудита нет. Это чинится только на Java-стороне
(``app.exteraless.plugins.PluginRuntime`` + хуки на стоках) и всё равно не
держит плагин с разрешением ``hooks`` — он снимает наши хуки. См.
см. уровни доступа в app.exteraless.plugins.PluginPermissions.
"""

import os
import sys
import threading
import time
from collections import deque
from typing import Optional

__all__ = ["install", "get_journal", "get_profile", "clear_plugin"]

# Модуль plugin_loader — ставится в install(), чтобы не было цикла импорта.
_loader = None

# Рекурсия: sys._getframe сам возбуждает событие, а атрибуция без него
# невозможна. Флаг потоковый: хук вызывается на том же потоке, что и действие.
_local = threading.local()


# Политика

# Никогда и никому: обход всей модели (нативный код, свой процесс, подмена
# наблюдения). Совпадает с правилами None в plugin_loader._IMPORT_RULES.
_DENY_ALWAYS = {
    "os.system": "run a shell command",
    "os.exec": "replace the process image",
    "os.spawn": "spawn a process",
    "os.posix_spawn": "spawn a process",
    "os.startfile": "spawn a process",
    "os.fork": "fork the process",
    "os.forkpty": "fork the process",
    "subprocess.Popen": "start a subprocess",
    "sys.addaudithook": "install an audit hook",
    "sys.settrace": "trace other code",
    "sys.setprofile": "profile other code",
    "sys._current_frames": "read other threads' stacks",
}

# Разрешение native: загрузка .so и работа с его памятью.
#
# Исключение — библиотеки самого рантайма. Chaquopy подгружает зависимости
# своих же расширений через ctypes: java/android/importer.py, load_needed()
# читает DT_NEEDED у .so и зовёт CDLL на каждую запись. Из-за этого `import PIL`
# в плагине падал с «dlopen failed: library "libjpeg_chaquopy.so" not found»:
# гейт видел плагина в стеке и отказывал интерпретатору. Путь к такой
# библиотеке лежит внутри каталогов рантайма, по нему их и отличаем — своё .so
# плагина оно не пропустит.
#
# Дробить этот набор бессмысленно. Кто получил dlopen и dlsym, тот исполняет
# в нашем процессе произвольный код, и запрет на string_at после этого ничего
# не защищает — он лишь ломает нормальные привязки, которые читают char* из
# ответа библиотеки. Поэтому разрешение одно на всю группу.
_NATIVE = {
    "ctypes.dlopen": "load a native library",
    "ctypes.dlsym": "resolve a native symbol",
    "ctypes.dlsym/handle": "resolve a native symbol",
    "ctypes.dlclose": "unload a native library",
    "ctypes.call_function": "call a native function",
    "ctypes.cdata": "cast a raw memory address",
    "ctypes.cdata/buffer": "wrap raw memory",
    "ctypes.string_at": "read raw memory",
    "ctypes.wstring_at": "read raw memory",
    "ctypes.set_exception": "hijack native error handling",
    "ctypes.addressof": "take the address of an object",
    "ctypes.create_string_buffer": "allocate a raw buffer",
    "ctypes.create_unicode_buffer": "allocate a raw buffer",
    "ctypes.get_errno": "read the native error code",
    "ctypes.set_errno": "set the native error code",
    "ctypes.get_last_error": "read the native error code",
    "ctypes.set_last_error": "set the native error code",
}

# Разрешение network.
_NETWORK = {
    "socket.connect": "connect to the network",
    "socket.bind": "listen on a socket",
    "socket.sendto": "send network data",
    "socket.getaddrinfo": "resolve a host name",
    "socket.gethostbyname": "resolve a host name",
    "socket.gethostbyaddr": "resolve a host name",
    "socket.sethostname": "change the host name",
    "urllib.Request": "make an HTTP request",
    "ftplib.connect": "connect to the network",
    "imaplib.open": "connect to the network",
    "poplib.connect": "connect to the network",
    "smtplib.connect": "connect to the network",
    "nntplib.connect": "connect to the network",
    "telnetlib.Telnet.open": "connect to the network",
}

# Разрешение files: доступ к базам данных приложения идёт мимо open()
# (sqlite3 открывает файл сам, из C), поэтому событие своё.
_DATABASE = {
    "sqlite3.connect": "open a database",
    "sqlite3.connect/handle": "open a database",
}

# Разрешение files. Значение — индекс аргумента с путём (для текста ошибки и
# для проверки «свой каталог»); None — путь не при чём (перечисление и т.п.).
_FILES = {
    "open": 0,
    "os.remove": 0,
    "os.rename": (0, 1),
    "os.rmdir": 0,
    "os.mkdir": 0,
    "os.chdir": 0,
    "os.chmod": 0,
    "os.chown": 0,
    "os.truncate": 0,
    "os.link": (0, 1),
    "os.symlink": (0, 1),
    "os.utime": 0,
    "os.listdir": 0,
    "os.scandir": 0,
    "glob.glob": 0,
    "pathlib.Path.glob": 1,
    "shutil.copyfile": (0, 1),
    "shutil.copymode": 0,
    "shutil.copystat": 0,
    "shutil.copytree": (0, 1),
    "shutil.move": (0, 1),
    "shutil.rmtree": 0,
    "shutil.unpack_archive": 0,
    "tempfile.mkstemp": 0,
}

# Атрибуты чужих кадров: через них читаются глобалы другого модуля, то есть
# и ссылки на всё, что гейт закрыл. Форматированию traceback они не нужны —
# tb_frame/f_code/f_lineno остаются доступными.
_FRAME_ATTRS = frozenset({"f_globals", "f_builtins", "f_locals"})

# Только в журнал: полезно видеть, но запрещать нечего или слишком дорого.
_JOURNAL_ONLY = frozenset({
    "exec", "compile", "import", "marshal.loads", "pickle.find_class",
    "code.__new__", "builtins.input",
})

_WATCHED = frozenset(set(_DENY_ALWAYS) | set(_NATIVE) | set(_NETWORK) | set(_FILES)
                     | set(_DATABASE) | set(_JOURNAL_ONLY))

# Категория для профиля плагина (что он вообще делал).
_CATEGORY = {}
for _e in _DENY_ALWAYS:
    _CATEGORY[_e] = "process" if _e.startswith(("os.", "subprocess")) else "introspection"
for _e in _NATIVE:
    _CATEGORY[_e] = "native"
for _e in _NETWORK:
    _CATEGORY[_e] = "network"
for _e in _FILES:
    _CATEGORY[_e] = "files"
for _e in _DATABASE:
    _CATEGORY[_e] = "database"
for _e in _JOURNAL_ONLY:
    _CATEGORY[_e] = "code" if _e in ("exec", "compile", "marshal.loads",
                                     "code.__new__", "pickle.find_class") else "misc"
_CATEGORY["import"] = "imports"
_CATEGORY["object.__getattr__"] = "introspection"
_CATEGORY["java class"] = "reflection"


# Журнал

_JOURNAL_LIMIT = 400
_journal = deque(maxlen=_JOURNAL_LIMIT)
_profile = {}
_journal_lock = threading.Lock()


#: Насколько далеко назад ищем родительский пакет того же импорта.
_LOOKBACK = 8


def _recent(count: int):
    """Последние *count* записей, новыми вперёд."""
    total = len(_journal)
    return [_journal[i] for i in range(total - 1, max(-1, total - count - 1), -1)]


def _relation(candidate: dict, entry: dict) -> Optional[str]:
    """Как новый импорт относится к уже записанному: "child", "parent" или None.

    Импорт `from org.telegram.messenger import X` порождает событие на каждом
    звене пути. В журнале это одно действие, и описывает его самое длинное имя.
    """
    if candidate["plugin"] != entry["plugin"] or candidate["event"] != entry["event"]:
        return None
    if entry["event"] != "import" or candidate["allowed"] != entry["allowed"]:
        return None
    old_detail, new_detail = candidate["detail"], entry["detail"]
    if not old_detail or not new_detail:
        return None
    if new_detail.startswith(old_detail + "."):
        return "child"
    if old_detail.startswith(new_detail + "."):
        return "parent"
    return None


def _repeats(previous: dict, entry: dict) -> bool:
    """То же самое действие подряд — считаем, а не плодим строки."""
    return (previous["plugin"] == entry["plugin"]
            and previous["event"] == entry["event"]
            and previous["detail"] == entry["detail"]
            and previous["allowed"] == entry["allowed"])


def _record(plugin_id: str, event: str, detail: Optional[str], allowed: bool) -> None:
    category = _CATEGORY.get(event, "misc")
    entry = {
        "ts": int(time.time() * 1000),
        "plugin": plugin_id,
        "event": event,
        "category": category,
        "detail": (detail or "")[:200],
        "allowed": allowed,
        "count": 1,
    }
    with _journal_lock:
        # Импорт `from org.telegram.tgnet import X` возбуждает событие на каждом
        # родителе: org, org.telegram, org.telegram.tgnet — три строки об одном
        # действии. Соседними они не идут: между ними чтения .py-файлов, — так
        # что смотрим на несколько записей назад, а не только на последнюю.
        for candidate in _recent(_LOOKBACK):
            relation = _relation(candidate, entry)
            if relation == "child":
                candidate["detail"] = entry["detail"]
                candidate["ts"] = entry["ts"]
                return
            if relation == "parent":
                # На устройстве события приходят листом вперёд:
                # org.telegram.messenger, затем org.telegram, затем org.
                # Родитель уже описан более точной записью — не плодим строку.
                candidate["ts"] = entry["ts"]
                return
        previous = _journal[-1] if _journal else None
        if previous is not None and _repeats(previous, entry):
            previous["count"] = previous.get("count", 1) + 1
            previous["ts"] = entry["ts"]
        else:
            _journal.append(entry)
        # Счётчик профиля растёт и на повторах: «сколько раз ходил в сеть» —
        # это про число обращений, а не про число строк в журнале.
        per_plugin = _profile.setdefault(plugin_id, {})
        bucket = per_plugin.setdefault(category, {"allowed": 0, "denied": 0})
        bucket["allowed" if allowed else "denied"] += 1


def get_journal(plugin_id: Optional[str] = None, limit: int = 100):
    """Последние наблюдения; для Java — через plugin_loader.get_audit_journal_json."""
    with _journal_lock:
        items = [e for e in _journal if plugin_id is None or e["plugin"] == plugin_id]
    return items[-limit:]


def get_profile(plugin_id: Optional[str] = None):
    """Что плагин делал по факту: счётчики по категориям."""
    with _journal_lock:
        if plugin_id is None:
            return {pid: dict(cats) for pid, cats in _profile.items()}
        return dict(_profile.get(plugin_id, {}))


def note_denied_class(plugin_id: str, name: str, permission: str) -> None:
    """Отказ в Java-классе — в тот же журнал, что и остальное поведение."""
    _record(plugin_id, "java class", name, False)


def clear_plugin(plugin_id: str) -> None:
    """Забыть наблюдения плагина (удаление плагина, сброс профиля)."""
    with _journal_lock:
        _profile.pop(plugin_id, None)
        kept = [e for e in _journal if e["plugin"] != plugin_id]
        _journal.clear()
        _journal.extend(kept)


# Хук

def _arg(args, index):
    try:
        return args[index]
    except Exception:
        return None


def _as_text(value) -> Optional[str]:
    if value is None:
        return None
    try:
        if isinstance(value, (str, bytes, os.PathLike)):
            return os.fsdecode(value)
        return str(value)
    except Exception:
        return None


#: Пути самого рантайма: стандартная библиотека, ассеты Chaquopy, каталоги из
#: sys.path. Открытие файла оттуда — это загрузка модуля, а не «плагин полез в
#: чужие файлы»: на устройстве `import shlex` упирался в stdlib-common.imy и
#: плагин без разрешения files не мог даже загрузиться.
_runtime_prefixes = None


def _runtime_paths():
    global _runtime_prefixes
    if _runtime_prefixes is not None:
        return _runtime_prefixes
    prefixes = set()
    try:
        for path in (sys.prefix, sys.base_prefix, os.path.dirname(os.__file__)):
            if path:
                prefixes.add(os.path.realpath(path))
        for entry in list(sys.path):
            if entry and os.path.isabs(entry):
                prefixes.add(os.path.realpath(entry))
        # Каталог ассетов Chaquopy лежит рядом с записями sys.path, но не всегда
        # среди них: stdlib-common.imy читается по пути files/chaquopy/...
        for entry in list(prefixes):
            marker = os.sep + "chaquopy" + os.sep
            index = entry.find(marker)
            if index > 0:
                prefixes.add(entry[:index + len(marker) - 1])
    except Exception:
        pass
    _runtime_prefixes = tuple(sorted(prefixes))
    return _runtime_prefixes


def _is_runtime_file(path: Optional[str]) -> bool:
    if not path:
        return False
    try:
        target = os.path.realpath(path)
    except Exception:
        target = path
    for prefix in _runtime_paths():
        if target == prefix or target.startswith(prefix + os.sep):
            return True
    return False


def _is_own_file(plugin_id: str, path: Optional[str]) -> bool:
    if not path:
        return False
    try:
        from file_utils import _is_own_path
        return bool(_is_own_path(plugin_id, path))
    except Exception:
        return False


def _hook(event, args, _watched=None, _loader_ref=None):
    """Точка входа PEP 578.

    ``_watched`` связывается значением на момент установки хука: раньше набор
    читался из глобали, и код плагина выключал гейт целиком одной строкой
    (``audit_gate._WATCHED.clear()``). Подмена глобали теперь ни на что не
    влияет, а сам набор — frozenset.
    """
    if _watched is None:
        _watched = _WATCHED
    if event not in _watched:
        return
    if _loader_ref is None:
        _loader_ref = _loader
    if getattr(_local, "busy", False):
        return  # мы сами внутри атрибуции: sys._getframe и open() тоже аудируются
    _local.busy = True
    try:
        _check(event, args, _loader_ref)
    except BaseException as e:
        # Отказ обязан долететь до плагина. Раньше здесь стояло `except
        # PermissionError: raise`, и когда сетевой отказ стал бросать
        # ConnectionRefusedError, его глотала же эта обёртка — гейт переставал
        # что-либо запрещать. Поэтому решает метка, а не тип исключения.
        if getattr(e, _DENIAL_MARK, False):
            raise
        if isinstance(e, PermissionError):
            raise
        # Сломанная проверка не должна ломать приложение.
    finally:
        _local.busy = False


#: Метка «это наш отказ, его нельзя глотать».
_DENIAL_MARK = "_exteraless_denial"


def _denial(error):
    setattr(error, _DENIAL_MARK, True)
    return error


def _network_detail(event, args) -> Optional[str]:
    """Адрес человеческим видом: `host:port`, а не repr сокета."""
    if event in ("socket.connect", "socket.bind", "socket.sendto"):
        address = _arg(args, 1)
        if isinstance(address, tuple) and len(address) >= 2:
            return "%s:%s" % (address[0], address[1])
        return _as_text(address)
    if event == "urllib.Request":
        return _as_text(_arg(args, 0))
    return _as_text(_arg(args, 0))


def _deny_network(plugin_id: str, event: str, what: str, detail: Optional[str]):
    """Отказ в сети — сетевой ошибкой, а не PermissionError.

    Плагин ждёт от сети отказов и умеет их показывать; PermissionError из
    середины socket.connect он не ждёт, и команда просто молча ничего не
    делала — так это и выглядело у пользователя. Поэтому бросаем то, что
    соответствует операции: несуществующий хост для резолва, отказ в
    соединении для подключения.
    """
    _loader.log_denial(plugin_id, event, what)
    message = ("plugin %r is not allowed to reach the network: grant it the "
               "'network' permission on the plugin's screen" % plugin_id)
    if event in ("socket.getaddrinfo", "socket.gethostbyname", "socket.gethostbyaddr"):
        socket_module = sys.modules.get("socket")
        error = getattr(socket_module, "gaierror", None) if socket_module else None
        if error is not None:
            raise _denial(error(-2, message))
    raise _denial(ConnectionRefusedError(message))


def _foreign_code_owner(event, args, plugin_id, loader):
    """id чужого плагина, чьим именем помечен компилируемый код, или None.

    Владелец действия определяется по ``co_filename`` кадра, а имя файла в
    ``compile()`` — произвольная строка. Подставив путь чужого плагина, плагин
    действовал от его имени: с его разрешениями и с его именем в журнале.
    Файл существовать не обязан, а пути предсказуемы — плагин лежит как
    ``<каталог плагинов>/<id>.py``.
    """
    if loader is None:
        return None
    try:
        if event == "compile":
            path = _arg(args, 1)
        else:
            path = getattr(_arg(args, 0), "co_filename", None)
        if not isinstance(path, str) or not path or path.startswith("<"):
            return None
        owner = loader._resolve_owner(path)
    except Exception:
        return None
    return owner if owner is not None and owner != plugin_id else None


def _check(event, args, loader):
    if event == "object.__getattr__":
        name = _arg(args, 1)
        if name not in _FRAME_ATTRS:
            return

    plugin_id = loader.plugin_frame_owner() if loader is not None else None
    if plugin_id is None:
        return  # код SDK или приложения — гейтить нечего

    if loader.unsafe_mode():
        return

    if event in ("compile", "exec"):
        victim = _foreign_code_owner(event, args, plugin_id, loader)
        if victim is not None:
            _record(plugin_id, event, victim, False)
            raise _denial(PermissionError(
                f"plugin {plugin_id!r} cannot run code labelled as {victim!r}: "
                f"co_filename of another plugin"))

    if event in _JOURNAL_ONLY:
        detail = _as_text(_arg(args, 0))
        _record(plugin_id, event, detail, True)
        return

    if event == "object.__getattr__":
        _record(plugin_id, event, _as_text(_arg(args, 1)), False)
        raise _denial(PermissionError(
            f"plugin {plugin_id!r} cannot read {_arg(args, 1)!r} of another frame: "
            f"frame introspection is never available to plugins"))

    what = _DENY_ALWAYS.get(event)
    if what is not None:
        detail = _as_text(_arg(args, 0))
        _record(plugin_id, event, detail, False)
        loader.log_denial(plugin_id, event, what)
        raise _denial(PermissionError(
            f"plugin {plugin_id!r} cannot {what}: this is never available to "
            f"plugins ({event})"))

    what = _NATIVE.get(event)
    if what is not None:
        detail = _as_text(_arg(args, 0))
        if _is_runtime_file(detail):
            return  # рантайм грузит зависимость своего же модуля, а не плагин лезет в FFI
        allowed = loader.has_permission(loader.PERM_NATIVE, plugin_id)
        _record(plugin_id, event, detail, allowed)
        loader.require_permission(loader.PERM_NATIVE, what,
                                   detail=detail, plugin_id=plugin_id)
        return

    what = _NETWORK.get(event)
    if what is not None:
        detail = _network_detail(event, args)
        allowed = loader.has_permission(loader.PERM_NETWORK, plugin_id)
        _record(plugin_id, event, detail, allowed)
        if not allowed:
            _deny_network(plugin_id, event, what, detail)
        return

    if event in _DATABASE:
        path = _as_text(_arg(args, 0))
        if _is_own_file(plugin_id, path) or _is_runtime_file(path):
            return
        allowed = loader.has_permission(loader.PERM_FILES, plugin_id)
        _record(plugin_id, event, path, allowed)
        loader.require_permission(loader.PERM_FILES, _DATABASE[event],
                                   detail=path, plugin_id=plugin_id)
        return

    if event in _FILES:
        index = _FILES[event]
        # У os.rename, os.link и shutil.move путей два, и раньше проверялся
        # только первый: плагин переносил свой файл поверх любого файла
        # приложения, не имея разрешения files.
        indexes = index if isinstance(index, tuple) else (index,)
        for one in indexes:
            path = _as_text(_arg(args, one)) if one is not None else None
            if path is None:
                continue
            if event == "open" and getattr(loader._context_state, "open_checked", None) == path:
                continue
            if _is_own_file(plugin_id, path):
                continue  # свой каталог плагину открыт всегда
            if _is_runtime_file(path):
                continue  # это загрузка модуля интерпретатором
            allowed = loader.has_permission(loader.PERM_FILES, plugin_id)
            _record(plugin_id, event, path, allowed)
            if allowed:
                return
            loader.require_permission(loader.PERM_FILES, _file_verb(event),
                                       detail=path, plugin_id=plugin_id)
            return
        return


def _file_verb(event: str) -> str:
    if event == "open":
        return "open a file"
    if event in ("os.listdir", "os.scandir", "glob.glob", "pathlib.Path.glob"):
        return "list a directory"
    return "modify files"


_installed = False


def install(loader_module) -> bool:
    """Поставить хук. Идемпотентно, не бросает. True — хук стоит."""
    global _loader, _installed
    _loader = loader_module
    if _installed:
        return True
    try:
        bound_watched = _WATCHED
        bound_hook = _hook
        bound_loader = loader_module
        sys.addaudithook(
            lambda event, args: event in bound_watched
            and bound_hook(event, args, bound_watched, bound_loader))
        _installed = True
    except Exception as e:  # интерпретатор без PEP 578 — работаем на врапперах
        print(f"[exteraless:audit_gate] install failed: {e}", file=sys.stderr)
        return False
    return True
