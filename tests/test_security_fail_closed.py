"""Security control failures must not turn into plugin authorization."""

import builtins
import importlib
import json
import sys
import types

import pytest

from test_sdk_nx_port import loader, sdk
from test_storage_gate import gate


@pytest.fixture
def principal(loader, monkeypatch):
    monkeypatch.setattr(loader, 'unsafe_mode', lambda: False)
    monkeypatch.setattr(loader, 'plugin_frame_owner', lambda: 'p')
    return loader


@pytest.mark.parametrize('failure', ['missing', 'exception'])
def test_permission_bridge_failure_denies_known_plugin(principal, monkeypatch, failure):
    def broken(*args):
        raise RuntimeError('bridge unavailable')

    bridge = None if failure == 'missing' else types.SimpleNamespace(
        hasPermission=broken, checkPermission=broken)
    monkeypatch.setattr(principal, '_permissions', lambda: bridge)
    assert principal.has_permission('files') is False
    with pytest.raises(PermissionError):
        principal.require_permission('files', 'read a file')


def test_host_work_does_not_require_permission_bridge(principal, monkeypatch):
    monkeypatch.setattr(principal, 'plugin_frame_owner', lambda: None)
    monkeypatch.setattr(principal, '_permissions', lambda: None)
    assert principal.has_permission('files') is True
    principal.require_permission('files', 'host work')


def test_explicit_principal_requires_permission_bridge(principal, monkeypatch):
    monkeypatch.setattr(principal, 'plugin_frame_owner', lambda: None)
    monkeypatch.setattr(principal, '_permissions', lambda: None)
    assert principal.has_permission('network', 'p') is False
    with pytest.raises(PermissionError):
        principal.require_permission('network', 'connect', plugin_id='p')


def test_unsafe_mode_remains_an_explicit_override(principal, monkeypatch):
    monkeypatch.setattr(principal, 'unsafe_mode', lambda: True)
    monkeypatch.setattr(principal, '_permissions', lambda: None)
    assert principal.has_permission('files', 'p') is True
    principal.require_permission('files', 'read a file', plugin_id='p')


def permission_bridge(grants):
    return types.SimpleNamespace(hasPermission=lambda owner, perm: perm in grants[owner])


@pytest.mark.parametrize('grants', [
    {'a': {'files'}, 'b': {'network'}},
    {'a': {'files'}, 'b': {'network', 'messages.read'}},
    {'a': {'intents'}, 'b': {'settings'}},
])
def test_permission_counts_do_not_define_a_common_principal(principal, monkeypatch, grants):
    monkeypatch.setattr(principal, '_permissions', lambda: permission_bridge(grants))
    for order in (['a', 'b'], ['b', 'a']):
        with pytest.raises(PermissionError, match='incomparable'):
            principal._least_trusted(order)


@pytest.mark.parametrize('order', [['a', 'b'], ['b', 'a']])
def test_actual_permission_subset_defines_a_common_principal(principal, monkeypatch, order):
    grants = {'a': {'ui', 'files'}, 'b': {'ui', 'files', 'network'}}
    monkeypatch.setattr(principal, '_permissions', lambda: permission_bridge(grants))
    assert principal._least_trusted(order) == 'a'


@pytest.mark.parametrize('failure', ['missing', 'exception'])
def test_shared_principal_selection_fails_closed(principal, monkeypatch, failure):
    def broken(*args):
        raise RuntimeError('bridge unavailable')

    bridge = None if failure == 'missing' else types.SimpleNamespace(hasPermission=broken)
    monkeypatch.setattr(principal, '_permissions', lambda: bridge)
    with pytest.raises(PermissionError):
        principal._least_trusted(['a', 'b'])


def test_same_principal_does_not_need_ranking(principal, monkeypatch):
    monkeypatch.setattr(principal, '_permissions', lambda: None)
    assert principal._least_trusted(['p', 'p']) == 'p'


def test_explicit_principal_cannot_replace_the_live_callers(principal, monkeypatch):
    grants = {'p': {'files'}, 'other': {'network'}}
    monkeypatch.setattr(principal, '_permissions', lambda: permission_bridge(grants))
    assert principal.has_permission('network', 'other') is False
    with pytest.raises(PermissionError):
        principal.require_permission('network', 'connect', plugin_id='other')


def test_runtime_context_is_combined_with_live_stack(principal, monkeypatch):
    grants = {'p': {'ui'}, 'context': {'ui', 'files'}}
    monkeypatch.setattr(principal, '_permissions', lambda: permission_bridge(grants))
    assert principal.permission_frame_owner('context') == 'p'
    monkeypatch.setattr(principal, 'plugin_frame_owner', lambda: None)
    assert principal.permission_frame_owner('context') == 'context'


@pytest.mark.parametrize('event,args', [
    ('open', ('/some/file', 'w', 1)),
    ('socket.connect', (None, ('localhost', 1234))),
    ('compile', ('pass', '<test>')),
])
def test_unexpected_audit_check_failure_denies_plugin(gate, monkeypatch, event, args):
    def broken(*args, **kwargs):
        raise RuntimeError('check failed')

    monkeypatch.setattr(gate.module, '_check', broken)
    with pytest.raises(PermissionError, match='audit check failed') as error:
        gate.module._hook(event, args, _loader_ref=gate.loader)
    assert getattr(error.value, gate.module._DENIAL_MARK)
    assert isinstance(error.value.__cause__, RuntimeError)
    assert gate.module._local.busy is False


def test_audit_attribution_failure_does_not_become_host_work(gate):
    def broken():
        raise RuntimeError('cannot resolve owner')

    gate.loader.plugin_frame_owner = broken
    with pytest.raises(PermissionError):
        gate.module._hook('open', ('/some/file', 'r', 0), _loader_ref=gate.loader)
    assert gate.module._local.busy is False


def test_unexpected_host_only_audit_failure_keeps_host_behavior(gate, monkeypatch):
    gate.loader.plugin_frame_owner = lambda: None

    def broken(*args, **kwargs):
        raise RuntimeError('host check failure')

    monkeypatch.setattr(gate.module, '_check', broken)
    gate.module._hook('open', ('/some/file', 'r', 0), _loader_ref=gate.loader)
    assert gate.module._local.busy is False


@pytest.mark.parametrize('error_type', [KeyboardInterrupt, SystemExit])
def test_audit_does_not_swallow_process_control_exceptions(gate, monkeypatch, error_type):
    def broken(*args, **kwargs):
        raise error_type()

    monkeypatch.setattr(gate.module, '_check', broken)
    with pytest.raises(error_type):
        gate.module._hook('open', ('/some/file', 'r', 0), _loader_ref=gate.loader)
    assert gate.module._local.busy is False


def test_marked_network_denial_keeps_its_original_type(gate, monkeypatch):
    denial = gate.module._denial(ConnectionRefusedError('network denied'))

    def denied(*args, **kwargs):
        raise denial

    monkeypatch.setattr(gate.module, '_check', denied)
    with pytest.raises(ConnectionRefusedError) as error:
        gate.module._hook('socket.connect', (None, ('localhost', 1234)), _loader_ref=gate.loader)
    assert error.value is denial
    assert gate.module._local.busy is False


def test_audit_hook_resolves_the_principal_once(gate):
    owners = []
    gate.loader.plugin_frame_owner = lambda: owners.append('p') or 'p'
    gate.module._hook('open', (str(gate.plugins / '.data' / 'p' / 'data.txt'), 'r', 0),
                      _loader_ref=gate.loader)
    assert owners == ['p']


@pytest.mark.parametrize('failure', ['false', 'exception'])
def test_python_sandbox_install_requires_audit_gate(loader, sdk, monkeypatch, failure):
    # Keep this test from installing process-wide import/open wrappers.
    monkeypatch.setattr(loader, '_original_import', builtins.__import__)
    monkeypatch.setattr(loader, '_original_import_module', importlib.import_module)
    monkeypatch.setattr(loader, '_original_open', builtins.open)
    monkeypatch.setattr(loader, '_install_fd_guards', lambda: None)
    audit = types.ModuleType('extera_utils.audit_gate')

    def install(*args):
        if failure == 'exception':
            raise RuntimeError('audit installation failure')
        return False

    audit.install = install
    monkeypatch.setitem(sys.modules, audit.__name__, audit)
    monkeypatch.setattr(sdk.package, 'audit_gate', audit, raising=False)
    with pytest.raises(RuntimeError, match='security initialization failed'):
        loader._install_sandbox()


def test_failed_security_initialization_stops_before_metadata(loader, monkeypatch):
    def broken():
        raise RuntimeError('security initialization failed')

    def unexpected(*args):
        pytest.fail('plugin processing started before security initialization')

    monkeypatch.setattr(loader, '_install_sandbox', broken)
    monkeypatch.setattr(loader, 'read_metadata', unexpected)
    result = json.loads(loader.load_plugin('plugin.py', 'p'))
    assert result['ok'] is False
