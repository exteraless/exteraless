import ast
import builtins
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


@pytest.mark.parametrize('entry', ['directory', 'settings'])
def test_java_bridge_import_does_not_reenter_plugin_directory(sdk, loader, monkeypatch,
                                                            tmp_path, entry):
    files = load_module(monkeypatch, 'file_utils')
    native = types.ModuleType('app.exteraless.plugins')
    calls = []
    directory = str(tmp_path)
    native.PythonBridge = types.SimpleNamespace(
        getPluginsDir=lambda: (calls.append(directory), directory)[1])
    original = builtins.__import__

    def java_import(name, globals=None, locals=None, fromlist=(), level=0):
        if name == native.__name__:
            return native
        return original(name, globals, locals, fromlist, level)

    monkeypatch.delitem(sys.modules, 'app', raising=False)
    monkeypatch.setattr(loader, '_original_import', java_import)
    monkeypatch.setattr(loader, '_unsafe_mode', False)
    plugin = sdk.base.BasePlugin()
    plugin.create_settings = lambda: [sdk.settings.Header(files.get_plugins_dir())]
    monkeypatch.setitem(loader.plugins, 'test_plugin', loader.PluginRecord(None, plugin, ''))
    with monkeypatch.context() as imports:
        imports.setattr(builtins, '__import__', loader._sandboxed_import)
        if entry == 'directory':
            result = files.get_plugins_dir()
        else:
            result = json.loads(loader.get_settings_json('test_plugin'))[0]['text']
    assert result == directory
    assert calls == [directory]


@pytest.mark.parametrize('name', ['app.exteraless.plugins', 'dalvik.system', 'kotlinx.coroutines'])
def test_import_module_java_packages_does_not_probe_neighbour_plugins(loader, monkeypatch, name):
    module = types.ModuleType(name)
    probes = []
    monkeypatch.delitem(sys.modules, name.partition('.')[0], raising=False)
    monkeypatch.setattr(loader, '_unsafe_mode', False)
    monkeypatch.setattr(loader, '_plugins_dir_path', lambda: probes.append(name))
    monkeypatch.setattr(loader, '_original_import_module', lambda *args: module)
    assert loader._sandboxed_import_module(name) is module
    assert probes == []


def test_library_imported_by_an_earlier_plugin_sees_no_importer_frames(loader, monkeypatch, tmp_path):
    (tmp_path / 'shared_library.py').write_text(
        'import os\n'
        'import sys\n'
        '\n'
        '\n'
        'def _owner():\n'
        '    index = 0\n'
        '    while True:\n'
        '        index += 1\n'
        '        try:\n'
        '            path = sys._getframe(index).f_code.co_filename\n'
        '        except ValueError:\n'
        '            return "shared_library"\n'
        '        if os.path.dirname(path) == os.path.dirname(__file__) and path != __file__:\n'
        '            return os.path.splitext(os.path.basename(path))[0]\n'
        '\n'
        '\n'
        'OWNER = _owner()\n')
    consumer = tmp_path / 'consumer_plugin.py'
    consumer.write_text(
        '__id__ = "consumer_plugin"\n'
        '__name__ = "Consumer"\n'
        '\n'
        'from base_plugin import BasePlugin\n'
        '\n'
        'try:\n'
        '    import shared_library\n'
        'except Exception:\n'
        '    shared_library = None\n'
        '\n'
        '\n'
        'class ConsumerPlugin(BasePlugin):\n'
        '    pass\n')
    for name in ('consumer_plugin', 'shared_library'):
        monkeypatch.setitem(sys.modules, name, None)
        monkeypatch.delitem(sys.modules, name)
    monkeypatch.setattr(sys, 'path', [*sys.path])
    monkeypatch.setattr(loader, '_install_sandbox', lambda: None)
    monkeypatch.setattr(loader, '_plugins_dir_path', lambda: str(tmp_path))
    result = json.loads(loader.load_plugin(str(consumer), 'consumer_plugin'))
    assert result['ok'], result['error']
    assert sys.modules['shared_library'].OWNER == 'shared_library'


def test_loaded_plugin_instance_carries_its_metadata(loader, monkeypatch, tmp_path):
    plugin = tmp_path / 'described_plugin.py'
    plugin.write_text(
        '__id__ = "described_plugin"\n'
        '__name__ = "Described"\n'
        '__description__ = "Keeps its metadata"\n'
        '__author__ = "@someone"\n'
        '__version__ = "2.1b"\n'
        '__icon__ = "pack/3"\n'
        '\n'
        'from base_plugin import BasePlugin\n'
        '\n'
        '\n'
        'class DescribedPlugin(BasePlugin):\n'
        '    pass\n')
    monkeypatch.setitem(sys.modules, 'described_plugin', None)
    monkeypatch.delitem(sys.modules, 'described_plugin')
    monkeypatch.setattr(sys, 'path', [*sys.path])
    monkeypatch.setattr(loader, '_install_sandbox', lambda: None)
    monkeypatch.setattr(loader, '_plugins_dir_path', lambda: str(tmp_path))
    result = json.loads(loader.load_plugin(str(plugin), 'described_plugin'))
    assert result['ok'], result['error']
    instance = loader.plugins['described_plugin'].instance
    assert (instance.id, instance.name, instance.description, instance.author,
            instance.version, instance.icon) == (
        'described_plugin', 'Described', 'Keeps its metadata', '@someone', '2.1b', 'pack/3')
    assert 'b' in instance.version
    assert instance.requirements == []


def test_base_hook_built_from_callbacks_exposes_hook_methods(sdk):
    seen = []
    before_only = sdk.base.BaseHook(None, before=lambda param: seen.append(('before', param)),
                                    after=None, before_filters=None, after_filters=None)
    before_only.before_hooked_method('p1')
    assert seen == [('before', 'p1')]
    assert not hasattr(before_only, 'after_hooked_method')
    after_only = sdk.base.BaseHook(None, after=lambda param: seen.append(('after', param)))
    after_only.after_hooked_method('p2')
    assert seen[-1] == ('after', 'p2')
    assert not hasattr(after_only, 'before_hooked_method')

    class Subclassed(sdk.base.MethodHook):
        def __init__(self, marker):
            super().__init__()
            self.marker = marker

    hook = Subclassed('m')
    assert hook.marker == 'm'
    assert hook.before_hooked_method('x') is None


def test_first_sdk_import_and_permission_lookup_do_not_reenter(loader, monkeypatch, tmp_path):
    native = types.ModuleType('app.exteraless.plugins')
    permissions, imports = [], []
    directory = str(tmp_path)
    native.PythonBridge = types.SimpleNamespace(
        getPluginsDir=lambda: directory,
        isUnsafeMode=lambda: (permissions.append(False), False)[1])
    original = builtins.__import__

    def java_import(name, globals=None, locals=None, fromlist=(), level=0):
        if name in ('app.exteraless.plugins', 'file_utils'):
            imports.append(name)
        if name == native.__name__:
            return native
        return original(name, globals, locals, fromlist, level)

    monkeypatch.delitem(sys.modules, 'app', raising=False)
    monkeypatch.delitem(sys.modules, 'file_utils', raising=False)
    monkeypatch.setattr(loader, '_original_import', java_import)
    with monkeypatch.context() as patch:
        patch.setattr(builtins, '__import__', loader._sandboxed_import)
        mode = loader.unsafe_mode()
        result = loader._plugins_dir_path()
    assert result == directory
    assert mode is False
    assert permissions == [False]
    assert imports == ['app.exteraless.plugins', 'file_utils', 'app.exteraless.plugins']


def test_settings_error_names_the_recursion_cycle(sdk, loader, monkeypatch):
    def ping(depth):
        return pong(depth + 1)

    def pong(depth):
        return ping(depth + 1)

    plugin = sdk.base.BasePlugin()
    plugin.create_settings = lambda: ping(0)
    monkeypatch.setitem(loader.plugins, 'test_plugin', loader.PluginRecord(None, plugin, ''))
    text = json.loads(loader.get_settings_json('test_plugin'))[0]['text']
    summary, cycle = text.split('\n\n', 1)
    assert summary.startswith('create_settings() failed: RecursionError')
    assert sorted(line.rsplit(' ', 1)[1] for line in cycle.splitlines()) == ['ping', 'pong']


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


def test_send_message_turns_a_params_dict_into_a_java_hash_map(sdk, monkeypatch):
    class FakeHashMap(dict):
        def put(self, key, value):
            self[key] = value

    class FakeArrayList(list):
        def add(self, item):
            self.append(item)

    classes = {
        'org.telegram.messenger.SendMessagesHelper$SendMessageParams': types.SimpleNamespace(
            of=lambda text, peer: types.SimpleNamespace(message=text, peer=peer)),
        'java.util.HashMap': FakeHashMap,
        'java.util.ArrayList': FakeArrayList,
    }
    queued, sent = [], []
    monkeypatch.setattr(sdk.client, '_require', lambda *args: None)
    monkeypatch.setattr(sdk.client, '_jclass', classes.__getitem__)
    monkeypatch.setattr(sdk.client, '_send_on_ui_thread', queued.append)
    monkeypatch.setattr(sdk.client, 'get_send_messages_helper', lambda account: types.SimpleNamespace(sendMessage=sent.append))
    sdk.client.send_message({
        'peer': 5,
        'message': 'text',
        'entities': ['bold'],
        'params': {'kpm_inline': '1', 'kpm_version': 153, 'skipped': None},
    }, account=0)
    queued[0]()
    params = sent[0].params
    assert isinstance(params, FakeHashMap)
    assert params == {'kpm_inline': '1', 'kpm_version': '153'}
    assert isinstance(sent[0].entities, FakeArrayList) and sent[0].entities == ['bold']


def test_send_message_with_media_does_not_become_a_text_message(sdk, monkeypatch):
    classes = {
        'org.telegram.messenger.SendMessagesHelper$SendMessageParams': types.SimpleNamespace(
            of=lambda text, peer: types.SimpleNamespace(message=text, peer=peer)),
    }
    queued, sent = [], []
    monkeypatch.setattr(sdk.client, '_require', lambda *args: None)
    monkeypatch.setattr(sdk.client, '_jclass', classes.__getitem__)
    monkeypatch.setattr(sdk.client, '_send_on_ui_thread', queued.append)
    monkeypatch.setattr(sdk.client, 'get_send_messages_helper', lambda account: types.SimpleNamespace(sendMessage=sent.append))
    photo = object()
    sdk.client.send_message({'peer': 5, 'photo': photo, 'path': '/quote.png'}, account=0)
    sdk.client.send_message({'peer': 5, 'photo': photo, 'path': '/quote.png', 'caption': 'hi'}, account=0)
    sdk.client.send_message({'peer': 5, 'message': 'text'}, account=0)
    for run in queued:
        run()
    assert sent[0].message is None and sent[0].photo is photo and sent[0].path == '/quote.png'
    assert sent[1].message is None and sent[1].caption == 'hi'
    assert sent[2].message == 'text'


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
    assert plugin._exteraless_menu_callbacks['action'] is callback
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


def test_markdown_link_to_a_document_id_is_a_custom_emoji(sdk, monkeypatch):
    formatting = load_module(monkeypatch, 'extera_utils.text_formatting')
    plain, entities = formatting.parse_raw(
        '[❤️](5278611606756942667) hi [🔍](tg://emoji?id=5276395476646653290) [site](https://a.b)',
        'Markdown')
    assert plain == '❤️ hi 🔍 site'
    kinds = [(entity.type, entity.offset, entity.length, entity.document_id, entity.url) for entity in entities]
    assert kinds == [
        (formatting.TLEntityType.CUSTOM_EMOJI, 0, 2, 5278611606756942667, None),
        (formatting.TLEntityType.CUSTOM_EMOJI, 6, 2, 5276395476646653290, None),
        (formatting.TLEntityType.TEXT_LINK, 9, 4, None, 'https://a.b'),
    ]


def test_alt_seekbar_resolves_to_our_appearance_component(sdk, monkeypatch):
    aliases = load_module(monkeypatch, 'extera_utils.class_aliases')
    name = 'com.exteragram.messenger.preferences.components.AltSeekbar'
    assert aliases.resolve(name) == 'app.exteraless.appearance.AltSeekbar'
    assert aliases.resolve(name + '$OnDrag') == 'app.exteraless.appearance.AltSeekbar$OnDrag'
    assert Path(corpus.PYTHON_ROOT).parent.joinpath(
        'java', 'app', 'exteraless', 'appearance', 'AltSeekbar.java').is_file()


def test_hooks_unwrap_field_shaped_class_wrappers(sdk, monkeypatch):
    aliases = load_module(monkeypatch, 'extera_utils.class_aliases')
    java_class = object()
    wrapper = aliases._FieldShapedClass(java_class, {})
    assert sdk.base.BasePlugin._exteraless_resolve_class(wrapper) is java_class
    assert sdk.base.BasePlugin._exteraless_resolve_class(java_class) is java_class


def test_broken_sub_page_item_does_not_drop_the_whole_page(sdk, loader, monkeypatch):
    items = [sdk.settings.Text('Page', create_sub_fragment=lambda: [
        sdk.settings.Header('Top'),
        sdk.settings.Selector('Action', 'after_gen_action', ['Preview', 'Photo'], 0),
        sdk.settings.Switch(key='flag', text='Flag', default=True),
    ])]
    settings_record(sdk, loader, monkeypatch, items)
    rows = json.loads(loader.get_settings_json('test_plugin'))
    assert len(rows) == 1
    assert [entry['type'] for entry in rows[0]['sub_page']] == ['header', 'switch']


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


def test_hiding_the_first_duplicate_does_not_reassign_its_callback(sdk, loader, monkeypatch):
    calls = []
    items = [sdk.settings.Text('Open', on_click=lambda view, key=key: calls.append(key))
             for key in ('first', 'second')]
    record = settings_record(sdk, loader, monkeypatch, items)
    before = json.loads(loader.get_settings_json('test_plugin'))
    items.pop(0)
    after = json.loads(loader.get_settings_json('test_plugin'))
    assert after[0]['row_id'] == before[1]['row_id']
    assert before[0]['callback_id'] not in record.click_callbacks
    for row in before:
        loader.dispatch_setting_click('test_plugin', row['callback_id'])
    assert calls == ['second']


def test_rebuilt_lambdas_keep_their_ids_when_the_list_order_changes(sdk, loader, monkeypatch):
    calls = []
    def make_row(key):
        return sdk.settings.Text('Open', on_click=lambda view: calls.append(key))
    items = [make_row('first'), make_row('second')]
    settings_record(sdk, loader, monkeypatch, items)
    before = json.loads(loader.get_settings_json('test_plugin'))
    items[:] = [make_row('second'), make_row('first')]
    after = json.loads(loader.get_settings_json('test_plugin'))
    assert [row['row_id'] for row in after] == [row['row_id'] for row in reversed(before)]
    for row in before:
        loader.dispatch_setting_click('test_plugin', row['callback_id'])
    assert calls == ['first', 'second']


def test_rebuilt_closures_over_nested_functions_keep_sub_page_callbacks(sdk, loader, monkeypatch):
    calls = []
    keep = []

    def build():
        def connect():
            calls.append('connect')
        keep.append(connect)
        return [sdk.settings.Text('Page', create_sub_fragment=lambda: [
            sdk.settings.Text('Connect', on_click=lambda view: connect())])]

    items = build()
    settings_record(sdk, loader, monkeypatch, items)
    before = json.loads(loader.get_settings_json('test_plugin'))
    items[:] = build()
    after = json.loads(loader.get_settings_json('test_plugin'))
    assert before[0]['row_id'] == after[0]['row_id']
    loader.dispatch_setting_click('test_plugin', before[0]['sub_page'][0]['callback_id'])
    assert calls == ['connect']


def test_disabling_callbacks_removes_the_previous_handlers(sdk, loader, monkeypatch):
    calls = []
    text = sdk.settings.Text('Open', link_alias='open', on_click=lambda view: calls.append('click'),
                             on_long_click=lambda view: calls.append('long'))
    switch = sdk.settings.Switch('enable_autoupdate', 'Update', False,
                                 on_change=lambda value: calls.append('change'))
    record = settings_record(sdk, loader, monkeypatch, [text, switch])
    record.instance.set_setting = lambda *args: None
    before = json.loads(loader.get_settings_json('test_plugin'))
    text.on_click = text.on_long_click = switch.on_change = None
    after = json.loads(loader.get_settings_json('test_plugin'))
    assert before[0]['row_id'] == after[0]['row_id']
    assert record.click_callbacks == record.change_callbacks == {}
    loader.dispatch_setting_click('test_plugin', before[0]['callback_id'])
    loader.dispatch_setting_click('test_plugin', before[0]['long_callback_id'])
    loader.notify_setting_changed('test_plugin', 'enable_autoupdate', 'true')
    assert calls == []


@pytest.mark.parametrize('empty', [[], None])
def test_empty_settings_release_callbacks_and_custom_views(sdk, loader, monkeypatch, empty):
    items = [sdk.settings.Custom(view=object(), on_click=lambda view: None),
             sdk.settings.Switch('enabled', 'Enabled', False, on_change=lambda value: None)]
    record = settings_record(sdk, loader, monkeypatch, items)
    loader.get_settings_json('test_plugin')
    assert record.click_callbacks and record.change_callbacks and record.custom_views
    record.instance.create_settings = lambda: empty
    loader.get_settings_json('test_plugin')
    assert record.click_callbacks == record.change_callbacks == record.custom_views == {}


def test_rebuilding_dynamic_labels_does_not_accumulate_old_callbacks(sdk, loader, monkeypatch):
    items = [sdk.settings.Text('First', on_click=lambda view: None)]
    record = settings_record(sdk, loader, monkeypatch, items)
    for i in range(100):
        items[0].text = str(i)
        loader.get_settings_json('test_plugin')
    assert len(record.click_callbacks) == 1


def test_native_custom_row_ids_survive_unrelated_insertion(sdk, loader, monkeypatch):
    native = types.SimpleNamespace(id=1729, view=object())
    custom = sdk.settings.Custom(item=native)
    items = [custom]
    record = settings_record(sdk, loader, monkeypatch, items)
    before = json.loads(loader.get_settings_json('test_plugin'))[0]
    items.insert(0, sdk.settings.Header('New section'))
    after = json.loads(loader.get_settings_json('test_plugin'))[1]
    assert after['row_id'] == before['row_id']
    assert loader._build_custom_view(record.custom_views[after['view_id']], None) is native


def test_expanded_options_keep_their_object_bound_callbacks(sdk, loader, monkeypatch):
    calls = []
    options = [types.SimpleNamespace(toggle=lambda key=key: calls.append(key)) for key in ('first', 'second')]
    def make_rows():
        return [sdk.settings.Custom(item=types.SimpleNamespace(id=-1),
                                    on_click=lambda view, option=option: option.toggle()) for option in options]
    record = settings_record(sdk, loader, monkeypatch, [])
    record.instance.create_settings = make_rows
    before = json.loads(loader.get_settings_json('test_plugin'))
    options.pop(0)
    after = json.loads(loader.get_settings_json('test_plugin'))
    assert after[0]['row_id'] == before[1]['row_id']
    for row in before:
        loader.dispatch_setting_click('test_plugin', row['callback_id'])
    assert calls == ['second']


def test_anonymous_custom_views_do_not_swap_after_insertion(sdk, loader, monkeypatch):
    views = [object(), object()]
    items = [sdk.settings.Custom(view=view) for view in views]
    record = settings_record(sdk, loader, monkeypatch, items)
    before = json.loads(loader.get_settings_json('test_plugin'))
    items.insert(0, sdk.settings.Header('New section'))
    after = json.loads(loader.get_settings_json('test_plugin'))[1:]
    assert [row['row_id'] for row in after] == [row['row_id'] for row in before]
    assert [record.custom_views[row['view_id']].view for row in before] == views


def native_custom_model(monkeypatch):
    class Factory:
        def getClass(self):
            return type(self)

    class Setting:
        def __init__(self, factory, args, on_click, subpage, on_long_click, alias):
            self.factory = factory
            self.args = args
            self.alias = alias

    Setting.Factory = Factory
    monkeypatch.setitem(sys.modules, 'java', types.SimpleNamespace(jclass=lambda name: Setting))
    return Setting


def test_native_custom_factory_receives_its_original_payload(sdk, loader, monkeypatch):
    model = native_custom_model(monkeypatch)
    factory, payload = model.Factory(), object()
    row = sdk.settings.Custom(factory=factory, factory_args=payload, link_alias='chat')
    native = loader._build_custom_view(row, object())
    assert isinstance(native, model)
    assert native.factory is factory and native.args is payload and native.alias == 'chat'


def test_native_custom_setting_conversion_keeps_factory_arguments(sdk, loader, monkeypatch):
    model = native_custom_model(monkeypatch)
    factory, payload = model.Factory(), object()
    original = types.SimpleNamespace(getType=lambda: 'custom', getClass=lambda: object,
                                     getFactory=lambda: factory, getFactoryArgs=lambda: payload)
    converted = loader._from_java_setting(original, 'custom')
    assert converted.factory_args is payload
    assert loader._build_custom_view(converted, None).args is payload


def test_python_custom_factory_still_builds_its_view(sdk, loader):
    view, context = object(), object()
    calls = []
    factory = types.SimpleNamespace(build_view=lambda *args: (calls.append(args), view)[1])
    assert loader._build_custom_view(sdk.settings.Custom(factory=factory), context) is view
    assert calls == [(context, False)]


def test_reference_factory_builds_through_instance_java(sdk, loader, monkeypatch):
    context, view, calls = object(), object(), []

    def create(context, list_view, current_account, class_guid, resources_provider):
        calls.append(('create', context, list_view, current_account, class_guid,
                      resources_provider))
        return view

    def bind(view, item, divider, adapter, list_view):
        calls.append(('bind', view, item, divider, adapter, list_view))

    monkeypatch.setitem(sys.modules, 'java', types.SimpleNamespace(
        jclass=lambda name: types.SimpleNamespace(selectedAccount=2)))
    factory = sdk.settings.SimpleSettingFactory(create, bind, is_clickable=False, is_shadow=False)
    row = sdk.settings.Custom(factory=factory.instance.java)
    assert loader._build_custom_view(row, context) is view
    assert calls == [('create', context, None, 2, 0, None),
                     ('bind', view, None, False, None, None)]


def test_short_factory_callbacks_still_build_the_view(sdk, loader):
    context, view, bound = object(), object(), []
    factory = sdk.settings.SimpleSettingFactory(lambda context: view, bound.append)
    assert loader._build_custom_view(sdk.settings.Custom(factory=factory), context) is view
    assert bound == [view]


def test_admin_tools_custom_user_cell_keeps_its_factory_payload(sdk, loader, monkeypatch):
    import __future__
    paths = sorted(Path(corpus.CORPUS_DIR).glob('admin_tools*.plugin'))
    if not paths:
        pytest.skip('admin_tools corpus file is unavailable')
    tree = ast.parse(paths[0].read_text())
    function = next(node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef)
                    and node.name == 'custom_user_cell')
    model = native_custom_model(monkeypatch)
    factory = model.Factory()
    namespace = {
        'Custom': sdk.settings.Custom,
        'user_cell_factory_instance': factory,
        'PyObjectWrapper': types.SimpleNamespace(new_instance=lambda: types.SimpleNamespace(java=types.SimpleNamespace())),
        'UserCellData': lambda *args: args,
    }
    exec(compile(ast.Module(body=[function], type_ignores=[]), str(paths[0]), 'exec',
                 flags=__future__.annotations.compiler_flag), namespace)
    click, long_click = object(), object()
    row = namespace['custom_user_cell'](-123, 'Admin chat', '13 members', click, long_click)
    native = loader._build_custom_view(row, None)
    assert native.factory is factory
    assert native.args.hold_object == (-123, 'Admin chat', '13 members', click, long_click)

def test_substituted_factory_class_exposes_the_java_singleton(sdk, monkeypatch):
    aliases = load_module(monkeypatch, 'extera_utils.class_aliases')
    factory_class = aliases.substitute('com.exteragram.messenger.plugins.models.PluginItemFactory')
    assert factory_class is sdk.settings.SimpleSettingFactory
    singleton, asked = object(), []
    peer = types.SimpleNamespace(getInstance=lambda: singleton)
    monkeypatch.setitem(sys.modules, 'java', types.SimpleNamespace(
        jclass=lambda name: (asked.append(name), peer)[1]))
    assert factory_class.getInstance() is singleton
    assert asked == ['app.exteraless.plugins.models.PluginItemFactory']
    assert sdk.settings.SimpleSettingFactory().java is singleton


def _java_behind_the_guard(loader, monkeypatch, peer):
    asked = []
    java = types.ModuleType('java')
    java.jclass = lambda name: (asked.append(name), peer)[1]
    monkeypatch.setitem(sys.modules, 'java', java)
    monkeypatch.setattr(loader, 'guard_java_class', lambda name: True)
    load_module(monkeypatch, 'extera_utils.class_aliases')
    loader._install_jclass_guard()
    return java, asked


def test_settings_factory_reaches_the_java_singleton_through_the_jclass_guard(sdk, loader,
                                                                               monkeypatch):
    singleton = object()
    java, asked = _java_behind_the_guard(
        loader, monkeypatch, types.SimpleNamespace(getInstance=lambda: singleton))
    factory = java.jclass('com.exteragram.messenger.plugins.models.PluginItemFactory')
    assert factory is sdk.settings.SimpleSettingFactory
    assert factory.getInstance() is singleton
    assert sdk.settings.SimpleSettingFactory().java is singleton
    assert asked == ['app.exteraless.plugins.models.PluginItemFactory'] * 3


def test_find_class_substitutes_only_the_reference_name(sdk, loader, monkeypatch):
    peer = types.SimpleNamespace(getInstance=lambda: None)
    _java_behind_the_guard(loader, monkeypatch, peer)
    hooks = load_module(monkeypatch, 'hook_utils')
    reference = hooks.find_class('com.exteragram.messenger.plugins.models.PluginItemFactory')
    assert reference is sdk.settings.SimpleSettingFactory
    assert hooks.find_class('app.exteraless.plugins.models.PluginItemFactory') is peer


def test_java_interface_called_with_a_python_callable_becomes_a_proxy(loader, monkeypatch):
    class JavaClass(type):
        def __call__(cls, *args, **kwargs):
            if cls.abstract:
                raise TypeError(f'{cls.__name__} is abstract and cannot be instantiated')
            return super().__call__(*args, **kwargs)

        def getClass(cls):
            return cls.reflected

    def reflected(interface, *methods):
        found = [types.SimpleNamespace(getName=lambda name=name: name,
                                       getModifiers=lambda flags=flags: flags)
                 for name, flags in methods]
        return types.SimpleNamespace(isInterface=lambda: interface, getMethods=lambda: found)

    proxied = []

    def dynamic_proxy(interface):
        proxied.append(interface)
        return JavaClass('Proxy', (), {'abstract': False, 'reflected': reflected(False)})

    java = types.ModuleType('java')
    java.dynamic_proxy = dynamic_proxy
    java.chaquopy = types.ModuleType('java.chaquopy')
    java.chaquopy.JavaClass = JavaClass
    monkeypatch.setitem(sys.modules, 'java', java)
    monkeypatch.setitem(sys.modules, 'java.chaquopy', java.chaquopy)
    monkeypatch.setattr(loader, '_CALLABLE_INTERFACE_PROXIES', {})
    loader._install_interface_call_shim()
    installed = JavaClass.__call__
    loader._install_interface_call_shim()
    assert JavaClass.__call__ is installed

    runnable = JavaClass('Runnable', (), {'abstract': True, 'reflected': reflected(
        True, ('run', 0x401), ('equals', 0x401), ('wait', 0x111))})
    listener = JavaClass('Listener', (), {'abstract': True, 'reflected': reflected(
        True, ('first', 0x401), ('second', 0x401))})
    abstract = JavaClass('Abstract', (), {'abstract': True, 'reflected': reflected(
        False, ('run', 0x401))})
    concrete = JavaClass('Concrete', (), {'abstract': False, 'reflected': reflected(False)})
    java_object = type('JavaObject', (), {'getClass': lambda self: None,
                                          '__call__': lambda self: None})()

    ran = []
    first = runnable(lambda: ran.append('first'))
    runnable(lambda: ran.append('second')).run()
    first.run()
    assert ran == ['second', 'first']
    assert proxied == [runnable]
    assert isinstance(concrete(), concrete)
    for target, argument in ((listener, lambda: None), (abstract, lambda: None),
                             (runnable, java_object), (runnable, concrete)):
        with pytest.raises(TypeError, match='abstract'):
            target(argument)


def test_settings_mirror_rereads_java_after_invalidation(sdk, loader, monkeypatch):
    store = {'set_federation_type': '0'}
    mirror = load_module(monkeypatch, 'plugin_settings')
    monkeypatch.setattr(mirror, '_bridge', types.SimpleNamespace(
        exportSettings=lambda plugin_id: json.dumps(store)))
    assert mirror.get_setting('admin_tools', 'set_federation_type', 0) == 0
    store['set_federation_type'] = '1'
    loader.invalidate_settings_mirror('admin_tools')
    assert mirror.get_setting('admin_tools', 'set_federation_type', 0) == 1


def test_get_setting_survives_repeated_reads(sdk, monkeypatch):
    bridge = types.SimpleNamespace(getSetting=lambda plugin_id, key: 'true', log=lambda *args: None)
    monkeypatch.setattr(sdk.base, 'PythonBridge', bridge)
    plugin = sdk.base.BasePlugin()
    plugin._exteraless_attach('test_plugin')
    assert plugin.get_setting('flag', False) is True
    assert plugin.get_setting('flag', False) is True
    assert plugin.get_setting('other', False) is True


def test_plugin_method_named_like_old_internals_survives_attach(sdk):
    class Plugin(sdk.base.BasePlugin):
        def _plugin_id(self, other):
            return f"id:{other}"

    plugin = Plugin()
    plugin._exteraless_attach('test_plugin')
    assert plugin._plugin_id('x') == 'id:x'
    assert plugin.plugin_id == 'test_plugin'
