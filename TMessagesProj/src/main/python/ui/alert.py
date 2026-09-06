"""AlertDialog builder wrapper — part of the exteraless plugin SDK.

Wraps org.telegram.ui.ActionBar.AlertDialog.Builder. All UI operations are
executed on the Android UI thread; mutating builder calls can be chained.
Button/item listeners receive (builder, which); dismiss/cancel/back
listeners receive (builder).
"""

import os
import sys
import threading

# Make sibling top-level modules importable regardless of interpreter setup.
_SRC_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SRC_DIR not in sys.path:
    sys.path.insert(0, _SRC_DIR)

from android_utils import safe_call


def _jclass(name: str):
    from java import jclass
    return jclass(name)


def _is_ui_thread() -> bool:
    try:
        Looper = _jclass("android.os.Looper")
        Thread = _jclass("java.lang.Thread")
        return Looper.getMainLooper().getThread() == Thread.currentThread()
    except Exception:
        return False


def _run_sync(fn):
    """Run fn on the UI thread and wait for its result."""
    if _is_ui_thread():
        return fn()
    from android_utils import R

    done = threading.Event()
    box = {}

    def _wrap():
        try:
            box["result"] = fn()
        except Exception as e:  # propagated to the waiting thread
            box["error"] = e
        finally:
            done.set()

    try:
        from app.exteraless.plugins import PluginServices
        PluginServices.runOnUiThread(_wrap, 0)
    except Exception:
        _jclass("org.telegram.messenger.AndroidUtilities").runOnUIThread(R(_wrap))
    if not done.wait(10.0):
        raise TimeoutError("UI thread did not respond within 10 seconds")
    if "error" in box:
        raise box["error"]
    return box.get("result")


def _post(fn):
    """Run fn on the UI thread without waiting."""
    if _is_ui_thread():
        fn()
    else:
        from android_utils import run_on_ui_thread
        run_on_ui_thread(fn)


def _default_context():
    try:
        fragment = _jclass("org.telegram.ui.LaunchActivity").getLastFragment()
        if fragment is not None:
            context = fragment.getContext()
            if context is not None:
                return context
    except Exception:
        pass
    return _jclass("org.telegram.messenger.ApplicationLoader").applicationContext


class AlertDialogBuilder:
    """Fluent wrapper around AlertDialog.Builder (see the plugin docs)."""

    # Mirrors org.telegram.ui.ActionBar.AlertDialog constants.
    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 2
    ALERT_TYPE_SPINNER = 3

    # android.content.DialogInterface button ids.
    BUTTON_POSITIVE = -1
    BUTTON_NEGATIVE = -2
    BUTTON_NEUTRAL = -3

    def __init__(self, context=None, alert_type=ALERT_TYPE_MESSAGE,
                 resources_provider=None, *, progress_style=None):
        if progress_style is not None:
            if alert_type != self.ALERT_TYPE_MESSAGE and alert_type != progress_style:
                raise TypeError("Conflicting alert_type and progress_style")
            alert_type = progress_style
        if context is None:
            context = _default_context()
        self._alert_type = alert_type
        self._dialog = None
        self._cancelable = None
        self._canceled_on_touch_outside = None
        self._proxies = []  # keep dynamic proxies alive for the dialog lifetime

        Builder = _jclass("org.telegram.ui.ActionBar.AlertDialog$Builder")
        if int(alert_type) == self.ALERT_TYPE_MESSAGE:
            if resources_provider is None:
                self._builder = _run_sync(lambda: Builder(context))
            else:
                self._builder = _run_sync(lambda: Builder(context, resources_provider))
        elif resources_provider is None:
            self._builder = _run_sync(lambda: Builder(context, int(alert_type)))
        else:
            self._builder = _run_sync(
                lambda: Builder(context, int(alert_type), resources_provider))

    @property
    def _java_builder(self):
        return self._builder

    # ---- content ----

    def set_title(self, text):
        _post(lambda: self._builder.setTitle(str(text)))
        return self

    def set_message(self, text):
        _post(lambda: self._builder.setMessage(str(text)))
        return self

    def set_view(self, view, height=-2):
        if height is None:
            _post(lambda: self._builder.setView(view))
        else:
            _post(lambda: self._builder.setView(view, int(height)))
        return self

    def set_items(self, items, listener, icons=None):
        """items: list of strings; listener(builder, which); icons: optional res ids."""
        from java import dynamic_proxy

        OnClickListener = _jclass("android.content.DialogInterface$OnClickListener")
        builder_self = self

        class _Listener(dynamic_proxy(OnClickListener)):
            def onClick(self, dialog, which):
                safe_call(listener, builder_self, which)

        proxy = _Listener()
        self._proxies.append(proxy)
        texts = [str(item) for item in items]

        def _apply():
            if icons is not None:
                self._builder.setItems(texts, [int(icon) for icon in icons], proxy)
            else:
                self._builder.setItems(texts, proxy)

        _post(_apply)
        return self

    # ---- buttons ----

    def _set_button(self, method_name, text, listener):
        from java import dynamic_proxy

        OnButtonClickListener = _jclass(
            "org.telegram.ui.ActionBar.AlertDialog$OnButtonClickListener")
        builder_self = self

        class _Listener(dynamic_proxy(OnButtonClickListener)):
            def onClick(self, dialog, which):
                safe_call(listener, builder_self, which)

        proxy = _Listener()
        self._proxies.append(proxy)
        _post(lambda: getattr(self._builder, method_name)(str(text), proxy))
        return self

    def set_positive_button(self, text, listener):
        return self._set_button("setPositiveButton", text, listener)

    def set_negative_button(self, text, listener):
        return self._set_button("setNegativeButton", text, listener)

    def set_neutral_button(self, text, listener):
        return self._set_button("setNeutralButton", text, listener)

    def make_button_red(self, which):
        """Recolor a dialog button (call after show())."""
        def _apply():
            try:
                if self._dialog is None:
                    return
                button = self._dialog.getButton(int(which))
                if button is None:
                    return
                try:
                    Theme = _jclass("org.telegram.ui.ActionBar.Theme")
                    color = Theme.getColor(Theme.key_dialogTextRed)
                except Exception:
                    from java import jint
                    color = jint(0xFFE53935, truncate=True)  # Material Red 600
                button.setTextColor(color)
            except Exception as e:
                try:
                    from android_utils import log
                    log(f"make_button_red failed: {e}")
                except Exception:
                    pass

        _post(_apply)
        return self

    # ---- behavior / appearance ----

    def set_cancelable(self, cancelable):
        self._cancelable = bool(cancelable)
        if self._dialog is not None:
            _post(lambda: self._dialog.setCancelable(self._cancelable))
        return self

    def set_canceled_on_touch_outside(self, cancel):
        self._canceled_on_touch_outside = bool(cancel)
        if self._dialog is not None:
            _post(lambda: self._dialog.setCanceledOnTouchOutside(self._canceled_on_touch_outside))
        return self

    def set_progress(self, progress):
        """Set progress (0-100) on a shown LOADING/SPINNER dialog."""
        if self._dialog is not None:
            _post(lambda: self._dialog.setProgress(int(progress)))
        return self

    def set_top_animation(self, res_id, size, auto_repeat, background_color):
        def _apply():
            from java import jint
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat),
                                          jint(int(background_color), truncate=True))
        _post(_apply)
        return self

    def set_dim_enabled(self, enabled):
        _post(lambda: self._builder.setDimEnabled(bool(enabled)))
        return self

    def set_on_dismiss_listener(self, listener):
        from java import dynamic_proxy

        OnDismissListener = _jclass("android.content.DialogInterface$OnDismissListener")
        builder_self = self

        class _Listener(dynamic_proxy(OnDismissListener)):
            def onDismiss(self, dialog):
                safe_call(listener, builder_self)

        proxy = _Listener()
        self._proxies.append(proxy)
        _post(lambda: self._builder.setOnDismissListener(proxy))
        return self

    def set_on_cancel_listener(self, listener):
        from java import dynamic_proxy

        OnCancelListener = _jclass("android.content.DialogInterface$OnCancelListener")
        builder_self = self

        class _Listener(dynamic_proxy(OnCancelListener)):
            def onCancel(self, dialog):
                safe_call(listener, builder_self)

        proxy = _Listener()
        self._proxies.append(proxy)
        _post(lambda: self._builder.setOnCancelListener(proxy))
        return self

    def set_on_back_button_listener(self, listener):
        from java import dynamic_proxy

        OnButtonClickListener = _jclass(
            "org.telegram.ui.ActionBar.AlertDialog$OnButtonClickListener")
        builder_self = self

        class _Listener(dynamic_proxy(OnButtonClickListener)):
            def onClick(self, dialog, which):
                safe_call(listener, builder_self)

        proxy = _Listener()
        self._proxies.append(proxy)
        _post(lambda: self._builder.setOnBackButtonListener(proxy))
        return self

    # ---- lifecycle ----

    def create(self):
        """Create the dialog (without showing it); returns self."""
        def _do():
            if self._dialog is None:
                self._dialog = self._builder.create()
                self._apply_cancel_flags()
        _run_sync(_do)
        return self

    def show(self):
        """Create (if needed) and show the dialog; returns self."""
        def _do():
            if self._dialog is None:
                self._dialog = self._builder.show()
                self._apply_cancel_flags()
            elif not self._dialog.isShowing():
                self._dialog.show()
        _run_sync(_do)
        return self

    def _apply_cancel_flags(self):
        if self._cancelable is not None:
            self._dialog.setCancelable(self._cancelable)
        if self._canceled_on_touch_outside is not None:
            self._dialog.setCanceledOnTouchOutside(self._canceled_on_touch_outside)

    def dismiss(self):
        if self._dialog is not None:
            _post(self._dialog.dismiss)
        return self

    def get_dialog(self):
        """The underlying AlertDialog, or None before create()/show()."""
        return self._dialog

    def get_button(self, which):
        """The dialog's button View for a BUTTON_* id, or None."""
        if self._dialog is None:
            return None
        try:
            return self._dialog.getButton(int(which))
        except Exception:
            return None
