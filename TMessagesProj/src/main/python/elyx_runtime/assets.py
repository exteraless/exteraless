"""Asset access for Elyx plugins (PLUGINS-ELYX.md §7).

`Assets` represents one resource directory, `Asset` one file. Lookup rules:
  - three equivalent styles: assets.logo, assets["logo"], assets.get("logo")
  - the extension may be included: assets["logo.svg"]
  - nested paths are supported: assets["icons/add"] and assets.icons.add
  - attribute lookup uses the normalized stem: everything before the first
    "." of the filename, with non [A-Za-z0-9] characters turned into "_"
    (empty-state.svg -> assets.empty_state, logo.dark.png -> assets.logo)
  - a directory match yields another Assets object, a file match an Asset;
    missing entries raise AssetNotFoundException.

Android conversion helpers (to_drawable, to_svg_*, to_lottie_drawable, ...)
resolve the Java classes lazily so this module stays importable on a host
interpreter; calling them outside the Chaquopy runtime raises RuntimeError.
"""

from __future__ import annotations

import json
import os
import re
import tempfile
from pathlib import Path
from typing import Iterator, Optional, Union

import yaml

from .utils import lazy_wrap

_NORMALIZE_PATTERN = re.compile(r"[^0-9A-Za-z]")


class AssetNotFoundException(AttributeError):
    """The requested asset file does not exist.

    Subclasses AttributeError so attribute-style lookup on Assets keeps normal
    Python semantics (hasattr/getattr with default) while plugin code can still
    `except AssetNotFoundException` exactly as the spec documents.
    """


class AssetsDirNotFoundException(Exception):
    """The assets root directory no longer exists."""


def normalize_name(name: str) -> str:
    """Normalization used for attribute-style lookup (punctuation -> '_')."""
    return _NORMALIZE_PATTERN.sub("_", name)


def logical_name(filename: str) -> str:
    """Logical lookup name of a file: normalized stem before the first dot."""
    return normalize_name(filename.split(".")[0])


def _java_class(name: str):
    """Lazily resolve a Java class through Chaquopy; clear error on a host."""
    try:
        from java import jclass
    except Exception:
        raise RuntimeError(
            f"{name} requires the Android/Chaquopy runtime; not available on a "
            "host interpreter"
        )
    return jclass(name)


class Asset:
    """One bundled asset file."""

    def __init__(self, dir_path: Union[str, Path], filename: str,
                 name: Optional[str] = None):
        self._dir_path = Path(dir_path)
        self._filename = filename
        self._name = name if name is not None else logical_name(filename)

    # ---- identity / paths ----

    @property
    def name(self) -> str:
        """Normalized logical name."""
        return self._name

    @property
    def filename(self) -> str:
        return self._filename

    @property
    def ext(self) -> str:
        """Last file extension without the dot ("" when none)."""
        return self._filename.rsplit(".", 1)[1] if "." in self._filename else ""

    @property
    def path(self) -> Path:
        return self._dir_path / self._filename

    @property
    def path_str(self) -> str:
        return str(self.path)

    @property
    def java_file(self):
        return _java_class("java.io.File")(self.path_str)

    def __repr__(self) -> str:
        return f"Asset({self.path_str!r})"

    # ---- content ----

    def content_bytes(self) -> bytes:
        return self.path.read_bytes()

    def content_string(self) -> str:
        return self.path.read_text(encoding="utf-8")

    def content_json(self):
        return lazy_wrap(json.loads(self.content_bytes().decode("utf-8")))

    def content_yaml(self):
        return lazy_wrap(yaml.safe_load(self.content_bytes().decode("utf-8")))

    def content(self):
        """Parse .json/.yaml/.yml, else UTF-8 text, else raw bytes."""
        ext = self.ext.lower()
        if ext == "json":
            return self.content_json()
        if ext in ("yaml", "yml"):
            return self.content_yaml()
        raw = self.content_bytes()
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError:
            return raw

    # ---- construction helpers ----

    @classmethod
    def from_path(cls, path) -> "Asset":
        """Build an Asset from a str / pathlib.Path / java.io.File path."""
        if isinstance(path, (str, Path)):
            fs_path = Path(path)
        elif hasattr(path, "getAbsolutePath"):  # java.io.File
            fs_path = Path(str(path.getAbsolutePath()))
        else:
            raise ValueError(f"unsupported asset path: {path!r}")
        if not fs_path.is_file():
            raise AssetNotFoundException(f"no asset file at {fs_path}")
        return cls(fs_path.parent, fs_path.name)

    @classmethod
    def temp_asset_from_url(cls, url: str, filename: str) -> "Asset":
        """Download *url* into a temp file and wrap it as an Asset.

        Synchronous network + file I/O: call from a background queue, never
        from a UI callback or a high-frequency hook.
        """
        from urllib.request import urlopen

        safe_name = os.path.basename(filename) or "download"
        dest_dir = Path(tempfile.mkdtemp(prefix="elyx_asset_"))
        dest = dest_dir / safe_name
        with urlopen(url) as response, open(dest, "wb") as handle:
            while True:
                chunk = response.read(65536)
                if not chunk:
                    break
                handle.write(chunk)
        return cls(dest_dir, safe_name)

    # ---- Android conversions (lazy; Chaquopy-only) ----

    def to_drawable(self):
        drawable = _java_class("android.graphics.drawable.Drawable")
        return drawable.createFromPath(self.path_str)

    def to_image_location(self):
        image_location = _java_class("org.telegram.messenger.ImageLocation")
        return image_location.getForPath(self.path_str)

    def to_bitmap_drawable(self, width: int = 32, height: int = 32):
        bitmap_factory = _java_class("android.graphics.BitmapFactory")
        bitmap = _java_class("android.graphics.Bitmap")
        bitmap_drawable = _java_class("android.graphics.drawable.BitmapDrawable")
        decoded = bitmap_factory.decodeFile(self.path_str)
        scaled = bitmap.createScaledBitmap(decoded, int(width), int(height), True)
        return bitmap_drawable(_resources(), scaled)

    def to_svg_drawable(self, width: Optional[int] = None, height: Optional[int] = None):
        svg_helper = _java_class("org.telegram.messenger.SvgHelper")
        if width is None or height is None:
            return svg_helper.getDrawable(self.content_string())
        bitmap_drawable = _java_class("android.graphics.drawable.BitmapDrawable")
        bitmap = svg_helper.getBitmap(self.java_file, int(width), int(height), False)
        return bitmap_drawable(_resources(), bitmap)

    def to_svg_bitmap(self, width: int = 32, height: int = 32, white: bool = False):
        svg_helper = _java_class("org.telegram.messenger.SvgHelper")
        return svg_helper.getBitmap(self.java_file, int(width), int(height), bool(white))

    def to_svg_thumb(self, color_key: str, alpha: float = 1.0):
        """Themed SVG drawable tinted with a Theme color key."""
        svg_helper = _java_class("org.telegram.messenger.SvgHelper")
        theme = _java_class("org.telegram.ui.ActionBar.Theme")
        porter_duff = _java_class("android.graphics.PorterDuff")
        color_filter = _java_class("android.graphics.PorterDuffColorFilter")
        drawable = svg_helper.getDrawable(self.content_string())
        color = theme.getColor(color_key)
        drawable.setColorFilter(color_filter(color, porter_duff.Mode.SRC_IN))
        drawable.setAlpha(max(0, min(255, int(round(alpha * 255)))))
        return drawable

    def to_lottie_drawable(self, width: int = 32, height: int = 32):
        """Lottie animation drawable; sizes are dp, converted via AndroidUtilities."""
        android_utilities = _java_class("org.telegram.messenger.AndroidUtilities")
        rlottie = _java_class("org.telegram.ui.Components.RLottieDrawable")
        w = android_utilities.dp(width)
        h = android_utilities.dp(height)
        return rlottie(self.java_file, self.content_string(), w, h,
                       None, False, None, 0, False)


def _resources():
    try:
        return _java_class("org.telegram.messenger.ApplicationLoader").applicationContext.getResources()
    except Exception:
        return None


class Assets:
    """One asset directory; supports get/item/attribute access and nesting."""

    def __init__(self, dir_path: Union[str, Path], _parent: Optional["Assets"] = None):
        path = Path(dir_path)
        if not path.exists():
            raise AssetsDirNotFoundException(f"assets directory {dir_path} does not exist")
        if not path.is_dir():
            raise ValueError(f"{dir_path} is not an asset directory")
        self._dir = path
        self._parent = _parent

    # ---- structure ----

    @property
    def dir_path(self) -> Path:
        return self._dir

    @property
    def parent(self) -> Optional["Assets"]:
        if self._parent is not None:
            return self._parent
        parent = self._dir.parent
        if parent == self._dir:
            return None
        return Assets(parent)

    def __len__(self) -> int:
        return sum(1 for _ in self._dir.iterdir())

    def __iter__(self) -> Iterator[str]:
        return iter(sorted(child.name for child in self._dir.iterdir()))

    def __contains__(self, name) -> bool:
        try:
            self.get(name)
            return True
        except AssetNotFoundException:
            return False

    def __repr__(self) -> str:
        return f"Assets({str(self._dir)!r}, entries={len(self)})"

    # ---- lookup ----

    def _lookup_child(self, segment: str) -> Path:
        """Resolve one path segment to an existing child path."""
        exact = self._dir / segment
        if exact.exists():
            return exact
        wanted = normalize_name(segment)
        for child in sorted(self._dir.iterdir()):
            if child.is_dir():
                if normalize_name(child.name) == wanted:
                    return child
            elif logical_name(child.name) == wanted or \
                    normalize_name(child.name) == wanted:
                return child
        raise AssetNotFoundException(f"asset {segment!r} not found in {self._dir}")

    def get(self, name) -> Union[Asset, "Assets"]:
        """Asset for a file, Assets for a directory; raises AssetNotFoundException."""
        if not isinstance(name, str):
            raise AssetNotFoundException(f"bad asset name {name!r}")
        segments = [part for part in name.strip("/").split("/") if part]
        if not segments:
            raise AssetNotFoundException("empty asset name")
        node: Assets = self
        for index, segment in enumerate(segments):
            child = node._lookup_child(segment)
            if child.is_dir():
                node = Assets(child, _parent=node)
            else:
                if index != len(segments) - 1:
                    raise AssetNotFoundException(
                        f"{segment!r} in {name!r} is a file, not a directory"
                    )
                return Asset(node._dir, child.name)
        return node

    def __getitem__(self, name) -> Union[Asset, "Assets"]:
        return self.get(name)

    def __getattr__(self, name: str) -> Union[Asset, "Assets"]:
        if name.startswith("_"):
            raise AttributeError(name)
        return self.get(name)
