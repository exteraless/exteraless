package app.exteraless.player;

import android.text.TextUtils;
import android.util.Xml;

import org.json.JSONTokener;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LyricsFormats {

    private static final Pattern YAML_ENTRY = Pattern.compile("^( *)(- )?([A-Za-z_]+):(?: (.*))?$");

    private LyricsFormats() {
    }

    static Lyrics parseTtml(String xml, int source, String provider) throws XmlPullParserException, IOException {
        if (TextUtils.isEmpty(xml)) {
            return null;
        }
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new StringReader(xml));
        ArrayList<Lyrics.Line> lines = new ArrayList<>();
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "p".equals(parser.getName())) {
                Lyrics.Line line = readParagraph(parser);
                if (line != null) {
                    lines.add(line);
                }
            }
            event = parser.next();
        }
        return lines.isEmpty() ? null : Lyrics.fromTimed(lines, source, provider);
    }

    private static boolean isSkippedRole(String role) {
        return "x-bg".equals(role) || "x-translation".equals(role) || "x-roman".equals(role);
    }

    private static int nextEvent(XmlPullParser parser) throws XmlPullParserException, IOException {
        int event = parser.next();
        if (event == XmlPullParser.END_DOCUMENT) {
            throw new XmlPullParserException("unexpected end of ttml");
        }
        return event;
    }

    private static void skipElement(XmlPullParser parser) throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            int event = nextEvent(parser);
            if (event == XmlPullParser.START_TAG) {
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static String readElementText(XmlPullParser parser) throws XmlPullParserException, IOException {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int event = nextEvent(parser);
            if (event == XmlPullParser.START_TAG) {
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            } else if (event == XmlPullParser.TEXT) {
                text.append(parser.getText());
            }
        }
        return text.toString();
    }

    private static Lyrics.Line readParagraph(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (isSkippedRole(parser.getAttributeValue(null, "role"))) {
            skipElement(parser);
            return null;
        }
        String paragraphBegin = parser.getAttributeValue(null, "begin");
        Lyrics.LineBuilder builder = new Lyrics.LineBuilder();
        int depth = 1;
        while (depth > 0) {
            int event = nextEvent(parser);
            if (event == XmlPullParser.START_TAG) {
                boolean span = "span".equals(parser.getName());
                if (span && isSkippedRole(parser.getAttributeValue(null, "role"))) {
                    skipElement(parser);
                    continue;
                }
                String begin = span ? parser.getAttributeValue(null, "begin") : null;
                String end = span ? parser.getAttributeValue(null, "end") : null;
                if (begin != null && end != null) {
                    builder.add(readElementText(parser), parseTime(begin), parseTime(end));
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            } else if (event == XmlPullParser.TEXT) {
                String text = parser.getText();
                if (text.trim().isEmpty()) {
                    if (!text.isEmpty()) {
                        builder.space();
                    }
                } else {
                    builder.appendText(text);
                }
            }
        }
        long time = paragraphBegin != null ? parseTime(paragraphBegin) : builder.firstStart();
        if (time < 0) {
            return null;
        }
        Lyrics.Line line = builder.build(time);
        return line.text.isEmpty() ? null : line;
    }

    private static long parseTime(String value) {
        String time = value.trim();
        if (time.indexOf(':') >= 0) {
            double seconds = 0;
            for (String part : time.split(":")) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return Math.round(seconds * 1000);
        }
        if (time.endsWith("ms")) {
            return Math.round(Double.parseDouble(time.substring(0, time.length() - 2)));
        }
        double multiplier = 1000;
        if (time.endsWith("h")) {
            multiplier = 3_600_000;
        } else if (time.endsWith("m")) {
            multiplier = 60_000;
        }
        if (time.endsWith("h") || time.endsWith("m") || time.endsWith("s")) {
            time = time.substring(0, time.length() - 1);
        }
        return Math.round(Double.parseDouble(time) * multiplier);
    }

    private static final class LyricsFileReader {
        final ArrayList<Lyrics.Line> lines = new ArrayList<>();
        Lyrics.LineBuilder builder;
        String lineText;
        long lineStart = -1;
        long lineEnd = -1;
        String wordText;
        long wordStart = -1;
        long wordEnd = -1;

        void finishWord() {
            if (wordText != null && builder != null && wordStart >= 0) {
                builder.add(wordText, wordStart, wordEnd);
            }
            wordText = null;
            wordStart = -1;
            wordEnd = -1;
        }

        void finishLine() {
            if (wordEnd < 0) {
                wordEnd = lineEnd;
            }
            finishWord();
            if (lineText != null && lineStart >= 0) {
                String text = lineText.trim();
                Lyrics.Line line = builder != null && builder.hasWords() ? builder.build(lineStart) : null;
                lines.add(line != null && line.text.equals(text) ? line : new Lyrics.Line(lineStart, text));
            }
            builder = new Lyrics.LineBuilder();
            lineText = null;
            lineStart = -1;
            lineEnd = -1;
        }
    }

    static Lyrics parseLyricsFile(String yaml, int source, String provider) throws Exception {
        if (TextUtils.isEmpty(yaml)) {
            return null;
        }
        LyricsFileReader reader = new LyricsFileReader();
        boolean inLines = false;
        boolean inWord = false;
        int lineIndent = -1;
        for (String row : yaml.split("\n")) {
            if (row.trim().isEmpty()) {
                continue;
            }
            Matcher m = YAML_ENTRY.matcher(row);
            boolean entry = m.matches();
            if (!inLines) {
                inLines = entry && m.group(1).isEmpty() && m.group(2) == null && "lines".equals(m.group(3));
                continue;
            }
            if (entry && m.group(1).isEmpty() && m.group(2) == null) {
                break;
            }
            if (!entry) {
                return null;
            }
            int indent = m.group(1).length();
            boolean item = m.group(2) != null;
            String key = m.group(3);
            String value = m.group(4);
            if (item) {
                if (!"text".equals(key)) {
                    return null;
                }
                if (lineIndent < 0 || indent <= lineIndent) {
                    reader.finishLine();
                    lineIndent = indent;
                    reader.lineText = yamlString(value);
                    inWord = false;
                } else {
                    reader.finishWord();
                    reader.wordText = yamlString(value);
                    inWord = true;
                }
            } else if ("start_ms".equals(key)) {
                if (inWord) {
                    reader.wordStart = Long.parseLong(value.trim());
                } else {
                    reader.lineStart = Long.parseLong(value.trim());
                }
            } else if ("end_ms".equals(key)) {
                if (inWord) {
                    reader.wordEnd = Long.parseLong(value.trim());
                } else {
                    reader.lineEnd = Long.parseLong(value.trim());
                }
            }
        }
        reader.finishLine();
        return reader.lines.isEmpty() ? null : Lyrics.fromTimed(reader.lines, source, provider);
    }

    private static String yamlString(String raw) throws Exception {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("|") || value.startsWith(">")) {
            throw new IllegalArgumentException("unsupported yaml block");
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return String.valueOf(new JSONTokener(value).nextValue());
        }
        return value;
    }
}
