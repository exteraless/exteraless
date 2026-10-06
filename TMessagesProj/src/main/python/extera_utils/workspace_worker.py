import os
import socket
import sys
import traceback

_KEEP = (0, 1, 2)
_SHIM_ROOTS = ("java", "com", "org", "javax", "android", "androidx")
_BLOCKED_ROOTS = ("chaquopy", "_chaquopy", "jnius", "pyjnius", "_java", "resource",
                  "_posixsubprocess", "pty")
_FROZEN_SYS = ("meta_path", "path_hooks", "path_importer_cache", "modules", "settrace",
               "setprofile", "audit", "setrecursionlimit", "setprofile")
_REFUSED = ("fork", "forkpty", "system", "popen", "execl", "execle", "execlp", "execv",
            "execve", "execvp", "execvpe", "posix_spawn", "posix_spawnp", "spawnl",
            "spawnle", "spawnlp", "spawnv", "spawnve", "spawnvp", "setuid", "seteuid",
            "setgid", "setegid", "setreuid", "setregid", "chroot", "kill", "killpg")
_SEALED_MODULES = ("extera_utils.audit_gate", "extera_utils.java_shim",
                   "extera_utils.workspace", "extera_utils.workspace_worker",
                   "extera_utils.class_aliases")


def _close_inherited(keep):
    try:
        entries = os.listdir("/proc/self/fd")
    except Exception:
        return
    for entry in entries:
        try:
            fd = int(entry)
        except ValueError:
            continue
        if fd in _KEEP or fd == keep:
            continue
        try:
            os.close(fd)
        except Exception:
            pass


def _limits():
    try:
        import resource

        tasks = 0
        try:
            tasks = len(os.listdir("/proc/self/task"))
        except Exception:
            pass
        for name, value in (("RLIMIT_CORE", 0), ("RLIMIT_CPU", 600),
                            ("RLIMIT_NOFILE", 256), ("RLIMIT_FSIZE", 1 << 30),
                            ("RLIMIT_MEMLOCK", 0), ("RLIMIT_NPROC", tasks + 64)):
            limit = getattr(resource, name, None)
            if limit is None:
                continue
            try:
                resource.setrlimit(limit, (value, value))
            except Exception:
                pass
    except Exception:
        pass
    sys.modules.pop("resource", None)


class _Guard:
    def _check(self, name):
        root = name.partition(".")[0]
        if root in _SHIM_ROOTS:
            return None
        if root in _BLOCKED_ROOTS:
            raise ImportError("module %r is not available in a plugin worker" % name)
        return None

    def find_spec(self, name, path=None, target=None):
        return self._check(name)

    def find_module(self, name, path=None):
        return self._check(name)


class _Sealed(type(sys)):
    def __setattr__(self, name, value):
        if name in _FROZEN_SYS:
            raise PermissionError("%s is sealed in a plugin worker" % name)
        super().__setattr__(name, value)


class _SealedModule(type(sys)):
    def __setattr__(self, name, value):
        if _from_plugin(3):
            raise PermissionError("%s is workspace state" % name)
        super().__setattr__(name, value)


def _from_plugin(skip=2):
    try:
        loader = sys.modules.get("extera_utils.plugin_loader")
        if loader is None:
            return False
        return bool(loader._writer_is_plugin(skip))
    except Exception:
        return False


def _is_bridge(finder):
    module = getattr(type(finder), "__module__", "") or ""
    return module.partition(".")[0] in ("chaquopy", "_chaquopy")


def _seal_sys():
    sys.meta_path.insert(0, _Guard())
    for finder in [f for f in sys.meta_path if _is_bridge(f)]:
        sys.meta_path.remove(finder)
    sys.meta_path = tuple(sys.meta_path)
    sys.__class__ = _Sealed


def _seal_modules():
    for name in _SEALED_MODULES:
        module = sys.modules.get(name)
        if module is not None and not isinstance(module, _SealedModule):
            try:
                module.__class__ = _SealedModule
            except Exception:
                pass


def _refuse_process_calls():
    def refuse(*args, **kwargs):
        raise PermissionError("the process is not available in a plugin worker")

    for name in _REFUSED:
        try:
            setattr(os, name, refuse)
        except Exception:
            pass


def _purge_sdk(keep):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for name, module in list(sys.modules.items()):
        if name in keep:
            continue
        path = getattr(module, "__file__", None)
        if not path:
            continue
        if os.path.abspath(path).startswith(root + os.sep):
            sys.modules.pop(name, None)


def _serve(op, message, state):
    from extera_utils import java_shim

    rpc = state["rpc"]
    loader = state["loader"]
    if op == "hello":
        return {"pid": os.getpid(), "python": sys.version.split()[0],
                "modules": len(sys.modules)}
    if op == "shutdown":
        state["stop"] = True
        return {"ok": True}
    if op == "callback":
        target = rpc.exported.get(message.get("target"))
        if target is None:
            raise java_shim.RemoteError("TypeError", "unknown callback")
        name = message.get("name")
        call = getattr(target, name) if name and hasattr(target, name) else target
        return java_shim.to_wire(call(*[java_shim.from_wire(a) for a in message.get("args", [])]), rpc)
    if op == "loader":
        function = getattr(loader, message["method"])
        args = [java_shim.from_wire(a) for a in message.get("args", [])]
        return java_shim.to_wire(function(*args), rpc)
    raise java_shim.RemoteError("AttributeError", "unknown workspace op %r" % op)


def main(fd, plugin_id, path):
    _close_inherited(fd)
    _limits()
    _refuse_process_calls()
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM, fileno=fd)
    from extera_utils import java_shim, workspace

    rpc = workspace.Rpc(sock)
    state = {"rpc": rpc, "loader": None, "stop": False, "plugin": plugin_id, "path": path}
    rpc.handler = lambda op, message: _serve(op, message, state)
    workspace.ACTIVE = rpc
    java_shim.RPC = rpc
    java_shim.install()
    _seal_sys()
    _purge_sdk({"extera_utils", "extera_utils.workspace", "extera_utils.java_shim",
                "extera_utils.workspace_worker"})
    import extera_utils.plugin_loader as loader

    state["loader"] = loader
    _seal_modules()
    try:
        rpc.serve_forever()
    except BaseException:
        try:
            traceback.print_exc()
        except Exception:
            pass
    finally:
        rpc.close()
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(0)
