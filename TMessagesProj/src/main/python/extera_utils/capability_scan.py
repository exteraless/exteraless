"""Что плагин может делать — по исходнику, без его запуска.

Зачем: `__permissions__` объявляет меньшинство плагинов. Из 512 плагинов двух
каталогов большинство не объявляет ничего, и диалог установки показывал либо
пусто, либо «получит всё». Ни то ни другое не даёт человеку решить.

Улики называются техническими именами (`requests`, `SendMessagesHelper`,
`sqlite3`): они одинаково читаются при любом языке интерфейса и указывают
прямо на строку в исходнике, которую можно проверить.

Разбор идёт AST-парсером и поиском по тексту, код не исполняется. Это
догадка по исходнику, а не гарантия: обфускация и вычисляемые имена его
обходят. Поэтому итог называется «что плагин может делать», а не «что он
делает», и решение остаётся за пользователем.

Обратная сторона тоже важна: ненайденное не значит невозможное. Настоящую
границу держат гейты во время работы (audit_gate, PluginSinkGate); этот
разбор — только чтобы спросить разрешения осмысленно, а не списком из восьми
галочек.
"""

import ast
import base64
import bz2
import codecs
import gzip
import lzma
import marshal
import re
import types
import zipfile
import zlib
from typing import Dict, List, Optional, Tuple

PERM_MESSAGES_READ = "messages.read"
PERM_MESSAGES_SEND = "messages.send"
PERM_NETWORK = "network"
PERM_FILES = "files"
PERM_INTENTS = "intents"
PERM_SETTINGS = "settings"
PERM_HOOKS = "hooks"
PERM_NATIVE = "native"

#: Признак -> (разрешение, человеческое имя улики).
#: Ключ ищется как подстрока исходника; порядок не важен, дубли схлопываются.
_MARKERS = (
    # ---- сеть ----
    ("import requests", PERM_NETWORK, "requests"),
    ("urllib.request", PERM_NETWORK, "urllib"),
    ("http.client", PERM_NETWORK, "http.client"),
    ("import socket", PERM_NETWORK, "socket"),
    ("websocket", PERM_NETWORK, "websocket"),
    ("java.net", PERM_NETWORK, "java.net"),
    ("HttpURLConnection", PERM_NETWORK, "HttpURLConnection"),
    ("OkHttpClient", PERM_NETWORK, "okhttp"),
    ("WebView", PERM_NETWORK, "WebView"),
    ("DownloadManager", PERM_NETWORK, "DownloadManager"),
    ("loadHttpFile", PERM_NETWORK, "loadHttpFile"),
    ("setDataSource", PERM_NETWORK, "setDataSource"),
    # ---- чтение переписки ----
    ("MessagesStorage", PERM_MESSAGES_READ, "MessagesStorage"),
    ("SQLiteDatabase", PERM_MESSAGES_READ, "MessagesStorage"),
    ("queryFinalized", PERM_MESSAGES_READ, "SQLite"),
    ("on_update", PERM_MESSAGES_READ, "on_update"),
    ("add_request_hook", PERM_MESSAGES_READ, "request hooks"),
    ("send_request(", PERM_NETWORK, "send_request"),
    ("send_request(", PERM_MESSAGES_READ, "send_request"),
    ("sendRequest(", PERM_MESSAGES_READ, "sendRequest"),
    ("get_messages", PERM_MESSAGES_READ, "getMessages"),
    ("getMessages", PERM_MESSAGES_READ, "getMessages"),
    # ---- отправка ----
    ("SendMessagesHelper", PERM_MESSAGES_SEND, "SendMessagesHelper"),
    ("send_message(", PERM_MESSAGES_SEND, "send_message"),
    ("send_text(", PERM_MESSAGES_SEND, "send_text"),
    ("on_send_message", PERM_MESSAGES_SEND, "on_send_message"),
    ("TL_messages_send", PERM_MESSAGES_SEND, "TL_messages_send"),
    ("TL_messages_edit", PERM_MESSAGES_SEND, "TL_messages_edit"),
    ("TL_messages_delete", PERM_MESSAGES_SEND, "TL_messages_delete"),
    ("ConnectionsManager", PERM_MESSAGES_SEND, "ConnectionsManager"),
    # ---- файлы ----
    ("java.io.File", PERM_FILES, "java.io.File"),
    ("FileOutputStream", PERM_FILES, "FileOutputStream"),
    ("ContentResolver", PERM_FILES, "ContentResolver"),
    ("MediaStore", PERM_FILES, "MediaStore"),
    ("import shutil", PERM_FILES, "shutil"),
    ("os.remove", PERM_FILES, "os.remove"),
    ("os.listdir", PERM_FILES, "os.listdir"),
    ("sqlite3", PERM_FILES, "sqlite3"),
    # ---- интенты ----
    ("startActivity", PERM_INTENTS, "startActivity"),
    ("android.content.Intent", PERM_INTENTS, "Intent"),
    ("register_intent_handler", PERM_INTENTS, "intent hooks"),
    # ---- настройки приложения ----
    ("NekoConfig", PERM_SETTINGS, "app config"),
    ("NaConfig", PERM_SETTINGS, "app config"),
    ("ExteraConfig", PERM_SETTINGS, "app config"),
    # ---- хуки и код на ходу ----
    ("MethodHook", PERM_HOOKS, "Xposed"),
    ("hook_method", PERM_HOOKS, "Xposed"),
    ("hook_all_methods", PERM_HOOKS, "Xposed"),
    ("hook_all_constructors", PERM_HOOKS, "Xposed"),
    ("XposedBridge", PERM_HOOKS, "Xposed"),
    ("InMemoryDexClassLoader", PERM_HOOKS, "DexClassLoader"),
    ("DexClassLoader", PERM_HOOKS, "DexClassLoader"),
    ("generate_proxy_class", PERM_HOOKS, "class proxy"),
    ("java_subclass", PERM_HOOKS, "class proxy"),
    ("joverride", PERM_HOOKS, "class proxy"),
    ("allocate_instance", PERM_HOOKS, "class proxy"),
    ("deoptimize", PERM_HOOKS, "deoptimize"),
    # ---- нативный код ----
    ("import ctypes", PERM_NATIVE, "ctypes"),
    ("ctypes.CDLL", PERM_NATIVE, "ctypes"),
    ("CDLL(", PERM_NATIVE, "ctypes"),
    ("loadLibrary", PERM_NATIVE, "loadLibrary"),
    (".so\"", PERM_NATIVE, "native library"),
    (".so'", PERM_NATIVE, "native library"),
)

KEY_OBFUSCATION = "obfuscation"

#: Упаковщики, которые подписываются сами.
_OBF_PACKERS = (
    ("__pyarmor__", "pyarmor"),
    ("pytransform", "pyarmor"),
    ("pyarmor_runtime", "pyarmor"),
    ("PYARMOR", "pyarmor"),
    ("pyminifier", "pyminifier"),
    ("Sourcedefender", "sourcedefender"),
    ("sourcedefender", "sourcedefender"),
)

#: Распаковщики: сами по себе законны, уликой становятся в паре с exec/eval.
_OBF_DECODERS = (
    ("marshal.loads(", "marshal"),
    ("zlib.decompress(", "zlib"),
    ("lzma.decompress(", "lzma"),
    ("bz2.decompress(", "bz2"),
    ("b64decode(", "base64"),
    ("b85decode(", "base85"),
    ("a85decode(", "base85"),
    ("b32decode(", "base32"),
    ("b16decode(", "base16"),
    ("unhexlify(", "unhexlify"),
    ("codecs.decode(", "codecs"),
)

#: Вызов exec/eval/compile, а не re.compile и не чужой метод .exec().
_OBF_EXEC = re.compile(r"(?<![\w.])(?:exec|eval|compile)\s*\(")

#: Имена вида _lt6i9dini5txqwg60v06rmg44mp6x5tv: подчёркивание, буква-другая и
#: длинный хвост из строчных букв и цифр. Человек так переменные не называет.
_OBF_NAME = re.compile(r"\b_[A-Za-z]{0,2}[0-9a-z]{16,}\b")

_OBF_HEX = re.compile(r"\\x[0-9a-fA-F]{2}")

#: Столько разных нечитаемых имён считаем перезаписью всего файла, а не
#: одним неудачно названным полем.
_OBF_NAME_LIMIT = 8

#: Длина строки, после которой исходник перестаёт быть читаемым глазами.
_OBF_LINE_LIMIT = 2000

_OBF_HEX_LIMIT = 50


def _detect_obfuscation(source: str) -> List[str]:
    """Улики того, что исходник намеренно сделан нечитаемым.

    Разбор возможностей выше опирается на то, что в тексте видны настоящие
    имена. Обфускация ровно это и ломает: `ctypes` превращается в
    `_lui3h1my3nt73zqovaek04oy4snpuk`, ни один маркер не совпадает, и диалог
    установки честно показывает пустой список. Поэтому нечитаемость — сама по
    себе улика, и человеку её нужно назвать.
    """
    strong: List[str] = []
    weak: List[str] = []

    def note(bucket: List[str], name: str) -> None:
        if name not in bucket:
            bucket.append(name)

    for marker, evidence in _OBF_PACKERS:
        if marker in source:
            note(strong, evidence)

    if _OBF_EXEC.search(source):
        for marker, evidence in _OBF_DECODERS:
            if marker in source:
                note(strong, "exec+" + evidence)

    names = set(_OBF_NAME.findall(source))
    if len(names) >= _OBF_NAME_LIMIT:
        note(strong, "mangled names")

    lines = source.splitlines() or [""]
    if max(len(line) for line in lines) >= _OBF_LINE_LIMIT:
        note(weak, "long lines")

    if len(_OBF_HEX.findall(source)) >= _OBF_HEX_LIMIT:
        note(weak, "escaped strings")

    if strong:
        return strong + weak
    if len(weak) >= 2:
        return weak
    return []


#: Файл больше этого не разбираем: плагины такого размера не встречаются,
#: а на упавшем установщике польза от разбора отрицательная.
_MAX_SOURCE_BYTES = 4 * 1024 * 1024


_MAX_ARCHIVE_BYTES = 16 * 1024 * 1024

_SOURCE_MEMBER_SUFFIXES = (".py", ".pyc")


def _merge(target: Dict[str, List[str]], addition: Dict[str, List[str]]) -> None:
    for permission, evidence in addition.items():
        bucket = target.setdefault(permission, [])
        for item in evidence:
            if item not in bucket:
                bucket.append(item)


_MAX_DECODED_BYTES = 256 * 1024

_MAX_DECODE_DEPTH = 2

_MAX_LITERALS = 512

_MIN_DECODED_CHARS = 12

_MIN_LITERAL_CHARS = 4

_MIN_PRINTABLE = 0.75

_HAS_PRINTABLE = re.compile(r"[\x20-\x7e\u0400-\u04ff]{8,}")


def _inflate(name, function, raw):
    try:
        data = function(raw)
    except Exception:
        return None
    if not isinstance(data, (bytes, bytearray)) or not data:
        return None
    if len(data) > _MAX_DECODED_BYTES:
        return None
    return name, bytes(data)


_DECODERS = (
    ("base64", lambda raw: base64.b64decode(raw + b"=" * (-len(raw) % 4), validate=False)),
    ("base64/url", lambda raw: base64.urlsafe_b64decode(raw + b"=" * (-len(raw) % 4))),
    ("base32", lambda raw: base64.b32decode(raw + b"=" * (-len(raw) % 8), casefold=True)),
    ("base16", lambda raw: base64.b16decode(raw, casefold=True)),
    ("base85", lambda raw: base64.b85decode(raw)),
    ("base85/ascii", lambda raw: base64.a85decode(raw)),
    ("zlib", lambda raw: zlib.decompressobj().decompress(raw, _MAX_DECODED_BYTES)),
    ("gzip", lambda raw: zlib.decompressobj(16 + zlib.MAX_WBITS).decompress(raw, _MAX_DECODED_BYTES)),
    ("bz2", lambda raw: bz2.BZ2Decompressor().decompress(raw, _MAX_DECODED_BYTES)),
    ("lzma", lambda raw: lzma.LZMADecompressor().decompress(raw, _MAX_DECODED_BYTES)),
    ("escapes", lambda raw: codecs.decode(raw.decode("latin-1", "replace"), "unicode_escape").encode("latin-1", "replace")),
    ("rot13", lambda raw: codecs.decode(raw.decode("utf-8", "replace"), "rot13").encode("utf-8")),
    ("reversed", lambda raw: raw[::-1]),
)


def _hex_payload(raw: bytes) -> Optional[bytes]:
    text = bytes(raw).strip()
    if len(text) < _MIN_DECODED_CHARS or len(text) % 2:
        return None
    if not all(c in b"0123456789abcdefABCDEF" for c in text):
        return None
    try:
        return bytes.fromhex(text.decode("ascii"))
    except Exception:
        return None


def _readable(data: bytes) -> Optional[str]:
    if not data or len(data) > _MAX_DECODED_BYTES:
        return None
    text = data.decode("utf-8", "replace")
    if len(text) < _MIN_DECODED_CHARS:
        return None
    if not _HAS_PRINTABLE.search(text):
        return None
    printable = sum(1 for ch in text if ch.isprintable() or ch in "\r\n\t")
    if printable / len(text) < _MIN_PRINTABLE:
        return None
    return text


_CONTAINER_MAGIC = (b"\x1f\x8b", b"BZh", b"\xfd7zXZ", b"\x78\x01", b"\x78\x5e",
                    b"\x78\x9c", b"\x78\xda", b"\x04\x22\x4d\x18")

_ENCODED_TEXT = re.compile(r"^[A-Za-z0-9+/=\-_]{24,}$")


def _worth_decoding(raw: bytes) -> bool:
    if len(raw) < _MIN_DECODED_CHARS:
        return False
    if raw.startswith(_CONTAINER_MAGIC):
        return True
    printable = sum(1 for byte in raw if 32 <= byte < 127)
    if printable / len(raw) < 0.95:
        return True
    return bool(_ENCODED_TEXT.match(raw.decode("ascii", "ignore")))


def _decode_payload(raw: bytes) -> List[Tuple[str, bytes]]:
    out: List[Tuple[str, bytes]] = []
    if not _worth_decoding(raw):
        return out
    seen = set()
    candidates = [raw, _hex_payload(raw)]
    for candidate in candidates:
        if not candidate:
            continue
        for name, function in _DECODERS:
            result = _inflate(name, function, candidate)
            if result is None:
                continue
            label, data = result
            if data == raw or label in seen:
                continue
            if _readable(data) is None and not data.startswith(_CONTAINER_MAGIC):
                continue
            seen.add(label)
            out.append((label, data))
    try:
        code = marshal.loads(raw)
    except Exception:
        code = None
    if isinstance(code, types.CodeType):
        text = "\n".join(_code_strings(code))
        if text and "marshal" not in seen:
            out.append(("marshal", text.encode("utf-8", "replace")))
    return out


def _is_minus_one(node) -> bool:
    if isinstance(node, ast.Constant):
        return node.value == -1
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
        inner = node.operand
        return isinstance(inner, ast.Constant) and inner.value == 1
    return False


def _constant_string(node) -> Optional[str]:
    if isinstance(node, ast.Constant):
        value = node.value
        if isinstance(value, str):
            return value
        if isinstance(value, bytes):
            return value.decode("latin-1")
        return None
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        left = _constant_string(node.left)
        right = _constant_string(node.right)
        if left is not None and right is not None:
            return left + right
        return None
    if isinstance(node, ast.JoinedStr):
        parts = [_constant_string(part) for part in node.values]
        if all(part is not None for part in parts):
            return "".join(parts)
        return None
    if isinstance(node, ast.Subscript) and isinstance(node.slice, ast.Slice):
        step = node.slice.step
        if _is_minus_one(step) and node.slice.lower is None and node.slice.upper is None:
            base = _constant_string(node.value)
            if base is not None:
                return base[::-1]
        return None
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
        if node.func.attr == "decode":
            return _constant_string(node.func.value)
        if node.func.attr != "join" or not node.args:
            return None
        separator = _constant_string(node.func.value)
        items = node.args[0]
        if separator is None or not isinstance(items, (ast.List, ast.Tuple, ast.Set)):
            return None
        parts = [_constant_string(item) for item in items.elts]
        if all(part is not None for part in parts):
            return separator.join(parts)
    return None


def _literals(source: str) -> List[str]:
    out: List[str] = []
    seen = set()
    try:
        tree = ast.parse(source)
    except Exception:
        for match in re.finditer(r"""(?s)(""" + "'''" + r"""|['"])(.{12,}?)\1""", source):
            value = match.group(2)
            if value not in seen:
                seen.add(value)
                out.append(value)
        return out[: _MAX_LITERALS]
    for node in ast.walk(tree):
        plain = (isinstance(node, ast.Constant)
                 and isinstance(node.value, (str, bytes)))
        if not plain and not isinstance(node, (ast.BinOp, ast.JoinedStr,
                                               ast.Call, ast.Subscript)):
            continue
        value = _constant_string(node)
        if value is None or len(value) < _MIN_LITERAL_CHARS or value in seen:
            continue
        seen.add(value)
        out.append(value)
        if len(out) >= _MAX_LITERALS:
            break
    return out


def _code_strings(code) -> List[str]:
    out: List[str] = []
    seen = set()
    stack = [code]
    while stack:
        current = stack.pop()
        names = list(getattr(current, "co_names", ()) or ())
        for const in list(getattr(current, "co_consts", ()) or ()):
            if isinstance(const, str):
                names.append(const)
            elif isinstance(const, types.CodeType):
                stack.append(const)
        for name in names:
            if isinstance(name, str) and name not in seen:
                seen.add(name)
                out.append(name)
    return out


def _scan_bytecode(raw: bytes, found: Dict[str, List[str]]) -> None:
    strings: List[str] = []
    for payload in (raw[16:], raw):
        try:
            code = marshal.loads(payload)
        except Exception:
            continue
        if isinstance(code, types.CodeType):
            strings = _code_strings(code)
            break
    for name in strings:
        _note_module(found, name)
        _note_name(found, name)
    text = "\n".join(strings)
    if not text:
        text = raw.decode("latin-1", "replace")
    _merge(found, _scan_source(text))


_IMMEDIATE = frozenset({"rot13", "reversed"})

_CODE_LIKE = re.compile(r"^\s*(?:import|from|exec|eval|compile|def|class)\b")


def _merge_decoded(found: Dict[str, List[str]], raw: bytes, depth: int,
                   prefix: str = "", last: str = "") -> None:
    if depth >= _MAX_DECODE_DEPTH:
        return
    for label, data in _decode_payload(raw):
        if label in _IMMEDIATE and label == last:
            continue
        name = prefix + label
        text = _readable(data)
        if text is not None:
            inner = _scan_source(text, depth + 1)
            for permission, names in inner.items():
                bucket = found.setdefault(permission, [])
                for item in names:
                    evidence = f"decoded({name}): {item}"
                    if evidence not in bucket:
                        bucket.append(evidence)
        _merge_decoded(found, data, depth + 1, name + "/", label)


def _scan_source(source: str, depth: int = 0) -> Dict[str, List[str]]:
    found: Dict[str, List[str]] = {}
    for marker, permission, evidence in _MARKERS:
        if marker in source and evidence not in found.setdefault(permission, []):
            found[permission].append(evidence)

    _merge(found, _scan_imports(source))

    for literal in _literals(source):
        _note_module(found, literal)
        _note_name(found, literal)
        if _CODE_LIKE.match(literal):
            _merge(found, _scan_source(literal, depth + 1))
        _merge_decoded(found, literal.encode("utf-8", "replace"), depth)

    obfuscation = _detect_obfuscation(source)
    if obfuscation:
        found[KEY_OBFUSCATION] = obfuscation

    return {perm: names for perm, names in found.items() if names}


def _note_opaque(found: Dict[str, List[str]], evidence: str) -> None:
    bucket = found.setdefault(KEY_OBFUSCATION, [])
    if evidence not in bucket:
        bucket.append(evidence)


def _is_bytecode(name: str, raw: bytes) -> bool:
    if name.endswith(".pyc"):
        return True
    if raw[2:4] != b"\r\n":
        return False
    try:
        raw.decode("utf-8")
    except UnicodeDecodeError:
        return True
    return False


def _scan_archive(path: str) -> Dict[str, List[str]]:
    found: Dict[str, List[str]] = {}
    budget = _MAX_ARCHIVE_BYTES
    with zipfile.ZipFile(path) as archive:
        members = [info for info in archive.infolist()
                   if not info.is_dir()
                   and info.filename.endswith(_SOURCE_MEMBER_SUFFIXES)]
        for index, info in enumerate(members):
            if budget <= 0:
                _note_opaque(found, f"unscanned files ({len(members) - index})")
                break
            limit = min(_MAX_SOURCE_BYTES, budget)
            try:
                with archive.open(info) as handle:
                    raw = handle.read(limit + 1)
            except Exception:
                continue
            truncated = len(raw) > limit
            raw = raw[:limit]
            budget -= len(raw)
            if _is_bytecode(info.filename, raw):
                _note_opaque(found, f"compiled bytecode ({info.filename})")
                _scan_bytecode(raw, found)
                continue
            if truncated:
                _note_opaque(found, f"truncated source ({info.filename})")
            _merge(found, _scan_source(raw.decode("utf-8", errors="replace")))
    return {perm: names for perm, names in found.items() if names}


def scan(path: str) -> Dict[str, List[str]]:
    """{разрешение: [улики]} — что плагин по исходнику может делать."""
    try:
        if zipfile.is_zipfile(path):
            return _scan_archive(path)
        with open(path, "rb") as handle:
            raw = handle.read(_MAX_SOURCE_BYTES + 1)
    except Exception:
        return {}

    truncated = len(raw) > _MAX_SOURCE_BYTES
    raw = raw[:_MAX_SOURCE_BYTES]
    if _is_bytecode(path, raw):
        found = {KEY_OBFUSCATION: ["compiled bytecode"]}
        _scan_bytecode(raw, found)
        return found

    found = _scan_source(raw.decode("utf-8", errors="replace"))
    if truncated:
        _note_opaque(found, "truncated source")
    return found


#: Что именно импортируют из пакетов мессенджера. Пакет целиком ни о чём не
#: говорит: из org.telegram.messenger берут и MessagesController, и
#: AndroidUtilities.dp() — по первому спрашивать разрешение нужно, по второму
#: нет, иначе диалог будет требовать доступ к переписке у каждого плагина.
_IMPORTED_NAMES = {
    "MessagesController": (PERM_MESSAGES_READ, "MessagesController"),
    "MessagesStorage": (PERM_MESSAGES_READ, "MessagesStorage"),
    "MessageObject": (PERM_MESSAGES_READ, "MessageObject"),
    "NotificationCenter": (PERM_MESSAGES_READ, "NotificationCenter"),
    "SendMessagesHelper": (PERM_MESSAGES_SEND, "SendMessagesHelper"),
    "ConnectionsManager": (PERM_MESSAGES_SEND, "ConnectionsManager"),
    "ContentResolver": (PERM_FILES, "ContentResolver"),
    "MediaStore": (PERM_FILES, "MediaStore"),
    "WebView": (PERM_NETWORK, "WebView"),
    "InMemoryDexClassLoader": (PERM_HOOKS, "DexClassLoader"),
    "DexClassLoader": (PERM_HOOKS, "DexClassLoader"),
    "XposedBridge": (PERM_HOOKS, "Xposed"),
    "XposedHelpers": (PERM_HOOKS, "Xposed"),
}


_IMPORTED_MODULES = {
    "ctypes": (PERM_NATIVE, "ctypes"),
    "requests": (PERM_NETWORK, "requests"),
    "httpx": (PERM_NETWORK, "httpx"),
    "aiohttp": (PERM_NETWORK, "aiohttp"),
    "urllib": (PERM_NETWORK, "urllib"),
    "urllib3": (PERM_NETWORK, "urllib"),
    "http": (PERM_NETWORK, "http.client"),
    "socket": (PERM_NETWORK, "socket"),
    "socketserver": (PERM_NETWORK, "socket"),
    "ftplib": (PERM_NETWORK, "ftplib"),
    "smtplib": (PERM_NETWORK, "smtplib"),
    "telnetlib": (PERM_NETWORK, "telnetlib"),
    "websocket": (PERM_NETWORK, "websocket"),
    "websockets": (PERM_NETWORK, "websocket"),
    "subprocess": (PERM_NATIVE, "subprocess"),
}


def _note_module(result: Dict[str, List[str]], name: str) -> None:
    rule = _IMPORTED_MODULES.get(name.partition(".")[0])
    if rule is None:
        return
    permission, evidence = rule
    bucket = result.setdefault(permission, [])
    if evidence not in bucket:
        bucket.append(evidence)


def _scan_imports(source: str) -> Dict[str, List[str]]:
    """Импорты: важно не откуда, а что именно."""
    result: Dict[str, List[str]] = {}
    try:
        tree = ast.parse(source)
    except Exception:
        # Битый Python разберём регулярным выражением: диалог установки всё
        # равно должен что-то показать.
        for module, names in re.findall(
                r"^\s*from\s+([A-Za-z0-9_.]+)\s+import\s+([^\n#]+)", source, re.M):
            _note_module(result, module)
            for name in names.split(","):
                _note_name(result, name.strip().split(" as ")[0])
        for names in re.findall(r"^\s*import\s+([^\n#]+)", source, re.M):
            for name in names.split(","):
                _note_module(result, name.strip().split(" as ")[0])
        return result

    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            if node.module:
                _note_module(result, node.module)
            for alias in node.names:
                _note_name(result, alias.name)
        elif isinstance(node, ast.Import):
            for alias in node.names:
                _note_module(result, alias.name)
                _note_name(result, alias.name.rpartition(".")[2])
    return result


def _note_name(result: Dict[str, List[str]], name: str) -> None:
    rule = _IMPORTED_NAMES.get(name)
    if rule is None:
        return
    permission, evidence = rule
    target = result.setdefault(permission, [])
    if evidence not in target:
        target.append(evidence)


def scan_json(path: str) -> str:
    """Для Java-стороны: JSON вида {"network": ["requests", ...], ...}."""
    import json
    try:
        return json.dumps(scan(path), ensure_ascii=False)
    except Exception as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)
