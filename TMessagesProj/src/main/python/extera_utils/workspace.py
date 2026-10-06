import json
import os
import socket
import struct
import sys
import threading
import time
import traceback

ACTIVE = None

MAX_MESSAGE = 64 * 1024 * 1024
HEADER = 4
DEFAULT_TIMEOUT = 30.0


class RemoteError(Exception):
    def __init__(self, kind, message, where=None):
        self.kind = kind
        self.where = where
        super().__init__(message if where is None else "%s (%s)" % (message, where))


_KINDS = {
    "PermissionError": PermissionError, "ImportError": ImportError,
    "AttributeError": AttributeError, "TypeError": TypeError,
    "ValueError": ValueError, "TimeoutError": TimeoutError,
    "ConnectionError": ConnectionError, "FileNotFoundError": FileNotFoundError,
    "RuntimeError": RuntimeError, "KeyError": KeyError, "IndexError": IndexError,
}


def raise_error(error):
    message = error.get("message", "the other side failed")
    where = error.get("where")
    kind = error.get("kind")
    factory = _KINDS.get(kind)
    if factory is None:
        raise RemoteError(kind or "RemoteError", message, where)
    raise factory(message if where is None else "%s (%s)" % (message, where))


def _error_payload(error):
    return {"kind": type(error).__name__, "message": str(error),
            "where": getattr(error, "where", None)}


class Rpc(object):
    def __init__(self, sock):
        self.sock = sock
        self.next_id = 1
        self.next_export = 1
        self.exported = {}
        self.handler = None
        self.lock = threading.RLock()
        self.closed = False
        self._buffer = b""

    def export(self, value):
        with self.lock:
            ident = self.next_export
            self.next_export += 1
            self.exported[ident] = value
            return ident

    def _send(self, message):
        data = json.dumps(message, ensure_ascii=False).encode("utf-8")
        with self.lock:
            if self.closed:
                raise ConnectionError("the plugin worker is gone")
            self.sock.sendall(struct.pack(">I", len(data)) + data)

    def _read(self, count):
        while len(self._buffer) < count:
            chunk = self.sock.recv(65536)
            if not chunk:
                return None
            self._buffer += chunk
        out, self._buffer = self._buffer[:count], self._buffer[count:]
        return out

    def _recv(self):
        header = self._read(HEADER)
        if header is None:
            return None
        size = struct.unpack(">I", header)[0]
        if size > MAX_MESSAGE:
            raise ConnectionError("the plugin worker sent an oversized message")
        body = self._read(size)
        if body is None:
            return None
        return json.loads(body.decode("utf-8"))

    def call(self, op, timeout=DEFAULT_TIMEOUT, **payload):
        payload["op"] = op
        with self.lock:
            if self.closed:
                raise ConnectionError("the plugin worker is gone")
            request_id = self.next_id
            self.next_id += 1
            payload["id"] = request_id
            payload["kind"] = "call"
            self._send(payload)
            deadline = None if timeout is None else time.monotonic() + timeout
            while True:
                if deadline is not None:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise TimeoutError("the plugin worker did not answer in %.0fs" % timeout)
                    self.sock.settimeout(remaining)
                message = self._recv()
                if message is None:
                    raise ConnectionError("the plugin worker is gone")
                if message.get("kind") == "reply" and message.get("id") == request_id:
                    error = message.get("error")
                    if error:
                        raise_error(error)
                    return message.get("value")
                if message.get("kind") == "call":
                    if timeout is not None:
                        deadline = time.monotonic() + timeout
                    self._serve(message)

    def _serve(self, message):
        request_id = message.get("id")
        try:
            value = self.handler(message["op"], message) if self.handler else None
            reply = {"kind": "reply", "id": request_id, "value": value}
        except BaseException as error:
            if isinstance(error, (KeyboardInterrupt, SystemExit)):
                raise
            reply = {"kind": "reply", "id": request_id, "error": _error_payload(error)}
        try:
            self._send(reply)
        except Exception:
            self.closed = True

    def serve_forever(self):
        while True:
            message = self._recv()
            if message is None:
                return
            if message.get("kind") == "call":
                self._serve(message)

    def close(self):
        with self.lock:
            self.closed = True
            try:
                self.sock.close()
            except Exception:
                pass


class Worker(object):
    def __init__(self, plugin_id, path):
        self.plugin_id = plugin_id
        self.path = path
        self.pid = None
        self.rpc = None
        self.hello = None
        self.status = "new"
        self.started = 0.0

    def start(self):
        parent, child = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
        import logging

        lock = logging._lock
        if lock is not None:
            lock.acquire()
        pid = os.fork()
        if pid == 0:
            logging._lock = None
            code = 0
            try:
                parent.close()
                from extera_utils import workspace_worker

                workspace_worker.main(child.detach(), self.plugin_id, self.path)
            except BaseException:
                code = 3
                try:
                    with open("/proc/self/fd/2", "w") as stream:
                        traceback.print_exc(file=stream)
                except Exception:
                    pass
            finally:
                os._exit(code)
        if lock is not None:
            lock.release()
        child.close()
        self.pid = pid
        self.rpc = Rpc(parent)
        self.rpc.handler = self._serve
        self.started = time.time()
        try:
            self.hello = self.rpc.call("hello", timeout=20)
            self.status = "running"
        except Exception:
            self.status = "dead"
            self.kill()
            raise
        return self

    def call(self, op, timeout=DEFAULT_TIMEOUT, **payload):
        try:
            return self.rpc.call(op, timeout=timeout, **payload)
        except (ConnectionError, TimeoutError):
            self.kill()
            self.status = "dead"
            raise

    def alive(self):
        if self.pid is None:
            return False
        try:
            done, _ = os.waitpid(self.pid, os.WNOHANG)
        except ChildProcessError:
            return False
        return done == 0

    def stop(self, timeout=3.0):
        if self.pid is None:
            return
        try:
            if self.alive():
                self.rpc.call("shutdown", timeout=timeout)
        except Exception:
            pass
        deadline = time.time() + timeout
        while time.time() < deadline and self.alive():
            time.sleep(0.05)
        self.kill()

    def kill(self):
        if self.pid is not None and self.alive():
            try:
                os.kill(self.pid, 9)
            except Exception:
                pass
            try:
                os.waitpid(self.pid, 0)
            except Exception:
                pass
        if self.rpc is not None:
            self.rpc.close()
        self.status = "stopped"

    def _serve(self, op, message):
        return serve_host(self.plugin_id, op, message)


_WORKERS = {}
_LOCK = threading.RLock()


def get(plugin_id):
    return _WORKERS.get(plugin_id)


def start(plugin_id, path):
    with _LOCK:
        worker = _WORKERS.get(plugin_id)
        if worker is not None and worker.alive():
            return worker
        if worker is not None:
            worker.stop()
        worker = Worker(plugin_id, path).start()
        _WORKERS[plugin_id] = worker
        return worker


def stop(plugin_id):
    with _LOCK:
        worker = _WORKERS.pop(plugin_id, None)
    if worker is not None:
        worker.stop()


def stop_all():
    for plugin_id in list(_WORKERS):
        stop(plugin_id)


def call(plugin_id, op, timeout=DEFAULT_TIMEOUT, **payload):
    worker = _WORKERS.get(plugin_id)
    if worker is None:
        raise ConnectionError("plugin %r has no worker" % plugin_id)
    return worker.call(op, timeout=timeout, **payload)


def status():
    out = []
    for plugin_id, worker in sorted(_WORKERS.items()):
        out.append({"plugin": plugin_id, "pid": worker.pid, "status": worker.status,
                    "alive": worker.alive(), "uptime": round(time.time() - worker.started, 1),
                    "hello": worker.hello})
    return out


class Handles(object):
    def __init__(self):
        self.by_id = {}
        self.by_object = {}
        self.next_id = 1

    def add(self, value):
        key = id(value)
        known = self.by_object.get(key)
        if known is not None:
            return known
        handle = self.next_id
        self.next_id += 1
        self.by_id[handle] = value
        self.by_object[key] = handle
        return handle

    def get(self, handle):
        return self.by_id.get(handle)

    def release(self, handle):
        value = self.by_id.pop(handle, None)
        if value is not None:
            self.by_object.pop(id(value), None)


_HANDLES = {}


def call_loader(plugin_id, method, args=None, timeout=None):
    values = list(args) if args is not None else []
    return call(plugin_id, "loader", method=method,
                args=[to_wire(value, plugin_id) for value in values],
                timeout=DEFAULT_TIMEOUT if timeout is None else timeout)


def loader_text(plugin_id, method, args=None) -> str:
    try:
        value = call_loader(plugin_id, method, args)
        return json.dumps({"ok": True, "value": value}, ensure_ascii=False)
    except Exception as error:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(error).__name__, error)},
                          ensure_ascii=False)


def loader_object(plugin_id, method, args=None):
    return from_wire(call_loader(plugin_id, method, args), plugin_id)


def resolve(plugin_id, wire_json):
    return from_wire(json.loads(wire_json), plugin_id)


def handles_for(plugin_id):
    return _HANDLES.setdefault(plugin_id, Handles())


def _class_name(value):
    try:
        return str(value.getClass().getName())
    except Exception:
        return None


_ENGINE_PREF_PREFIXES = ("plugin_", "plugins", "watchdog_", "native_hooks_")
_PREFS_WRITES = ("putString", "putStringSet", "putInt", "putLong", "putFloat",
                 "putBoolean", "remove", "clear")


def check_prefs_write(plugin_id, cls, name, args):
    from extera_utils import plugin_loader

    if not isinstance(cls, str) or "SharedPreferences" not in cls:
        return
    if name not in _PREFS_WRITES or plugin_loader.unsafe_mode():
        return
    key = None if name == "clear" else ((args or [None])[0])
    if key is not None and not (isinstance(key, str)
                                and key.startswith(_ENGINE_PREF_PREFIXES)):
        return
    plugin_loader.log_denial(plugin_id, "workspace_prefs", key or name)
    raise PermissionError("plugin %r may not change engine settings" % plugin_id)


def check_class(plugin_id, name, what):
    from extera_utils import plugin_loader

    if not name:
        return
    if plugin_loader.unsafe_mode():
        return
    if name in plugin_loader._JAVA_CLASS_DENIED:
        plugin_loader.log_denial(plugin_id, "workspace_" + what, name)
        raise PermissionError("class %r is never available to plugins" % name)
    permission = plugin_loader.java_class_permission(name)
    if permission is None:
        return
    try:
        plugin_loader.require_permission(permission, "workspace_" + what, name, plugin_id)
    except PermissionError:
        plugin_loader.log_denial(plugin_id, "workspace_" + what, name)
        raise


def to_wire(value, plugin_id):
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, (bytes, bytearray, memoryview)):
        import base64

        return {"b": base64.b64encode(bytes(value)).decode("ascii")}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [to_wire(item, plugin_id) for item in value]
    if isinstance(value, dict):
        return {str(key): to_wire(item, plugin_id) for key, item in value.items()}
    try:
        return {"h": handles_for(plugin_id).add(value)}
    except TypeError:
        return {"s": str(value)}


def from_wire(value, plugin_id):
    if isinstance(value, list):
        return [from_wire(item, plugin_id) for item in value]
    if isinstance(value, dict):
        if "h" in value:
            return handles_for(plugin_id).get(value["h"])
        if "b" in value:
            import base64

            return base64.b64decode(value["b"])
        if "c" in value:
            return _class_object(value["c"])
        if "s" in value:
            return value["s"]
        if "f" in value:
            return RemoteCallable(plugin_id, value["f"])
        if "e" in value:
            raise RemoteError(value["e"].get("kind", "JavaException"),
                              value["e"].get("message", "java call failed"),
                              value["e"].get("where"))
        return {key: from_wire(item, plugin_id) for key, item in value.items()}
    return value


class RemoteCallable(object):
    def __init__(self, plugin_id, ident):
        object.__setattr__(self, "_plugin", plugin_id)
        object.__setattr__(self, "_ident", ident)

    def __call__(self, *args):
        return self._invoke(None, args)

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return _RemoteMethod(self, name)

    def _invoke(self, name, args):
        worker = _WORKERS.get(self._plugin)
        if worker is None:
            raise RemoteError("ConnectionError", "no worker")
        return from_wire(worker.call("callback", timeout=None, target=self._ident, name=name,
                                     args=[to_wire(item, self._plugin) for item in args]),
                         self._plugin)

    def __repr__(self):
        return "<java callback %d>" % self._ident


class _RemoteMethod(object):
    def __init__(self, owner, name):
        self.owner = owner
        self.name = name

    def __call__(self, *args):
        return self.owner._invoke(self.name, args)


def _class_object(name):
    from java import jclass

    return jclass(name)


def _member(target, name):
    if isinstance(target, str):
        from java import jclass

        return getattr(jclass(target), name)
    return getattr(target, name)


def _arguments(values, plugin_id):
    if values is None:
        return []
    return [from_wire(value, plugin_id) for value in values]


def serve_host(plugin_id, op, message):
    from extera_utils import plugin_loader

    with plugin_loader.java_runtime_mark(plugin_id):
        return _serve_host(plugin_id, op, message)


def _serve_host(plugin_id, op, message):
    if message.get("name") in ("forName", "loadClass"):
        for value in message.get("args") or ():
            if isinstance(value, str):
                check_class(plugin_id, value, "forName")
    if op == "class":
        name = message.get("name")
        check_class(plugin_id, name, "class")
        return {"c": name}
    if op in ("new", "static"):
        cls = message.get("cls")
        check_class(plugin_id, cls, op)
        member = _member(cls, message["name"]) if op == "static" else _class_object(cls)
        result = member(*_arguments(message.get("args"), plugin_id))
        return to_wire(result, plugin_id)
    if op in ("getstatic", "setstatic"):
        cls = message.get("cls")
        check_class(plugin_id, cls, op)
        if op == "getstatic":
            return to_wire(getattr(_class_object(cls), message["name"]), plugin_id)
        setattr(_class_object(cls), message["name"], from_wire(message.get("value"), plugin_id))
        return None
    if op == "invoke":
        target = message.get("target")
        name = message["name"]
        if isinstance(target, str):
            check_class(plugin_id, target, "invoke")
            member = _member(target, name)
        else:
            value = _resolve_target(plugin_id, target)
            check_class(plugin_id, _class_name(value), "invoke")
            check_prefs_write(plugin_id, _class_name(value), name,
                              message.get("args"))
            member = getattr(value, name)
        return to_wire(member(*_arguments(message.get("args"), plugin_id)), plugin_id)
    if op in ("member", "setmember"):
        target = message.get("target")
        name = message["name"]
        if isinstance(target, str):
            check_class(plugin_id, target, op)
            owner = _member(target, name) if op == "member" else None
            if op == "member":
                return to_wire(owner, plugin_id)
            setattr(_class_object(target), name, from_wire(message.get("value"), plugin_id))
            return None
        value = _resolve_target(plugin_id, target)
        check_class(plugin_id, _class_name(value), op)
        if op == "member":
            return to_wire(getattr(value, name), plugin_id)
        setattr(value, name, from_wire(message.get("value"), plugin_id))
        return None
    if op == "classof":
        target = _resolve_target(plugin_id, message.get("target"))
        name = _class_name(target)
        return {"h": handles_for(plugin_id).add(_class_object(name)), "c": "java.lang.Class"}
    if op == "instanceof":
        target = _resolve_target(plugin_id, message.get("target"))
        return bool(_class_object(message["cls"]).isInstance(target))
    if op == "subclass":
        return bool(_class_object(message["parent"]).isAssignableFrom(_class_object(message["cls"])))
    if op == "cast":
        target = _resolve_target(plugin_id, message.get("target"))
        return to_wire(_class_object(message["cls"]).cast(target), plugin_id)
    if op == "len":
        target = _resolve_target(plugin_id, message.get("target"))
        return int(target.length) if hasattr(target, "length") else len(target)
    if op in ("getitem", "setitem"):
        target = _resolve_target(plugin_id, message.get("target"))
        index = from_wire(message.get("index"), plugin_id)
        if op == "getitem":
            return to_wire(target[int(index)], plugin_id)
        target[int(index)] = from_wire(message.get("value"), plugin_id)
        return None
    if op == "list":
        target = _resolve_target(plugin_id, message.get("target"))
        return [to_wire(item, plugin_id) for item in _as_list(target)]
    if op == "str":
        return str(_resolve_target(plugin_id, message.get("target")))
    if op == "truth":
        return bool(_resolve_target(plugin_id, message.get("target")))
    if op == "equals":
        return bool(_resolve_target(plugin_id, message.get("target"))
                    .equals(from_wire(message.get("value"), plugin_id)))
    if op == "release":
        handles_for(plugin_id).release(message.get("target"))
        return None
    if op == "log":
        plugin_loader._log("plugin %s: %s" % (plugin_id, message.get("text", "")))
        return None
    if op == "journal":
        from extera_utils import audit_gate

        audit_gate._record(plugin_id, "workspace:" + str(message.get("event", "note")),
                           message.get("text"), False)
        return None
    raise RemoteError("AttributeError", "unknown workspace op %r" % op)


def _resolve_target(plugin_id, target):
    if isinstance(target, str):
        return _class_object(target)
    value = handles_for(plugin_id).get(target)
    if value is None:
        raise RemoteError("ReferenceError", "handle %r is gone" % target)
    return value


def _as_list(target):
    if hasattr(target, "length"):
        return [target[index] for index in range(int(target.length))]
    if hasattr(target, "toArray"):
        return _as_list(target.toArray())
    if hasattr(target, "size") and hasattr(target, "get"):
        return [target.get(index) for index in range(int(target.size()))]
    if hasattr(target, "iterator"):
        out = []
        iterator = target.iterator()
        while bool(iterator.hasNext()) and len(out) < 10000:
            out.append(iterator.next())
        return out
    if isinstance(target, dict):
        return list(target.values())
    return list(target)
