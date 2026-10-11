"""Data-only metadata parsing and shared bundled-wheel extraction limits."""
import importlib
import json
import sys
import zipfile

import pytest

import corpus

sys.path.insert(0, corpus.PYTHON_ROOT)
archive = importlib.import_module('elyx_runtime.archive')
metadata = importlib.import_module('elyx_runtime.metadata')
localization = importlib.import_module('elyx_runtime.localization')


def write_zip(path, members):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
        for name, value in members:
            zf.writestr(name, value)


@pytest.mark.parametrize('kind', ['metainfo', 'localization'])
def test_python_data_does_not_execute_calls(tmp_path, kind):
    marker = tmp_path / 'executed'
    source = f"name = 'Demo'\nopen({str(marker)!r}, 'w').write('executed')\n"
    with pytest.raises(metadata.MetainfoError):
        metadata.parse_mapping_file('data.py', source.encode(), what=kind)
    assert not marker.exists()


@pytest.mark.parametrize('source', [
    "import os\nname = 'Demo'",
    "name = str(42)",
    "name = f'{1 + 2}'",
    "for i in range(1):\n    name = 'Demo'",
    "def helper():\n    return 'Demo'\nname = 'Demo'",
    "name = [x for x in range(2)]",
    "name = 'Demo'\n_hidden = __import__('os')",
])
def test_python_data_rejects_executable_statements(source):
    with pytest.raises(metadata.MetainfoError):
        metadata.parse_mapping_file('metainfo.py', source.encode())


def test_python_data_accepts_literal_assignments():
    source = '''"""Static plugin data."""
id = 'static_plugin'
name: str = 'Demo'
version = '1.0'
requirements = ['packaging>=20']
values = {'enabled': True, 'missing': None, 'count': -3}
alias = label = 'hello'
_private = 42
'''
    parsed = metadata.parse_mapping_file('metainfo.py', source.encode())
    assert parsed['values'] == {'enabled': True, 'missing': None, 'count': -3}
    assert parsed['alias'] == parsed['label'] == 'hello'
    assert '_private' not in parsed
    assert metadata.build_metadata(parsed)['id'] == 'static_plugin'


@pytest.mark.parametrize('kind', ['metainfo', 'localization'])
def test_archive_metadata_read_cannot_run_python(tmp_path, kind):
    marker = tmp_path / 'executed'
    path = tmp_path / 'plugin.elyx'
    executable = f"open({str(marker)!r}, 'w').write('executed')\n"
    if kind == 'metainfo':
        refmap = {'main': 'main.py', 'metainfo': 'metainfo.py'}
        members = [('metainfo.py', "id = 'safe_plugin'\nname = 'Demo'\n" + executable)]
    else:
        refmap = {'main': 'main.py', 'metainfo': 'metainfo.json', 'strings': 'strings'}
        members = [('metainfo.json', json.dumps({'id': 'safe_plugin', 'name': 'Demo'})),
                   ('strings/strings_en.py', "hello = 'Hello'\n" + executable)]
    write_zip(path, [('refmap.json', json.dumps(refmap)), ('main.py', ''), *members])
    with pytest.raises(metadata.MetainfoError):
        metadata.read_metadata(str(path))
    assert not marker.exists()


def test_literal_python_localization_remains_supported(tmp_path):
    path = tmp_path / 'strings_en.py'
    path.write_text("hello = 'Hello {name}'\nitems = ['item', 'items', 'items']\n")
    catalog = localization.load_strings_from_dir(path)
    strings = localization.Strings(catalog)
    assert strings('hello', name='Alice', locale='en') == 'Hello Alice'


@pytest.fixture
def wheels_layout(tmp_path, monkeypatch):
    monkeypatch.setattr(archive, 'MAX_ARCHIVE_MEMBER_BYTES', 64)
    monkeypatch.setattr(archive, 'MAX_ARCHIVE_UNCOMPRESSED_BYTES', 64)
    monkeypatch.setattr(archive, 'MAX_ARCHIVE_MEMBERS', 3)
    extract = tmp_path / 'extracted'
    wheels = extract / 'wheels'
    wheels.mkdir(parents=True)
    return extract, wheels, tmp_path / 'plugins'


def process(layout):
    extract, _, plugins = layout
    return archive.process_wheels(str(extract), {'wheels': 'wheels'}, str(plugins), 'demo')


@pytest.mark.parametrize('cached', [False, True])
def test_wheels_share_uncompressed_byte_budget(wheels_layout, cached):
    _, wheels, plugins = wheels_layout
    for name in ['first.whl', 'second.whl']:
        write_zip(wheels / name, [('module.py', b'x' * 40)])
        if cached:
            dest = plugins / archive.LOCAL_LIBS_DIRNAME / 'demo' / name[:-4]
            dest.mkdir(parents=True)
            (dest / archive._WHEEL_MARKER).write_text(name[:-4])
    with pytest.raises(archive.ElyxArchiveError, match='bundled wheels.*bytes'):
        process(wheels_layout)
    if not cached:
        assert not (plugins / archive.LOCAL_LIBS_DIRNAME).exists()
    else:
        assert len(list((plugins / archive.LOCAL_LIBS_DIRNAME).glob('demo/*/.elyx_wheel_complete'))) == 2


def test_wheels_share_entry_budget(wheels_layout):
    _, wheels, plugins = wheels_layout
    for name in ['first.whl', 'second.whl']:
        write_zip(wheels / name, [('one.py', b'x'), ('two.py', b'x')])
    with pytest.raises(archive.ElyxArchiveError, match='bundled wheels.*entry limit'):
        process(wheels_layout)
    assert not (plugins / archive.LOCAL_LIBS_DIRNAME).exists()


def test_all_wheels_validated_before_mutating_existing_cache(wheels_layout):
    _, wheels, plugins = wheels_layout
    write_zip(wheels / 'first.whl', [('module.py', b'new')])
    write_zip(wheels / 'second.whl', [('../outside.py', b'unsafe')])
    dest = plugins / archive.LOCAL_LIBS_DIRNAME / 'demo' / 'first'
    dest.mkdir(parents=True)
    sentinel = dest / 'module.py'
    sentinel.write_text('previous release')
    with pytest.raises(archive.ElyxArchiveError, match='path traversal'):
        process(wheels_layout)
    assert sentinel.read_text() == 'previous release'
    assert not list(dest.parent.glob('*.tmp*'))


def test_wheels_exactly_at_shared_limit_are_accepted(wheels_layout, monkeypatch):
    monkeypatch.setattr(archive, 'MAX_ARCHIVE_MEMBERS', 2)
    _, wheels, _ = wheels_layout
    for name in ['first.whl', 'second.whl']:
        write_zip(wheels / name, [('module.py', b'x' * 32)])
    paths = process(wheels_layout)
    assert len(paths) == 2
    assert all(__import__('pathlib').Path(p, 'module.py').read_bytes() == b'x' * 32 for p in paths)


def test_invalid_wheel_is_reported_without_creating_cache(wheels_layout):
    _, wheels, plugins = wheels_layout
    (wheels / 'invalid.whl').write_bytes(b'not a ZIP')
    with pytest.raises(archive.ElyxArchiveError):
        process(wheels_layout)
    assert not (plugins / archive.LOCAL_LIBS_DIRNAME).exists()
