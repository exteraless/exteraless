"""Java reflection helpers (find_class, private fields) — exteraless plugin SDK.

NOTE: Android's hidden-API restrictions may block setAccessible(True) on
SDK-internal classes on newer Android versions; all helpers fail softly
(return None/False) in that case.
"""

from typing import Any, Optional


_internal_modules = {}


def _internal(name):
    module = _internal_modules.get(name)
    if module is None:
        import importlib
        module = importlib.import_module("extera_utils." + name)
        _internal_modules[name] = module
    return module


def find_class(name: str):
    """Java-класс по имени или None, если его нет или он плагину не положен.

    None здесь — штатный ответ, а не поломка: плагины его проверяют (188 мест
    в каталоге). Поэтому отказ в разрешении выглядит для плагина так же, как
    отсутствующий класс, и обрабатывается его же кодом.
    """
    requested = name
    try:
        resolve = _internal("class_aliases").resolve
        name = resolve(name)
    except Exception:
        pass
    try:
        guard_java_class = _internal("plugin_loader").guard_java_class
        if not guard_java_class(name):
            return None
    except Exception:
        pass  # сломанная проверка не должна закрывать доступ к Java
    try:
        from java import jclass
        found = jclass(name)
    except Exception:
        return None
    try:
        adapt = _internal("class_aliases").adapt
        return adapt(requested, found)
    except Exception:
        return found


_class_type = None


def _as_class(obj):
    """Normalize obj to a java.lang.Class instance.

    Accepts a live object, a jclass wrapper (find_class result) or an
    already-reflected java.lang.Class.
    """
    global _class_type
    try:
        unwrap = _internal("class_aliases").unwrap
        obj = unwrap(obj)
    except Exception:
        pass
    class_type = _class_type
    if class_type is None:
        from java import jclass
        class_type = _class_type = jclass("java.lang.Class")
    if isinstance(obj, class_type):
        return obj
    # Chaquopy: SomeClass.getClass() on a jclass is equivalent to SomeClass.class.
    return obj.getClass()


def _find_field(class_obj, name: str):
    """Find a declared field walking up the superclass chain."""
    current = class_obj
    while current is not None:
        try:
            fields = current.getDeclaredFields()
            for index in range(len(fields)):
                field = fields[index]
                if str(field.getName()) == name:
                    return field
        except Exception:
            pass
        try:
            current = current.getSuperclass()
        except Exception:
            break
    return None


_field_cache = {}
_NO_FIELD = object()


def _accessible_field(class_obj, name: str):
    try:
        key = (class_obj, name)
        cached = _field_cache.get(key)
    except Exception:
        key = None
        cached = None
    if cached is not None:
        return None if cached is _NO_FIELD else cached
    field = _find_field(class_obj, name)
    if field is not None:
        field.setAccessible(True)
    if key is not None and len(_field_cache) < 2048:
        _field_cache[key] = _NO_FIELD if field is None else field
    return field


def get_private_field(obj, name: str) -> Any:
    """Read a (possibly private) instance field; None when not found/inaccessible."""
    try:
        field = _accessible_field(_as_class(obj), name)
        if field is None:
            return None
        return field.get(obj)
    except Exception:
        return None


def set_private_field(obj, name: str, value) -> bool:
    """Write a (possibly private) instance field; True on success."""
    try:
        field = _accessible_field(_as_class(obj), name)
        if field is None:
            return False
        field.set(obj, value)
        return True
    except Exception:
        return False


def get_static_private_field(clazz, name: str) -> Any:
    """Read a (possibly private) static field of a class; None on failure."""
    try:
        field = _accessible_field(_as_class(clazz), name)
        if field is None:
            return None
        return field.get(None)
    except Exception:
        return None


def set_static_private_field(clazz, name: str, value) -> bool:
    """Write a (possibly private) static field of a class; True on success."""
    try:
        field = _accessible_field(_as_class(clazz), name)
        if field is None:
            return False
        field.set(None, value)
        return True
    except Exception:
        return False


# Plugins use both spellings interchangeably; the reference SDK exposes the
# short names as aliases of the private-field helpers.
get_field = get_private_field
set_field = set_private_field
