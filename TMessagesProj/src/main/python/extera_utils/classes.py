"""extera_utils.classes — class-proxy DSL for the exteraless plugin engine.

Generates REAL Java classes at runtime (subclass Java classes, override methods,
implement interfaces, declare fields) via the dexmaker-backed
app.exteraless.plugins.utils.ClassProxyFactory on the Java side.

Scope notes for this port (stock Chaquopy 17.0.0):
- The built-in J-modifiers of exteraGram's Chaquopy fork (obj.JA/JGS/JIR/JS,
  ``from java import JNone``) are runtime-level features of THEIR fork and are
  OUT OF SCOPE here. Use the ``J()``/JavaHelper wrapper below instead.
- jMVELmethod/jMVELoverride are accepted as aliases of jmethod/joverride, but
  the body stays Python (MVEL expressions are only honored when a spec with an
  explicit "mvel" entry is handed to PluginServices.generateProxyClass directly).
- super() here is extera_utils.classes.super (shadow the builtin via
  ``from extera_utils.classes import super`` if you want the docs' spelling);
  it calls the Java superclass implementation through PluginServices.invokeSuper.

Instantiation order (docs.PLUGINS-API.md §7):
    jpreconstructor -> Java ctor -> __init__ -> jconstructor -> on_post_init

jpreconstructor runs INSIDE the generated Java constructor (before super.<init>),
so it also fires when Java code instantiates the class directly. It receives the
constructor arguments and may return a replacement tuple/list (or None to keep).
"""

import inspect
import json
import sys
import threading


_internal_modules = {}


def _internal(name):
    module = _internal_modules.get(name)
    if module is None:
        import importlib
        module = importlib.import_module("extera_utils." + name)
        _internal_modules[name] = module
    return module


try:
    from app.exteraless.plugins import PluginServices
except Exception:  # host interpreter (no Chaquopy) — generation paths raise
    PluginServices = None

_PEER_FIELD = "__extera_peer__"
_DISPATCH_ATTR = "__extera_dispatch__"

# classKey -> Python wrapper class; used by the Java-initiated pre-construct hook.
_CLASSES = {}
_CLASS_OWNERS = {}


def forget_plugin_classes(plugin_id):
    if plugin_id is None:
        return
    for key, owner in list(_CLASS_OWNERS.items()):
        if owner == plugin_id:
            _CLASS_OWNERS.pop(key, None)
            _CLASSES.pop(key, None)

_PRIMITIVE_DESCRIPTORS = {
    "void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
    "int": "I", "long": "J", "float": "F", "double": "D",
}

_OBJECT_ARRAY_FACTORY = None
_dispatch_state = threading.local()


# Type names / JVM descriptors

def _java_class_name(obj):
    """Resolve a superclass/interface/field-type token to a Java class name.

    Accepts fqcn strings, Chaquopy jclass proxies and other @java_subclass
    wrappers (proxy-of-proxy: the parent class is generated on demand).
    """
    if isinstance(obj, str):
        return obj
    if isinstance(obj, type) and issubclass(obj, Base):
        info = _info_for(obj)
        key = _ensure_java_class(info)
        return str(PluginServices.getProxyClass(key).getName())
    get_name = getattr(obj, "getName", None)
    if callable(get_name):
        try:
            return str(get_name())
        except Exception:
            pass
    name = getattr(obj, "name", None)
    if isinstance(name, str) and name:
        return name
    text = str(obj)  # e.g. "<class 'java.util.ArrayList'>"
    if "'" in text:
        candidate = text.split("'")[-2]
        if candidate:
            return candidate
    raise TypeError(f"cannot resolve a Java class name from {obj!r}; pass an fqcn string")


def _java_type_name(token):
    """Map an annotation/argument token to a Java type name string."""
    if isinstance(token, str):
        return token
    if token is bool:
        return "boolean"
    if token is int:
        return "int"
    if token is float:
        return "double"
    if token is str:
        return "java.lang.String"
    if token is None or token is type(None):
        return "void"
    return _java_class_name(token)


def _descriptor(type_name):
    if type_name in _PRIMITIVE_DESCRIPTORS:
        return _PRIMITIVE_DESCRIPTORS[type_name]
    if type_name.endswith("[]"):
        return "[" + _descriptor(type_name[:-2])
    if type_name.startswith("["):
        return type_name
    if type_name.startswith("L") and type_name.endswith(";"):
        return type_name
    return "L" + type_name.replace(".", "/") + ";"


def _method_sig(arg_types, return_type="void"):
    return "(" + "".join(_descriptor(t) for t in arg_types) + ")" + _descriptor(return_type)


def _normalize_ctor_sig(sig):
    """jconstructor/jpreconstructor sig: JVM descriptor or list of type names."""
    if sig is None:
        return None
    if isinstance(sig, str):
        return sig if sig.endswith(")V") else (sig + "V" if sig.endswith(")") else sig)
    return _method_sig([_java_type_name(t) for t in sig], "void")


def _arity(fn, skip_self):
    """Positional arity of fn (None when it takes *args)."""
    try:
        params = list(inspect.signature(fn).parameters.values())
    except (TypeError, ValueError):
        return None
    if skip_self and params and params[0].name == "self":
        params = params[1:]
    count = 0
    for p in params:
        if p.kind == p.VAR_POSITIONAL:
            return None
        if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD):
            count += 1
    return count


def _infer_arg_types(fn):
    params = list(inspect.signature(fn).parameters.values())
    if params and params[0].name == "self":
        params = params[1:]
    out = []
    for p in params:
        if p.kind in (p.VAR_POSITIONAL, p.VAR_KEYWORD):
            raise TypeError(f"jmethod {fn.__name__}: *args/**kwargs need explicit arg_types")
        if p.annotation is inspect.Parameter.empty:
            raise TypeError(
                f"jmethod {fn.__name__}: parameter {p.name!r} needs a type annotation "
                f"(or pass arg_types explicitly)")
        out.append(_java_type_name(p.annotation))
    return out


def _infer_return_type(fn):
    ann = inspect.signature(fn).return_annotation
    if ann is inspect.Signature.empty:
        return "void"
    return _java_type_name(ann)


# Fields

class _JFieldMethod:
    """Java-only accessor declaration for jfield(methods=[...])."""

    def __init__(self, name, getter):
        self.name = name
        self.getter = getter


def jgetmethod(name=None):
    """Declare a Java getter for a jfield (default name: get<Name>)."""
    return _JFieldMethod(name, True)


def jsetmethod(name=None):
    """Declare a Java setter for a jfield (default name: set<Name>)."""
    return _JFieldMethod(name, False)


class _JField:
    """Descriptor for a real Java field on the generated class.

    Python-side access is routed to the Java instance field via Chaquopy.
    """

    def __init__(self, type_name, default=None, static=False, methods=None):
        self.type_name = _java_type_name(type_name)
        self.default = default
        self.static = static
        self.accessors = list(methods or [])
        self.name = None

    def __set_name__(self, owner, name):
        if self.name is None:
            self.name = name

    def _java_class(self, objtype):
        info = getattr(objtype, "__extera_proxy_info__", None)
        if info is None or info.class_key is None or PluginServices is None:
            return None
        return PluginServices.getProxyClass(info.class_key)

    def __get__(self, obj, objtype=None):
        if obj is None:
            if not self.static:
                return self
            jclass = self._java_class(objtype)
            if jclass is None:
                return self.default
            return jclass.getField(self.name).get(None)
        java = obj.__dict__.get("java")
        if java is None:
            return self.default
        return getattr(java, self.name)

    def __set__(self, obj, value):
        if obj is None:
            raise AttributeError("static jfield assignment: use an instance or the java class")
        java = obj.__dict__.get("java")
        if java is None:
            raise AttributeError("proxy is not attached to a Java instance yet")
        setattr(java, self.name, value)

    def to_spec(self):
        entry = {"name": self.name, "type": self.type_name, "static": bool(self.static)}
        if self.default is not None:
            if isinstance(self.default, (bool, int, float, str)):
                entry["initial"] = self.default
        cap = self.name[:1].upper() + self.name[1:]
        for accessor in self.accessors:
            key = "getter" if accessor.getter else "setter"
            entry[key] = accessor.name or (("get" if accessor.getter else "set") + cap)
        return entry


def jfield(type, default=None, static=False, methods=None, initial=None):
    """Declare a real Java field: ``count = jfield("int", default=0)``."""
    if initial is not None and default is None:
        default = initial
    return _JField(type, default=default, static=static, methods=methods)


# Method markers

def _mark(fn, **meta):
    fn.__extera_java__ = meta
    return fn


def joverride(fn=None, name=None):
    """Override a Java method: @joverride / @joverride("equals") / @joverride(name=...).

    Without an explicit signature ALL overloads of the Java method are routed
    to the Python function; use @joverload for one specific overload.
    """
    if isinstance(fn, str):
        name = fn
        fn = None

    def decorate(f):
        return _mark(f, kind="override", name=name or f.__name__, arg_types=None)

    return decorate(fn) if callable(fn) else decorate


def joverload(name, arg_types):
    """Override one specific overload: @joverload("add", ["java.lang.Object"])."""
    resolved = [_java_type_name(t) for t in arg_types]

    def decorate(f):
        return _mark(f, kind="override", name=name, arg_types=resolved)

    return decorate


def jmethod(fn=None, name=None, return_type=None, arg_types=None):
    """Declare a NEW Java method (not an override).

    Forms: @jmethod | @jmethod("label") | @jmethod("label", "java.lang.String", ["int"])
    | @jmethod(name=..., return_type=..., arg_types=...). Without explicit types,
    they are inferred from Python annotations (missing return annotation = void).
    """
    if isinstance(fn, str):
        # positional form: jmethod("name"[, return_type[, arg_types]])
        fn, name, return_type, arg_types = None, fn, name, return_type

    def decorate(f):
        return _mark(f, kind="method", name=name or f.__name__,
                     return_type=return_type, arg_types=arg_types)

    return decorate(fn) if callable(fn) else decorate


def jMVELmethod(fn=None, name=None, return_type=None, arg_types=None):
    """Docs alias of jmethod. Stock-Chaquopy port: the body stays Python
    (no MVEL compilation); the Java side still honors explicit "mvel" spec entries."""
    return jmethod(fn, name=name, return_type=return_type, arg_types=arg_types)


def jMVELoverride(fn=None, name=None):
    """Docs alias of joverride; the body stays Python (see jMVELmethod)."""
    return joverride(fn, name=name)


def jconstructor(fn=None, sig=None):
    """Post-constructor hook: fires after __init__ with the Java ctor args."""
    if isinstance(fn, (str, list, tuple)):
        sig = fn
        fn = None

    def decorate(f):
        return _mark(f, kind="constructor", sig=_normalize_ctor_sig(sig))

    return decorate(fn) if callable(fn) else decorate


def jpreconstructor(fn=None, sig=None):
    """Pre-constructor hook (no self): may return replacement ctor args.

    Runs inside the generated Java constructor before super.<init>, so it also
    fires for direct Java-side instantiation.
    """
    if isinstance(fn, (str, list, tuple)):
        sig = fn
        fn = None

    def decorate(f):
        return _mark(f, kind="preconstructor", sig=_normalize_ctor_sig(sig))

    return decorate(fn) if callable(fn) else decorate


# super() helper

class _SuperCaller:
    """Attribute access resolves the overridden Java method; calling it invokes super."""

    __slots__ = ("_info", "_proxy", "_py_key", "_fn_name")

    def __init__(self, info, proxy, py_key, fn_name):
        object.__setattr__(self, "_info", info)
        object.__setattr__(self, "_proxy", proxy)
        object.__setattr__(self, "_py_key", py_key)
        object.__setattr__(self, "_fn_name", fn_name)

    def __getattr__(self, attr):
        info = object.__getattribute__(self, "_info")
        fn_name = object.__getattribute__(self, "_fn_name")
        if attr == fn_name:
            key = object.__getattribute__(self, "_py_key")
        else:
            key = info.key_by_fn_name.get(attr)
        if key is None:
            raise AttributeError(
                f"no Java override bound to Python method {attr!r} on {info.cls.__name__}")
        proxy = object.__getattribute__(self, "_proxy")

        def call_super(*args):
            return _invoke_super(info, proxy, key, args)

        return call_super


def super():
    """Java-super caller for inside a proxy method: ``super().add_item(x)``."""
    ctx = getattr(_dispatch_state, "ctx", None)
    if ctx is None:
        raise RuntimeError(
            "extera_utils.classes.super() is only valid inside a proxy method dispatch")
    info, proxy, py_key, fn_name = ctx
    return _SuperCaller(info, proxy, py_key, fn_name)


def _invoke_super(info, proxy, py_key, args):
    if PluginServices is None:
        raise RuntimeError("class proxy requires the exteraless Android runtime")
    return PluginServices.invokeSuper(info.class_key, proxy, py_key, _object_array(args))


def _object_array(values):
    global _OBJECT_ARRAY_FACTORY
    if _OBJECT_ARRAY_FACTORY is None:
        from java import jarray, jclass
        _OBJECT_ARRAY_FACTORY = jarray(jclass("java.lang.Object"))
    return _OBJECT_ARRAY_FACTORY(list(values))


# Proxy class machinery

class _ProxyInfo:
    """Collected declarations + generated-class state of one @java_subclass."""

    def __init__(self, cls, superclass, interfaces, custom_name):
        self.cls = cls
        self.superclass = superclass
        self.interfaces = list(interfaces)
        self.custom_name = custom_name
        self.class_key = None
        self.lock = threading.Lock()
        self.fields = {}          # attr name -> _JField
        self.method_specs = []    # JSON "methods" entries
        self.dispatch = {}        # py_key -> python function
        self.key_by_fn_name = {}  # python fn name -> py_key (for super().name())
        self.ctor_hooks = []      # (sig|None, arity|None, fn)
        self.pre_hooks = []       # (sig|None, arity|None, fn)
        self._collect()

    def _collect(self):
        for klass in reversed(self.cls.__mro__):
            if klass in (object, Base):
                continue
            for attr_name, value in vars(klass).items():
                if isinstance(value, _JField):
                    if value.name is None:
                        value.name = attr_name
                    self.fields[attr_name] = value
                elif callable(value) and hasattr(value, "__extera_java__"):
                    self._register(value, value.__extera_java__)

    def _register(self, fn, meta):
        kind = meta["kind"]
        if kind == "override":
            java_name = meta["name"]
            arg_types = meta.get("arg_types")
            if arg_types is not None:
                # Return type is resolved Java-side from the matched super method;
                # the "V" here is only a placeholder for the spec key/signature.
                sig = _method_sig(arg_types, "void")
                key = f"{java_name}|{sig}"
                entry = {"key": key, "name": java_name, "sig": sig, "super": True}
            else:
                key = f"{java_name}|*"
                entry = {"key": key, "name": java_name, "super": True}
            self.method_specs.append(entry)
            self.dispatch[key] = fn
            self.key_by_fn_name[fn.__name__] = key
        elif kind == "method":
            java_name = meta["name"]
            arg_types = meta.get("arg_types")
            if arg_types is None:
                arg_types = _infer_arg_types(fn)
            else:
                arg_types = [_java_type_name(t) for t in arg_types]
            return_type = meta.get("return_type")
            return_type = _java_type_name(return_type) if return_type is not None \
                else _infer_return_type(fn)
            sig = _method_sig(arg_types, return_type)
            key = f"{java_name}|{sig}"
            self.method_specs.append({
                "key": key, "name": java_name, "sig": sig,
                "return": return_type, "super": False,
            })
            self.dispatch[key] = fn
            self.key_by_fn_name[fn.__name__] = key
        elif kind == "constructor":
            self.ctor_hooks.append((meta.get("sig"), _arity(fn, skip_self=True), fn))
        elif kind == "preconstructor":
            self.pre_hooks.append((meta.get("sig"), _arity(fn, skip_self=False), fn))

    def build_spec(self):
        return {
            "name": self.custom_name or self.cls.__name__,
            "superclass": _java_class_name(self.superclass),
            "interfaces": [_java_class_name(i) for i in self.interfaces],
            "fields": [f.to_spec() for f in self.fields.values()],
            "methods": self.method_specs,
        }


def _info_for(cls):
    info = getattr(cls, "__extera_proxy_info__", None)
    if info is None:
        raise TypeError(f"{cls!r} is not a @java_subclass class")
    return info


def _owner_plugin_id():
    try:
        caller_plugin_id = _internal("plugin_loader").caller_plugin_id
        return caller_plugin_id()
    except Exception:
        return None


def _ensure_java_class(info):
    if info.class_key is not None:
        return info.class_key
    if PluginServices is None:
        raise RuntimeError("class proxy requires the exteraless Android runtime (Chaquopy)")
    with info.lock:
        if info.class_key is not None:
            return info.class_key
        spec_json = json.dumps(info.build_spec())
        owner = _owner_plugin_id()
        key = PluginServices.generateProxyClass(owner, spec_json)
        if key is None:
            try:
                reason = PluginServices.getProxyError()
            except Exception:
                reason = None
            raise RuntimeError(
                f"generateProxyClass failed for {info.cls.__name__}: "
                f"{reason or 'no permission or generator error, see logcat'}")
        info.class_key = key
        _CLASSES[key] = info.cls
        if owner is not None:
            _CLASS_OWNERS[key] = owner
        return info.class_key


def __extera_java_preconstruct__(class_key, ctor_sig, *args):
    """Called from the generated Java constructor (before super.<init>).

    Returns a replacement Object[] for the ctor args, or None to keep them.
    """
    cls = _CLASSES.get(class_key)
    if cls is None:
        return None
    info = cls.__extera_proxy_info__
    for sig_hint, arity, fn in info.pre_hooks:
        if sig_hint is not None and sig_hint != ctor_sig:
            continue
        if arity is not None and arity != len(args):
            continue
        result = fn(*args)
        if result is None:
            return None
        return _object_array(list(result))
    return None


# Base

class Base:
    """Base class of java_subclass proxies.

    Instance attrs: .java / .this — the raw Java proxy object. Unknown
    attributes fall through to the Java instance. release() detaches the
    Python peer from the Java instance (breaks the cross-heap GC cycle).
    """

    java = None
    this = None

    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        java = self.__dict__.get("java")
        if java is None:
            raise AttributeError(name)
        return getattr(java, name)

    # -- instantiation --

    @classmethod
    def new_instance(cls, *java_ctor_args, init_args=None, ctor_sig=None):
        """Create the Java proxy + Python peer. init_args feed __init__
        (default: the Java ctor args); ctor_sig forces a JVM ctor signature."""
        info = _info_for(cls)
        class_key = _ensure_java_class(info)
        peer = cls.__new__(cls)
        proxy = PluginServices.newProxyInstance(
            _owner_plugin_id(), class_key, ctor_sig or "",
            _object_array(java_ctor_args), peer)
        if proxy is None:
            raise RuntimeError(f"newProxyInstance failed for {cls.__name__}; see logcat")
        peer.java = proxy
        peer.this = proxy

        user_init = any("__init__" in vars(k) for k in cls.__mro__ if k not in (Base, object))
        if user_init:
            cls.__init__(peer, *(init_args if init_args is not None else java_ctor_args))

        for sig_hint, arity, fn in info.ctor_hooks:
            if sig_hint is not None and ctor_sig is not None and sig_hint != ctor_sig:
                continue
            if arity is not None and arity != len(java_ctor_args):
                continue
            fn(peer, *java_ctor_args)

        post = None
        for k in cls.__mro__:
            if k in (Base, object):
                continue
            if "on_post_init" in vars(k):
                post = vars(k)["on_post_init"]
                break
        if post is not None:
            post(peer)
        return peer

    @classmethod
    def new_java_instance(cls, *java_ctor_args, **kwargs):
        """new_instance(...) but returns the raw Java object."""
        return cls.new_instance(*java_ctor_args, **kwargs).java

    @classmethod
    def from_java(cls, raw):
        """Wrap a raw Java instance of the generated class back into its peer."""
        peer = getattr(raw, _PEER_FIELD, None)
        if peer is not None:
            return peer
        peer = cls.__new__(cls)
        peer.java = raw
        peer.this = raw
        try:
            setattr(raw, _PEER_FIELD, peer)
        except Exception:
            pass
        return peer

    @classmethod
    def java_class(cls):
        """The generated java.lang.Class (generates it on first call)."""
        info = _info_for(cls)
        key = _ensure_java_class(info)
        return PluginServices.getProxyClass(key)

    @classmethod
    def bind(cls, java_superclass, *interfaces, custom_name=None):
        """Rebind the same Python class body to another Java superclass."""
        bound = type(cls.__name__ + "Bound", (cls,), {"__module__": cls.__module__})
        return java_subclass(java_superclass, *interfaces, custom_name=custom_name)(bound)

    def release(self):
        """Detach the Python peer from the Java instance (GC hygiene)."""
        java = self.__dict__.get("java")
        if java is not None:
            try:
                setattr(java, _PEER_FIELD, None)
            except Exception:
                pass
        self.java = None
        self.this = None

    # -- dispatch entry (called by ClassProxyFactory.dispatch) --

    def __extera_dispatch__(self, py_key, proxy, *args):
        info = type(self).__extera_proxy_info__
        fn = info.dispatch.get(py_key)
        if fn is None:
            return _invoke_super(info, proxy, py_key, args)
        prev = getattr(_dispatch_state, "ctx", None)
        _dispatch_state.ctx = (info, proxy, py_key, fn.__name__)
        try:
            return fn(self, *args)
        except Exception:
            return _dispatch_failed(info, py_key, fn.__name__)
        finally:
            _dispatch_state.ctx = prev


_JAVA_DEFAULTS = {
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


def _log(message):
    try:
        from android_utils import log
        log(message)
        return
    except Exception:
        pass
    print(f"[exteraless:classes] {message}", file=sys.stderr)


def _return_defaults(info):
    cached = getattr(info, "return_defaults", None)
    if cached is not None:
        return cached
    defaults = {}
    by_name = {}
    sources = [info.superclass]
    sources.extend(info.interfaces)
    for source in sources:
        if source is None:
            continue
        try:
            methods = source.getClass().getMethods()
        except Exception:
            continue
        for index in range(len(methods)):
            try:
                method = methods[index]
                name = str(method.getName())
                if name in by_name:
                    continue
                by_name[name] = _JAVA_DEFAULTS.get(str(method.getReturnType().getName()))
            except Exception:
                continue
    for spec in info.method_specs:
        declared = spec.get("return")
        if declared is not None:
            defaults[spec["key"]] = _JAVA_DEFAULTS.get(declared)
        else:
            defaults[spec["key"]] = by_name.get(spec["name"])
    info.return_defaults = defaults
    return defaults


def _dispatch_failed(info, py_key, fn_name):
    try:
        import traceback
        _log(f"{info.cls.__name__}.{fn_name} raised into Java:\n"
             + traceback.format_exc())
    except Exception:
        pass
    try:
        return _return_defaults(info).get(py_key)
    except Exception:
        return None


def java_subclass(superclass, *extra_interfaces, custom_name=None, interfaces=None):
    """Class decorator: generate a real Java subclass of *superclass*.

    ``@java_subclass(ArrayList)`` or ``@java_subclass(FrameLayout, Iface1)``;
    ``custom_name=`` sets the name segment of the generated class
    (Proxy_<Super>_<name>_<hash>_<rand>); without it the Python class name is used.
    """
    ifaces = list(extra_interfaces)
    if interfaces:
        ifaces.extend(interfaces)

    def decorate(cls):
        if not isinstance(cls, type) or not issubclass(cls, Base):
            raise TypeError("@java_subclass classes must extend extera_utils.classes.Base")
        cls.__extera_proxy_info__ = _ProxyInfo(cls, superclass, ifaces, custom_name)
        return cls

    return decorate


# PyObj / JavaHelper (reflection wrapper — self-contained, stock Chaquopy)

class PyObj:
    """Carry an arbitrary Python object through Java APIs.

    On stock Chaquopy any Python object crossing into Java arrives as
    com.chaquo.python.PyObject, so create() is an explicit-marker passthrough.
    """

    @staticmethod
    def create(obj):
        return obj


class JavaHelper:
    """Reflection wrapper over a Java object.

    Attribute read: direct member (method/field via Chaquopy), then
    getXxx()/isXxx() getter unless JNotUseGetterAndSetter was chained.
    Attribute write: setXxx(value), then direct field assignment.
    Overloads are not disambiguated — use plain Chaquopy reflection for that.

    Chaining flags (each returns self): JAccessAll (also consult private
    fields up the class hierarchy), JNotUseGetterAndSetter, JIgnoreResult.
    """

    def __init__(self, obj):
        object.__setattr__(self, "_j_obj", obj)
        object.__setattr__(self, "_j_access_all", False)
        object.__setattr__(self, "_j_no_accessors", False)
        object.__setattr__(self, "_j_ignore_result", False)

    # -- chaining flags --

    @property
    def JAccessAll(self):
        object.__setattr__(self, "_j_access_all", True)
        return self

    @property
    def JNotUseGetterAndSetter(self):
        object.__setattr__(self, "_j_no_accessors", True)
        return self

    @property
    def JIgnoreResult(self):
        object.__setattr__(self, "_j_ignore_result", True)
        return self

    # -- attribute access --

    def __getattr__(self, name):
        if name.startswith("_j_"):
            raise AttributeError(name)
        obj = object.__getattribute__(self, "_j_obj")
        access_all = object.__getattribute__(self, "_j_access_all")
        no_accessors = object.__getattribute__(self, "_j_no_accessors")

        try:
            return getattr(obj, name)
        except AttributeError:
            pass

        if not no_accessors:
            capitalized = name[:1].upper() + name[1:]
            for prefix in ("get", "is"):
                getter = getattr(obj, prefix + capitalized, None)
                if callable(getter):
                    return getter()

        if access_all:
            import hook_utils
            value = hook_utils.get_private_field(obj, name)
            if value is not None:
                return value

        raise AttributeError(
            f"{obj!r} has no accessible member {name!r}"
        )

    def __setattr__(self, name, value):
        if name.startswith("_j_"):
            object.__setattr__(self, name, value)
            return
        obj = object.__getattribute__(self, "_j_obj")
        no_accessors = object.__getattribute__(self, "_j_no_accessors")

        if not no_accessors:
            setter = getattr(obj, "set" + name[:1].upper() + name[1:], None)
            if callable(setter):
                setter(value)
                return
        try:
            setattr(obj, name, value)
            return
        except AttributeError:
            pass
        if object.__getattribute__(self, "_j_access_all"):
            import hook_utils
            if hook_utils.set_private_field(obj, name, value):
                return
        raise AttributeError(
            f"{obj!r} has no writable member {name!r}"
        )


# Documented aliases: J(obj) = JavaHelper = ClassHelper.
J = JavaHelper
ClassHelper = JavaHelper
