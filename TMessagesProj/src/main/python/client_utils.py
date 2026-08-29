"""Telegram client helpers: queues, requests, controllers, sending — exteraless plugin SDK.

All Java interop is lazy; importing this module on a host interpreter is
safe, calling into it requires the Android runtime.

Multi-account (PLUGINS-API.md §4.1): every account-scoped helper accepts an
optional ``account`` keyword. Without it the helper works on the UI-selected
account (``UserConfig.selectedAccount``); called from inside a hook callback
without ``account=`` it logs a one-time warning, because the hook's account
is almost always the intended one — take it from ``get_hook_account()`` /
``self.client`` instead. The hook account itself propagates automatically
around hook callbacks, work posted via ``run_on_queue`` from them and
``send_request`` completion callbacks.
"""

import os
import sys
import threading
from contextlib import contextmanager

# Make sibling top-level modules importable regardless of interpreter setup.
_SRC_DIR = os.path.dirname(os.path.abspath(__file__))
if _SRC_DIR not in sys.path:
    sys.path.insert(0, _SRC_DIR)

# DispatchQueue name constants (see the plugin docs).
STAGE_QUEUE = "STAGE_QUEUE"
GLOBAL_QUEUE = "GLOBAL_QUEUE"
CACHE_CLEAR_QUEUE = "CACHE_CLEAR_QUEUE"
SEARCH_QUEUE = "SEARCH_QUEUE"
PHONE_BOOK_QUEUE = "PHONE_BOOK_QUEUE"
THEME_QUEUE = "THEME_QUEUE"
EXTERNAL_NETWORK_QUEUE = "EXTERNAL_NETWORK_QUEUE"
PLUGINS_QUEUE = "PLUGINS_QUEUE"

_queues = {}


def _jclass(name: str):
    from java import jclass
    return jclass(name)


def _log(message):
    try:
        from android_utils import log
        log(message)
    except Exception:
        print(f"[exteraless:client_utils] {message}", file=sys.stderr)


def _require(perm: str, what: str, detail=None):
    """Проверить разрешение плагина.

    Импорт ленивый: plugin_loader импортирует client_utils первым, на уровне
    модуля это был бы цикл. Плагин определяется по стеку, поэтому проверка
    работает и в колбэках из Java, где plugin_context не выставлен.
    """
    from extera_utils.plugin_loader import require_permission
    require_permission(perm, what, detail=detail)


# Hook account scope

_hook_state = threading.local()
_MISSING = object()


def get_hook_account():
    """Account of the hook callback running on this thread, or None."""
    return getattr(_hook_state, "account", None)


def get_selected_account() -> int:
    """The account currently selected in the UI."""
    return int(_jclass("org.telegram.messenger.UserConfig").selectedAccount)


@contextmanager
def hook_scope(account):
    """Bind *account* as the hook account for the current thread.

    Used by the plugin loader around hook dispatch and by client_utils
    itself around queued work / request callbacks. Restores the previous
    value on exit; safe to nest.
    """
    previous = getattr(_hook_state, "account", _MISSING)
    _hook_state.account = account
    try:
        yield account
    finally:
        if previous is _MISSING:
            try:
                del _hook_state.account
            except AttributeError:
                pass
        else:
            _hook_state.account = previous


_warned_helpers = set()


def _resolve_account(account, helper_name: str = None) -> int:
    """explicit account -> UI-selected account (warn once per helper inside
    a hook scope: the scope account is usually the intended one there)."""
    if account is not None:
        return int(account)
    hook_account = get_hook_account()
    if hook_account is not None and helper_name is not None \
            and helper_name not in _warned_helpers:
        _warned_helpers.add(helper_name)
        _log(f"client_utils.{helper_name}() called without account= inside a hook "
             f"callback (account {hook_account}); helpers default to the UI-selected "
             "account — pass account= explicitly (see PLUGINS-API.md §4.1)")
    return get_selected_account()


def _resolve_scoped_account(account) -> int:
    """AccountClient resolution: explicit -> hook scope -> UI-selected."""
    if account is not None:
        return int(account)
    hook_account = get_hook_account()
    if hook_account is not None:
        return int(hook_account)
    return get_selected_account()


# Dispatch queues

def get_queue_by_name(name: str):
    """Return (creating lazily) a named org.telegram.messenger.DispatchQueue."""
    queue = _queues.get(name)
    if queue is None:
        queue = _jclass("org.telegram.messenger.DispatchQueue")(str(name))
        _queues[name] = queue
    return queue


def show_error_bulletin(message, fragment=None):
    """Ошибка плашкой. Есть в SDK exteraGram и зовётся плагинами из client_utils,
    хотя реализация живёт в ui.bulletin, — без этого имени они не грузятся."""
    from ui.bulletin import BulletinHelper
    BulletinHelper.show_error(message, fragment)


def show_info_bulletin(message, fragment=None):
    """Сообщение плашкой; парная к show_error_bulletin."""
    from ui.bulletin import BulletinHelper
    BulletinHelper.show_info(message, fragment)


def run_on_queue(fn, queue: str = PLUGINS_QUEUE, delay: int = 0, delay_ms: int = None):
    """Post *fn* to a named DispatchQueue, optionally after *delay* ms.

    The current hook account (if any) is captured now and restored around
    the execution of *fn* (PLUGINS-API.md §4.1).
    """
    from android_utils import R

    if delay_ms is not None:
        delay = delay_ms
    account = get_hook_account()
    # Владельца берём в момент постановки в очередь: исполняться _run будет на
    # чужом потоке, где кадра плагина на стеке уже нет, и Java-гейт без метки
    # пропустил бы обращения плагина к сети и рефлексии.
    from extera_utils.plugin_loader import java_runtime_mark, plugin_frame_owner
    owner = plugin_frame_owner()

    def _run():
        with java_runtime_mark(owner):
            if account is None:
                fn()
            else:
                with hook_scope(account):
                    fn()

    dispatch_queue = get_queue_by_name(queue)
    runnable = R(_run)
    if delay and int(delay) > 0:
        dispatch_queue.postRunnable(runnable, int(delay))
    else:
        dispatch_queue.postRunnable(runnable)
    return dispatch_queue


# TL requests

def RequestCallback(fn, account=None):
    """Wrap ``fn(response, error)`` as a Java ``RequestDelegate``.

    Plugins use this in two ways, both supported:

    * handed to :func:`send_request` — which also accepts a bare callable, so
      the wrapper is optional there;
    * handed straight to ``get_connections_manager().sendRequest(req, cb, flags)``,
      where a bare Python callable would not satisfy the Java signature.

    The callback runs inside the hook scope of *account* (the UI-selected
    account when not given), so account-scoped helpers called from within it
    target the account the request was sent on.
    """
    from java import dynamic_proxy

    RequestDelegate = _jclass("org.telegram.tgnet.RequestDelegate")
    resolved = _resolve_account(account, "RequestCallback")

    class _RequestDelegate(dynamic_proxy(RequestDelegate)):
        def run(self, response, error):
            # Колбэк уходит в Java: ошибка плагина не должна ронять приложение.
            from android_utils import safe_call

            with hook_scope(resolved):
                safe_call(fn, response, error)

    proxy = _RequestDelegate()
    # Marks an already-wrapped callback so send_request does not double-wrap.
    try:
        proxy.__dict__["_exteraless_request_delegate"] = True
    except Exception:
        pass
    return proxy


def _is_request_delegate(fn) -> bool:
    """True when *fn* is already a Java RequestDelegate rather than a callable."""
    if getattr(fn, "_exteraless_request_delegate", False):
        return True
    # A dynamic_proxy instance exposes run() but is not callable itself;
    # every plain Python callback is callable. That is the discriminator.
    return not callable(fn) and hasattr(fn, "run")


def send_request(request, fn, account=None) -> int:
    """Send a TL request; fn(response, error) is called on completion.

    *fn* may be a plain callable or an already-built :func:`RequestCallback`
    delegate — plugins in the wild pass both.

    Defaults to the UI-selected account; the completion callback runs with
    the scope of the account the request was sent on. Returns the
    ConnectionsManager request id.
    """
    # TL-запрос из кода плагина — это сетевой запрос из кода плагина, то есть
    # ровно то, что закрывает "network" (PLUGINS-SECURITY.md, набор разрешений).
    _require("network", "send_request")
    resolved = _resolve_account(account, "send_request")
    proxy = fn if _is_request_delegate(fn) else RequestCallback(fn, account=resolved)
    return int(get_connections_manager(resolved).sendRequest(request, proxy))


# Core controller accessors

def get_account_instance(account=None):
    """AccountInstance for *account* (default: UI-selected / hook scope rules)."""
    return _jclass("org.telegram.messenger.AccountInstance").getInstance(
        _resolve_account(account, "get_account_instance"))


def get_last_fragment():
    """The currently visible BaseFragment, or None when unavailable."""
    try:
        return _jclass("org.telegram.ui.LaunchActivity").getLastFragment()
    except Exception:
        return None


def get_messages_controller(account=None):
    return get_account_instance(_resolve_account(account, "get_messages_controller")) \
        .getMessagesController()


def get_contacts_controller(account=None):
    return get_account_instance(_resolve_account(account, "get_contacts_controller")) \
        .getContactsController()


def get_media_data_controller(account=None):
    return get_account_instance(_resolve_account(account, "get_media_data_controller")) \
        .getMediaDataController()


def get_connections_manager(account=None):
    return get_account_instance(_resolve_account(account, "get_connections_manager")) \
        .getConnectionsManager()


def get_location_controller(account=None):
    return get_account_instance(_resolve_account(account, "get_location_controller")) \
        .getLocationController()


def get_notifications_controller(account=None):
    return get_account_instance(_resolve_account(account, "get_notifications_controller")) \
        .getNotificationsController()


def get_messages_storage(account=None):
    return get_account_instance(_resolve_account(account, "get_messages_storage")) \
        .getMessagesStorage()


def get_send_messages_helper(account=None):
    return get_account_instance(_resolve_account(account, "get_send_messages_helper")) \
        .getSendMessagesHelper()


def get_file_loader(account=None):
    return get_account_instance(_resolve_account(account, "get_file_loader")).getFileLoader()


def get_secret_chat_helper(account=None):
    return get_account_instance(_resolve_account(account, "get_secret_chat_helper")) \
        .getSecretChatHelper()


def get_download_controller(account=None):
    return get_account_instance(_resolve_account(account, "get_download_controller")) \
        .getDownloadController()


def get_notifications_settings(account=None):
    return get_account_instance(_resolve_account(account, "get_notifications_settings")) \
        .getNotificationsSettings()


def get_notification_center(account=None):
    return get_account_instance(_resolve_account(account, "get_notification_center")) \
        .getNotificationCenter()


def get_media_controller():
    """Global singleton — intentionally has no account parameter."""
    return _jclass("org.telegram.messenger.MediaController").getInstance()


def get_user_config(account=None):
    return get_account_instance(_resolve_account(account, "get_user_config")).getUserConfig()


# Sending messages

def _to_array_list(items):
    array_list = _jclass("java.util.ArrayList")()
    for item in items:
        array_list.add(item)
    return array_list


def _apply_parse_mode(params, field: str, text, parse_mode):
    """Replace params.message/params.caption with parsed text + entities."""
    if not parse_mode or text is None:
        return
    from extera_utils.text_formatting import parse_text

    parsed = parse_text(str(text), parse_mode, is_caption=(field == "caption"))
    plain = parsed.get(field, str(text))
    setattr(params, field, plain)
    entities = parsed.get("entities") or []
    if entities:
        params.entities = _to_array_list(entities)


def _parse_caption(caption, parse_mode):
    """-> (caption_str | None, entities ArrayList | None) for prepareSending*."""
    if caption is None:
        return None, None
    if not parse_mode:
        return str(caption), None
    from extera_utils.text_formatting import parse_text

    parsed = parse_text(str(caption), parse_mode, is_caption=True)
    plain = parsed.get("caption", str(caption))
    entities = parsed.get("entities") or []
    return plain, (_to_array_list(entities) if entities else None)


def _new_text_params(peer_id, text, parse_mode=None):
    SendMessageParams = _jclass(
        "org.telegram.messenger.SendMessagesHelper$SendMessageParams")
    params = SendMessageParams.of(str(text), int(peer_id))
    _apply_parse_mode(params, "message", text, parse_mode)
    return params


def send_text(peer_id, text, replyToMsg=None, parse_mode=None, account=None):
    """Send a text message to *peer_id*.

    replyToMsg must be a MessageObject; raw integer message ids cannot be
    resolved without a dialog context in this build and are ignored with a
    warning. parse_mode is 'HTML' or 'Markdown'.
    """
    _require("messages.send", "send_text")
    params = _new_text_params(peer_id, text, parse_mode)
    if replyToMsg is not None:
        if hasattr(replyToMsg, "getId") or hasattr(replyToMsg, "messageOwner"):
            params.replyToMsg = replyToMsg
        else:
            _log("send_text: replyToMsg must be a MessageObject; "
                 "integer ids are not resolvable in this build — ignored")
    _send_on_ui_thread(lambda: get_send_messages_helper(
        _resolve_account(account, "send_text")).sendMessage(params))


def send_message(params: dict, parse_mode=None, account=None):
    """Send a message from a dict of SendMessageParams fields.

    Recognized keys: peer, message, caption, replyToMsg, replyToTopMsg,
    replyMarkup, notify, scheduleDate, ttl, hasMediaSpoilers,
    sendingHighQuality, path, photo, document, params, searchLinks.
    Unknown keys are ignored with a warning.
    """
    _require("messages.send", "send_message")
    SendMessageParams = _jclass(
        "org.telegram.messenger.SendMessagesHelper$SendMessageParams")

    params = dict(params or {})
    peer = params.pop("peer", params.pop("peer_id", 0))
    message = params.pop("message", None)
    caption = params.pop("caption", None)

    base_text = message if message is not None else (caption or "")
    send_params = SendMessageParams.of(str(base_text), int(peer))
    if message is not None:
        _apply_parse_mode(send_params, "message", message, parse_mode)
    if caption is not None:
        send_params.caption = str(caption)
        _apply_parse_mode(send_params, "caption", caption, parse_mode)

    for key, value in params.items():
        if value is None:
            continue
        # Списковые поля SendMessageParams — java.util.ArrayList. Питоновский
        # список Chaquopy в него не превращает, setattr падал, а ошибка уходила
        # в лог, и сообщение отправлялось без форматирования: плагин строит
        # entities сам и кладёт их обычным list'ом.
        if isinstance(value, (list, tuple)):
            value = _to_array_list(value)
        try:
            setattr(send_params, key, value)
        except Exception as exc:
            _log(f"send_message: cannot set params key {key!r}: {exc}")

    _send_on_ui_thread(lambda: get_send_messages_helper(
        _resolve_account(account, "send_message")).sendMessage(send_params))


def _on_ui_thread(fn):
    from android_utils import run_on_ui_thread
    run_on_ui_thread(fn)


def _is_ui_thread() -> bool:
    try:
        Looper = _jclass("android.os.Looper")
        Thread = _jclass("java.lang.Thread")
        return Looper.getMainLooper().getThread() == Thread.currentThread()
    except Exception:
        return False


def _send_on_ui_thread(fn):
    if _is_ui_thread():
        fn()
    else:
        _on_ui_thread(fn)


def send_photo(peer_id, path, caption=None, high_quality=False, parse_mode=None,
               replyToMsg=None, account=None):
    """Send a photo file to *peer_id* (high_quality=True sends it as a document).

    Uses SendMessagesHelper.prepareSendingPhoto (24-arg overload:
    accountInstance, imageFilePath, thumbFilePath, imageUri, dialogId,
    replyToMsg, replyToTopMsg, storyItem, quote, entities, stickers,
    inputContent, ttl, editingMessageObject, videoEditedInfo, notify,
    scheduleDate, mode, forceDocument, caption, quickReplyShortcut,
    quickReplyShortcutId, effectId, payStars).
    """
    _require("messages.send", "send_photo")
    resolved = _resolve_account(account, "send_photo")
    caption_str, entities = _parse_caption(caption, parse_mode)

    def _send():
        _jclass("org.telegram.messenger.SendMessagesHelper").prepareSendingPhoto(
            get_account_instance(resolved), str(path), None, None, int(peer_id),
            replyToMsg, None, None, None, entities, None, None, 0, None, None,
            True, 0, 0, bool(high_quality), caption_str, None, 0, 0, 0)

    _on_ui_thread(_send)


def send_video(peer_id, path, caption=None, parse_mode=None,
               replyToMsg=None, account=None):
    """Send a video file to *peer_id*.

    Uses SendMessagesHelper.prepareSendingVideo (23-arg overload:
    accountInstance, videoPath, info, coverPath, coverPhoto, dialogId,
    replyToMsg, replyToTopMsg, storyItem, quote, entities, ttl,
    editingMessageObject, notify, scheduleDate, scheduleRepeatPeriod,
    forceDocument, hasMediaSpoilers, caption, quickReplyShortcut,
    quickReplyShortcutId, effectId, stars).
    """
    _require("messages.send", "send_video")
    resolved = _resolve_account(account, "send_video")
    caption_str, entities = _parse_caption(caption, parse_mode)

    def _send():
        _jclass("org.telegram.messenger.SendMessagesHelper").prepareSendingVideo(
            get_account_instance(resolved), str(path), None, None, None, int(peer_id),
            replyToMsg, None, None, None, entities, 0, None, True, 0, 0, False,
            False, caption_str, None, 0, 0, 0)

    _on_ui_thread(_send)


def _send_document_like(peer_id, path, caption, parse_mode, replyToMsg, resolved,
                        mime, helper_name):
    # Одна проверка на send_document/send_audio: обе идут сюда.
    _require("messages.send", helper_name)
    caption_str, entities = _parse_caption(caption, parse_mode)

    def _send():
        # prepareSendingDocument(accountInstance, path, originalPath, uri,
        #   caption, mime, dialogId, replyToMsg, replyToTopMsg, storyItem,
        #   quote, editingMessageObject, notify, scheduleDate, inputContent,
        #   quickReplyShortcut, quickReplyShortcutId, invertMedia)
        #
        # caption entities cannot ride along in this overload (the plural
        # prepareSendingDocuments variants carrying them are ambiguous from
        # Python), so parsed captions fall back to plain text here.
        _jclass("org.telegram.messenger.SendMessagesHelper").prepareSendingDocument(
            get_account_instance(resolved), str(path), str(path), None,
            caption_str, mime, int(peer_id), replyToMsg, None, None, None, None,
            True, 0, None, None, 0, False)

    if entities is not None:
        _log(f"{helper_name}: parse_mode entities are not supported for "
             "documents in this build — sending plain caption")
    _on_ui_thread(_send)


def send_document(peer_id, path, caption=None, parse_mode=None,
                  replyToMsg=None, account=None):
    """Send an arbitrary file as a document to *peer_id*."""
    _send_document_like(peer_id, path, caption, parse_mode, replyToMsg,
                        _resolve_account(account, "send_document"), None,
                        "send_document")


def send_audio(peer_id, path, caption=None, parse_mode=None,
               replyToMsg=None, account=None):
    """Send an audio file to *peer_id*.

    There is no path-based audio sender in this tree
    (prepareSendingAudioDocuments takes existing MessageObjects), so audio
    goes through prepareSendingDocument with an audio/* mime type — the
    internal pipeline detects mp3/m4a/opus/ogg/flac and attaches audio
    attributes (duration/performer/title) itself.
    """
    import mimetypes

    mime, _ = mimetypes.guess_type(str(path))
    if mime is None or not mime.startswith("audio/"):
        mime = "audio/mpeg"
    _send_document_like(peer_id, path, caption, parse_mode, replyToMsg,
                        _resolve_account(account, "send_audio"), mime,
                        "send_audio")


def edit_message(message_obj, text=None, file_path=None, with_spoiler=False,
                 parse_mode=None, account=None):
    """Edit a message's text in place; returns the ConnectionsManager request id.

    Text edit uses the real SendMessagesHelper.editMessage(MessageObject,
    String, boolean searchLinks, BaseFragment, ArrayList<MessageEntity>,
    int scheduleDate, int scheduleRepeatPeriod). The fragment argument is
    the currently visible BaseFragment (get_last_fragment()).

    Media replacement (file_path=...) is NOT available: the media
    editMessage overloads operate on already-uploaded TLRPC.TL_photo /
    TL_document objects, and this tree has no path-based media-edit entry
    point to build them from a raw file.
    """
    _require("messages.send", "edit_message")
    resolved = _resolve_account(account, "edit_message")
    if file_path is not None:
        raise NotImplementedError(
            "edit_message(file_path=...) media edit is not available in this "
            "build of exteraless: SendMessagesHelper.editMessage media overloads "
            "require pre-built TLRPC.TL_photo/TL_document objects, not a raw path")
    if text is None:
        raise ValueError("edit_message requires text= or file_path=")
    if with_spoiler:
        _log("edit_message: with_spoiler only applies to media edits — ignored")

    entities = None
    message = str(text)
    if parse_mode:
        from extera_utils.text_formatting import parse_text

        parsed = parse_text(message, parse_mode)
        message = parsed.get("message", message)
        raw_entities = parsed.get("entities") or []
        if raw_entities:
            entities = _to_array_list(raw_entities)

    fragment = get_last_fragment()
    if fragment is None:
        raise RuntimeError(
            "edit_message: no visible BaseFragment — the real editMessage API "
            "requires one and returns 0 without it")
    return int(get_send_messages_helper(resolved).editMessage(
        message_obj, message, True, fragment, entities, 0, 0))


# AccountClient (PLUGINS-API.md §4.1)

class AccountClient:
    """Per-account view of the client_utils helpers.

    ``AccountClient(None)`` follows the current hook scope (then the
    UI-selected account); ``AccountClient(3)`` is pinned to account 3.
    Calling an instance — ``client(account)`` — returns a pinned client for
    that account, which is what makes ``self.client(account)`` on BasePlugin
    work while ``self.client`` stays a property.
    """

    def __init__(self, account=None):
        self._account = None if account is None else int(account)

    def __call__(self, account=None) -> "AccountClient":
        return AccountClient(account)

    @property
    def account(self) -> int:
        """The resolved account this client currently targets."""
        return _resolve_scoped_account(self._account)

    # -- controllers --

    def get_account_instance(self):
        return get_account_instance(self.account)

    def get_messages_controller(self):
        return get_messages_controller(self.account)

    def get_contacts_controller(self):
        return get_contacts_controller(self.account)

    def get_media_data_controller(self):
        return get_media_data_controller(self.account)

    def get_connections_manager(self):
        return get_connections_manager(self.account)

    def get_location_controller(self):
        return get_location_controller(self.account)

    def get_notifications_controller(self):
        return get_notifications_controller(self.account)

    def get_messages_storage(self):
        return get_messages_storage(self.account)

    def get_send_messages_helper(self):
        return get_send_messages_helper(self.account)

    def get_file_loader(self):
        return get_file_loader(self.account)

    def get_secret_chat_helper(self):
        return get_secret_chat_helper(self.account)

    def get_download_controller(self):
        return get_download_controller(self.account)

    def get_notifications_settings(self):
        return get_notifications_settings(self.account)

    def get_notification_center(self):
        return get_notification_center(self.account)

    def get_user_config(self):
        return get_user_config(self.account)

    def get_media_controller(self):
        return get_media_controller()

    def get_last_fragment(self):
        return get_last_fragment()

    # -- requests / queues --

    def send_request(self, request, fn) -> int:
        return send_request(request, fn, account=self.account)

    def run_on_queue(self, fn, queue: str = PLUGINS_QUEUE, delay: int = 0,
                     delay_ms: int = None):
        return run_on_queue(fn, queue=queue, delay=delay, delay_ms=delay_ms)

    # -- sending --

    def send_text(self, peer_id, text, replyToMsg=None, parse_mode=None):
        return send_text(peer_id, text, replyToMsg=replyToMsg,
                         parse_mode=parse_mode, account=self.account)

    def send_message(self, params: dict, parse_mode=None):
        return send_message(params, parse_mode=parse_mode, account=self.account)

    def send_photo(self, peer_id, path, caption=None, high_quality=False,
                   parse_mode=None, replyToMsg=None):
        return send_photo(peer_id, path, caption=caption, high_quality=high_quality,
                          parse_mode=parse_mode, replyToMsg=replyToMsg,
                          account=self.account)

    def send_video(self, peer_id, path, caption=None, parse_mode=None, replyToMsg=None):
        return send_video(peer_id, path, caption=caption, parse_mode=parse_mode,
                          replyToMsg=replyToMsg, account=self.account)

    def send_document(self, peer_id, path, caption=None, parse_mode=None, replyToMsg=None):
        return send_document(peer_id, path, caption=caption, parse_mode=parse_mode,
                             replyToMsg=replyToMsg, account=self.account)

    def send_audio(self, peer_id, path, caption=None, parse_mode=None, replyToMsg=None):
        return send_audio(peer_id, path, caption=caption, parse_mode=parse_mode,
                          replyToMsg=replyToMsg, account=self.account)

    def edit_message(self, message_obj, text=None, file_path=None,
                     with_spoiler=False, parse_mode=None):
        return edit_message(message_obj, text=text, file_path=file_path,
                            with_spoiler=with_spoiler, parse_mode=parse_mode,
                            account=self.account)


# Re-exports: some plugins reach for these through client_utils rather than
# android_utils, and an ImportError at module level kills the whole plugin.
from android_utils import log, run_on_ui_thread  # noqa: E402,F401

# Alias used by some plugins for the same "topmost visible fragment" lookup.
get_current_fragment = get_last_fragment


def get_client(account=None) -> AccountClient:
    """AccountClient for *account* (None: follow hook scope, then UI selection)."""
    return AccountClient(account)


# NotificationCenter

class NotificationCenterDelegate:
    """Python base for NotificationCenter.NotificationCenterDelegate.

    Subclass it and override didReceivedNotification(id, account, args).
    Pass the `.java` proxy to Java APIs, or use start_observing().

    NOTE: the hook-account scope does NOT propagate into
    didReceivedNotification — bind explicitly with get_client(account)
    (PLUGINS-API.md §4.1).
    """

    def didReceivedNotification(self, notification_id, account, args):
        """Override in a subclass. `args` is a Java Object[] array."""

    def _create_proxy(self):
        from java import dynamic_proxy

        interface = _jclass(
            "org.telegram.messenger.NotificationCenter$NotificationCenterDelegate")
        outer = self

        class _Proxy(dynamic_proxy(interface)):
            def didReceivedNotification(self, notification_id, account, args):
                outer.didReceivedNotification(notification_id, account, args)

        return _Proxy()

    @property
    def java(self):
        """The Java-side proxy of this delegate (created lazily)."""
        proxy = self.__dict__.get("_java_proxy")
        if proxy is None:
            proxy = self._create_proxy()
            self.__dict__["_java_proxy"] = proxy
        return proxy

    def start_observing(self, notification_id: int, account=None):
        """addObserver(self) on the account's NotificationCenter."""
        get_notification_center(account).addObserver(self.java, int(notification_id))
        return self

    def stop_observing(self, notification_id: int, account=None):
        """removeObserver(self) on the account's NotificationCenter."""
        get_notification_center(account).removeObserver(self.java, int(notification_id))
        return self
