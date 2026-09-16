"""HTML/Markdown → Telegram message entities parser — exteraless plugin SDK.

The parsing core is pure Python and host-testable: it produces RawEntity
objects. Only the final materialization into org.telegram.tgnet.TLRPC
entities requires the Android runtime (lazy import).

Entity offsets/lengths are expressed in UTF-16 code units, matching Java
string semantics on Android (this is what the TL layer expects).
"""

import re
from dataclasses import dataclass
from enum import Enum
from html.parser import HTMLParser
from typing import Any, Dict, List, Optional, Tuple


class TLEntityType(Enum):
    CODE = "code"
    PRE = "pre"
    STRIKETHROUGH = "strikethrough"
    TEXT_LINK = "text_link"
    BOLD = "bold"
    ITALIC = "italic"
    UNDERLINE = "underline"
    SPOILER = "spoiler"
    CUSTOM_EMOJI = "custom_emoji"
    BLOCKQUOTE = "blockquote"


@dataclass
class RawEntity:
    """Intermediate entity representation before TLRPC materialization.

    offset/length are UTF-16 code-unit positions into the plain text.
    """
    offset: int
    length: int
    type: TLEntityType
    url: Optional[str] = None
    language: Optional[str] = None
    document_id: Optional[int] = None
    collapsed: bool = False


# HTML parsing

_HTML_TAGS = {
    "b": TLEntityType.BOLD,
    "strong": TLEntityType.BOLD,
    "i": TLEntityType.ITALIC,
    "em": TLEntityType.ITALIC,
    "u": TLEntityType.UNDERLINE,
    "s": TLEntityType.STRIKETHROUGH,
    "del": TLEntityType.STRIKETHROUGH,
    "strike": TLEntityType.STRIKETHROUGH,
    "code": TLEntityType.CODE,
    "pre": TLEntityType.PRE,
    "spoiler": TLEntityType.SPOILER,
    "tg-spoiler": TLEntityType.SPOILER,
    "a": TLEntityType.TEXT_LINK,
    "blockquote": TLEntityType.BLOCKQUOTE,
    "emoji": TLEntityType.CUSTOM_EMOJI,
    "tg-emoji": TLEntityType.CUSTOM_EMOJI,
}

_LANGUAGE_CLASS_RE = re.compile(r"language-([\w#+-]+)")


class _EntityHTMLParser(HTMLParser):
    """Streaming HTML → (plain text, RawEntity list) converter."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self._parts: List[str] = []
        self._length = 0
        self._stack: List[list] = []  # [tag, TLEntityType, start_offset, extra]
        self.entities: List[RawEntity] = []

    # -- output --

    def _emit(self, data: str):
        if data:
            self._parts.append(data)
            self._length += len(data)

    # -- tag handling --

    def handle_starttag(self, tag, attrs):
        self._start(tag.lower(), dict(attrs))

    def handle_startendtag(self, tag, attrs):
        tag = tag.lower()
        if tag == "br":
            self._emit("\n")
        else:
            self._start(tag, dict(attrs))
            self._end(tag)

    def handle_endtag(self, tag):
        self._end(tag.lower())

    def handle_data(self, data):
        self._emit(data)

    def _inside_pre(self) -> bool:
        return bool(self._stack) and self._stack[-1][1] is TLEntityType.PRE

    def _start(self, tag: str, attrs: Dict[str, str]):
        if tag == "br":
            self._emit("\n")
            return
        entity_type = _HTML_TAGS.get(tag)
        if entity_type is None:
            return  # unknown tag: ignored, its text content is still captured

        if self._inside_pre():
            # <pre><code class="language-x"> carries the language of the block;
            # any other formatting inside <pre> is treated as literal text.
            if entity_type is TLEntityType.CODE:
                match = _LANGUAGE_CLASS_RE.search(attrs.get("class") or "")
                if match:
                    self._stack[-1][3]["language"] = match.group(1)
            return

        extra: Dict[str, Any] = {}
        if entity_type is TLEntityType.TEXT_LINK:
            extra["url"] = attrs.get("href") or ""
        elif entity_type is TLEntityType.PRE:
            extra["language"] = attrs.get("language") or ""
        elif entity_type is TLEntityType.CUSTOM_EMOJI:
            raw_id = attrs.get("id") or attrs.get("emoji-id") or ""
            extra["document_id"] = int(raw_id) if str(raw_id).isdigit() else None
        elif entity_type is TLEntityType.BLOCKQUOTE:
            extra["collapsed"] = "expandable" in attrs or "collapsed" in attrs
        self._stack.append([tag, entity_type, self._length, extra])

    def _end(self, tag: str):
        # Close the nearest matching open tag, implicitly closing anything
        # still open above it (browser-style recovery from misnesting).
        for index in range(len(self._stack) - 1, -1, -1):
            if self._stack[index][0] == tag:
                while len(self._stack) > index:
                    self._close_top()
                return

    def _close_top(self):
        _tag, entity_type, start, extra = self._stack.pop()
        length = self._length - start
        if length <= 0:
            return  # zero-length entities are invalid for Telegram
        if entity_type is TLEntityType.TEXT_LINK and not extra.get("url"):
            return
        if entity_type is TLEntityType.CUSTOM_EMOJI and extra.get("document_id") is None:
            return
        self.entities.append(RawEntity(offset=start, length=length,
                                       type=entity_type, **extra))

    def result(self) -> Tuple[str, List[RawEntity]]:
        while self._stack:
            self._close_top()
        self.entities.sort(key=lambda e: (e.offset, -e.length))
        return "".join(self._parts), self.entities


def _parse_html(text: str) -> Tuple[str, List[RawEntity]]:
    parser = _EntityHTMLParser()
    parser.feed(text)
    parser.close()
    return parser.result()


# Markdown parsing
#
# Single left-to-right pass with a combined regex; the leftmost alternative
# wins, and matched spans are never re-scanned (so code/pre content stays
# literal). Nested inline markup (e.g. bold inside a link text) is not
# expanded — a documented simplification of this SDK build.

_MD_RE = re.compile(
    r"(?P<pre>```(?P<prelang>\w+)?[ \t]*\n?(?P<prebody>.*?)```)"
    r"|(?P<quote>^(?:\*\*>|>)[ \t]?[^\n]*(?:\n(?:\*\*>|>)[ \t]?[^\n]*)*)"
    r"|(?P<emoji>!\[(?P<emojialt>[^\]]*)\]\(tg://emoji\?id=(?P<emojiid>\d+)\))"
    r"|(?P<link>\[(?P<linktext>[^\]]+)\]\((?P<linkurl>[^)\s]+)\))"
    r"|(?P<code>`(?P<codebody>[^`\n]+)`)"
    r"|(?P<spoiler>\|\|(?P<spoilerbody>.+?)\|\|)"
    r"|(?P<bold2>\*\*(?P<bold2body>[^*\n]+?)\*\*)"
    r"|(?P<bold>\*(?P<boldbody>[^*\n]+?)\*)"
    r"|(?P<underline>(?<![\w])__(?P<underlinebody>[^_\n]+?)__(?![\w]))"
    r"|(?P<italic>(?<![\w])_(?P<italicbody>[^_\n]+?)_(?![\w]))"
    r"|(?P<strike2>~~(?P<strike2body>[^~\n]+?)~~)"
    r"|(?P<strike>~(?P<strikebody>[^~\n]+?)~)",
    re.S | re.M,
)

_MD_QUOTE_MARKER_RE = re.compile(r"^(?:\*\*>|>)[ \t]?")

_MD_INLINE_TYPES = {
    "code": ("codebody", TLEntityType.CODE),
    "spoiler": ("spoilerbody", TLEntityType.SPOILER),
    "bold2": ("bold2body", TLEntityType.BOLD),
    "bold": ("boldbody", TLEntityType.BOLD),
    "underline": ("underlinebody", TLEntityType.UNDERLINE),
    "italic": ("italicbody", TLEntityType.ITALIC),
    "strike2": ("strike2body", TLEntityType.STRIKETHROUGH),
    "strike": ("strikebody", TLEntityType.STRIKETHROUGH),
}


def _parse_markdown(text: str) -> Tuple[str, List[RawEntity]]:
    out: List[str] = []
    entities: List[RawEntity] = []
    plain_length = 0
    position = 0

    for match in _MD_RE.finditer(text):
        start, end = match.span()
        chunk = text[position:start]
        out.append(chunk)
        plain_length += len(chunk)

        kind = match.lastgroup
        offset = plain_length

        if kind == "pre":
            body = match.group("prebody")
            if body.endswith("\n"):
                body = body[:-1]
            language = match.group("prelang") or None
            out.append(body)
            plain_length += len(body)
            if body:
                entities.append(RawEntity(offset, len(body), TLEntityType.PRE,
                                          language=language))
        elif kind == "quote":
            raw = match.group("quote")
            collapsed = raw.startswith("**>")
            body = "\n".join(
                _MD_QUOTE_MARKER_RE.sub("", line) for line in raw.split("\n")
            )
            out.append(body)
            plain_length += len(body)
            if body:
                entities.append(RawEntity(offset, len(body), TLEntityType.BLOCKQUOTE,
                                          collapsed=collapsed))
        elif kind == "emoji":
            alt = match.group("emojialt") or "\U0001F642"  # placeholder char
            document_id = int(match.group("emojiid"))
            out.append(alt)
            plain_length += len(alt)
            entities.append(RawEntity(offset, len(alt), TLEntityType.CUSTOM_EMOJI,
                                      document_id=document_id))
        elif kind == "link":
            body = match.group("linktext")
            url = match.group("linkurl")
            out.append(body)
            plain_length += len(body)
            emoji_id = url[len("tg://emoji?id="):] if url.startswith("tg://emoji?id=") else url
            if emoji_id.isdigit():
                entities.append(RawEntity(offset, len(body), TLEntityType.CUSTOM_EMOJI,
                                          document_id=int(emoji_id)))
            else:
                entities.append(RawEntity(offset, len(body), TLEntityType.TEXT_LINK,
                                          url=url))
        else:
            group_name, entity_type = _MD_INLINE_TYPES[kind]
            body = match.group(group_name)
            out.append(body)
            plain_length += len(body)
            entities.append(RawEntity(offset, len(body), entity_type))

        position = end

    out.append(text[position:])
    return "".join(out), entities


# Public API

def _utf16_length(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def parse_raw(text: str, parse_mode: Optional[str] = "HTML") -> Tuple[str, List[RawEntity]]:
    """Parse *text* into (plain_text, [RawEntity]) with UTF-16 code-unit offsets.

    Pure Python; safe to use on a host interpreter.
    """
    if text is None:
        text = ""
    text = str(text)
    mode = (parse_mode or "HTML").lower()
    if mode == "html":
        plain, entities = _parse_html(text)
    elif mode == "markdown":
        plain, entities = _parse_markdown(text)
    else:
        raise ValueError(
            f"unsupported parse_mode {parse_mode!r}: expected 'HTML' or 'Markdown'"
        )

    for entity in entities:
        utf16_offset = _utf16_length(plain[:entity.offset])
        utf16_length = _utf16_length(plain[entity.offset:entity.offset + entity.length])
        entity.offset, entity.length = utf16_offset, utf16_length
    return plain, entities


def _materialize_entities(raw_entities: List[RawEntity]) -> List[Any]:
    """Convert RawEntity objects into TLRPC.MessageEntity instances (needs the JVM)."""
    from org.telegram.tgnet import TLRPC

    result = []
    for raw in raw_entities:
        entity_type = raw.type
        if entity_type is TLEntityType.BOLD:
            entity = TLRPC.TL_messageEntityBold()
        elif entity_type is TLEntityType.ITALIC:
            entity = TLRPC.TL_messageEntityItalic()
        elif entity_type is TLEntityType.UNDERLINE:
            entity = TLRPC.TL_messageEntityUnderline()
        elif entity_type is TLEntityType.STRIKETHROUGH:
            entity = TLRPC.TL_messageEntityStrike()
        elif entity_type is TLEntityType.CODE:
            entity = TLRPC.TL_messageEntityCode()
        elif entity_type is TLEntityType.PRE:
            entity = TLRPC.TL_messageEntityPre()
            entity.language = raw.language or ""
        elif entity_type is TLEntityType.TEXT_LINK:
            entity = TLRPC.TL_messageEntityTextUrl()
            entity.url = raw.url or ""
        elif entity_type is TLEntityType.SPOILER:
            entity = TLRPC.TL_messageEntitySpoiler()
        elif entity_type is TLEntityType.CUSTOM_EMOJI:
            entity = TLRPC.TL_messageEntityCustomEmoji()
            entity.document_id = int(raw.document_id or 0)  # long field
        elif entity_type is TLEntityType.BLOCKQUOTE:
            entity = TLRPC.TL_messageEntityBlockquote()
            entity.collapsed = bool(raw.collapsed)
        else:
            continue
        entity.offset = int(raw.offset)
        entity.length = int(raw.length)
        result.append(entity)
    return result


def parse_text(text: str, parse_mode: Optional[str] = "HTML",
               is_caption: bool = False) -> Dict[str, Any]:
    """Parse formatted text into {"message"|"caption": str, "entities": [...]}.

    Entities are TLRPC.MessageEntity objects. On a host interpreter without
    Chaquopy the TLRPC classes are unavailable; in that case the raw
    RawEntity list is returned instead (useful for tests).
    """
    plain, raw_entities = parse_raw(text, parse_mode)
    try:
        from org.telegram.tgnet import TLRPC  # noqa: F401
    except Exception:
        entities: List[Any] = list(raw_entities)
    else:
        entities = _materialize_entities(raw_entities)
    key = "caption" if is_caption else "message"
    return {key: plain, "entities": entities}
