import ast
import functools
import glob
import os
import re

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PYTHON_ROOT = os.path.join(REPO, "TMessagesProj", "src", "main", "python")
JAVA_ROOT = os.path.join(REPO, "TMessagesProj", "src", "main", "java")
MAPPING_DIR = os.path.join(REPO, "TMessagesProj", "build", "outputs", "mapping", "release")
CORPUS_DIR = os.environ.get(
    "EXTERALESS_PLUGIN_CORPUS", "/home/coral/openExtera/plugin-corpus/all")

JAVA_ROOTS = (
    "java", "javax", "android", "androidx", "org", "com", "kotlin", "dalvik")

MODULE_ROOTS = tuple(sorted(glob.glob(os.path.join(
    REPO, "TMessagesProj_Modules", "*", "libraries", "*", "src", "main", "java"))))

SOURCE_ROOTS = (JAVA_ROOT,) + MODULE_ROOTS


def find_java_source(fqcn):
    rel = os.path.join(*fqcn.split(".")) + ".java"
    for root in SOURCE_ROOTS:
        path = os.path.join(root, rel)
        if os.path.isfile(path):
            return path
    return None


class Plugin:

    def __init__(self, path, source):
        self.path = path
        self.name = os.path.basename(path)
        self.source = source

    @property
    def tree(self):
        try:
            return ast.parse(self.source, filename=self.name)
        except SyntaxError:
            return None

    def __repr__(self):
        return f"<Plugin {self.name}>"


@functools.lru_cache(maxsize=1)
def load_corpus():
    if not os.path.isdir(CORPUS_DIR):
        return ()
    out = []
    for fn in sorted(os.listdir(CORPUS_DIR)):
        path = os.path.join(CORPUS_DIR, fn)
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8", errors="replace") as fh:
            out.append(Plugin(path, fh.read()))
    return tuple(out)


@functools.lru_cache(maxsize=1)
def java_source_classes():
    found = set()
    for dirpath, _, files in os.walk(JAVA_ROOT):
        rel = os.path.relpath(dirpath, JAVA_ROOT).replace(os.sep, ".")
        for fn in files:
            if fn.endswith(".java"):
                found.add(f"{rel}.{fn[:-5]}" if rel != "." else fn[:-5])
    return frozenset(found)


@functools.lru_cache(maxsize=1)
def java_nested_classes():
    pattern = re.compile(
        r"^\s*(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+|abstract\s+)*"
        r"(?:class|interface|enum|@interface)\s+(\w+)")
    found = {}
    for dirpath, _, files in os.walk(JAVA_ROOT):
        rel = os.path.relpath(dirpath, JAVA_ROOT).replace(os.sep, ".")
        for fn in files:
            if not fn.endswith(".java"):
                continue
            outer = f"{rel}.{fn[:-5]}" if rel != "." else fn[:-5]
            names = set()
            with open(os.path.join(dirpath, fn), encoding="utf-8",
                      errors="replace") as fh:
                for line in fh:
                    m = pattern.match(line)
                    if m:
                        names.add(m.group(1))
            names.discard(fn[:-5])
            found[outer] = frozenset(names)
    return found


@functools.lru_cache(maxsize=1)
def r8_mapping():
    kept, renamed = set(), {}
    path = os.path.join(MAPPING_DIR, "mapping.txt")
    if not os.path.isfile(path):
        return frozenset(), {}
    line_re = re.compile(r"^([\w.$]+) -> ([\w.$]+):$")
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line[:1] in (" ", "\t", "#"):
                continue
            m = line_re.match(line.rstrip("\n"))
            if not m:
                continue
            src, dst = m.groups()
            if src == dst:
                kept.add(src)
            else:
                renamed[src] = dst
    return frozenset(kept), renamed


@functools.lru_cache(maxsize=1)
def r8_removed():
    path = os.path.join(MAPPING_DIR, "usage.txt")
    if not os.path.isfile(path):
        return frozenset()
    out = set()
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line[:1] in (" ", "\t"):
                continue
            s = line.strip()
            if s and ":" not in s and "(" not in s and " " not in s:
                out.add(s)
    return frozenset(out)
