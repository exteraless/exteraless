import os
import socket
import sys
import traceback

_KEEP = (0, 1, 2)


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

        for name, value in (("RLIMIT_CORE", 0), ("RLIMIT_CPU", 600),
                            ("RLIMIT_NOFILE", 256), ("RLIMIT_FSIZE", 1 << 30)):
            limit = getattr(resource, name, None)
            if limit is None:
                continue
            try:
                resource.setrlimit(limit, (value, value))
            except Exception:
                pass
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
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM, fileno=fd)
    from extera_utils import java_shim, workspace

    rpc = workspace.Rpc(sock)
    state = {"rpc": rpc, "loader": None, "stop": False, "plugin": plugin_id, "path": path}
    rpc.handler = lambda op, message: _serve(op, message, state)
    workspace.ACTIVE = rpc
    java_shim.RPC = rpc
    java_shim.install()
    _purge_sdk({"extera_utils", "extera_utils.workspace", "extera_utils.java_shim",
                "extera_utils.workspace_worker"})
    import extera_utils.plugin_loader as loader

    state["loader"] = loader
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
