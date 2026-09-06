import ast
import contextlib
import importlib.util
import json
import re
import sys
import types
from pathlib import Path

import pytest

import corpus
import javaapi


def load_module(monkeypatch, name):
    path = Path(corpus.PYTHON_ROOT, *name.split('.')).with_suffix('.py')
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, name, module)
    if '.' in name:
        parent, leaf = name.rsplit('.', 1)
        if parent in sys.modules:
            monkeypatch.setattr(sys.modules[parent], leaf, module, raising=False)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def sdk(monkeypatch):
    monkeypatch.syspath_prepend(corpus.PYTHON_ROOT)
    package = types.ModuleType('extera_utils')
    package.__path__ = [str(Path(corpus.PYTHON_ROOT, 'extera_utils'))]
    monkeypatch.setitem(sys.modules, 'extera_utils', package)
    loader = types.ModuleType('extera_utils.plugin_loader')
    loader.plugin_frame_owner = lambda: 'test_plugin'
    loader.java_runtime_mark = lambda owner: contextlib.nullcontext()
    monkeypatch.setitem(sys.modules, loader.__name__, loader)
    package.plugin_loader = loader
    android = types.ModuleType('android_utils')
    android.safe_call = lambda fn, *args: fn(*args)
    android.log = lambda *args: None
    android.run_on_ui_thread = lambda fn, *args: fn()

    def forbidden_proxy(*args, **kwargs):
        raise AssertionError('A Python DynamicProxy reached a Java callback path')

    android.R = forbidden_proxy
    monkeypatch.setitem(sys.modules, 'android_utils', android)
    import ui
    return types.SimpleNamespace(client=load_module(monkeypatch, 'client_utils'),
                                 base=load_module(monkeypatch, 'base_plugin'),
                                 settings=load_module(monkeypatch, 'ui.settings'),
                                 android=android, package=package)


@pytest.fixture
def loader(sdk, monkeypatch):
    pip = types.ModuleType('pip_controller')
    pip.restore_sys_path = lambda: None
    monkeypatch.setitem(sys.modules, 'pip_controller', pip)
    path = Path(corpus.PYTHON_ROOT, 'extera_utils/plugin_loader.py')
    tree = ast.parse(path.read_text())
    tree.body = [node for node in tree.body if not (
        isinstance(node, ast.Expr) and isinstance(node.value, ast.Call)
        and isinstance(node.value.func, ast.Name) and node.value.func.id == '_install_sandbox')]
    spec = importlib.util.spec_from_file_location('extera_utils.plugin_loader', path)
    module = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, module.__name__, module)
    sdk.package.plugin_loader = module
    exec(compile(tree, str(path), 'exec'), module.__dict__)
    return module


def test_requests_keep_the_java_delegate_path(sdk, monkeypatch):
    sent, received = [], []
    monkeypatch.setattr(sdk.client, '_require', lambda *args: None)
    monkeypatch.setattr(sdk.client, 'RequestCallback', sdk.android.R)
    services = types.SimpleNamespace(sendRequest=lambda *args: (sent.append(args), 99)[1])
    monkeypatch.setattr(sdk.client, '_plugin_services', lambda: services)
    request = object()
    token = sdk.client.send_request(request, lambda response, error: received.append(
        (response, error, sdk.client.get_hook_account())), account=2)
    assert token == 99 and sent[0][:2] == (2, request)
    sent[0][2]('response', None)
    assert received == [('response', None, 2)]
    assert sdk.client.get_hook_account() is None


def test_background_tasks_keep_the_java_runnable_path(sdk, monkeypatch):
    queued, calls = [], []
    queue = object()
    monkeypatch.setattr(sdk.client, 'get_queue_by_name', lambda name: queue)
    monkeypatch.setattr(sdk.client, '_plugin_services', lambda: types.SimpleNamespace(
        postRunnable=lambda *args: queued.append(args)))
    with sdk.client.hook_scope(3):
        result = sdk.client.run_on_queue(lambda: calls.append(sdk.client.get_hook_account()), delay=10)
    assert result is queue and queued[0][0] is queue and queued[0][2] == 10
    queued[0][1]()
    assert calls == [3]


def test_text_sending_is_still_marshaled_to_the_ui_thread(sdk, monkeypatch):
    queued, sent = [], []
    params = object()
    monkeypatch.setattr(sdk.client, '_require', lambda *args: None)
    monkeypatch.setattr(sdk.client, '_new_text_params', lambda *args: params)
    monkeypatch.setattr(sdk.client, '_send_on_ui_thread', queued.append)
    monkeypatch.setattr(sdk.client, 'get_send_messages_helper', lambda account: types.SimpleNamespace(sendMessage=sent.append))
    sdk.client.send_text(123, 'text', account=1)
    assert sent == [] and len(queued) == 1
    queued[0]()
    assert sent == [params]


def test_bulletin_buttons_keep_java_owned_runnables(sdk, monkeypatch):
    native = types.ModuleType('app.exteraless.plugins')
    runnable = object()
    native.PluginServices = types.SimpleNamespace(runnable=lambda fn: runnable)
    monkeypatch.setitem(sys.modules, native.__name__, native)
    bulletin = load_module(monkeypatch, 'ui.bulletin')
    calls = []
    monkeypatch.setattr(bulletin, '_show', lambda make, fallback: make())
    monkeypatch.setattr(bulletin, '_factory', lambda fragment: types.SimpleNamespace(createSimpleBulletin=lambda *args: calls.append(args)))
    bulletin.BulletinHelper.show_with_button('text', 1, 'button', lambda: None)
    assert calls[0][-1] is runnable


def test_slider_preserves_its_range_in_settings_json(sdk, loader):
    slider = sdk.settings.Slider('alpha', 'Alpha', default=5, min=0, max=10, step=2)
    assert slider.normalize(5) == 6
    assert slider.normalize(100) == 10
    assert slider.normalize(float('nan')) == 6
    with pytest.raises(ValueError):
        sdk.settings.Slider('bad', 'Bad', min=10, max=0)
    record = loader.PluginRecord(None, sdk.base.BasePlugin(), '')
    row = loader._serialize_setting_item(slider, record)
    assert row['type'] == 'slider' and row['value'] == 6 and row['step'] == 2


def test_short_menu_form_retains_the_callback(sdk, monkeypatch):
    monkeypatch.setattr(sdk.base, 'PythonBridge', None)
    plugin = sdk.base.BasePlugin()
    callback = lambda context: None
    assert plugin.add_menu_item(plugin.MenuType.CHAT_CONTEXT, 'Action', on_click=callback, item_id='action') == 'action'
    assert plugin._menu_callbacks['action'] is callback
    assert plugin.MenuType.CHAT_CONTEXT == sdk.base.MenuItemType.MESSAGE_CONTEXT_MENU
    with pytest.raises(TypeError):
        plugin.add_menu_item(sdk.base.MenuItemData(plugin.MenuType.CHAT_CONTEXT, 'Action', callback), text='conflict')


@pytest.mark.parametrize('field', ['params', 'request', 'response', 'update', 'updates'])
def test_generic_hook_result_keeps_the_replacement(sdk, loader, field):
    replacement = object()
    result = loader._dispatch_hook('test_plugin', 2,
                                  lambda: sdk.base.HookResult(sdk.base.HookStrategy.MODIFY_FINAL, result=replacement),
                                  result_field=field)
    assert result.strategy == sdk.base.HookStrategy.MODIFY_FINAL
    assert result.value is replacement
    assert sdk.base.HookStrategy.NONE == sdk.base.HookStrategy.DEFAULT


def test_specific_hook_result_has_precedence(sdk, loader):
    replacement, fallback = object(), object()
    result = loader._dispatch_hook('test_plugin', 0,
                                  lambda: sdk.base.HookResult(sdk.base.HookStrategy.MODIFY, request=replacement, result=fallback),
                                  result_field='request')
    assert result.value is replacement


def test_media_edit_uses_the_existing_ui_dispatcher(sdk, monkeypatch, tmp_path):
    path = tmp_path / 'image.jpg'
    path.write_bytes(b'image')
    queued, sent = [], []
    message = object()
    monkeypatch.setattr(sdk.client, '_require', lambda *args: None)
    monkeypatch.setitem(sys.modules, 'file_utils', types.SimpleNamespace(_require_files=lambda *args: None))
    monkeypatch.setattr(sdk.client, '_send_on_ui_thread', queued.append)
    monkeypatch.setattr(sdk.client, '_media_services', lambda: types.SimpleNamespace(editMedia=lambda *args: sent.append(args)))
    assert sdk.client.edit_message(message, file_path=path, with_spoiler=True, account=2) is None
    assert sent == []
    queued[0]()
    assert sent == [(2, message, str(path), None, None, True)]
    with pytest.raises(FileNotFoundError):
        sdk.client.edit_message(message, file_path=tmp_path / 'absent', account=2)


def test_document_preparation_checks_file_access(sdk, monkeypatch):
    seen, document = [], object()
    monkeypatch.setitem(sys.modules, 'file_utils', types.SimpleNamespace(_require_files=lambda *args: seen.append(args)))
    monkeypatch.setattr(sdk.client, '_media_services', lambda: types.SimpleNamespace(prepareDocument=lambda path: document))
    assert sdk.client._prepare_document('/plugin/export.json') is document
    assert seen == [('/plugin/export.json', 'prepare document')]


def test_temporary_exports_do_not_overwrite_each_other(sdk, monkeypatch, tmp_path):
    monkeypatch.setattr(sdk.client._LocalFileSystem, 'tempdir', classmethod(lambda cls: str(tmp_path)))
    first = sdk.client._LocalFileSystem.write_temp_file('export.plugin', b'first')
    second = sdk.client._LocalFileSystem.write_temp_file('export.plugin', b'second')
    assert first != second
    assert Path(first).read_bytes() == b'first' and Path(second).read_bytes() == b'second'


def test_progress_style_keeps_the_current_dialog_builder(sdk, monkeypatch):
    alert = load_module(monkeypatch, 'ui.alert')
    created = []
    monkeypatch.setattr(alert, '_jclass', lambda name: lambda *args: created.append(args))
    monkeypatch.setattr(alert, '_run_sync', lambda fn: fn())
    context, provider = object(), object()
    alert.AlertDialogBuilder(context, resources_provider=provider, progress_style=3)
    assert created == [(context, 3, provider)]
    with pytest.raises(TypeError):
        alert.AlertDialogBuilder(context, alert_type=2, progress_style=3)


def test_text_setting_has_both_eight_argument_layouts():
    source = Path(corpus.JAVA_ROOT, 'app/exteraless/plugins/models/TextSetting.java').read_text()
    constructors = re.findall(r'public TextSetting\((.*?)\)\s*\{', javaapi.strip_noise(source), re.S)
    types = [tuple(param.strip().split()[0] for param in args.split(',')) for args in constructors]
    assert ('String', 'String', 'boolean', 'boolean', 'PyObject', 'PyObject', 'PyObject', 'String') in types
    assert ('String', 'String', 'String', 'boolean', 'boolean', 'PyObject', 'PyObject', 'PyObject') in types


def test_media_and_message_sinks_keep_nx_chat_arguments():
    media = Path(corpus.JAVA_ROOT, 'app/exteraless/plugins/PluginMediaServices.java').read_text()
    sending = Path(corpus.JAVA_ROOT, 'org/telegram/messenger/SendMessagesHelper.java').read_text()
    assert 'SendMessageChatArguments.EMPTY' in media
    assert 'DeletedReplyQuote.rewrite(currentAccount, sendMessageParams)' in sending
    assert 'sendMessageParams.sendMessageChatArguments' in sending
    invocation = re.search(r'SendMessagesHelper\.prepareSendingMedia\((.*?)\);', media, re.S)
    arguments = javaapi.split_params(invocation.group(1))
    declarations = re.findall(r'public static void prepareSendingMedia\(([^\n]+)\)\s*\{', sending)
    signatures = [javaapi.param_types(params) for params in declarations]
    assert arguments[16].strip() == 'SendMessageChatArguments.EMPTY'
    assert any(len(signature) == len(arguments) and signature[16] == 'SendMessageChatArguments'
               for signature in signatures)


def settings_record(sdk, loader, monkeypatch, items):
    plugin = sdk.base.BasePlugin()
    plugin.create_settings = lambda: items
    record = loader.PluginRecord(None, plugin, '')
    monkeypatch.setitem(loader.plugins, 'test_plugin', record)
    return record


def test_uitweaks_chats_category_and_group_have_separate_rows(sdk, loader, monkeypatch):
    calls = []
    items = [
        sdk.settings.Text('Chats', icon='msg_msgbubble3_solar', link_alias='chats',
                          create_sub_fragment=lambda: [sdk.settings.Header('Chat settings')]),
        sdk.settings.Text('Chats', icon='msg_groups_solar', on_click=lambda view: calls.append(view)),
    ]
    settings_record(sdk, loader, monkeypatch, items)
    category, group = json.loads(loader.get_settings_json('test_plugin'))
    assert category['row_id'] != group['row_id']
    assert category['link_alias'] == 'chats'
    assert category['sub_page'][0]['text'] == 'Chat settings'
    assert 'callback_id' not in category and 'sub_page' not in group
    loader.dispatch_setting_click('test_plugin', group['callback_id'], 'group')
    assert calls == ['group']


def test_identical_labels_keep_each_click_and_long_click(sdk, loader, monkeypatch):
    calls = []
    items = [sdk.settings.Text('Chats', on_click=lambda view, i=i: calls.append(('click', i)),
                               on_long_click=lambda view, i=i: calls.append(('long', i)))
             for i in range(3)]
    settings_record(sdk, loader, monkeypatch, items)
    rows = json.loads(loader.get_settings_json('test_plugin'))
    assert len({row['row_id'] for row in rows}) == 3
    for row in rows:
        loader.dispatch_setting_click('test_plugin', row['callback_id'])
        loader.dispatch_setting_click('test_plugin', row['long_callback_id'])
    assert calls == [('click', 0), ('long', 0), ('click', 1), ('long', 1), ('click', 2), ('long', 2)]


def test_duplicate_page_titles_keep_their_child_callbacks(sdk, loader, monkeypatch):
    calls = []
    items = [sdk.settings.Text('Chats', create_sub_fragment=lambda i=i: [
        sdk.settings.Text('Open', on_click=lambda view: calls.append(i))]) for i in range(2)]
    settings_record(sdk, loader, monkeypatch, items)
    rows = json.loads(loader.get_settings_json('test_plugin'))
    assert rows[0]['row_id'] != rows[1]['row_id']
    for row in rows:
        loader.dispatch_setting_click('test_plugin', row['sub_page'][0]['callback_id'])
    assert calls == [0, 1]


def test_row_callbacks_survive_unrelated_insertion_and_alias_translation(sdk, loader, monkeypatch):
    calls = []
    first = sdk.settings.Text('Chats', link_alias='chat_settings', on_click=lambda view: calls.append('settings'))
    second = sdk.settings.Text('Chats', on_click=lambda view: calls.append('group'))
    items = [first, second]
    settings_record(sdk, loader, monkeypatch, items)
    before = json.loads(loader.get_settings_json('test_plugin'))
    items.insert(0, sdk.settings.Header('New section'))
    first.text = 'Чаты'
    after = json.loads(loader.get_settings_json('test_plugin'))[1:]
    assert [row['row_id'] for row in before] == [row['row_id'] for row in after]
    assert [row['callback_id'] for row in before] == [row['callback_id'] for row in after]
    for row in before:
        loader.dispatch_setting_click('test_plugin', row['callback_id'])
    assert calls == ['settings', 'group']


def test_custom_rows_with_the_same_alias_keep_their_views(sdk, loader, monkeypatch):
    items = [sdk.settings.Custom(view=object(), link_alias='same') for _ in range(2)]
    record = settings_record(sdk, loader, monkeypatch, items)
    rows = json.loads(loader.get_settings_json('test_plugin'))
    assert rows[0]['row_id'] != rows[1]['row_id']
    assert rows[0]['view_id'] != rows[1]['view_id']
    assert [record.custom_views[row['view_id']] for row in rows] == items


def test_native_text_settings_share_the_unique_identity_contract(sdk, loader, monkeypatch):
    calls = []
    items = [types.SimpleNamespace(getClass=lambda: object, getType=lambda: 'text',
                                  getText=lambda: 'Chats',
                                  getOnClickCallback=lambda i=i: lambda view: calls.append(i))
             for i in range(2)]
    settings_record(sdk, loader, monkeypatch, items)
    rows = json.loads(loader.get_settings_json('test_plugin'))
    assert rows[0]['row_id'] != rows[1]['row_id']
    for row in rows:
        loader.dispatch_setting_click('test_plugin', row['callback_id'])
    assert calls == [0, 1]


def test_android_settings_use_serialized_identity_for_rows_and_subpages():
    source = Path(corpus.JAVA_ROOT, 'app/exteraless/plugins/ui/PluginSettingsActivity.java').read_text()
    row_id = source[source.index('private int rowId('):source.index('private UItem toUItem(')]
    assert 'optNonEmpty(item, "row_id")' in row_id
    assert 'rowIds.get(identity)' in row_id and 'rowIds.put(identity, id)' in row_id
    assert 'hashCode()' not in row_id
    assert 'owner.equals(subPageOwner(obj))' in source
    assert 'ownersTo(null)' not in source
    assert 'targetSetting.equals(optNonEmpty(row, "link_alias"))' in source
