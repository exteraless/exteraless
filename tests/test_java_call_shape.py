import ast
import functools
import importlib.util
import os
import sys
import warnings

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import baseline
import corpus
import javaapi

MEMBER_BASELINE = "java_call_members.json"
ARITY_BASELINE = "java_call_arity.json"

OWNED_PREFIXES = ("app.exteraless.", "com.exteragram.", "org.telegram.")
FACTORY_CALLS = ("find_class", "jclass", "autoclass", "load_class", "get_class")
INIT = "<init>"
GENERATED = ("R", "BuildConfig")
OBJECT_MEMBERS = frozenset((
    "getClass", "toString", "hashCode", "equals", "clone", "finalize",
    "notify", "notifyAll", "wait"))

ALIASES_PATH = os.path.join(corpus.PYTHON_ROOT, "extera_utils", "class_aliases.py")

PLUGIN_FQCN = "app.exteraless.plugins.Plugin"
PLUGIN_METHODS = ("setEngine", "setIcon", "setVersion",
                  "getId", "getDisplayName", "getSubtitle")
CELL_FQCN = "app.exteraless.plugins.ui.components.PluginCell"
FACTORY_FQCN = CELL_FQCN + "$Factory"
DELEGATE_FQCN = "app.exteraless.plugins.ui.components.PluginCellDelegate"
DELEGATE_METHODS = frozenset((
    "sharePlugin", "openInExternalApp", "deletePlugin", "togglePlugin",
    "openPluginSettings", "pinPlugin", "canOpenInExternalApp"))

WATCHED_TYPES = (PLUGIN_FQCN, CELL_FQCN, FACTORY_FQCN, DELEGATE_FQCN)

pytestmark = pytest.mark.skipif(
    not os.path.isdir(corpus.CORPUS_DIR),
    reason=f"plugin corpus not found at {corpus.CORPUS_DIR}")


@functools.lru_cache(maxsize=1)
def aliases():
    spec = importlib.util.spec_from_file_location(
        "exteraless_class_aliases_call_shape", ALIASES_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _single_name(node):
    if len(node.targets) != 1 or not isinstance(node.targets[0], ast.Name):
        return None
    return node.targets[0].id


def _names_in(target):
    if isinstance(target, ast.Name):
        return [target.id]
    if isinstance(target, (ast.Tuple, ast.List)):
        out = []
        for item in target.elts:
            out.extend(_names_in(item))
        return out
    if isinstance(target, ast.Starred):
        return _names_in(target.value)
    return []


def _assigned_names(node):
    if isinstance(node, ast.Assign):
        out = []
        for target in node.targets:
            out.extend(_names_in(target))
        return out
    if isinstance(node, (ast.AnnAssign, ast.AugAssign, ast.NamedExpr)):
        return _names_in(node.target)
    if isinstance(node, (ast.For, ast.AsyncFor)):
        return _names_in(node.target)
    if isinstance(node, (ast.With, ast.AsyncWith)):
        out = []
        for item in node.items:
            if item.optional_vars is not None:
                out.extend(_names_in(item.optional_vars))
        return out
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
        return [node.name]
    if isinstance(node, ast.ExceptHandler):
        return [node.name] if node.name else []
    return []


def _arg_names(args):
    out = [a.arg for a in list(getattr(args, "posonlyargs", []))
           + list(args.args) + list(args.kwonlyargs)]
    if args.vararg is not None:
        out.append(args.vararg.arg)
    if args.kwarg is not None:
        out.append(args.kwarg.arg)
    return out


def _factory_target(value):
    if not isinstance(value, ast.Call) or not value.args:
        return None
    func = value.func
    name = func.attr if isinstance(func, ast.Attribute) else getattr(func, "id", None)
    if name not in FACTORY_CALLS:
        return None
    first = value.args[0]
    if not isinstance(first, ast.Constant) or not isinstance(first.value, str):
        return None
    text = first.value
    if text.split(".")[0] not in corpus.JAVA_ROOTS:
        return None
    return text


_STMT_FIELDS = ("body", "orelse", "finalbody", "handlers")
_SCOPES = (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)


def _flat(body):
    stack = list(body)
    while stack:
        node = stack.pop()
        yield node
        if isinstance(node, _SCOPES):
            continue
        for field in _STMT_FIELDS:
            stack.extend(getattr(node, field, None) or ())


def _argc(node):
    if node.keywords:
        return None
    for arg in node.args:
        if isinstance(arg, ast.Starred):
            return None
    return len(node.args)


class _Collector:

    def __init__(self, plugin, resolve):
        self.plugin = plugin
        self.resolve = resolve
        self.bindings = {}
        self.records = []

    def run(self, tree):
        self._imports(tree)
        self._poison(tree)
        self._scope(tree.body, {})
        return self.records

    def _bind(self, name, fqcn):
        if name in self.bindings and self.bindings[name] != fqcn:
            self.bindings[name] = None
            return
        self.bindings[name] = fqcn

    def _imports(self, tree):
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom):
                if node.level or not node.module:
                    continue
                if node.module.split(".")[0] not in corpus.JAVA_ROOTS:
                    continue
                for alias in node.names:
                    if alias.name != "*" and alias.name[:1].isupper():
                        self._bind(alias.asname or alias.name,
                                   node.module + "." + alias.name)
            elif isinstance(node, ast.Import):
                for alias in node.names:
                    if not alias.asname:
                        continue
                    if alias.name.split(".")[0] in corpus.JAVA_ROOTS:
                        self._bind(alias.asname, alias.name)
            elif isinstance(node, ast.Assign):
                name = _single_name(node)
                target = _factory_target(node.value)
                if name is not None and target is not None:
                    self._bind(name, target)

    def _poison(self, tree):
        for node in ast.walk(tree):
            if isinstance(node, (ast.Import, ast.ImportFrom)):
                continue
            if isinstance(node, ast.Assign) and _factory_target(node.value):
                continue
            for name in _assigned_names(node):
                if name in self.bindings:
                    self.bindings[name] = None

    def _instance_type(self, value):
        target = _factory_target(value)
        if target is not None:
            return target
        if not isinstance(value, ast.Call):
            return None
        if not isinstance(value.func, ast.Name):
            return None
        return self.bindings.get(value.func.id)

    def _locals(self, body, local):
        seen = {}
        for node in _flat(body):
            if isinstance(node, (ast.Import, ast.ImportFrom)):
                continue
            names = _assigned_names(node)
            if not names:
                continue
            fqcn = None
            if isinstance(node, ast.Assign) and len(names) == 1:
                fqcn = self._instance_type(node.value)
            for name in names:
                seen.setdefault(name, set()).add(fqcn if len(names) == 1 else None)
        for name, kinds in seen.items():
            if name in self.bindings:
                continue
            local[name] = kinds.pop() if len(kinds) == 1 else None

    def _scope(self, body, inherited):
        local = dict(inherited)
        self._locals(body, local)
        for stmt in body:
            self._visit(stmt, local)

    def _visit_args(self, args, local):
        for default in list(args.defaults) + [d for d in args.kw_defaults if d]:
            self._visit(default, local)

    def _visit(self, node, local):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            for deco in node.decorator_list:
                self._visit(deco, local)
            self._visit_args(node.args, local)
            inner = dict(local)
            for name in _arg_names(node.args):
                inner[name] = None
            self._scope(node.body, inner)
            return
        if isinstance(node, ast.ClassDef):
            for deco in node.decorator_list:
                self._visit(deco, local)
            for base in node.bases:
                self._visit(base, local)
            for kw in node.keywords:
                self._visit(kw.value, local)
            self._scope(node.body, dict(local))
            return
        if isinstance(node, ast.Lambda):
            self._visit_args(node.args, local)
            inner = dict(local)
            for name in _arg_names(node.args):
                inner[name] = None
            self._visit(node.body, inner)
            return
        if isinstance(node, ast.Call):
            target = self._resolve(node.func, local)
            if target is None:
                self._visit(node.func, local)
            else:
                self._record(node, target[0], target[1], _argc(node))
            for arg in node.args:
                self._visit(arg, local)
            for kw in node.keywords:
                self._visit(kw.value, local)
            return
        if isinstance(node, ast.NamedExpr):
            for name in _names_in(node.target):
                local[name] = None
            self._visit(node.value, local)
            return
        if isinstance(node, ast.Attribute):
            target = self._resolve(node, local)
            if target is not None:
                self._record(node, target[0], target[1], None)
                return
            self._visit(node.value, local)
            return
        for child in ast.iter_child_nodes(node):
            self._visit(child, local)

    def _resolve(self, node, local):
        parts = []
        cur = node
        while isinstance(cur, ast.Attribute):
            parts.append(cur.attr)
            cur = cur.value
        if not isinstance(cur, ast.Name):
            return None
        parts.reverse()
        base = self.bindings.get(cur.id) or local.get(cur.id)
        if not base:
            return None
        if not parts:
            return base, INIT
        for part in parts[:-1]:
            if not part[:1].isupper():
                return None
            base = base + "$" + part
        return base, parts[-1]

    def _record(self, node, fqcn, member, argc):
        resolved = self.resolve(fqcn)
        if not resolved.startswith(OWNED_PREFIXES):
            return
        if resolved.partition("$")[0].rpartition(".")[2] in GENERATED:
            return
        self.records.append((resolved, member, argc, node.lineno))


def _overloads(fqcn, member, seen=None):
    seen = set() if seen is None else seen
    if fqcn in seen:
        return []
    seen.add(fqcn)
    jtype = javaapi.type_of(fqcn)
    if jtype is None:
        return []
    out = list(jtype.methods.get(member, ()))
    for parent in javaapi.supertypes(jtype):
        out.extend(_overloads(parent, member, seen))
    return out


@functools.lru_cache(maxsize=None)
def _member_overloads(fqcn, member):
    return tuple(tuple(params) for params in _overloads(fqcn, member))


@functools.lru_cache(maxsize=None)
def _in_fork_sources(fqcn):
    parts = fqcn.split("$", 1)[0].split(".")
    for cut in range(len(parts), 0, -1):
        if os.path.isfile(os.path.join(corpus.JAVA_ROOT, *parts[:cut]) + ".java"):
            return True
    return False


@functools.lru_cache(maxsize=None)
def _declares(fqcn, member):
    return javaapi.declares(fqcn, member)


def _fits(overloads, argc):
    for params in overloads:
        if len(params) == argc:
            return True
        if params and params[-1].endswith("...") and argc >= len(params) - 1:
            return True
    return False


def _add(bucket, key, plugin, lineno, arities=()):
    entry = bucket.setdefault(key, {"plugins": set(), "examples": [], "arities": set()})
    entry["plugins"].add(plugin.name)
    entry["arities"].update(arities)
    if len(entry["examples"]) < 3:
        lines = plugin.source.splitlines()
        text = lines[lineno - 1].strip() if 0 < lineno <= len(lines) else ""
        entry["examples"].append((plugin.name, lineno, text[:120]))


def _classify(buckets, stats, plugin, fqcn, member, argc, lineno):
    jtype = javaapi.type_of(fqcn) if _in_fork_sources(fqcn) else None
    if jtype is None:
        _add(buckets["missing_class"], fqcn, plugin, lineno)
        return
    if member == INIT:
        stats["checked"] += 1
        overloads = list(jtype.constructors) or [[]]
        if argc is not None and not _fits(overloads, argc):
            _add(buckets["arity_mismatch"], f"{fqcn}#{INIT}({argc})", plugin,
                 lineno, jtype.constructor_arities() or {0})
        else:
            stats["ok"] += 1
        return
    if member in OBJECT_MEMBERS:
        return
    owner = _declares(fqcn, member)
    if owner is None:
        stats["checked"] += 1
        _add(buckets["missing_member"], f"{fqcn}#{member}", plugin, lineno)
        return
    if argc is None:
        stats["checked"] += 1
        stats["ok"] += 1
        return
    if member in owner.nested and member not in owner.methods:
        nested = javaapi.type_of(owner.fqcn + "$" + member)
        if nested is None:
            return
        stats["checked"] += 1
        overloads = list(nested.constructors) or [[]]
        if not _fits(overloads, argc):
            _add(buckets["arity_mismatch"], f"{nested.fqcn}#{INIT}({argc})", plugin,
                 lineno, nested.constructor_arities() or {0})
        else:
            stats["ok"] += 1
        return
    overloads = _member_overloads(fqcn, member)
    if not overloads:
        return
    stats["checked"] += 1
    if not _fits(overloads, argc):
        _add(buckets["arity_mismatch"], f"{fqcn}#{member}({argc})", plugin, lineno,
             {len(params) for params in overloads})
    else:
        stats["ok"] += 1


@functools.lru_cache(maxsize=1)
def analyse():
    resolve = aliases().resolve
    buckets = {"missing_class": {}, "missing_member": {}, "arity_mismatch": {}}
    stats = {"plugins": 0, "parsed": 0, "accesses": 0, "checked": 0, "ok": 0}
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        for plugin in corpus.load_corpus():
            stats["plugins"] += 1
            tree = plugin.tree
            if tree is None:
                continue
            stats["parsed"] += 1
            for fqcn, member, argc, lineno in _Collector(plugin, resolve).run(tree):
                stats["accesses"] += 1
                _classify(buckets, stats, plugin, fqcn, member, argc, lineno)
    return stats, buckets


def _report(bucket, keys, limit=20, with_arity=False):
    items = sorted(((key, bucket[key]) for key in keys),
                   key=lambda kv: (-len(kv[1]["plugins"]), kv[0]))
    lines = []
    for key, entry in items[:limit]:
        head = f"  {key} - {len(entry['plugins'])} plugin(s)"
        if with_arity:
            head += f", declared arities {sorted(entry['arities']) or 'none'}"
        lines.append(head)
        for name, lineno, text in entry["examples"][:3]:
            lines.append(f"      {name}:{lineno}: {text}")
    if len(items) > limit:
        lines.append(f"  ... and {len(items) - limit} more")
    return "\n".join(lines)


def test_no_new_missing_java_members():
    _, buckets = analyse()
    found = buckets["missing_member"]
    new, gone = baseline.compare(MEMBER_BASELINE, found)
    message = ""
    if new:
        message += (
            f"{len(new)} Java member(s) called by plugins do not exist in the fork "
            "(port the member or fix its name):\n" + _report(found, new))
    if gone:
        message += (
            f"\n{len(gone)} entries in tests/baselines/{MEMBER_BASELINE} are no longer "
            "missing, drop them from the baseline:\n"
            + "\n".join(f"  {key}" for key in gone[:20]))
    assert not new, message


def test_no_new_java_arity_mismatches():
    _, buckets = analyse()
    found = buckets["arity_mismatch"]
    new, gone = baseline.compare(ARITY_BASELINE, found)
    message = ""
    if new:
        message += (
            f"{len(new)} Java call(s) pass an argument count no overload accepts "
            "(the key ends with the count plugins pass):\n"
            + _report(found, new, with_arity=True))
    if gone:
        message += (
            f"\n{len(gone)} entries in tests/baselines/{ARITY_BASELINE} no longer "
            "mismatch, drop them from the baseline:\n"
            + "\n".join(f"  {key}" for key in gone[:20]))
    assert not new, message


def test_plugin_model_keeps_the_reference_shape():
    jtype = javaapi.type_of(PLUGIN_FQCN)
    assert jtype is not None, (
        f"{PLUGIN_FQCN} is gone; the plugin manager builds every catalogue entry "
        "from it")
    assert 2 in jtype.constructor_arities(), (
        f"{PLUGIN_FQCN} lost the two-argument constructor Plugin(String id, "
        f"String name); found arities {sorted(jtype.constructor_arities())}. "
        "Catalogue plugins build models with it, see commit 51a23bb6b")
    missing = [name for name in PLUGIN_METHODS
               if javaapi.declares(PLUGIN_FQCN, name) is None]
    assert not missing, (
        f"{PLUGIN_FQCN} lost reference method(s): {', '.join(missing)}. "
        "Chaquopy exposes fields as attributes but plugins call getters and "
        "setters, so a missing one fails at call time, see commit 51a23bb6b")


def test_plugin_cell_entry_points_exist():
    cell = javaapi.type_of(CELL_FQCN)
    assert cell is not None, f"{CELL_FQCN} is gone"
    assert 2 in cell.method_arities("set"), (
        f"{CELL_FQCN}.set(Plugin, PluginCellDelegate) is gone; found arities "
        f"{sorted(cell.method_arities('set'))}, see commit ec0e9f961")
    factory = javaapi.type_of(FACTORY_FQCN)
    assert factory is not None, f"{FACTORY_FQCN} is gone"
    assert 2 in factory.method_arities("asPlugin"), (
        f"{FACTORY_FQCN}.asPlugin(Plugin, PluginCellDelegate) is gone; found "
        f"arities {sorted(factory.method_arities('asPlugin'))}, "
        "see commit ec0e9f961")
    delegate = javaapi.type_of(DELEGATE_FQCN)
    assert delegate is not None, f"{DELEGATE_FQCN} is gone"
    declared = set(delegate.methods)
    assert declared == set(DELEGATE_METHODS), (
        f"{DELEGATE_FQCN} drifted from the reference interface; missing "
        f"{sorted(DELEGATE_METHODS - declared)}, extra "
        f"{sorted(declared - DELEGATE_METHODS)}")


def test_signature_index_is_not_silently_broken():
    jtype = javaapi.type_of(PLUGIN_FQCN)
    assert jtype is not None and jtype.methods, (
        f"the signature index parsed no methods out of {PLUGIN_FQCN}; every "
        "member check in this module would pass on an empty index")
    watched = set()
    for fqcn in WATCHED_TYPES:
        found = javaapi.type_of(fqcn)
        if found is not None:
            watched.add(found.path)
    broken = [(path, why) for path, why in javaapi.PARSE_ERRORS if path in watched]
    assert not broken, (
        "the signature index failed on files this module asserts about:\n"
        + "\n".join(f"  {path}: {why}" for path, why in broken))
    stats, buckets = analyse()
    assert stats["parsed"] > 0, "no plugin in the corpus parsed"
    assert stats["checked"] > 0, (
        "the corpus walk resolved no Java member access at all; the type "
        "inference is broken and both baseline tests would pass on nothing")
