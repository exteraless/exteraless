import functools
import os
import re

import corpus

KOTLIN_ROOT = os.path.join(corpus.REPO, "TMessagesProj", "src", "main", "kotlin")

REFERENCE_ROOT = os.environ.get(
    "EXTERALESS_REFERENCE_SOURCES",
    "/home/coral/openExtera/exteragram-new/sources")

_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//[^\n]*")
_STRING = re.compile(r'"(?:\\.|[^"\\])*"')
_CHAR = re.compile(r"'(?:\\.|[^'\\])'")
_ANNOTATION = re.compile(r"@[\w.]+\s*(?:\([^()]*(?:\([^()]*\)[^()]*)*\))?")

_TYPE_DECL = re.compile(
    r"(?P<mods>(?:\b(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\b\s+)*)"
    r"(?P<kind>class|interface|enum|record|@interface)\s+"
    r"(?P<name>\w+)"
    r"(?P<rest>[^{;]*)")

_MEMBER = re.compile(
    r"(?P<mods>(?:\b(?:public|protected|private|static|final|abstract|synchronized|native|default|transient|volatile)\b\s+)*)"
    r"(?P<sig>[\w.<>\[\],?\s]*?)"
    r"(?P<name>\w+)\s*\((?P<params>[^)]*)\)")

_FIELD = re.compile(
    r"(?P<mods>(?:\b(?:public|protected|private|static|final|transient|volatile)\b\s+)*)"
    r"(?P<type>[\w.<>\[\],?\s]+?)\s+(?P<name>\w+)\s*(?:=[^;]*)?;")

MODIFIERS = {"public", "protected", "private", "static", "final", "abstract",
             "synchronized", "native", "default", "transient", "volatile",
             "strictfp", "sealed", "non-sealed"}

KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "new", "throw",
            "synchronized", "do", "else", "try", "assert", "super", "this"}


class JavaType:

    def __init__(self, fqcn, kind, modifiers, extends, implements, path):
        self.fqcn = fqcn
        self.kind = kind
        self.modifiers = modifiers
        self.extends = extends
        self.implements = implements
        self.path = path
        self.constructors = []
        self.methods = {}
        self.fields = {}
        self.nested = set()

    @property
    def simple_name(self):
        return self.fqcn.rsplit(".", 1)[-1].rsplit("$", 1)[-1]

    def method_arities(self, name):
        return {len(params) for params in self.methods.get(name, ())}

    def constructor_arities(self):
        return {len(params) for params in self.constructors}

    def __repr__(self):
        return f"<JavaType {self.fqcn} {self.kind}>"


def _blank_run(length, source):
    return "".join("\n" if ch == "\n" else " " for ch in source[:length])


def strip_noise(text):
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j == -1 else j
            out.append(_blank_run(j - i, text[i:j]))
            i = j
            continue
        if ch == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j == -1 else j + 2
            out.append(_blank_run(j - i, text[i:j]))
            i = j
            continue
        if ch == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append(_blank_run(j - i, text[i:j]))
            i = j
            continue
        if ch == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'" or text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append(_blank_run(j - i, text[i:j]))
            i = j
            continue
        if ch == "@":
            m = _ANNOTATION.match(text, i)
            if m:
                out.append(_blank_run(m.end() - i, text[i:m.end()]))
                i = m.end()
                continue
        out.append(ch)
        i += 1
    return "".join(out)


def split_params(raw):
    raw = raw.strip()
    if not raw:
        return []
    out, depth, current = [], 0, []
    for ch in raw:
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(current).strip())
            current = []
        else:
            current.append(ch)
    if current:
        out.append("".join(current).strip())
    return [p for p in out if p]


def param_types(raw):
    types = []
    for param in split_params(raw):
        param = param.replace("final ", "").strip()
        if not param:
            continue
        vararg = "..." in param
        parts = param.replace("...", "[]").split()
        name = parts[0] if len(parts) == 1 else " ".join(parts[:-1])
        if vararg and name.endswith("[]"):
            name = name[:-2] + "..."
        types.append(name)
    return types


def _hierarchy(rest):
    extends, implements = None, []
    ext = re.search(r"\bextends\s+([\w.$<>,\s\[\]]+?)(?:\bimplements\b|$)", rest)
    if ext:
        extends = ext.group(1).strip().split("<")[0].strip() or None
    imp = re.search(r"\bimplements\s+([\w.$<>,\s\[\]]+)$", rest)
    if imp:
        implements = [part.split("<")[0].strip()
                      for part in split_params(imp.group(1))]
    return extends, implements


_BRACE = re.compile(r"[{}]")
_SPACES = re.compile(r"[ \t]{2,}")


def parse_file(path, package_of):
    with open(path, encoding="utf-8", errors="replace") as fh:
        raw = fh.read()
    text = strip_noise(raw)
    package = package_of
    m = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
    if m:
        package = m.group(1)

    types = {}
    stack = []
    frames = [[]]
    skip = 0
    prev = 0
    for hit in _BRACE.finditer(text):
        chunk = text[prev:hit.start()]
        prev = hit.start() + 1
        brace = hit.group()
        if skip:
            if brace == "{":
                skip += 1
            else:
                skip -= 1
            continue
        frames[-1].append(chunk)
        if brace == "{":
            head = "".join(frames[-1])
            declared = _declared_type(head, package, stack, path)
            if declared is None:
                skip = 1
                continue
            types[declared.fqcn] = declared
            if stack:
                stack[-1].nested.add(declared.simple_name)
                _absorb(stack[-1], head, cut=True)
            frames[-1] = []
            stack.append(declared)
            frames.append([])
            continue
        if stack:
            _absorb(stack[-1], "".join(frames[-1]))
            frames.pop()
            stack.pop()
            if not frames:
                frames.append([])
        else:
            frames[-1] = []
    return types


def _declared_type(head, package, stack, path):
    matches = list(_TYPE_DECL.finditer(head))
    if not matches:
        return None
    m = matches[-1]
    if head[m.end():].strip():
        return None
    name = m.group("name")
    outer = stack[-1].fqcn if stack else package
    sep = "$" if stack else "."
    extends, implements = _hierarchy(m.group("rest"))
    return JavaType(f"{outer}{sep}{name}", m.group("kind"),
                    set(m.group("mods").split()), extends, implements, path)


_ENUM_CONSTANT = re.compile(r"^[A-Za-z_]\w*$")


def _absorb_enum_constants(owner, body):
    for part in split_params(body.split(";", 1)[0]):
        name = part.strip().split("(", 1)[0].split("{", 1)[0].strip()
        if _ENUM_CONSTANT.match(name):
            owner.fields.setdefault(name, owner.fqcn)


def _absorb(owner, body, cut=False):
    if owner is None or not body.strip():
        return
    body = _SPACES.sub(" ", body)
    if owner.kind == "enum" and not owner.fields and not owner.methods:
        _absorb_enum_constants(owner, body)
    if cut:
        matches = list(_TYPE_DECL.finditer(body))
        if matches:
            body = body[:matches[-1].start()]
    for m in _MEMBER.finditer(body):
        name = m.group("name")
        if name in KEYWORDS:
            continue
        words = [w for w in (m.group("mods") + " " + m.group("sig")).split()
                 if w not in MODIFIERS]
        if words and words[-1] in KEYWORDS:
            continue
        params = param_types(m.group("params"))
        if not words and name == owner.simple_name:
            owner.constructors.append(params)
        elif words:
            owner.methods.setdefault(name, []).append(params)
    for m in _FIELD.finditer(body):
        declared = m.group("type").strip()
        if not declared or declared in KEYWORDS or "(" in declared:
            continue
        owner.fields[m.group("name")] = declared


PARSE_ERRORS = []

_FILE_CACHE = {}


def _roots():
    return [corpus.JAVA_ROOT, KOTLIN_ROOT, REFERENCE_ROOT]


def _parse_cached(path, package):
    cached = _FILE_CACHE.get(path)
    if cached is None:
        try:
            cached = parse_file(path, package)
        except Exception as exc:
            PARSE_ERRORS.append((path, f"{type(exc).__name__}: {exc}"))
            cached = {}
        _FILE_CACHE[path] = cached
    return cached


def _candidate_files(fqcn):
    outer = fqcn.split("$", 1)[0]
    parts = outer.split(".")
    out = []
    for root in _roots():
        if not os.path.isdir(root):
            continue
        for cut in range(len(parts), 0, -1):
            path = os.path.join(root, *parts[:cut]) + ".java"
            if os.path.isfile(path):
                out.append((path, ".".join(parts[:cut - 1])))
    return out


@functools.lru_cache(maxsize=8192)
def type_of(fqcn):
    if not fqcn:
        return None
    for path, package in _candidate_files(fqcn):
        found = _match(_parse_cached(path, package), fqcn)
        if found is not None:
            return found
    return None


_KT_TYPE = re.compile(r"\b(?:object|interface|(?:data\s+|sealed\s+|enum\s+|abstract\s+|open\s+)*class)\s+(?P<name>\w+)")
_KT_FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?P<name>\w+)\s*\((?P<params>[^()]*)\)")
_KT_PROP = re.compile(r"\b(?:val|var)\s+(?P<name>\w+)\s*[:=]")


def _kt_params(raw):
    raw = raw.strip()
    if not raw:
        return []
    out = []
    depth = 0
    current = ""
    for ch in raw:
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(current)
            current = ""
        else:
            current += ch
    out.append(current)
    return [item for item in out if item.strip()]


def _parse_kotlin(path, package):
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = strip_noise(fh.read())
    m = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
    if m:
        package = m.group(1)
    spans = [(hit.start(), hit.group("name")) for hit in _KT_TYPE.finditer(text)]
    types = {}
    for index, (start, name) in enumerate(spans):
        end = spans[index + 1][0] if index + 1 < len(spans) else len(text)
        fqcn = f"{package}.{name}" if package else name
        jtype = JavaType(fqcn, "class", set(), None, [], path)
        body = text[start:end]
        for hit in _KT_FUN.finditer(body):
            jtype.methods.setdefault(hit.group("name"), []).append(
                _kt_params(hit.group("params")))
        for hit in _KT_PROP.finditer(body):
            jtype.fields[hit.group("name")] = "?"
        types[fqcn] = jtype
    return types


@functools.lru_cache(maxsize=2048)
def kotlin_type_of(fqcn):
    if not fqcn or not os.path.isdir(KOTLIN_ROOT):
        return None
    outer = fqcn.split("$", 1)[0]
    parts = outer.split(".")
    for cut in range(len(parts), 0, -1):
        path = os.path.join(KOTLIN_ROOT, *parts[:cut]) + ".kt"
        if not os.path.isfile(path):
            continue
        found = _match(_parse_kotlin(path, ".".join(parts[:cut - 1])), fqcn)
        if found is not None:
            return found
    return None


def any_type_of(fqcn):
    return type_of(fqcn) or kotlin_type_of(fqcn)


def _match(types, fqcn):
    if fqcn in types:
        return types[fqcn]
    dotted = fqcn.replace("$", ".")
    for name, jtype in types.items():
        if name.replace("$", ".") == dotted:
            return jtype
    return None


def declares(fqcn, member, seen=None):
    seen = seen or set()
    if fqcn in seen:
        return None
    seen.add(fqcn)
    jtype = type_of(fqcn)
    if jtype is None:
        return None
    if member in jtype.methods or member in jtype.fields or member in jtype.nested:
        return jtype
    for parent in supertypes(jtype):
        found = declares(parent, member, seen)
        if found is not None:
            return found
    return None


def supertypes(jtype):
    out = []
    package = jtype.fqcn.split("$", 1)[0].rsplit(".", 1)[0]
    for raw in filter(None, [jtype.extends, *jtype.implements]):
        name = raw.strip()
        if not name or name == "Object":
            continue
        if "." in name:
            out.append(name)
        else:
            out.append(f"{jtype.fqcn}${name}")
            out.append(f"{package}.{name}")
    return out


def lookup(index, fqcn):
    return _match(index, fqcn)
