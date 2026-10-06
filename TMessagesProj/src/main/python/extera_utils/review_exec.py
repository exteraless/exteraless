import base64
import binascii
import bz2
import codecs
import gzip
import hashlib
import io
import lzma
import sys
import time
import traceback
import zlib

MAX_CODE = 8192
MAX_STEPS = 5000000
MAX_SECONDS = 5.0
MAX_OUTPUT = 20000

_REFUSED = (
    "__", "import", "open(", "eval(", "exec(", "compile(", "getattr(", "setattr(",
    "delattr(", "globals(", "locals(", "vars(", "input(", "sys.", "os.", "socket",
    "subprocess", "ctypes", "pathlib", "marshal", ".read(",
)

_BUILTINS = {
    "abs": abs, "all": all, "any": any, "bin": bin, "bool": bool,
    "bytearray": bytearray, "bytes": bytes, "chr": chr, "dict": dict,
    "divmod": divmod, "enumerate": enumerate, "filter": filter, "float": float,
    "format": format, "frozenset": frozenset, "hex": hex, "int": int,
    "isinstance": isinstance, "iter": iter, "len": len, "list": list, "map": map,
    "max": max, "memoryview": memoryview, "min": min, "next": next, "oct": oct,
    "ord": ord, "pow": pow, "range": range, "repr": repr, "reversed": reversed,
    "round": round, "set": set, "slice": slice, "sorted": sorted, "str": str,
    "sum": sum, "tuple": tuple, "zip": zip, "Exception": Exception,
    "ValueError": ValueError, "KeyError": KeyError, "IndexError": IndexError,
    "TypeError": TypeError, "ZeroDivisionError": ZeroDivisionError,
}


def _padded(value, size):
    if isinstance(value, (bytes, bytearray)):
        value = bytes(value).decode("latin-1")
    return (value + "=" * (-len(value) % size)).encode("utf-8", "replace")


def b64(value):
    return base64.b64decode(_padded(value, 4))


def b64url(value):
    return base64.urlsafe_b64decode(_padded(value, 4))


def b32(value):
    return base64.b32decode(_padded(value, 8), casefold=True)


def b16(value):
    return base64.b16decode(value, casefold=True)


def b85(value):
    return base64.b85decode(value)


def a85(value):
    return base64.a85decode(value)


def hexbytes(value):
    return bytes.fromhex(value)


def unzlib(value):
    return zlib.decompressobj().decompress(bytes(value), 64 * 1024 * 1024)


def ungzip(value):
    return zlib.decompressobj(16 + zlib.MAX_WBITS).decompress(bytes(value), 64 * 1024 * 1024)


def unbz2(value):
    return bz2.BZ2Decompressor().decompress(bytes(value), 64 * 1024 * 1024)


def unlzma(value):
    return lzma.LZMADecompressor().decompress(bytes(value), 64 * 1024 * 1024)


def rot13(value):
    return codecs.decode(value, "rot13")


def xor(data, key):
    if isinstance(key, int):
        key = bytes([key & 0xff])
    key = bytes(key)
    if not key:
        return bytes(data)
    return bytes(byte ^ key[index % len(key)] for index, byte in enumerate(data))


def sha256(value):
    return hashlib.sha256(bytes(value)).hexdigest()


def sha1(value):
    return hashlib.sha1(bytes(value)).hexdigest()


def md5(value):
    return hashlib.md5(bytes(value)).hexdigest()


def crc32(value):
    return binascii.crc32(bytes(value)) & 0xffffffff


def utf16le(value):
    return bytes(value).decode("utf-16-le", "replace")


def utf16be(value):
    return bytes(value).decode("utf-16-be", "replace")


def to_text(value):
    return bytes(value).decode("utf-8", "replace")


_HELPERS = {
    "b64": b64, "b64url": b64url, "b32": b32, "b16": b16, "b85": b85, "a85": a85,
    "hexbytes": hexbytes, "unzlib": unzlib, "ungzip": ungzip, "unbz2": unbz2,
    "unlzma": unlzma, "rot13": rot13, "xor": xor, "sha256": sha256, "sha1": sha1,
    "md5": md5, "crc32": crc32, "utf16le": utf16le, "utf16be": utf16be,
    "to_text": to_text,
}


def run(code, payload):
    if not isinstance(code, str) or not code.strip():
        return "error: empty script"
    if len(code) > MAX_CODE:
        return "error: script is longer than %d characters" % MAX_CODE
    for token in _REFUSED:
        if token in code:
            return ("error: '%s' is not allowed here; the script has no imports and no "
                    "attribute games, use the provided helpers and print()" % token)
    try:
        data = base64.b64decode(payload) if payload else b""
    except Exception as error:
        return "error: bad data: %s" % error

    captured = io.StringIO()

    def _print(*values, sep=" ", end="\n"):
        if captured.tell() < MAX_OUTPUT:
            captured.write(sep.join(str(value) for value in values) + end)

    steps = [0]
    deadline = time.monotonic() + MAX_SECONDS

    def _trace(frame, event, argument):
        if event == "line":
            steps[0] += 1
            if steps[0] > MAX_STEPS:
                raise RuntimeError("the script did not finish in %d steps" % MAX_STEPS)
            if not steps[0] % 2048 and time.monotonic() > deadline:
                raise RuntimeError("the script did not finish in %.0f seconds" % MAX_SECONDS)
        return _trace

    namespace = {"__builtins__": _BUILTINS, "data": data, "print": _print}
    namespace.update(_HELPERS)
    previous = sys.gettrace()
    sys.settrace(_trace)
    status = None
    try:
        exec(compile(code, "<script>", "exec"), namespace)
    except Exception as error:
        status = _failure(error, code)
    finally:
        sys.settrace(previous)

    text = captured.getvalue()
    if "result" in namespace:
        text += repr(namespace["result"]) + "\n"
    if status is not None:
        text = status + "\n" + text
    if not text.strip():
        text = "the script printed nothing; print() what you want back"
    return text[:MAX_OUTPUT]


def _failure(error, code):
    line = None
    frame = error.__traceback__
    while frame is not None:
        if frame.tb_frame.f_code.co_filename == "<script>":
            line = frame.tb_lineno
        frame = frame.tb_next
    parts = ["error: %s: %s" % (type(error).__name__, error)]
    if line is not None:
        parts.append("line %d" % line)
        source = code.splitlines()
        if 0 < line <= len(source):
            parts.append("    " + source[line - 1].strip())
    parts.append(traceback.format_exc(limit=2).rstrip())
    return "\n".join(parts)
