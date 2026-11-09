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
import re
import struct
import zipfile
import zlib
from typing import Dict, List

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
    ("add_hook(", PERM_MESSAGES_READ, "request hooks"),
    ("send_request(", PERM_NETWORK, "send_request"),
    ("send_request(", PERM_MESSAGES_READ, "send_request"),
    ("sendRequest(", PERM_MESSAGES_READ, "sendRequest"),
    ("get_messages", PERM_MESSAGES_READ, "getMessages"),
    ("getMessages", PERM_MESSAGES_READ, "getMessages"),
    # ---- отправка ----
    ("SendMessagesHelper", PERM_MESSAGES_SEND, "SendMessagesHelper"),
    ("send_message(", PERM_MESSAGES_SEND, "send_message"),
    ("send_text(", PERM_MESSAGES_SEND, "send_text"),
    ("send_photo(", PERM_MESSAGES_SEND, "send_photo"),
    ("send_video(", PERM_MESSAGES_SEND, "send_video"),
    ("send_document(", PERM_MESSAGES_SEND, "send_document"),
    ("send_audio(", PERM_MESSAGES_SEND, "send_audio"),
    ("edit_message(", PERM_MESSAGES_SEND, "edit_message"),
    ("get_send_messages_helper", PERM_MESSAGES_SEND, "SendMessagesHelper"),
    ("prepareSending", PERM_MESSAGES_SEND, "prepareSending"),
    ("sendSticker", PERM_MESSAGES_SEND, "sendSticker"),
    ("sendGame", PERM_MESSAGES_SEND, "sendGame"),
    ("sendCurrentLocation", PERM_MESSAGES_SEND, "sendCurrentLocation"),
    ("retrySendMessage", PERM_MESSAGES_SEND, "retrySendMessage"),
    ("editMessage(", PERM_MESSAGES_SEND, "editMessage"),
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
    ("get_files_dir", PERM_FILES, "get_files_dir"),
    ("get_cache_dir", PERM_FILES, "get_cache_dir"),
    ("get_images_dir", PERM_FILES, "get_images_dir"),
    ("get_videos_dir", PERM_FILES, "get_videos_dir"),
    ("get_audios_dir", PERM_FILES, "get_audios_dir"),
    ("get_documents_dir", PERM_FILES, "get_documents_dir"),
    ("getFilesDir", PERM_FILES, "getFilesDir"),
    ("getCacheDir", PERM_FILES, "getCacheDir"),
    ("getExternalFilesDir", PERM_FILES, "getExternalFilesDir"),
    ("getExternalStorage", PERM_FILES, "getExternalStorage"),
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
    ("TYPE_APPLICATION_OVERLAY", PERM_HOOKS, "overlay window"),
    ("TYPE_SYSTEM_ALERT", PERM_HOOKS, "overlay window"),
    ("TYPE_SYSTEM_OVERLAY", PERM_HOOKS, "overlay window"),
    ("LayoutParams.TYPE_PHONE", PERM_HOOKS, "overlay window"),
    # ---- нативный код ----
    ("import ctypes", PERM_NATIVE, "ctypes"),
    ("ctypes.CDLL", PERM_NATIVE, "ctypes"),
    ("CDLL(", PERM_NATIVE, "ctypes"),
    ("loadLibrary", PERM_NATIVE, "loadLibrary"),
    (".so\"", PERM_NATIVE, "native library"),
    (".so'", PERM_NATIVE, "native library"),
)

KEY_OBFUSCATION = "obfuscation"
KEY_DEX = "dex"

_DEX_BLOCK = re.compile(r"^[ \t]*#[ \t]*__DEX_BEGIN__[ \t\r]*$(.*?)^[ \t]*#[ \t]*__DEX_END__[ \t\r]*$",
                        re.M | re.S)
_DEX_LITERAL = re.compile(r"(\"\"\"|'''|\"|')([A-Za-z0-9+/=\s]{512,})\1")
_DEX_LITERAL_PREFIXES = ("ZGV4C", "eJ", "eN", "eA", "eF")
_DEX_LOADERS = ("InMemoryDexClassLoader", "DexClassLoader", "PathClassLoader")
_MAX_DEX_BYTES = 8 * 1024 * 1024
_DEX_CLASS_LIMIT = 6


def _uleb128(data: bytes, offset: int):
    result = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, offset
        shift += 7


def _dex_strings(data: bytes) -> List[str]:
    count, table = struct.unpack_from("<II", data, 0x38)
    strings = []
    for index in range(count):
        offset, = struct.unpack_from("<I", data, table + index * 4)
        _, start = _uleb128(data, offset)
        end = data.index(b"\0", start)
        strings.append(data[start:end].decode("utf-8", errors="replace"))
    return strings


def _dex_symbols(data: bytes):
    strings = _dex_strings(data)
    type_count, type_table = struct.unpack_from("<II", data, 0x40)
    types = [strings[struct.unpack_from("<I", data, type_table + i * 4)[0]]
             for i in range(type_count)]
    method_count, method_table = struct.unpack_from("<II", data, 0x58)
    methods = []
    for i in range(method_count):
        class_index, _, name_index = struct.unpack_from("<HHI", data, method_table + i * 8)
        methods.append((types[class_index], strings[name_index]))
    class_count, class_table = struct.unpack_from("<II", data, 0x60)
    defined = {types[struct.unpack_from("<I", data, class_table + i * 32)[0]]
               for i in range(class_count)}
    return defined, types, methods


def _dotted(descriptor: str) -> str:
    if descriptor.startswith("L") and descriptor.endswith(";"):
        return descriptor[1:-1].replace("/", ".")
    return descriptor


def _decode_dex_block(body: str):
    text = "".join(line.strip().lstrip("#").strip() for line in body.splitlines())
    raw = base64.b64decode(text)
    try:
        raw = zlib.decompress(raw)
    except zlib.error:
        pass
    if not raw.startswith(b"dex\n") or len(raw) > _MAX_DEX_BYTES:
        return None
    return raw


def _decode_dex_literal(body: str):
    text = "".join(body.split())
    if not text.startswith(_DEX_LITERAL_PREFIXES) or len(text) > _MAX_DEX_BYTES * 2:
        return None
    try:
        raw = base64.b64decode(text)
    except Exception:
        return None
    if not raw.startswith(b"dex\n"):
        try:
            if not zlib.decompressobj().decompress(raw, 4).startswith(b"dex\n"):
                return None
            raw = zlib.decompressobj().decompress(raw, _MAX_DEX_BYTES + 1)
        except zlib.error:
            return None
    if len(raw) > _MAX_DEX_BYTES:
        return None
    return raw


def _scan_embedded_dex(source: str) -> Dict[str, List[str]]:
    payloads = []
    spans = []
    for block in _DEX_BLOCK.finditer(source):
        try:
            payloads.append(_decode_dex_block(block.group(1)))
        except Exception:
            payloads.append(None)
        spans.append(block.span())
    for literal in _DEX_LITERAL.finditer(source):
        raw = _decode_dex_literal(literal.group(2))
        if raw is not None:
            payloads.append(raw)
            spans.append(literal.span(2))
    if not payloads:
        return {}
    parts = []
    cursor = 0
    for start, end in sorted(spans):
        if start >= cursor:
            parts.append(source[cursor:start])
            cursor = end
    parts.append(source[cursor:])
    outside = "".join(parts)
    loaded = any(loader in outside for loader in _DEX_LOADERS)
    summary: List[str] = []
    found: Dict[str, List[str]] = {}
    total = 0
    defined_all: List[str] = []
    for raw in payloads:
        if raw is None:
            _note_opaque(found, "unreadable DEX block")
            continue
        total += len(raw)
        try:
            defined, types, methods = _dex_symbols(raw)
        except Exception:
            _note_opaque(found, "unreadable DEX block")
            continue
        defined_all.extend(sorted(_dotted(name) for name in defined))
        if not loaded:
            continue
        external = [_dotted(name) for name in types if name not in defined]
        calls = [f"{_dotted(owner)}.{name}" for owner, name in methods if owner not in defined]
        text = "\n".join(external + calls)
        for marker, permission, evidence in _MARKERS:
            label = f"DEX: {evidence}"
            if marker in text and label not in found.setdefault(permission, []):
                found[permission].append(label)
        if any(".exec" in call for call in calls if call.startswith("java.lang.Runtime")) \
                or "java.lang.ProcessBuilder" in external:
            bucket = found.setdefault(PERM_NATIVE, [])
            if "DEX: Runtime.exec" not in bucket:
                bucket.append("DEX: Runtime.exec")
    if total == 0 and not found:
        return {}
    summary.append(f"size:{total}")
    summary.append("loaded:" + ("yes" if loaded else "no"))
    for name in defined_all[:_DEX_CLASS_LIMIT]:
        summary.append(f"class:{name}")
    if len(defined_all) > _DEX_CLASS_LIMIT:
        summary.append(f"more:{len(defined_all) - _DEX_CLASS_LIMIT}")
    found[KEY_DEX] = summary
    return {perm: names for perm, names in found.items() if names}

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


def _scan_source(source: str) -> Dict[str, List[str]]:
    found: Dict[str, List[str]] = {}
    for marker, permission, evidence in _MARKERS:
        if marker in source and evidence not in found.setdefault(permission, []):
            found[permission].append(evidence)

    _merge(found, _scan_imports(source))

    obfuscation = _detect_obfuscation(source)
    if obfuscation:
        found[KEY_OBFUSCATION] = obfuscation

    _merge(found, _scan_embedded_dex(source))

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
        return {KEY_OBFUSCATION: ["compiled bytecode"]}

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
