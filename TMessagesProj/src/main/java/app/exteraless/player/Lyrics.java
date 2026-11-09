package app.exteraless.player;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Lyrics {

    public static final int SOURCE_FILE = 0;
    public static final int SOURCE_ONLINE = 1;

    public static final class Word {
        public final long start;
        public final long end;
        public final int from;
        public final int to;

        Word(long start, long end, int from, int to) {
            this.start = start;
            this.end = end;
            this.from = from;
            this.to = to;
        }
    }

    public static final class Line {
        public final long time;
        public final String text;
        public final ArrayList<Word> words;

        Line(long time, String text) {
            this(time, text, null);
        }

        Line(long time, String text, ArrayList<Word> words) {
            this.time = time;
            this.text = text;
            this.words = words;
        }

        long end() {
            return words == null || words.isEmpty() ? -1 : words.get(words.size() - 1).end;
        }
    }

    static final class LineBuilder {
        private final StringBuilder text = new StringBuilder();
        private final ArrayList<long[]> spans = new ArrayList<>();

        void space() {
            if (text.length() > 0 && text.charAt(text.length() - 1) != ' ') {
                text.append(' ');
            }
        }

        void appendText(String value) {
            if (value.isEmpty()) {
                return;
            }
            if (Character.isWhitespace(value.charAt(0))) {
                space();
            }
            text.append(value.trim());
            if (Character.isWhitespace(value.charAt(value.length() - 1))) {
                space();
            }
        }

        void add(String token, long start, long end) {
            if (token.isEmpty()) {
                return;
            }
            if (Character.isWhitespace(token.charAt(0))) {
                space();
            }
            String body = token.trim();
            if (!body.isEmpty()) {
                int from = text.length();
                text.append(body);
                spans.add(new long[]{start, end, from, text.length()});
            }
            if (Character.isWhitespace(token.charAt(token.length() - 1))) {
                space();
            }
        }

        boolean hasWords() {
            return !spans.isEmpty();
        }

        long firstStart() {
            return spans.isEmpty() ? -1 : spans.get(0)[0];
        }

        Line build(long time) {
            String value = text.toString().trim();
            boolean timed = false;
            for (long[] span : spans) {
                timed |= span[0] > 0;
            }
            if (!timed) {
                return new Line(time, value);
            }
            ArrayList<Word> words = new ArrayList<>(spans.size());
            for (int i = 0; i < spans.size(); i++) {
                long[] span = spans.get(i);
                long start = Math.max(0, span[0]);
                long next = i + 1 < spans.size() ? spans.get(i + 1)[0] : -1;
                long end = span[1] > start ? span[1] : next > start ? next : start + DEFAULT_WORD_MS;
                words.add(new Word(start, end, (int) span[2], Math.min((int) span[3], value.length())));
            }
            return new Line(time, value, words);
        }
    }

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_TIME = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final long DEFAULT_WORD_MS = 300;
    private static final long INTERLUDE_GAP_MS = 5000;
    private static final Pattern OFFSET = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern META = Pattern.compile("^\\[[a-zA-Z#]+:.*]$");

    public final ArrayList<Line> lines;
    public final boolean synced;
    public final boolean wordSynced;
    public final boolean instrumental;
    public final int source;
    public final String provider;

    private Lyrics(ArrayList<Line> lines, boolean synced, boolean instrumental, int source, String provider) {
        this.lines = lines;
        this.synced = synced;
        boolean hasWords = false;
        for (Line line : lines) {
            hasWords |= line.words != null;
        }
        this.wordSynced = synced && hasWords;
        this.instrumental = instrumental;
        this.source = source;
        this.provider = provider;
    }

    public static Lyrics instrumental(int source, String provider) {
        return new Lyrics(new ArrayList<>(), false, true, source, provider);
    }

    static Lyrics synced(ArrayList<Line> lines, int source, String provider) {
        return new Lyrics(lines, true, false, source, provider);
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (Line line : lines) {
            if (synced) {
                sb.append('[').append(formatTime(line.time)).append(']');
            }
            if (line.words == null) {
                sb.append(line.text);
            } else {
                int cursor = 0;
                for (Word word : line.words) {
                    sb.append(line.text, cursor, word.from);
                    sb.append('<').append(formatTime(word.start)).append('>');
                    sb.append(line.text, word.from, word.to);
                    sb.append('<').append(formatTime(word.end)).append('>');
                    cursor = word.to;
                }
                sb.append(line.text, cursor, line.text.length());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String formatTime(long ms) {
        long t = Math.max(0, ms);
        return String.format(Locale.US, "%02d:%02d.%03d", t / 60000, t / 1000 % 60, t % 1000);
    }

    static boolean hasTiming(ArrayList<Line> lines) {
        for (Line line : lines) {
            if (line.time > 0) {
                return true;
            }
        }
        return false;
    }

    static Lyrics fromTimed(ArrayList<Line> timed, int source, String provider) {
        Collections.sort(timed, (a, b) -> Long.compare(a.time, b.time));
        ArrayList<Line> out = new ArrayList<>(timed.size());
        for (int i = 0; i < timed.size(); i++) {
            Line l = timed.get(i);
            boolean blank = l.text.isEmpty();
            if (blank && (out.isEmpty() || out.get(out.size() - 1).text.isEmpty() || i == timed.size() - 1)) {
                continue;
            }
            if (!blank && !out.isEmpty()) {
                Line prev = out.get(out.size() - 1);
                long end = prev.end();
                if (end > 0 && !prev.text.isEmpty() && l.time - end >= INTERLUDE_GAP_MS) {
                    out.add(new Line(end, ""));
                }
            }
            out.add(l);
        }
        return out.isEmpty() ? null : new Lyrics(out, true, false, source, provider);
    }

    private static Line timedLine(long time, String rest, long offset, boolean withWords) {
        if (!withWords) {
            return new Line(time, WORD_TIME.matcher(rest).replaceAll("").trim());
        }
        Matcher m = WORD_TIME.matcher(rest);
        LineBuilder builder = new LineBuilder();
        int cursor = 0;
        long pending = -1;
        boolean tagged = false;
        while (m.find()) {
            tagged = true;
            long tag = Math.max(0, toMillis(m.group(1), m.group(2), m.group(3)) - offset);
            addSegment(builder, rest.substring(cursor, m.start()), pending, tag);
            pending = tag;
            cursor = m.end();
        }
        if (!tagged) {
            return new Line(time, rest.trim());
        }
        addSegment(builder, rest.substring(cursor), pending, -1);
        return builder.build(time);
    }

    private static void addSegment(LineBuilder builder, String segment, long start, long end) {
        if (segment.trim().isEmpty()) {
            if (!segment.isEmpty()) {
                builder.space();
            }
        } else if (start < 0) {
            builder.appendText(segment);
        } else {
            builder.add(segment, start, end);
        }
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public static Lyrics parse(String text, int source) {
        return parse(text, source, null);
    }

    public static Lyrics parse(String text, int source, String provider) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] raw = normalized.split("\n");
        long offset = 0;
        ArrayList<Line> timed = new ArrayList<>();
        for (String row : raw) {
            String line = row.trim();
            Matcher off = OFFSET.matcher(line);
            if (off.find()) {
                try {
                    offset = Long.parseLong(off.group(1));
                } catch (NumberFormatException ignore) {
                }
                continue;
            }
            Matcher m = TIME.matcher(line);
            ArrayList<Long> times = null;
            int end = 0;
            while (m.find() && m.start() == end) {
                if (times == null) {
                    times = new ArrayList<>();
                }
                times.add(toMillis(m.group(1), m.group(2), m.group(3)));
                end = m.end();
            }
            if (times == null) {
                continue;
            }
            String rest = line.substring(end);
            for (long t : times) {
                timed.add(timedLine(Math.max(0, t - offset), rest, offset, times.size() == 1));
            }
        }
        if (!timed.isEmpty()) {
            return fromTimed(timed, source, provider);
        }
        ArrayList<Line> plain = new ArrayList<>();
        for (String row : raw) {
            String line = row.trim();
            if (META.matcher(line).matches()) {
                continue;
            }
            if (line.isEmpty() && (plain.isEmpty() || plain.get(plain.size() - 1).text.isEmpty())) {
                continue;
            }
            plain.add(new Line(-1, line));
        }
        while (!plain.isEmpty() && plain.get(plain.size() - 1).text.isEmpty()) {
            plain.remove(plain.size() - 1);
        }
        return plain.isEmpty() ? null : new Lyrics(plain, false, false, source, provider);
    }

    private static long toMillis(String min, String sec, String frac) {
        long ms = Long.parseLong(min) * 60_000L + Long.parseLong(sec) * 1000L;
        if (frac != null) {
            int f = Integer.parseInt(frac);
            if (frac.length() == 1) {
                ms += f * 100L;
            } else if (frac.length() == 2) {
                ms += f * 10L;
            } else {
                ms += f;
            }
        }
        return ms;
    }

    public int indexAt(long ms) {
        if (!synced) {
            return -1;
        }
        int lo = 0;
        int hi = lines.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).time <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }
}
