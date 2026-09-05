"""Public plugin API (BasePlugin, hook/menu contracts) — part of the exteraless plugin SDK.

This module is import-safe without Chaquopy: the Java bridge is resolved at
import time inside try/except, and every bridge call degrades gracefully when
running on a host interpreter.
"""

import json
import sys
from dataclasses import dataclass
from typing import Any, Callable, Dict, Optional

try:
    from app.exteraless.plugins import PluginServices, PythonBridge
except Exception:  # host interpreter (no Chaquopy) — see fallback paths below
    PluginServices = None
    PythonBridge = None


# Hook contracts

class HookStrategy:
    """Strategy strings returned by event hooks. Java compares them literally."""
    DEFAULT = "DEFAULT"
    CANCEL = "CANCEL"
    MODIFY = "MODIFY"
    MODIFY_FINAL = "MODIFY_FINAL"
    NONE = DEFAULT


class HookResult:
    __slots__ = ("strategy", "request", "response", "update", "updates", "params", "result")

    def __init__(self, strategy: str = HookStrategy.DEFAULT, request=None, response=None,
                 update=None, updates=None, params=None, result=None):
        self.strategy = strategy
        self.request = request
        self.response = response
        self.update = update
        self.updates = updates
        self.params = params
        self.result = result

    def __repr__(self):
        return f"HookResult(strategy={self.strategy!r})"


class AppEvent:
    """Application lifecycle events delivered to on_app_event()."""
    START = "START"
    STOP = "STOP"
    PAUSE = "PAUSE"
    RESUME = "RESUME"

    @staticmethod
    def from_java(event: str) -> Optional[str]:
        """Map a Java-side event name ("app_start", ...) to an AppEvent constant."""
        return _APP_EVENT_FROM_JAVA.get(event)


_APP_EVENT_FROM_JAVA = {
    "app_start": AppEvent.START,
    "app_stop": AppEvent.STOP,
    "app_pause": AppEvent.PAUSE,
    "app_resume": AppEvent.RESUME,
}


# Menus

class MenuItemType:
    """Menu placements; values are exact Java enum names (MenuItemRecord.MenuType)."""
    MESSAGE_CONTEXT_MENU = "MESSAGE_CONTEXT_MENU"
    DRAWER_MENU = "DRAWER_MENU"
    MAIN_MENU = "MAIN_MENU"
    CHAT_ACTION_MENU = "CHAT_ACTION_MENU"
    PROFILE_ACTION_MENU = "PROFILE_ACTION_MENU"
    CHAT_CONTEXT = MESSAGE_CONTEXT_MENU


@dataclass
class MenuItemData:
    """Declaration of a menu item added by a plugin."""
    menu_type: str
    text: str
    on_click: Callable[[Dict[str, Any]], None]
    item_id: Optional[str] = None
    icon: Optional[str] = None
    subtext: Optional[str] = None
    condition: Optional[str] = None  # MVEL visibility expression (stored as-is)
    priority: int = 0


# Xposed-style method hooking

class BaseHook:
    """Base class for Xposed-style hook handlers.

    The Java bridge checks for the attributes below on the handler object
    (``before_hooked_method(param)`` / ``after_hooked_method(param)`` /
    ``replace_hooked_method(param)``); ``param`` is the Xposed
    MethodHookParam Java object (fields ``thisObject``, ``args``, ``method``;
    methods ``getResult()`` / ``setResult(v)`` / ``getThrowable()``).
    """


class MethodHook(BaseHook):
    """Override before/after handlers around an original Java method."""

    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        pass


class MethodReplacement(BaseHook):
    """Override to fully replace a Java method."""

    def replace_hooked_method(self, param):
        return None


# ``XposedHook`` is the name several published plugins subclass; in the
# reference SDK it is the same thing as MethodHook (before/after around the
# original), so it is an alias rather than a second class — subclasses that
# call ``super().__init__()`` keep working.
XposedHook = MethodHook


class _PluginsControllerProxy:
    """``base_plugin.PluginsController`` — handle on the Java plugins controller.

    Published plugins import the controller either from here or straight from
    the reference Java package. This fork's controller lives in
    ``app.exteraless.plugins``, so attribute access is forwarded to it and the
    Java package rename stays invisible to the plugin.
    """

    _java = None

    def _resolve(self):
        if _PluginsControllerProxy._java is None:
            from java import jclass

            _PluginsControllerProxy._java = jclass(
                "app.exteraless.plugins.PluginsController")
        return _PluginsControllerProxy._java

    def getInstance(self):
        return self._resolve().getInstance()

    def __getattr__(self, name):
        return getattr(self._resolve(), name)


PluginsController = _PluginsControllerProxy()


class PluginsConstants:
    """Mirror of the Java PluginsConstants values plugins reference by name.

    Only the string constants are exposed: the Java class itself lives in a
    different package in this fork, so plugins that imported it from Java get
    the values from here instead.
    """

    PYTHON = "python"

    class MenuItemTypes:
        MESSAGE_CONTEXT_MENU = MenuItemType.MESSAGE_CONTEXT_MENU
        DRAWER_MENU = MenuItemType.DRAWER_MENU
        MAIN_MENU = MenuItemType.MAIN_MENU
        CHAT_ACTION_MENU = MenuItemType.CHAT_ACTION_MENU
        PROFILE_ACTION_MENU = MenuItemType.PROFILE_ACTION_MENU

    class Strategy:
        MODIFY = HookStrategy.MODIFY
        CANCEL = HookStrategy.CANCEL
        DEFAULT = HookStrategy.DEFAULT
        MODIFY_FINAL = HookStrategy.MODIFY_FINAL

    class Xposed:
        REPLACE_HOOKED_METHOD = "replace_hooked_method"
        BEFORE_HOOKED_METHOD = "before_hooked_method"
        AFTER_HOOKED_METHOD = "after_hooked_method"
        HOOK_FILTERS = "__hook_filters__"


class _FunctionalHook:
    """Handler wrapper for the functional style: exposes only the attrs
    for which actual callables were supplied (Java probes attribute presence)."""


class UnhookId(str):
    """Unhook id that also answers ``.unhook()``, the way Xposed handles do."""

    __slots__ = ("_owner",)

    def __new__(cls, value, owner=None):
        obj = super().__new__(cls, value)
        obj._owner = owner
        return obj

    def unhook(self):
        owner = self._owner
        if owner is not None:
            owner.unhook_method(str(self))


class UnhookIdList(list):
    """List of unhook ids with a bulk ``.unhook()``."""

    def __init__(self, ids=(), owner=None):
        super().__init__(ids)
        self._owner = owner

    def unhook(self):
        owner = self._owner
        if owner is not None:
            owner.unhook_method(list(self))


class HookParam:
    """Thin proxy over Xposed's MethodHookParam.

    Everything is forwarded to the Java object; only ``result`` and
    ``throwable`` are special-cased, so both the Xposed accessor style
    (``param.getResult()`` / ``param.setResult(v)``) and the attribute style
    (``param.result``, ``param.result = v``) work.
    """

    __slots__ = ("_param",)

    def __init__(self, param):
        object.__setattr__(self, "_param", param)

    @property
    def java(self):
        """The underlying MethodHookParam, for calls into Java."""
        return object.__getattribute__(self, "_param")

    def __getattr__(self, name):
        param = object.__getattribute__(self, "_param")
        if name == "result":
            return param.getResult()
        if name == "throwable":
            return param.getThrowable()
        return getattr(param, name)

    def __setattr__(self, name, value):
        param = object.__getattribute__(self, "_param")
        if name == "result":
            param.setResult(value)
        elif name == "throwable":
            param.setThrowable(value)
        else:
            setattr(param, name, value)

    def __repr__(self):
        return f"HookParam({object.__getattribute__(self, '_param')!r})"


def dispatch_hook(handler, attr, param):
    """Entry point for the Java hook bridge: calls *handler.attr(param)*."""
    return getattr(handler, attr)(HookParam(param))


def _filter_value(value):
    """Make a filter value JSON-safe (primitives pass through, rest str()'d)."""
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)


def _filter_class_name(cls):
    """Accept a class name, a jclass wrapper or a java.lang.Class; return a name."""
    if isinstance(cls, str):
        return cls
    try:
        return str(cls.getName())
    except Exception:
        return str(cls)


class HookFilter:
    """Declarative Java-side hook filter descriptor (PLUGINS-API.md §6).

    Static constructors mirror the documented nested-class names
    (``HookFilter.ArgumentIsNull(0)``) as well as snake_case aliases
    (``HookFilter.argument_is_null(0)``). Each descriptor serializes to
    ``{"type": <kind>, ...}`` inside the filters JSON handed to Java.
    """

    __slots__ = ("kind", "data")

    def __init__(self, kind: str, **data):
        self.kind = kind
        self.data = data

    def to_dict(self) -> Dict[str, Any]:
        out = {"type": self.kind}
        out.update(self.data)
        return out

    def __eq__(self, other):
        return isinstance(other, HookFilter) and self.to_dict() == other.to_dict()

    def __hash__(self):
        return hash((self.kind, repr(sorted(self.data.items()))))

    def __repr__(self):
        args = ", ".join(f"{k}={v!r}" for k, v in self.data.items())
        return f"HookFilter({self.kind}{', ' if args else ''}{args})"

    # ---- argument filters ----

    @staticmethod
    def argument_is_null(index: int) -> "HookFilter":
        return HookFilter("argument_is_null", index=int(index))

    @staticmethod
    def argument_not_null(index: int) -> "HookFilter":
        return HookFilter("argument_not_null", index=int(index))

    @staticmethod
    def argument_is_true(index: int) -> "HookFilter":
        return HookFilter("argument_is_true", index=int(index))

    @staticmethod
    def argument_is_false(index: int) -> "HookFilter":
        return HookFilter("argument_is_false", index=int(index))

    @staticmethod
    def argument_equal(index: int, value) -> "HookFilter":
        return HookFilter("argument_equal", index=int(index), value=_filter_value(value))

    @staticmethod
    def argument_not_equal(index: int, value) -> "HookFilter":
        return HookFilter("argument_not_equal", index=int(index), value=_filter_value(value))

    @staticmethod
    def argument_is_instance_of(index: int, cls) -> "HookFilter":
        return HookFilter("argument_is_instance_of", index=int(index),
                          **{"class": _filter_class_name(cls)})

    # ---- result filters ----

    @staticmethod
    def result_is_null() -> "HookFilter":
        return HookFilter("result_is_null")

    @staticmethod
    def result_not_null() -> "HookFilter":
        return HookFilter("result_not_null")

    @staticmethod
    def result_is_true() -> "HookFilter":
        return HookFilter("result_is_true")

    @staticmethod
    def result_is_false() -> "HookFilter":
        return HookFilter("result_is_false")

    @staticmethod
    def result_equal(value) -> "HookFilter":
        return HookFilter("result_equal", value=_filter_value(value))

    @staticmethod
    def result_not_equal(value) -> "HookFilter":
        return HookFilter("result_not_equal", value=_filter_value(value))

    @staticmethod
    def result_is_instance_of(cls) -> "HookFilter":
        return HookFilter("result_is_instance_of", **{"class": _filter_class_name(cls)})

    # ---- MVEL condition / combinators ----

    @staticmethod
    def condition(expression: str, object=None) -> "HookFilter":
        data: Dict[str, Any] = {"expression": str(expression)}
        if object is not None:
            data["object"] = _filter_value(object)
        return HookFilter("condition", **data)

    @staticmethod
    def or_(*filters: "HookFilter") -> "HookFilter":
        return HookFilter("or", filters=[
            f.to_dict() if hasattr(f, "to_dict") else f for f in filters
        ])


# Documented CamelCase aliases (PLUGINS-API.md §6) and parameterless constants.
HookFilter.ArgumentIsNull = HookFilter.argument_is_null
HookFilter.ArgumentNotNull = HookFilter.argument_not_null
HookFilter.ArgumentIsTrue = HookFilter.argument_is_true
HookFilter.ArgumentIsFalse = HookFilter.argument_is_false
HookFilter.ArgumentEqual = HookFilter.argument_equal
HookFilter.ArgumentNotEqual = HookFilter.argument_not_equal
HookFilter.ArgumentIsInstanceOf = HookFilter.argument_is_instance_of
HookFilter.ResultIsNull = HookFilter.result_is_null
HookFilter.ResultNotNull = HookFilter.result_not_null
HookFilter.ResultIsTrue = HookFilter.result_is_true
HookFilter.ResultIsFalse = HookFilter.result_is_false
HookFilter.ResultEqual = HookFilter.result_equal
HookFilter.ResultNotEqual = HookFilter.result_not_equal
HookFilter.ResultIsInstanceOf = HookFilter.result_is_instance_of
HookFilter.Condition = HookFilter.condition
HookFilter.Or = HookFilter.or_

HookFilter.RESULT_IS_NULL = HookFilter.result_is_null()
HookFilter.RESULT_NOT_NULL = HookFilter.result_not_null()
HookFilter.RESULT_IS_TRUE = HookFilter.result_is_true()
HookFilter.RESULT_IS_FALSE = HookFilter.result_is_false()


def hook_filters(*filters, before=None, after=None):
    """Decorator attaching HookFilter descriptors to a hook handler method.

    Positional filters (and the ``before=[...]`` list) apply before the hooked
    method runs; ``after=[...]`` filters gate the after-callback. When the
    plugin hooks the method, these are merged into the filters JSON.
    """
    def decorator(fn):
        fn.__hook_filters__ = {
            "before": [*(before or ()), *filters],
            "after": list(after or ()),
        }
        return fn
    return decorator


def _handler_declared_filters(handler) -> Dict[str, list]:
    """Collect @hook_filters metadata from a handler's hook methods."""
    declared = {"before": [], "after": []}
    for attr, bucket in (("before_hooked_method", "before"),
                         ("replace_hooked_method", "before"),
                         ("after_hooked_method", "after")):
        spec = getattr(getattr(handler, attr, None), "__hook_filters__", None)
        if spec:
            declared["before"].extend(spec.get("before") or ())
            declared["after"].extend(spec.get("after") or ())
    return declared


def _serialize_hook_filters(before, after) -> str:
    def dump(items):
        return [f.to_dict() if hasattr(f, "to_dict") else f for f in (items or ())]
    return json.dumps({"before": dump(before), "after": dump(after)},
                      ensure_ascii=False)


# BasePlugin

class BasePlugin:
    """Base class every plugin must subclass exactly once per module."""

    _plugin_id: Optional[str] = None
    MenuType = MenuItemType
    _registered_send_message = False

    #: Идентификатор плагина. Плагины читают именно `self.id` — так называется
    #: это поле у exteraGram, и без него у них падает всё, что обращается к
    #: контроллеру по id (обновление экрана настроек, поиск себя в реестре).
    id: Optional[str] = None

    # ---- lifecycle (override in the subclass) ----

    def on_plugin_load(self):
        """Called when the plugin is enabled or restored at app start."""

    def on_plugin_unload(self):
        """Called when the plugin is disabled or the app stops."""

    def on_app_event(self, event_type: str):
        """Called with an AppEvent constant on app lifecycle events."""

    def create_settings(self) -> Optional[list]:
        """Return a list of ui.settings items, or None for no settings UI."""
        return None

    # ---- event hook callbacks (override AND register to activate) ----

    def pre_request_hook(self, request_name, account, request):
        return None

    def post_request_hook(self, request_name, account, response, error):
        return None

    def on_send_message_hook(self, account, params):
        return None

    def on_update_hook(self, update_name, account, update):
        return None

    def on_updates_hook(self, container_name, account, updates):
        return None

    # ---- internal state (managed by the loader via _attach) ----

    def _state(self, name: str, factory):
        value = self.__dict__.get(name)
        if not isinstance(value, factory):
            value = factory()
            self.__dict__[name] = value
        return value

    def _attach(self, plugin_id: str):
        """Bind this instance to a plugin id. Called by extera_utils.plugin_loader."""
        self._plugin_id = plugin_id
        self.id = plugin_id
        self._state("_registered_request_hooks", list)
        self._state("_menu_callbacks", dict)
        if "_registered_send_message" not in self.__dict__:
            self.__dict__["_registered_send_message"] = False

    @property
    def plugin_id(self) -> Optional[str]:
        return self._plugin_id

    def _bridge_available(self) -> bool:
        return PythonBridge is not None and bool(self._plugin_id)

    # ---- logging ----

    def log(self, msg):
        """Write a message to the app's plugin log pipeline."""
        text = str(msg)
        if self._bridge_available():
            try:
                PythonBridge.log(self._plugin_id, text)
                return
            except Exception:
                pass  # fall through to stderr
        print(f"[plugin:{self._plugin_id or '?'}] {text}", file=sys.stderr)

    # ---- settings storage ----

    def get_setting(self, key: str, default=None):
        if not self._bridge_available():
            return default
        try:
            raw = PythonBridge.getSetting(self._plugin_id, key)
        except Exception as e:
            self.log(f"get_setting({key!r}) failed: {e}")
            return default
        if raw is None:
            return default
        try:
            return json.loads(raw)
        except (ValueError, TypeError):
            return default

    def set_setting(self, key: str, value, reload_settings: bool = False):
        if not self._bridge_available():
            return
        try:
            PythonBridge.setSetting(self._plugin_id, key,
                                    json.dumps(value, ensure_ascii=False),
                                    bool(reload_settings))
        except Exception as e:
            self.log(f"set_setting({key!r}) failed: {e}")

    def export_settings(self) -> dict:
        if not self._bridge_available():
            return {}
        try:
            raw = PythonBridge.exportSettings(self._plugin_id)
            data = json.loads(raw) if raw else {}
            return data if isinstance(data, dict) else {}
        except Exception as e:
            self.log(f"export_settings() failed: {e}")
            return {}

    def import_settings(self, settings: dict, reload_settings: bool = True):
        if not self._bridge_available():
            return
        try:
            PythonBridge.importSettings(self._plugin_id,
                                        json.dumps(dict(settings), ensure_ascii=False),
                                        bool(reload_settings))
        except Exception as e:
            self.log(f"import_settings() failed: {e}")

    # ---- hook registration ----

    def add_hook(self, name: str, match_substring: bool = False, priority: int = 0):
        """Register pre/post request hooks for a TL request name."""
        entry = (str(name), bool(match_substring), int(priority))
        hooks = self._state("_registered_request_hooks", list)
        if entry not in hooks:
            hooks.append(entry)
        if not self._bridge_available():
            return
        try:
            PythonBridge.addRequestHook(self._plugin_id, entry[0], entry[1], entry[2])
        except Exception as e:
            self.log(f"add_hook({entry[0]!r}) failed: {e}")

    def remove_hook(self, name: str):
        """Снять хук, поставленный add_hook()/add_on_send_message_hook()."""
        key = str(name)
        if key == "on_send_message_hook":
            self.__dict__["_registered_send_message"] = False
            if not self._bridge_available():
                return
            try:
                PythonBridge.removeSendMessageHook(self._plugin_id)
            except Exception as e:
                self.log(f"remove_hook({key!r}) failed: {e}")
            return
        hooks = self._state("_registered_request_hooks", list)
        hooks[:] = [entry for entry in hooks if entry[0] != key]
        if not self._bridge_available():
            return
        try:
            PythonBridge.removeRequestHook(self._plugin_id, key)
        except Exception as e:
            self.log(f"remove_hook({key!r}) failed: {e}")

    def add_on_send_message_hook(self, priority: int = 0):
        """Register the outgoing-message hook (on_send_message_hook)."""
        self.__dict__["_registered_send_message"] = True
        if not self._bridge_available():
            return
        try:
            PythonBridge.addSendMessageHook(self._plugin_id, int(priority))
        except Exception as e:
            self.log(f"add_on_send_message_hook() failed: {e}")

    # ---- menus ----

    def add_menu_item(self, menu_item_data: MenuItemData, text=None, *, on_click=None,
                      condition=None, priority=0, icon=None, subtext=None, item_id=None) -> Optional[str]:
        """Register a menu item; returns its item_id."""
        if isinstance(menu_item_data, str) and text is not None:
            menu_item_data = MenuItemData(menu_item_data, text, on_click, item_id, icon, subtext, condition, priority)
        elif isinstance(menu_item_data, MenuItemData) and (text is not None or on_click is not None
                or condition is not None or priority != 0 or icon is not None or subtext is not None or item_id is not None):
            raise TypeError("Use either MenuItemData or individual menu fields")
        if not isinstance(menu_item_data, MenuItemData):
            raise TypeError("add_menu_item() expects a MenuItemData instance")
        payload = {
            "menu_type": str(menu_item_data.menu_type),
            "text": str(menu_item_data.text),
            "priority": int(menu_item_data.priority),
        }
        for key in ("item_id", "icon", "subtext", "condition"):
            value = getattr(menu_item_data, key)
            if value is not None:
                payload[key] = value
        if self._bridge_available():
            try:
                # 3-arg signature: Java stores the PyObject and calls it
                # directly with a java.util.Map context on menu clicks.
                item_id = PythonBridge.addMenuItem(self._plugin_id,
                                                   json.dumps(payload, ensure_ascii=False),
                                                   menu_item_data.on_click)
            except Exception as e:
                self.log(f"add_menu_item({menu_item_data.text!r}) failed: {e}")
                return None
        else:
            # Host fallback: deterministic local id so callbacks remain testable.
            item_id = menu_item_data.item_id or f"local_{abs(hash((menu_item_data.menu_type, menu_item_data.text))) & 0xFFFFFFFF:08x}"
        if menu_item_data.on_click is not None and item_id:
            self._state("_menu_callbacks", dict)[item_id] = menu_item_data.on_click
        return item_id

    def remove_menu_item(self, item_id: str):
        self._state("_menu_callbacks", dict).pop(item_id, None)
        if not self._bridge_available():
            return
        try:
            PythonBridge.removeMenuItem(self._plugin_id, item_id)
        except Exception as e:
            self.log(f"remove_menu_item({item_id!r}) failed: {e}")

    def _dispatch_menu_click(self, item_id: str, context):
        """Called by the loader when the user taps a plugin menu item."""
        callback = self._state("_menu_callbacks", dict).get(item_id)
        if callback is None:
            self.log(f"no menu callback registered for item {item_id!r}")
            return
        callback(context)

    # ---- multi-account client ----

    @property
    def client(self):
        """AccountClient following the current hook scope.

        ``self.client`` inside a hook callback operates on the hook's account
        (falling back to the UI-selected account outside hooks);
        ``self.client(account)`` returns an AccountClient bound to *account*.
        """
        import client_utils
        return client_utils.AccountClient(None)

    # ---- Xposed-style method hooking ----

    def _build_method_handler(self, handler, before=None, after=None):
        """Normalize the handler argument to a Java hook-protocol object."""
        if isinstance(handler, (MethodHook, MethodReplacement)):
            return handler
        if handler is not None and any(
                hasattr(handler, attr) for attr in
                ("before_hooked_method", "after_hooked_method", "replace_hooked_method")):
            return handler  # any object already speaking the hook protocol
        if handler is not None:
            if not callable(handler):
                raise TypeError(
                    "handler must be a MethodHook/MethodReplacement instance or a callable")
            if before is not None:
                raise TypeError("pass either a handler callable or before=, not both")
            before, handler = handler, None
        if before is None and after is None:
            raise TypeError("hook_method requires a handler or before=/after= callbacks")
        wrapper = _FunctionalHook()
        if before is not None:
            wrapper.before_hooked_method = before
        if after is not None:
            wrapper.after_hooked_method = after
        return wrapper

    def _build_filters_json(self, handler_obj, filters, before_filters, after_filters) -> str:
        declared = _handler_declared_filters(handler_obj)
        before = [*declared["before"], *(filters or ()), *(before_filters or ())]
        after = [*declared["after"], *(after_filters or ())]
        return _serialize_hook_filters(before, after)

    def _track_unhook_ids(self, ids) -> None:
        tracked = self._state("_method_hook_ids", list)
        for unhook_id in ids:
            if unhook_id is not None:
                tracked.append(str(unhook_id))

    def _hook_all(self, kind: str, clazz, method_name, handler_obj, priority, filters_json):
        """Shared tail for hook_all_methods/hook_all_constructors."""
        if PluginServices is None or not self._plugin_id:
            self.log(f"{kind} requires the Android runtime")
            return UnhookIdList((), self)
        try:
            raw = PluginServices.hookAllMethods(self._plugin_id, clazz, method_name,
                                                handler_obj, int(priority), filters_json) \
                if kind == "methods" else \
                PluginServices.hookAllConstructors(self._plugin_id, clazz, handler_obj,
                                                   int(priority), filters_json)
            ids = [str(x) for x in (json.loads(raw) if raw else [])]
        except Exception as e:
            self.log(f"hook_all_{kind}({method_name or clazz!r}) failed: {e}")
            return UnhookIdList((), self)
        self._track_unhook_ids(ids)
        return UnhookIdList(ids, self)

    @staticmethod
    def _resolve_class(clazz):
        """Accept a jclass/java.lang.Class or a dotted class name."""
        if isinstance(clazz, str):
            from hook_utils import find_class
            resolved = find_class(clazz)
            if resolved is None:
                raise RuntimeError(f"class not found: {clazz!r}")
            return resolved
        return clazz

    def hook_method(self, method, handler=None, priority: int = 50, filters=None,
                    before=None, after=None, before_filters=None, after_filters=None):
        """Hook a Java method/constructor; returns an unhook id (or list, or None).

        *method* may be a java.lang.reflect.Method/Constructor (e.g. from
        ``hook_utils.find_class(...).getDeclaredMethod(...)``) or a
        ``(clazz, "name")`` pair — a declared method is resolved by name and,
        with multiple overloads, all of them are hooked (a list of unhook ids
        is returned then).
        """
        handler_obj = self._build_method_handler(handler, before=before, after=after)
        filters_json = self._build_filters_json(handler_obj, filters,
                                                before_filters, after_filters)

        if isinstance(method, (tuple, list)) and len(method) == 2:
            clazz = self._resolve_class(method[0])
            name = str(method[1])
            matches = [m for m in clazz.getDeclaredMethods()
                       if str(m.getName()) == name]
            if not matches:
                self.log(f"hook_method: no declared method {name!r} on {clazz}")
                return None
            if len(matches) > 1:
                return self._hook_all("methods", clazz, name, handler_obj,
                                      priority, filters_json)
            member = matches[0]
            try:
                member.setAccessible(True)
            except Exception:
                pass
        else:
            member = method

        if PluginServices is None or not self._plugin_id:
            self.log("hook_method requires the Android runtime")
            return None
        try:
            unhook_id = PluginServices.hookMethod(self._plugin_id, member, handler_obj,
                                                  int(priority), filters_json)
        except Exception as e:
            self.log(f"hook_method({member!r}) failed: {e}")
            return None
        if unhook_id is None:
            return None
        unhook_id = str(unhook_id)
        self._track_unhook_ids((unhook_id,))
        return UnhookId(unhook_id, self)

    def hook_all_methods(self, clazz, method_name, handler=None, priority: int = 50,
                         filters=None, before=None, after=None,
                         before_filters=None, after_filters=None) -> list:
        """Hook every overload of *method_name* on *clazz*; returns unhook ids."""
        handler_obj = self._build_method_handler(handler, before=before, after=after)
        filters_json = self._build_filters_json(handler_obj, filters,
                                                before_filters, after_filters)
        return self._hook_all("methods", self._resolve_class(clazz), str(method_name),
                              handler_obj, priority, filters_json)

    def hook_all_constructors(self, clazz, handler=None, priority: int = 50,
                              filters=None, before=None, after=None,
                              before_filters=None, after_filters=None) -> list:
        """Hook every constructor of *clazz*; returns unhook ids."""
        handler_obj = self._build_method_handler(handler, before=before, after=after)
        filters_json = self._build_filters_json(handler_obj, filters,
                                                before_filters, after_filters)
        return self._hook_all("constructors", self._resolve_class(clazz), None,
                              handler_obj, priority, filters_json)

    def unhook_method(self, unhook_obj):
        """Remove hook(s) by unhook id; accepts a single id or a list of them."""
        if unhook_obj is None:
            return
        ids = [unhook_obj] if isinstance(unhook_obj, str) else list(unhook_obj)
        tracked = self._state("_method_hook_ids", list)
        for unhook_id in ids:
            if unhook_id is None:
                continue
            unhook_id = str(unhook_id)
            if PluginServices is not None:
                try:
                    PluginServices.unhook(unhook_id)
                except Exception as e:
                    self.log(f"unhook_method({unhook_id!r}) failed: {e}")
            try:
                tracked.remove(unhook_id)
            except ValueError:
                pass

    # ---- resource cleanup (driven by the loader on unload) ----

    def _cleanup_resources(self):
        """Best-effort release of SDK resources registered by this plugin.

        Called by extera_utils.plugin_loader on unload, after the user's
        on_plugin_unload(); never raises. The Java side performs its own
        per-plugin sweep as well — every call here tolerates duplicates.
        """
        if PluginServices is not None:
            for unhook_id in list(self._state("_method_hook_ids", list)):
                try:
                    PluginServices.unhook(str(unhook_id))
                except Exception:
                    pass
        self.__dict__["_method_hook_ids"] = []
        for module_name, cleanup in (("file_utils", "_unregister_all_for_plugin"),
                                     ("intents", "_unhandle_all_for_plugin")):
            try:
                module = __import__(module_name)
                getattr(module, cleanup)(self._plugin_id)
            except Exception:
                pass
