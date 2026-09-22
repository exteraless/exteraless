"""Mini-pip for plugin ``__requirements__`` — pure Python (requests + packaging).

Resolution goes through the PyPI JSON API; only pure-Python wheels are
supported (``py3-none-any.whl`` / ``py2.py3-none-any.whl``). Packages are
extracted into ``<files>/plugins/shared_libs/<normalized_name>/<version>/``
and shared between plugins with reference counting tracked in
``shared_libs/manifest.json`` — removing a plugin drops dependencies no other
plugin needs anymore (PLUGINS-API.md §9).

Called from extera_utils.plugin_loader, which runs on the engine's
background executor thread, so network I/O here never touches the UI thread.
PyPI responses are cached in-memory for the process lifetime.
"""

import io
import json
import os
import platform
import re
import hashlib
import shutil
import sys
import threading
import zipfile

import importlib as _importlib


class _LazyModule:
    def __init__(self, name):
        self._name = name
        self._module = None

    def _load(self):
        module = self._module
        if module is None:
            module = self._module = _importlib.import_module(self._name)
        return module

    def __getattr__(self, attr):
        return getattr(self._load(), attr)


class _LazyAttr:
    def __init__(self, module, attr):
        self._module = module
        self._attr = attr
        self._value = None

    def _load(self):
        value = self._value
        if value is None:
            value = self._value = getattr(_importlib.import_module(self._module), self._attr)
        return value

    def __call__(self, *args, **kwargs):
        return self._load()(*args, **kwargs)

    def __getattr__(self, attr):
        return getattr(self._load(), attr)


requests = _LazyModule("requests")
Requirement = _LazyAttr("packaging.requirements", "Requirement")
Version = _LazyAttr("packaging.version", "Version")
SpecifierSet = _LazyAttr("packaging.specifiers", "SpecifierSet")


def _invalid_version():
    return _importlib.import_module("packaging.version").InvalidVersion

_PYPI_JSON = "https://pypi.org/pypi/{}/json"
_PURE_WHEEL_SUFFIXES = ("py3-none-any.whl", "py2.py3-none-any.whl")
_HTTP_TIMEOUT = 30
#: Потолок размера колеса, MAX_WHEEL_BYTES = 262144000: без него опечатка в имени зависимости может
#: утянуть на телефон гигабайты, а места на нём и так немного.
_MAX_WHEEL_BYTES = 250 * 1024 * 1024

_lock = threading.RLock()
_pypi_cache = {}          # normalized name -> PyPI JSON dict
_sys_path_added = set()   # extracted package dirs already on sys.path
_manifest_cache = None    # manifest dict, loaded lazily


# Paths / manifest

def _files_dir() -> str:
    """The app-private files dir (same source as the rest of the SDK).

    Falls back to $EXTERALESS_FILES_DIR so the controller stays testable on
    a host interpreter.
    """
    try:
        import file_utils
        path = file_utils.get_files_dir()
        if path:
            return path
    except Exception:
        pass
    path = os.environ.get("EXTERALESS_FILES_DIR")
    if path:
        return path
    raise RuntimeError("pip_controller: cannot resolve the app files directory")


def _shared_libs_dir() -> str:
    path = os.path.join(_files_dir(), "plugins", "shared_libs")
    os.makedirs(path, exist_ok=True)
    return path


def _manifest_path() -> str:
    return os.path.join(_shared_libs_dir(), "manifest.json")


def _load_manifest() -> dict:
    global _manifest_cache
    with _lock:
        if _manifest_cache is not None:
            return _manifest_cache
        try:
            with open(_manifest_path(), "r", encoding="utf-8") as handle:
                data = json.load(handle)
            if not isinstance(data, dict) or not isinstance(data.get("packages"), dict):
                raise ValueError("bad manifest shape")
            _manifest_cache = data
        except Exception:
            _manifest_cache = {"packages": {}}
        return _manifest_cache


def _save_manifest() -> None:
    with _lock:
        if _manifest_cache is None:
            return
        tmp = _manifest_path() + ".tmp"
        with open(tmp, "w", encoding="utf-8") as handle:
            json.dump(_manifest_cache, handle, indent=2, sort_keys=True)
        os.replace(tmp, _manifest_path())


def _normalize(name: str) -> str:
    """PEP 503 normalization."""
    return re.sub(r"[-_.]+", "-", name).lower()


def _ensure_on_sys_path(path: str) -> None:
    with _lock:
        if path not in _sys_path_added:
            if path not in sys.path:
                sys.path.insert(0, path)
            _sys_path_added.add(path)


def restore_sys_path() -> None:
    """Re-add every already-installed package to sys.path (engine start)."""
    try:
        manifest = _load_manifest()
    except Exception:
        return
    for entry in list(manifest["packages"].values()):
        path = entry.get("path")
        if path and os.path.isdir(path):
            _ensure_on_sys_path(path)


# PyPI resolution

def _fetch_pypi_json(normalized_name: str) -> dict:
    with _lock:
        cached = _pypi_cache.get(normalized_name)
    if cached is not None:
        return cached
    try:
        response = requests.get(_PYPI_JSON.format(normalized_name),
                                timeout=_HTTP_TIMEOUT)
    except requests.RequestException as e:
        raise RuntimeError(f"failed to reach PyPI for {normalized_name!r}: {e}")
    if response.status_code == 404:
        raise RuntimeError(f"Package not found: {normalized_name!r}")
    try:
        response.raise_for_status()
        data = response.json()
    except Exception as e:
        raise RuntimeError(f"bad PyPI response for {normalized_name!r}: {e}")
    with _lock:
        _pypi_cache[normalized_name] = data
    return data


#: Имя пакета не совпадает с именем модуля чаще, чем кажется.
_IMPORT_ALIASES = {
    "pillow": "PIL",
    "beautifulsoup4": "bs4",
    "pyyaml": "yaml",
    "python-dateutil": "dateutil",
    "msgpack-python": "msgpack",
    "opencv-python": "cv2",
    "scikit-learn": "sklearn",
    "protobuf": "google.protobuf",
    "pycryptodome": "Crypto",
    "pycryptodomex": "Cryptodome",
}


_bundled_versions = {}
_provided = set()


def bundled_version(name: str):
    """Версия пакета, вшитого в сборку приложения, или None.

    Chaquopy ставит часть колёс во время сборки — там же собираются нативные,
    которых на PyPI под Android нет вовсе. Ставить такое в рантайме не нужно
    и невозможно.
    """
    cached = _bundled_versions.get(name)
    if cached is not None:
        return cached
    from importlib.metadata import version as _version
    for candidate in (name, _normalize(name), name.replace("-", "_")):
        try:
            found = Version(_version(candidate))
        except Exception:
            continue
        _bundled_versions[name] = found
        return found
    return None


def is_provided(name: str) -> bool:
    """Есть ли пакет в сборке, даже если метаданные до него не достают.

    Chaquopy держит зависимости в своих архивах, и importlib.metadata видит
    их не всегда, — поэтому вторая попытка идёт по имени модуля.
    """
    if name in _provided:
        return True
    if bundled_version(name) is not None:
        _provided.add(name)
        return True
    import importlib.util
    key = _normalize(name)
    candidates = [_IMPORT_ALIASES.get(key), name.replace("-", "_"),
                  name.replace("-", "_").lower()]
    for candidate in candidates:
        if not candidate:
            continue
        try:
            if importlib.util.find_spec(candidate) is not None:
                _provided.add(name)
                return True
        except Exception:
            continue
    return False


def _bundled_names() -> str:
    """Имена вшитых пакетов — для сообщения об ошибке."""
    try:
        from importlib.metadata import distributions
        names = sorted({d.metadata["Name"] for d in distributions()
                        if d.metadata and d.metadata["Name"]})
        return ", ".join(names)
    except Exception:
        return ""


def _pick_pure_wheel(pypi_data: dict, requirement: Requirement):
    """-> (Version, file dict) of the newest satisfying pure-Python wheel."""
    python_version = platform.python_version()
    best = None
    releases = pypi_data.get("releases") or {}
    for version_text, files in releases.items():
        try:
            version = Version(version_text)
        except _invalid_version():
            continue
        if not requirement.specifier.contains(version, prereleases=None):
            continue
        for file_info in files or []:
            filename = file_info.get("filename") or ""
            if file_info.get("packagetype") != "bdist_wheel" \
                    or not filename.endswith(_PURE_WHEEL_SUFFIXES):
                continue
            requires_python = file_info.get("requires_python")
            if requires_python:
                try:
                    if not SpecifierSet(requires_python).contains(python_version):
                        continue
                except Exception:
                    continue
            if best is None or version > best[0]:
                best = (version, file_info)
            break  # one pure wheel per release is enough
    if best is None:
        spec = str(requirement.specifier) or "(any version)"
        bundled = _bundled_names()
        raise RuntimeError(
            f"{requirement.name} {spec}: на Android ставятся только чисто "
            "питоновские пакеты, а у этого есть нативная часть — её собирают "
            "вместе с приложением, а не по требованию плагина."
            + (f" Уже вшиты: {bundled}." if bundled else ""))
    return best


def _safe_extract(archive_bytes: bytes, target_dir: str) -> None:
    os.makedirs(target_dir, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as zf:
        for member in zf.namelist():
            normalized = os.path.normpath(member)
            if normalized.startswith("..") or os.path.isabs(normalized):
                raise RuntimeError(f"unsafe path in wheel archive: {member!r}")
        zf.extractall(target_dir)


def _verify_digest(normalized_name: str, version: Version, file_info: dict,
                   payload: bytes) -> None:
    """Сверить sha256 с тем, что отдал индекс PyPI.

    Цифры нет — не повод отказывать (её нет у части старых релизов), но если
    она есть и не сходится, файл не наш и разворачивать его нельзя.
    """
    expected = (file_info.get("digests") or {}).get("sha256")
    if not expected:
        return
    actual = hashlib.sha256(payload).hexdigest()
    if actual != expected.lower():
        raise RuntimeError(
            f"sha256 mismatch for {normalized_name!r} {version}: "
            f"PyPI says {expected}, downloaded file is {actual}")


def _download_and_install(normalized_name: str, version: Version, file_info: dict) -> str:
    url = file_info.get("url")
    if not url:
        raise RuntimeError(f"No pure-Python wheel found for {normalized_name!r} "
                           f"{version} (file entry has no download URL)")
    declared = file_info.get("size")
    if isinstance(declared, int) and declared > _MAX_WHEEL_BYTES:
        raise RuntimeError(
            f"{normalized_name!r} {version} is {declared // (1024 * 1024)} MB, "
            f"over the {_MAX_WHEEL_BYTES // (1024 * 1024)} MB limit")
    try:
        response = requests.get(url, timeout=_HTTP_TIMEOUT * 2, stream=True)
        response.raise_for_status()
        chunks = []
        received = 0
        for chunk in response.iter_content(chunk_size=64 * 1024):
            if not chunk:
                continue
            received += len(chunk)
            # Проверяем по мере скачивания: заявленный размер PyPI может и
            # соврать, а память телефона кончится по-настоящему.
            if received > _MAX_WHEEL_BYTES:
                raise RuntimeError(
                    f"{normalized_name!r} {version} exceeds the "
                    f"{_MAX_WHEEL_BYTES // (1024 * 1024)} MB limit")
            chunks.append(chunk)
        payload = b"".join(chunks)
    except requests.RequestException as e:
        raise RuntimeError(f"failed to download {normalized_name!r} {version}: {e}")
    _verify_digest(normalized_name, version, file_info, payload)
    target = os.path.join(_shared_libs_dir(), normalized_name, str(version))
    if os.path.isdir(target):
        shutil.rmtree(target, ignore_errors=True)
    try:
        _safe_extract(payload, target)
    except Exception:
        shutil.rmtree(target, ignore_errors=True)
        raise
    return target


# Public API

def ensure_requirements(plugin_id: str, requirements) -> None:
    """Install (or reuse) every PEP 508 requirement of *plugin_id*.

    Raises RuntimeError with a clear message on unresolvable requirements,
    missing pure-Python wheels or version conflicts with packages already
    installed for other plugins.
    """
    if not requirements:
        return
    parsed = []
    for raw in requirements:
        try:
            requirement = Requirement(str(raw))
        except Exception as e:
            raise RuntimeError(f"invalid requirement {raw!r}: {e}")
        if requirement.marker is not None and not requirement.marker.evaluate():
            continue  # base marker support: python_version/sys_platform & co.
        parsed.append(requirement)

    for requirement in parsed:
        name = _normalize(requirement.name)
        with _lock:
            manifest = _load_manifest()
            existing = manifest["packages"].get(name)

        if existing is not None:
            installed = Version(existing["version"])
            if requirement.specifier.contains(installed, prereleases=True):
                with _lock:
                    plugins = existing.setdefault("plugins", [])
                    if plugin_id not in plugins:
                        plugins.append(plugin_id)
                        _save_manifest()
                if os.path.isdir(existing.get("path") or ""):
                    _ensure_on_sys_path(existing["path"])
                continue
            others = ", ".join(existing.get("plugins") or ()) or "other plugins"
            raise RuntimeError(
                f"Dependency conflict: {name} {existing['version']} is already "
                f"installed for {others}, but {plugin_id!r} requires "
                f"'{requirement}'")

        bundled = bundled_version(requirement.name)
        if bundled is not None:
            if not requirement.specifier.contains(bundled, prereleases=True):
                print(f"[exteraless:pip] {plugin_id!r} wants '{requirement}', "
                      f"the build ships {bundled} — using it anyway",
                      file=sys.stderr)
            continue
        if is_provided(requirement.name):
            print(f"[exteraless:pip] '{requirement}' is already in the build",
                  file=sys.stderr)
            continue

        pypi_data = _fetch_pypi_json(name)
        version, file_info = _pick_pure_wheel(pypi_data, requirement)
        path = _download_and_install(name, version, file_info)
        with _lock:
            manifest = _load_manifest()
            manifest["packages"][name] = {
                "version": str(version),
                "path": path,
                "plugins": [plugin_id],
            }
            _save_manifest()
        _ensure_on_sys_path(path)


def remove_requirements(plugin_id: str) -> None:
    """Drop *plugin_id* from every package's refcount; remove orphans."""
    _bundled_versions.clear()
    _provided.clear()
    with _lock:
        manifest = _load_manifest()
        removed_paths = []
        for name in list(manifest["packages"]):
            entry = manifest["packages"][name]
            plugins = entry.setdefault("plugins", [])
            if plugin_id in plugins:
                plugins.remove(plugin_id)
            if not plugins:
                path = entry.get("path")
                if path:
                    shutil.rmtree(path, ignore_errors=True)
                    removed_paths.append(path)
                del manifest["packages"][name]
        _save_manifest()
        for path in removed_paths:
            _sys_path_added.discard(path)
            while path in sys.path:
                sys.path.remove(path)


def installed_packages() -> dict:
    """Snapshot of the manifest (name -> entry); for diagnostics/dev-server."""
    return json.loads(json.dumps(_load_manifest()["packages"]))
