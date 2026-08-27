import importlib.util
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import corpus
import javaapi

REPO = os.environ.get("EXTERALESS_REPO", corpus.REPO)
PYTHON_ROOT = os.environ.get("EXTERALESS_PYTHON_ROOT", corpus.PYTHON_ROOT)
CLASS_ALIASES = os.path.join(PYTHON_ROOT, "extera_utils", "class_aliases.py")
PROGUARD_RULES = os.path.join(REPO, "TMessagesProj", "proguard-rules.pro")
USAGE = os.path.join(REPO, "TMessagesProj", "build", "outputs", "mapping", "release", "usage.txt")

EXTERA_CONFIG = "com.exteragram.messenger.ExteraConfig"

PLUGIN_ENTRY_POINTS = (
    ("com.exteragram.messenger.utils.AppUtils", "getGson"),
    ("com.exteragram.messenger.plugins.PluginsController", "getEngines"),
    ("com.exteragram.messenger.utils.chats.ChatUtils", "getDCName"),
)


def _load(path, name):
    if not os.path.isfile(path):
        pytest.skip(f"missing {path}")
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def aliases():
    return _load(CLASS_ALIASES, "exteraless_class_aliases")


class FakeExteraConfig:

    calls = 0

    @classmethod
    def pluginsSafeMode(cls):
        cls.calls += 1
        return False

    @classmethod
    def iconPack(cls):
        return "solar"

    @classmethod
    def getDialogFilters(cls):
        return ()

    version = 12


@pytest.fixture
def fake_config():
    FakeExteraConfig.calls = 0
    return FakeExteraConfig


def test_adapt_does_not_wrap_an_already_adapted_class(aliases, fake_config):
    once = aliases.adapt(EXTERA_CONFIG, fake_config)
    twice = aliases.adapt(EXTERA_CONFIG, once)

    assert aliases.unwrap(once) is fake_config
    assert aliases.unwrap(twice) is fake_config

    value = twice.pluginsSafeMode

    assert value is False
    assert not callable(value)
    assert isinstance(value, bool)
    assert fake_config.calls == 1


def test_adapt_survives_the_loader_and_find_class_pair(aliases, fake_config):
    from_jclass = aliases.adapt(EXTERA_CONFIG, fake_config)
    from_find_class = aliases.adapt(EXTERA_CONFIG, from_jclass)

    if from_find_class.pluginsSafeMode:
        raise AssertionError("safe mode must read as False, not as a truthy method object")


def test_field_shaped_class_calls_only_field_shaped_names(aliases, fake_config):
    wrapped = aliases.adapt(EXTERA_CONFIG, fake_config)

    assert wrapped.pluginsSafeMode is False
    assert wrapped.iconPack == "solar"
    assert callable(wrapped.getDialogFilters)
    assert wrapped.getDialogFilters() == ()
    assert wrapped.version == 12
    assert aliases.unwrap(wrapped) is fake_config
    assert aliases.unwrap(fake_config) is fake_config
    assert aliases.unwrap(None) is None
    with pytest.raises(AttributeError):
        wrapped.thereIsNoSuchMember


def test_adapt_leaves_unlisted_classes_alone(aliases, fake_config):
    other = aliases.adapt("com.exteragram.messenger.plugins.PythonPluginsEngine", fake_config)

    assert other is fake_config
    assert aliases.adapt(EXTERA_CONFIG, None) is None


def _shape_pair(target):
    if isinstance(target, (tuple, list)):
        return target[0], (target[1] if len(target) > 1 else None)
    return target, None


def test_adapt_wraps_by_the_name_find_class_really_passes(aliases):
    for source, fields in aliases._FIELD_SHAPED.items():
        fake = type("FakeJavaClass", (), {
            _shape_pair(target)[0]: staticmethod(lambda: "read as a field")
            for target in fields.values()})
        for name in (source, aliases.resolve(source)):
            wrapped = aliases.adapt(name, fake)
            assert aliases.unwrap(wrapped) is fake
            for attr in fields:
                assert getattr(wrapped, attr) == "read as a field", \
                    f"adapt({name!r}) left {attr} as a method object"


def test_field_shaped_attributes_point_at_a_real_java_method(aliases):
    assert aliases._FIELD_SHAPED
    for source, fields in aliases._FIELD_SHAPED.items():
        target = aliases.resolve(source)
        jtype = javaapi.type_of(target)
        assert jtype is not None, f"{source} resolves to a missing class {target}"
        assert fields
        for attr, shape in fields.items():
            method, setter = _shape_pair(shape)
            assert jtype.method_arities(method), \
                f"{target}.{method}() is gone, so {source}.{attr} reads as nothing"
            assert 0 in jtype.method_arities(method), \
                f"{target}.{method}() needs arguments, so it cannot read as a field"
            if setter is None:
                continue
            assert 1 in jtype.method_arities(setter), \
                f"{target}.{setter}() cannot take the value written to {source}.{attr}"


def test_alias_targets_declare_what_plugins_call(aliases):
    for source, member in PLUGIN_ENTRY_POINTS:
        target = aliases.resolve(source)
        jtype = javaapi.type_of(target)
        assert jtype is not None, f"{source} resolves to a missing class {target}"
        assert jtype.method_arities(member), \
            f"{source} resolves to {target}, which has no {member}()"


def test_resolve_maps_every_exact_name(aliases):
    assert len(aliases._EXACT) >= 7
    for source, expected in aliases._EXACT.items():
        assert aliases.resolve(source) == expected


def test_resolve_maps_every_prefix(aliases):
    assert len(aliases._PREFIXES) >= 12
    for old, new in aliases._PREFIXES:
        assert aliases.resolve(old + "Sample") == new + "Sample"


def test_resolve_keeps_the_nested_class_suffix(aliases):
    assert aliases.resolve(
        "com.exteragram.messenger.pillstack.core.PillRegistry$PillInfo") == \
        "app.exteraless.pillstack.PillRegistry$PillInfo"
    assert aliases.resolve(
        "com.exteragram.messenger.pillstack.ui.PillStackLayout$Slot") == \
        "app.exteraless.pillstack.PillStackView$Slot"


def test_resolve_leaves_foreign_names_untouched(aliases):
    for name in (
        "org.telegram.messenger.ApplicationLoader",
        "app.exteraless.plugins.PluginsController",
        "com.exteragram.messenger.SomethingWeDoNotShip",
        "java.lang.String",
    ):
        assert aliases.resolve(name) == name
    assert aliases.resolve(None) is None
    assert aliases.is_alias("org.telegram.messenger.ApplicationLoader") is False
    assert aliases.is_alias(EXTERA_CONFIG) is True


def _glob_to_regex(pattern):
    out = []
    index = 0
    while index < len(pattern):
        if pattern.startswith("**", index):
            out.append(".*")
            index += 2
        elif pattern[index] == "*":
            out.append("[^.]*")
            index += 1
        elif pattern[index] == "?":
            out.append("[^.]")
            index += 1
        else:
            out.append(re.escape(pattern[index]))
            index += 1
    return re.compile("^" + "".join(out) + "$")


KEEP_CLASS_DIRECTIVES = ("-keep", "-keepclasseswithmembers", "-keepclasseswithmembernames")
CLASS_KEYWORDS = ("class", "interface", "enum")


def _kept_class_patterns(path):
    patterns = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line.startswith("-"):
                continue
            head = line.partition("{")[0]
            tokens = head.split()
            directive, _, modifiers = tokens[0].partition(",")
            if directive not in KEEP_CLASS_DIRECTIVES:
                continue
            if "allowshrinking" in modifiers:
                continue
            if "extends" in tokens or "implements" in tokens:
                continue
            spec = None
            for index, token in enumerate(tokens):
                if token in CLASS_KEYWORDS and index + 1 < len(tokens):
                    spec = tokens[index + 1]
                    break
            if spec is None:
                continue
            for item in spec.split(","):
                item = item.strip()
                if item and not item.startswith("@") and not item.startswith("!"):
                    patterns.append(item)
    return patterns


TGNET_TL_SAMPLES = (
    "org.telegram.tgnet.tl.TL_stories",
    "org.telegram.tgnet.tl.TL_account",
    "org.telegram.tgnet.tl.TL_stories$StoryItem",
    "org.telegram.tgnet.tl.TL_bots$botInfo",
)


def test_proguard_keeps_tgnet_tl_containers():
    patterns = _kept_class_patterns(PROGUARD_RULES)
    compiled = [(item, _glob_to_regex(item)) for item in patterns]
    for sample in TGNET_TL_SAMPLES:
        covering = [item for item, rx in compiled if rx.match(sample)]
        assert covering, (
            f"{PROGUARD_RULES} has no unconditional keep rule covering {sample}; "
            f"without it R8 shrinks the tgnet TL containers away")


def test_release_mapping_did_not_remove_tgnet_tl():
    if not os.path.isfile(USAGE):
        pytest.skip(f"no R8 usage report at {USAGE}")
    if os.path.getmtime(USAGE) < os.path.getmtime(PROGUARD_RULES):
        pytest.skip("the R8 usage report predates proguard-rules.pro")
    removed = sorted(
        name for name in corpus.r8_removed()
        if name.startswith("org.telegram.tgnet.tl."))
    assert not removed, "R8 removed tgnet TL containers: " + ", ".join(removed[:20])
