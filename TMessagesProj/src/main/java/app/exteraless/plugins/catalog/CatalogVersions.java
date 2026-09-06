package app.exteraless.plugins.catalog;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic, exception-free comparison for semver-like and legacy plugin versions. */
public final class CatalogVersions {
    private CatalogVersions() {}

    public static boolean isUpdateAvailable(String installed, String catalog) {
        return compare(catalog, installed) > 0;
    }

    public static int compare(String left, String right) {
        Version a = Version.parse(left);
        Version b = Version.parse(right);
        int max = Math.max(a.core.size(), b.core.size());
        for (int i = 0; i < max; i++) {
            Token x = i < a.core.size() ? a.core.get(i) : Token.ZERO;
            Token y = i < b.core.size() ? b.core.get(i) : Token.ZERO;
            int result = x.compareTo(y);
            if (result != 0) return result;
        }
        if (a.pre.isEmpty() != b.pre.isEmpty()) return a.pre.isEmpty() ? 1 : -1;
        max = Math.max(a.pre.size(), b.pre.size());
        for (int i = 0; i < max; i++) {
            if (i >= a.pre.size()) return -1;
            if (i >= b.pre.size()) return 1;
            int result = a.pre.get(i).comparePre(b.pre.get(i));
            if (result != 0) return result;
        }
        return 0;
    }

    private static final class Version {
        final String original;
        final List<Token> core;
        final List<Token> pre;

        Version(String original, List<Token> core, List<Token> pre) {
            this.original = original;
            this.core = core;
            this.pre = pre;
        }

        static Version parse(String source) {
            String original = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
            String normalized = original.startsWith("v") ? original.substring(1) : original;
            int build = normalized.indexOf('+');
            if (build >= 0) normalized = normalized.substring(0, build);
            int dash = normalized.indexOf('-');
            String core = dash < 0 ? normalized : normalized.substring(0, dash);
            String pre = dash < 0 ? "" : normalized.substring(dash + 1);
            return new Version(original, tokenize(core), tokenize(pre));
        }

        static List<Token> tokenize(String value) {
            ArrayList<Token> result = new ArrayList<>();
            int start = -1;
            boolean digit = false;
            for (int i = 0; i <= value.length(); i++) {
                char c = i < value.length() ? value.charAt(i) : '.';
                boolean currentDigit = i < value.length() && Character.isDigit(c);
                boolean word = i < value.length() && Character.isLetterOrDigit(c);
                if (!word || (start >= 0 && currentDigit != digit)) {
                    if (start >= 0) result.add(new Token(value.substring(start, i), digit));
                    start = word ? i : -1;
                    digit = currentDigit;
                } else if (start < 0) {
                    start = i;
                    digit = currentDigit;
                }
            }
            return result;
        }
    }

    private static final class Token implements Comparable<Token> {
        static final Token ZERO = new Token("0", true);
        final String text;
        final BigInteger number;

        Token(String text, boolean numeric) {
            this.text = text;
            this.number = numeric ? new BigInteger(text) : null;
        }

        @Override
        public int compareTo(Token other) {
            if (number != null && other.number != null) return number.compareTo(other.number);
            if (number != null) return 1;
            if (other.number != null) return -1;
            return text.compareTo(other.text);
        }

        int comparePre(Token other) {
            if (number != null && other.number == null) return -1;
            if (number == null && other.number != null) return 1;
            return compareTo(other);
        }
    }
}
