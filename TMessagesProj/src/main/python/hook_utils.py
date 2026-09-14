"""Java reflection helpers (find_class, private fields) — exteraless plugin SDK.

NOTE: Android's hidden-API restrictions may block setAccessible(True) on
SDK-internal classes on newer Android versions; all helpers fail softly
(return None/False) in that case.
"""

from typing import Any, Optional


def find_class(name: str):
    """Java-класс по имени или None, если его нет или он плагину не положен.

    None здесь — штатный ответ, а не поломка: плагины его проверяют (188 мест
    в каталоге). Поэтому отказ в разрешении выглядит для плагина так же, как
    отсутствующий класс, и обрабатывается его же кодом.
    """
    requested = name
    try:
        from extera_utils.class_aliases import resolve
        name = resolve(name)
    except Exception:
        pass
    try:
        from extera_utils.plugin_loader import guard_java_class
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
        from extera_utils.class_aliases import adapt
        return adapt(requested, found)
    except Exception:
        return found


def _as_class(obj):
    """Normalize obj to a java.lang.Class instance.

    Accepts a live object, a jclass wrapper (find_class result) or an
    already-reflected java.lang.Class.
    """
    from java import jclass
    try:
        from extera_utils.class_aliases import unwrap
        obj = unwrap(obj)
    except Exception:
        pass
    class_type = jclass("java.lang.Class")
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


def get_private_field(obj, name: str) -> Any:
    """Read a (possibly private) instance field; None when not found/inaccessible."""
    try:
        field = _find_field(_as_class(obj), name)
        if field is None:
            return None
        field.setAccessible(True)
        return field.get(obj)
    except Exception:
        return None


def set_private_field(obj, name: str, value) -> bool:
    """Write a (possibly private) instance field; True on success."""
    try:
        field = _find_field(_as_class(obj), name)
        if field is None:
            return False
        field.setAccessible(True)
        field.set(obj, value)
        return True
    except Exception:
        return False


def get_static_private_field(clazz, name: str) -> Any:
    """Read a (possibly private) static field of a class; None on failure."""
    try:
        field = _find_field(_as_class(clazz), name)
        if field is None:
            return None
        field.setAccessible(True)
        return field.get(None)
    except Exception:
        return None


def set_static_private_field(clazz, name: str, value) -> bool:
    """Write a (possibly private) static field of a class; True on success."""
    try:
        field = _find_field(_as_class(clazz), name)
        if field is None:
            return False
        field.setAccessible(True)
        field.set(None, value)
        return True
    except Exception:
        return False


# Plugins use both spellings interchangeably; the reference SDK exposes the
# short names as aliases of the private-field helpers.
get_field = get_private_field
set_field = set_private_field
