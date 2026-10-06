import base64
import sys
import types

_roots = ("java", "com", "org", "javax", "android", "androidx")
_blocked = ("chaquopy", "_chaquopy", "ctypes", "_ctypes", "cffi", "jnius", "java.lang.Runtime")

_MISSING = object()


def _b64(value):
    if isinstance(value, str):
        value = value.encode("latin-1", "replace")
    return base64.b64encode(bytes(value)).decode("ascii")


def _unb64(value):
    return base64.b64decode(value)


class RemoteError(Exception):
    def __init__(self, kind, message, where=None):
        self.kind = kind
        self.where = where
        super().__init__(message if where is None else "%s (%s)" % (message, where))


def to_wire(value, rpc=None):
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, (bytes, bytearray, memoryview)):
        return {"b": _b64(bytes(value))}
    if isinstance(value, JavaInstance):
        return {"h": value._h}
    if isinstance(value, JavaClass):
        return {"c": value._n}
    if callable(value) and not isinstance(value, type):
        if rpc is None:
            raise RemoteError("TypeError", "a callable cannot be passed here")
        return {"f": rpc.export(value)}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [to_wire(item, rpc) for item in value]
    if isinstance(value, dict):
        return {str(key): to_wire(item, rpc) for key, item in value.items()}
    return {"s": str(value)}


def from_wire(value):
    if isinstance(value, list):
        return [from_wire(item) for item in value]
    if isinstance(value, dict):
        if "h" in value:
            return JavaInstance(value["h"], value.get("c"))
        if "c" in value:
            return JavaClass(value["c"])
        if "b" in value:
            return _unb64(value["b"])
        if "s" in value:
            return value["s"]
        if "e" in value:
            _raise(value["e"])
        return {key: from_wire(item) for key, item in value.items()}
    return value


_KINDS = {
    "PermissionError": PermissionError, "ImportError": ImportError,
    "AttributeError": AttributeError, "TypeError": TypeError,
    "ValueError": ValueError, "TimeoutError": TimeoutError,
    "ConnectionError": ConnectionError, "FileNotFoundError": FileNotFoundError,
    "RuntimeError": RuntimeError, "KeyError": KeyError, "IndexError": IndexError,
}


def _raise(error):
    message = error.get("message", "java call failed")
    where = error.get("where")
    kind = error.get("kind")
    factory = _KINDS.get(kind)
    if factory is None:
        raise RemoteError(kind or "JavaException", message, where)
    raise factory(message if where is None else "%s (%s)" % (message, where))


class JavaClass(object):
    def __init__(self, name):
        object.__setattr__(self, "_n", name)

    def __call__(self, *args):
        return from_wire(_rpc().call("new", cls=self._n, args=[to_wire(a, _rpc()) for a in args]))

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return RemoteMember(self._n, name)

    def __setattr__(self, name, value):
        _rpc().call("setstatic", cls=self._n, name=name, value=to_wire(value, _rpc()))

    def __getitem__(self, name):
        return RemoteMember(self._n, name)

    def __instancecheck__(self, instance):
        if isinstance(instance, JavaClass):
            return _rpc().call("subclass", cls=instance._n, parent=self._n)
        if isinstance(instance, JavaInstance):
            return _rpc().call("instanceof", target=instance._h, cls=self._n)
        return False

    def __repr__(self):
        return "<java class %s>" % self._n

    def __str__(self):
        return self._n


class JavaInstance(object):
    def __init__(self, handle, name=None):
        object.__setattr__(self, "_h", handle)
        object.__setattr__(self, "_n", name)

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return RemoteMember(self._h, name)

    def __setattr__(self, name, value):
        if name.startswith("_"):
            object.__setattr__(self, name, value)
            return
        _rpc().call("setfield", target=self._h, name=name, value=to_wire(value, _rpc()))

    def __getitem__(self, index):
        return from_wire(_rpc().call("getitem", target=self._h, index=to_wire(index, _rpc())))

    def __setitem__(self, index, value):
        _rpc().call("setitem", target=self._h, index=to_wire(index, _rpc()), value=to_wire(value, _rpc()))

    def __len__(self):
        return _rpc().call("len", target=self._h)

    def __iter__(self):
        return iter(from_wire(_rpc().call("list", target=self._h)))

    def __bool__(self):
        return bool(_rpc().call("truth", target=self._h))

    def __str__(self):
        return _rpc().call("str", target=self._h)

    def __repr__(self):
        return "<java %s object>" % (self._n or "?")

    def __eq__(self, other):
        if isinstance(other, JavaInstance):
            return self._h == other._h
        return _rpc().call("equals", target=self._h, value=to_wire(other, _rpc()))

    def __hash__(self):
        return hash(self._h)

    def getClass(self):
        return from_wire(_rpc().call("classof", target=self._h))


class RemoteMember(object):
    def __init__(self, ref, name):
        object.__setattr__(self, "_r", ref)
        object.__setattr__(self, "_m", name)
        object.__setattr__(self, "_v", _MISSING)

    def __call__(self, *args):
        rpc = _rpc()
        return from_wire(rpc.call("invoke", target=self._r, name=self._m,
                                  args=[to_wire(a, rpc) for a in args]))

    def _value(self):
        if self._v is _MISSING:
            object.__setattr__(self, "_v", from_wire(_rpc().call("member", target=self._r, name=self._m)))
        return self._v

    def _replace(self, value, rpc):
        _rpc().call("setmember", target=self._r, name=self._m, value=to_wire(value, rpc))

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return getattr(self._value(), name)

    def __setattr__(self, name, value):
        if name.startswith("_"):
            object.__setattr__(self, name, value)
            return
        setattr(self._value(), name, value)

    def __str__(self):
        return str(self._value())

    def __repr__(self):
        return repr(self._value())

    def __eq__(self, other):
        return self._value() == other

    def __ne__(self, other):
        return self._value() != other

    def __bool__(self):
        return bool(self._value())

    def __int__(self):
        return int(self._value())

    def __float__(self):
        return float(self._value())

    def __len__(self):
        return len(self._value())

    def __getitem__(self, item):
        return self._value()[item]

    def __setitem__(self, item, value):
        self._value()[item] = value

    def __iter__(self):
        return iter(self._value())

    def __hash__(self):
        return hash(self._value())


RPC = None


def _rpc():
    if RPC is None:
        raise ImportError("java is not available in this process")
    return RPC


class _ShimModule(types.ModuleType):
    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        full = "%s.%s" % (self.__name__, name)
        if name[:1].islower():
            child = _ShimModule(full)
            child.__dict__["__package__"] = full
            sys.modules.setdefault(full, child)
            return child
        return JavaClass(full)

    def __call__(self, *args):
        return JavaClass(self.__name__)(*args)


def _build_java_module():
    module = _ShimModule("java")
    for name in ("lang", "io", "net", "util", "nio", "text", "math", "security",
                 "concurrent", "reflect", "time", "sql"):
        child = _ShimModule("java.%s" % name)
        sys.modules["java.%s" % name] = child
        module.__dict__[name] = child
    module.__dict__["String"] = JavaClass("java.lang.String")
    module.__dict__["Object"] = JavaClass("java.lang.Object")
    module.__dict__["Class"] = JavaClass("java.lang.Class")
    module.__dict__["Integer"] = JavaClass("java.lang.Integer")
    module.__dict__["Long"] = JavaClass("java.lang.Long")
    module.__dict__["Boolean"] = JavaClass("java.lang.Boolean")
    module.__dict__["Double"] = JavaClass("java.lang.Double")
    module.__dict__["Float"] = JavaClass("java.lang.Float")
    module.__dict__["Byte"] = JavaClass("java.lang.Byte")
    module.__dict__["Short"] = JavaClass("java.lang.Short")
    module.__dict__["Character"] = JavaClass("java.lang.Character")
    module.__dict__["Runnable"] = JavaClass("java.lang.Runnable")
    module.__dict__["Thread"] = JavaClass("java.lang.Thread")
    module.__dict__["System"] = JavaClass("java.lang.System")
    module.__dict__["Exception"] = JavaClass("java.lang.Exception")
    module.__dict__["jclass"] = jclass
    module.__dict__["jarray"] = jarray
    module.__dict__["cast"] = cast
    module.__dict__["array"] = array
    module.__dict__["JavaClass"] = JavaClass
    module.__dict__["JavaObject"] = JavaInstance
    return module


def jclass(name):
    if not isinstance(name, str):
        return name
    return JavaClass(name)


class array(list):
    def __init__(self, typecode, values=()):
        super().__init__(values)
        self.typecode = typecode


class jarray(object):
    def __new__(cls, typecode):
        class _Array(array):
            def __init__(self, values=()):
                super().__init__(typecode, values)

        _Array.__name__ = "array"
        return _Array


def cast(value, cls):
    name = cls._n if isinstance(cls, JavaClass) else str(cls)
    target = value._h if isinstance(value, JavaInstance) else to_wire(value, _rpc())
    return from_wire(_rpc().call("cast", target=target, cls=name))


class _ShimFinder(object):
    def find_module(self, fullname, path=None):
        return self if self._handles(fullname) else None

    def find_spec(self, fullname, path=None, target=None):
        if not self._handles(fullname):
            return None
        import importlib.machinery

        return importlib.machinery.ModuleSpec(fullname, _ShimLoader())

    def _handles(self, fullname):
        root = fullname.partition(".")[0]
        if fullname in _blocked or root in _blocked:
            raise ImportError("%s is not available in the plugin workspace" % fullname)
        return root in _roots


class _ShimLoader(object):
    def create_module(self, spec):
        module = _ShimModule(spec.name)
        parent, _, leaf = spec.name.rpartition(".")
        if parent in sys.modules:
            setattr(sys.modules[parent], leaf, module)
        return module

    def exec_module(self, module):
        pass


def install():
    for name in list(sys.modules):
        if name.partition(".")[0] in _roots or name in _blocked or name.startswith("_chaquopy"):
            sys.modules.pop(name, None)
    finder = _ShimFinder()
    sys.meta_path.insert(0, finder)
    sys.modules["java"] = _build_java_module()
    for root in ("com", "org", "javax", "android"):
        module = _ShimModule(root)
        module.__dict__["__path__"] = []
        sys.modules[root] = module
    return finder
