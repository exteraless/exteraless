"""Settings screen item declarations for plugins — part of the exteraless plugin SDK.

Pure Python dataclasses; serialization into the JSON schema consumed by the
Java renderer lives in extera_utils.plugin_loader.
"""

from typing import Any, Callable, List, Optional

from dataclasses import dataclass
import math


@dataclass
class Header:
    text: str


@dataclass
class Divider:
    text: Optional[str] = None


@dataclass
class Switch:
    key: str
    text: str
    default: bool
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable] = None
    on_long_click: Optional[Callable] = None
    link_alias: Optional[str] = None


@dataclass
class Selector:
    key: str
    text: str
    default: int
    items: List[str]
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable] = None
    on_long_click: Optional[Callable] = None
    link_alias: Optional[str] = None


@dataclass
class Input:
    key: str
    text: str
    default: Optional[str] = None
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable] = None
    on_long_click: Optional[Callable] = None
    link_alias: Optional[str] = None


@dataclass
class Text:
    text: str
    subtext: Optional[str] = None
    icon: Optional[str] = None
    accent: bool = False
    red: bool = False
    on_click: Optional[Callable] = None
    on_long_click: Optional[Callable] = None
    create_sub_fragment: Optional[Callable[[], list]] = None
    link_alias: Optional[str] = None


@dataclass
class EditText:
    key: str
    hint: str
    default: str = ""
    multiline: bool = False
    max_length: Optional[int] = None
    mask: Optional[str] = None
    on_change: Optional[Callable] = None


@dataclass
class Custom:
    item: Optional[Any] = None
    view: Optional[Any] = None
    factory: Optional[Any] = None
    factory_args: Optional[tuple] = None
    on_click: Optional[Callable] = None
    on_long_click: Optional[Callable] = None
    create_sub_fragment: Optional[Callable[[], list]] = None
    link_alias: Optional[str] = None


class SimpleSettingFactory:
    """Declarative factory for Custom settings items.

    ``create_view(context)`` and ``bind_view(view, item, divider)`` are called
    by the settings renderer when the row is drawn. ``java``/``instance`` give
    the shared Java peer (app.exteraless.plugins.models.PluginItemFactory),
    which delegates back to this object instead of generating a subclass.
    """

    def __init__(self, create_view=None, bind_view=None, is_clickable: bool = False,
                 is_shadow: bool = False, create_item=None, on_click=None,
                 on_long_click=None, attached_view=None, equals=None,
                 content_equals=None):
        self.create_view = create_view
        self.bind_view = bind_view
        self.is_clickable = is_clickable
        self.is_shadow = is_shadow
        self.create_item = create_item
        self.on_click = on_click
        self.on_long_click = on_long_click
        self.attached_view = attached_view
        self.equals = equals
        self.content_equals = content_equals

    def build_view(self, context, divider=False):
        if not callable(self.create_view):
            return None
        try:
            view = self.create_view(context)
        except TypeError:
            view = self.create_view()
        if view is None:
            return None
        if callable(self.bind_view):
            item = self.create_item() if callable(self.create_item) else None
            try:
                self.bind_view(view, item, divider)
            except TypeError:
                try:
                    self.bind_view(view)
                except TypeError:
                    pass
        return view

    @property
    def instance(self):
        """The bridged Java peer of this factory."""
        return self.java

    @property
    def java(self):
        from java import jclass
        return jclass("app.exteraless.plugins.models.PluginItemFactory").getInstance()

    def to_item(self, *factory_args):
        from java import jclass
        return jclass("app.exteraless.plugins.models.PluginItemFactory").create(
            self, factory_args or None)

    def __call__(self, *factory_args, link_alias: Optional[str] = None) -> Custom:
        """Factory(link_alias="x") or Factory(*factory_args) -> Custom(...)."""
        return Custom(factory=self,
                      factory_args=factory_args or None,
                      link_alias=link_alias)


PluginItemFactory = SimpleSettingFactory


@dataclass
class Slider:
    key: str
    text: str
    default: float = 0
    min: float = 0
    max: float = 100
    step: float = 1
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable] = None
    on_long_click: Optional[Callable] = None
    link_alias: Optional[str] = None

    def __post_init__(self):
        if not all(math.isfinite(float(value)) for value in (self.default, self.min, self.max, self.step)):
            raise ValueError("Slider values must be finite")
        if self.max <= self.min or self.step <= 0 or math.ceil((self.max - self.min) / self.step) > 2147483647:
            raise ValueError("Invalid slider range or step")

    def normalize(self, value):
        try:
            value = float(value)
            if not math.isfinite(value):
                value = self.default
        except (TypeError, ValueError):
            value = self.default
        value = min(self.max, max(self.min, value))
        value = min(self.max, self.min + math.floor((value - self.min) / self.step + 0.5) * self.step)
        return int(value) if all(float(v).is_integer() for v in (self.min, self.max, self.step)) else value
