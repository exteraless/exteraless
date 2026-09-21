"""Android runtime helpers (UI thread, listener proxies, clipboard, log) — exteraless plugin SDK.

All Java interop is resolved lazily so importing this module on a host
interpreter is safe; calling the helpers without a JVM raises the underlying
import error.
"""

import contextlib
import sys

_BRIDGE_UNSET = object()
_bridge_cache = _BRIDGE_UNSET


def _engine_class(name):
    try:
        from extera_utils.plugin_loader import engine_java_class
    except Exception:
        return None
    return engine_java_class(name)


def _bridge():
    """Resolve app.exteraless.plugins.PythonBridge once, tolerating its absence."""
    global _bridge_cache
    if _bridge_cache is _BRIDGE_UNSET:
        try:
            from app.exteraless.plugins import PythonBridge
            _bridge_cache = PythonBridge
        except Exception:
            _bridge_cache = _engine_class("app.exteraless.plugins.PythonBridge")
    return _bridge_cache


_services_cache = _BRIDGE_UNSET


def _services():
    global _services_cache
    if _services_cache is _BRIDGE_UNSET:
        try:
            from app.exteraless.plugins import PluginServices
            _services_cache = PluginServices
        except Exception:
            _services_cache = _engine_class("app.exteraless.plugins.PluginServices")
    return _services_cache


def log(data):
    """Log a value into the app's plugin pipeline (plugin id "sdk").

    Simple values are stringified; other objects are repr()'d.
    """
    if isinstance(data, (str, int, float, bool)) or data is None:
        message = str(data)
    else:
        try:
            message = repr(data)
        except Exception:
            message = object.__repr__(data)
    bridge = _bridge()
    if bridge is not None:
        try:
            bridge.log("sdk", message)
            return
        except Exception:
            pass
    print(f"[exteraless:sdk] {message}", file=sys.stderr)


@contextlib.contextmanager
def _plugin_mark(fn):
    """Пометить поток на Java-стороне владельцем колбэка.

    Владелец берётся из файла самой функции, а не из стека: в момент вызова
    кадра плагина на стеке ещё нет — колбэк прилетел из Java. Без метки
    Java-гейт (PluginSinkGate) не знает, чей код побежит, и пропускает
    обращения плагина к сети и рефлексии из UI-колбэков.
    """
    try:
        from extera_utils import plugin_loader
        owner = plugin_loader.owner_of_function(fn)
        if owner is None:
            yield
            return
        with plugin_loader.java_runtime_mark(owner):
            yield
        return
    except Exception:
        pass
    yield


def safe_call(fn, *args, **kwargs):
    """Вызвать колбэк плагина так, чтобы ошибка не убила приложение.

    Колбэки уезжают в Java через dynamic_proxy, и исключение из Python
    поднимается по стеку прямо в UI-поток Android: одна опечатка в плагине
    роняла всё приложение с FATAL EXCEPTION. Здесь ошибка логируется и
    гасится — ровно как это делает Java-сторона на своих точках входа.

    ``fn is None`` — не ошибка: у кнопок диалога это штатный способ сказать
    «просто закрой» (так делают 18 плагинов из публичного каталога).
    """
    if fn is None:
        return None
    try:
        with _plugin_mark(fn):
            return fn(*args, **kwargs)
    except PermissionError as e:
        # Отказ в разрешении — не поломка плагина, а его собственный выбор не
        # объявлять разрешение (отказ не роняет
        # плагин»). Трассировка тут ничего не объясняет, текст исключения
        # объясняет всё, а сам отказ уже записан на Java-стороне.
        log(f"permission denied: {e}")
        return None
    except Exception:
        import traceback
        log("callback failed:\n" + traceback.format_exc())
        return None


def R(fn):
    """Wrap a Python callable as a java.lang.Runnable."""
    from java import dynamic_proxy, jclass

    class _Runnable(dynamic_proxy(jclass("java.lang.Runnable"))):
        def run(self):
            safe_call(fn)

    return _Runnable()


def run_on_ui_thread(func, delay=0):
    """Run *func* on the Android UI thread, optionally after *delay* ms.

    Колбэк уходит в Java как обычный объект, а Runnable создаётся там же. Через
    ``R(func)`` этого делать нельзя: в Handler попадал бы python-прокси, и
    Chaquopy разворачивал бы его обратно в момент срабатывания. Если к этому
    времени плагин успели перезагрузить, разворот падает NotImplementedError
    прямо в UI-потоке и роняет приложение — поймать это из python нечем.
    """
    if func is None:
        return
    services = _services()
    if services is None:
        raise ImportError("app.exteraless.plugins.PluginServices is unavailable")
    services.runOnUiThread(lambda: safe_call(func), int(delay or 0))


def OnClickListener(fn):
    """android.view.View.OnClickListener proxy calling fn(view)."""
    from java import dynamic_proxy, jclass

    class _OnClickListener(dynamic_proxy(jclass("android.view.View$OnClickListener"))):
        def onClick(self, view):
            safe_call(fn, view)

    return _OnClickListener()


def OnLongClickListener(fn):
    """android.view.View.OnLongClickListener proxy; fn(view) must return bool."""
    from java import dynamic_proxy, jclass

    class _OnLongClickListener(dynamic_proxy(jclass("android.view.View$OnLongClickListener"))):
        def onLongClick(self, view):
            return bool(safe_call(fn, view))

    return _OnLongClickListener()


def OnTouchListener(fn):
    """android.view.View.OnTouchListener proxy; fn(view, event) must return bool."""
    from java import dynamic_proxy, jclass

    class _OnTouchListener(dynamic_proxy(jclass("android.view.View$OnTouchListener"))):
        def onTouch(self, view, event):
            return bool(safe_call(fn, view, event))

    return _OnTouchListener()


def OnKeyListener(fn):
    """android.view.View.OnKeyListener proxy; fn(view, key_code, event) -> bool."""
    from java import dynamic_proxy, jclass

    class _OnKeyListener(dynamic_proxy(jclass("android.view.View$OnKeyListener"))):
        def onKey(self, view, key_code, event):
            return bool(safe_call(fn, view, key_code, event))

    return _OnKeyListener()


def OnSeekBarChangeListener(on_progress_changed, on_start=None, on_stop=None):
    """android.widget.SeekBar.OnSeekBarChangeListener proxy.

    Only the progress callback is required; the start/stop tracking callbacks
    default to no-ops, which is how plugins use it.
    """
    from java import dynamic_proxy, jclass

    class _Listener(dynamic_proxy(
            jclass("android.widget.SeekBar$OnSeekBarChangeListener"))):
        def onProgressChanged(self, seek_bar, progress, from_user):
            safe_call(on_progress_changed, seek_bar, progress, from_user)

        def onStartTrackingTouch(self, seek_bar):
            safe_call(on_start, seek_bar)

        def onStopTrackingTouch(self, seek_bar):
            safe_call(on_stop, seek_bar)

    return _Listener()


def get_context():
    """The application Context."""
    from java import jclass

    return jclass("org.telegram.messenger.ApplicationLoader").applicationContext


def get_activity():
    """The current LaunchActivity, or None when the UI is not up.

    Plugins pass this to AlertDialog builders, so returning None rather than
    raising lets them fall back to a bulletin.
    """
    from java import jclass

    try:
        activity = jclass("org.telegram.ui.LaunchActivity").instance
        if activity is not None:
            return activity
    except Exception:
        pass
    try:
        from client_utils import get_last_fragment

        fragment = get_last_fragment()
        return fragment.getParentActivity() if fragment is not None else None
    except Exception:
        return None


def copy_to_clipboard(text):
    """Copy text to the system clipboard and show a "copied" bulletin (best-effort)."""
    from java import jclass

    context = jclass("org.telegram.messenger.ApplicationLoader").applicationContext
    ClipData = jclass("android.content.ClipData")
    clipboard = context.getSystemService("clipboard")
    clipboard.setPrimaryClip(ClipData.newPlainText("exteraless", str(text)))
    try:
        from ui.bulletin import BulletinHelper
        BulletinHelper.show_copied_to_clipboard()
    except Exception:
        pass  # the copy itself already succeeded


_JAVA_EXPORTS = {
    "AndroidUtilities": "org.telegram.messenger.AndroidUtilities",
}


def __getattr__(name):
    target = _JAVA_EXPORTS.get(name)
    if target is None:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    from java import jclass

    return jclass(target)
