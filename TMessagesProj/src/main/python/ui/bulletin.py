"""Bulletin (in-app notification) helpers — part of the exteraless plugin SDK.

Everything is posted onto the UI thread automatically. If BulletinFactory
cannot serve a request, the helper degrades to an Android Toast.
"""

import os
import sys

# Make sibling top-level modules importable regardless of interpreter setup.
_SRC_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SRC_DIR not in sys.path:
    sys.path.insert(0, _SRC_DIR)

DURATION_SHORT = 1500
DURATION_LONG = 2750
DURATION_PROLONG = 5000


def _jclass(name: str):
    from java import jclass
    return jclass(name)


def _raw_icon(name: str):
    """org.telegram.messenger.R.raw.<name> (Lottie res id), 0 when missing."""
    try:
        return getattr(_jclass("org.telegram.messenger.R$raw"), name)
    except Exception:
        return 0


def _factory(fragment=None):
    """BulletinFactory for *fragment*, the last visible fragment, or global()."""
    BulletinFactory = _jclass("org.telegram.ui.Components.BulletinFactory")
    if fragment is None:
        try:
            fragment = _jclass("org.telegram.ui.LaunchActivity").getLastFragment()
        except Exception:
            fragment = None
    if fragment is not None:
        try:
            return BulletinFactory.of(fragment)
        except Exception:
            pass
    # "global" is a Python reserved word, so it cannot be used as an attribute.
    return getattr(BulletinFactory, "global")()


def _toast(text):
    """Last-resort fallback when BulletinFactory is unavailable."""
    try:
        Toast = _jclass("android.widget.Toast")
        context = _jclass("org.telegram.messenger.ApplicationLoader").applicationContext
        Toast.makeText(context, str(text), Toast.LENGTH_SHORT).show()
    except Exception:
        pass


def _runnable(fn):
    try:
        from app.exteraless.plugins import PluginServices
        return PluginServices.runnable(fn)
    except Exception:
        from android_utils import R
        return R(fn)


def _safe(fn):
    """Wrap a user callback so exceptions never escape into Java."""
    def _wrapped(*args):
        try:
            return fn(*args)
        except Exception as e:
            try:
                from android_utils import log
                log(f"bulletin callback failed: {type(e).__name__}: {e}")
            except Exception:
                pass
    return _wrapped


def _show(make_bulletin, fallback_text):
    """Post bulletin creation+show onto the UI thread; Toast on failure."""
    from android_utils import run_on_ui_thread

    def _do():
        try:
            bulletin = make_bulletin()
            if bulletin is not None:
                bulletin.show()
                return
        except Exception:
            pass
        _toast(fallback_text)

    try:
        run_on_ui_thread(_do)
    except Exception:
        _toast(fallback_text)


class BulletinHelper:
    """Static bulletin helpers; all calls are marshalled to the UI thread."""

    DURATION_SHORT = DURATION_SHORT
    DURATION_LONG = DURATION_LONG
    DURATION_PROLONG = DURATION_PROLONG

    @staticmethod
    def show_info(message, fragment=None):
        _show(lambda: _factory(fragment).createSimpleBulletin(
            _raw_icon("info"), str(message)), message)

    @staticmethod
    def show_error(message, fragment=None):
        _show(lambda: _factory(fragment).createErrorBulletin(str(message)), message)

    @staticmethod
    def show_success(message, fragment=None):
        _show(lambda: _factory(fragment).createSuccessBulletin(str(message)), message)

    @staticmethod
    def show_simple(text, icon_res_id, fragment=None, duration=None):
        def make():
            bulletin = _factory(fragment).createSimpleBulletin(
                int(icon_res_id), str(text))
            if duration is not None:
                bulletin = bulletin.setDuration(int(duration))
            return bulletin
        _show(make, text)

    @staticmethod
    def show_two_line(title, subtitle, icon_res_id, fragment=None, duration=None):
        def make():
            bulletin = _factory(fragment).createSimpleBulletin(
                int(icon_res_id), str(title), str(subtitle))
            if duration is not None:
                bulletin = bulletin.setDuration(int(duration))
            return bulletin
        _show(make, title)

    @staticmethod
    def show_with_button(text, icon_res_id, button_text, on_click,
                         fragment=None, duration=DURATION_PROLONG):
        def make():
            from android_utils import R
            runnable = _runnable(_safe(on_click)) if on_click is not None else _runnable(lambda: None)
            return _factory(fragment).createSimpleBulletin(
                int(icon_res_id), str(text), str(button_text), int(duration), runnable)
        _show(make, text)

    @staticmethod
    def show_undo(text, on_undo, on_action=None, subtitle=None, fragment=None):
        def make():
            from android_utils import R
            undo_runnable = _runnable(_safe(on_undo)) if on_undo is not None else _runnable(lambda: None)
            action_runnable = _runnable(_safe(on_action)) if on_action is not None else _runnable(lambda: None)
            factory = _factory(fragment)
            if subtitle is not None:
                return factory.createUndoBulletin(
                    str(text), str(subtitle), undo_runnable, action_runnable)
            return factory.createUndoBulletin(str(text), undo_runnable, action_runnable)
        _show(make, text)

    # -- convenience shortcuts (best-effort, English fallback strings) --

    @staticmethod
    def show_copied_to_clipboard(fragment=None):
        BulletinHelper.show_simple("Copied to clipboard", _raw_icon("copy"), fragment)

    @staticmethod
    def show_link_copied(fragment=None):
        BulletinHelper.show_simple("Link copied to clipboard", _raw_icon("copy"), fragment)

    @staticmethod
    def show_file_saved_to_gallery(fragment=None):
        BulletinHelper.show_simple("Saved to gallery",
                                   _raw_icon("ic_save_to_gallery"), fragment)

    @staticmethod
    def show_file_saved_to_downloads(file_type="DOCUMENT", amount=1, fragment=None):
        """file_type: a BulletinFactory.FileType enum name (e.g. "PHOTO_TO_DOWNLOADS")."""
        def make():
            FileType = _jclass("org.telegram.ui.Components.BulletinFactory$FileType")
            file_type_enum = FileType.valueOf(str(file_type))
            factory = _factory(fragment)
            try:
                return factory.createDownloadBulletin(file_type_enum, int(amount), None)
            except Exception:
                return factory.createDownloadBulletin(file_type_enum)
        _show(make, "Saved to downloads")
