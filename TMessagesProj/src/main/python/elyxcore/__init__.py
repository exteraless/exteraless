from elyx_runtime.assets import (
    Asset,
    AssetNotFoundException,
    Assets,
    AssetsDirNotFoundException,
)
from elyx_runtime.facade import gen, gen2, get_environment, import_module, mvel_execute
from elyx_runtime.facade import install_facade as _install_facade
from elyx_runtime.localization import Strings
from elyx_runtime.settings import SettingsController
from elyx_runtime.utils import LazyDict

__all__ = (
    "Asset",
    "AssetNotFoundException",
    "Assets",
    "AssetsDirNotFoundException",
    "Callback",
    "Callback2",
    "Callback3",
    "CallbackReturn",
    "LazyDict",
    "OnClickListener",
    "Runnable",
    "SettingsController",
    "Strings",
    "gen",
    "gen2",
    "get_environment",
    "import_module",
    "mvel_execute",
)

_PROXIES = frozenset((
    "Callback",
    "Callback2",
    "Callback3",
    "CallbackReturn",
    "OnClickListener",
    "Runnable",
))


def __getattr__(name):
    if name not in _PROXIES:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    value = getattr(_install_facade(), name)
    globals()[name] = value
    return value
