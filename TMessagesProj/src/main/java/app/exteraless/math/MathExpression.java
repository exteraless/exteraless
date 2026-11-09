package app.exteraless.math;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MathExpression {

    public static final class Suggestion {
        public final int insertAt;
        public final String insertText;
        public final String value;

        public Suggestion(int insertAt, String insertText, String value) {
            this.insertAt = insertAt;
            this.insertText = insertText;
            this.value = value;
        }
    }

    public static final class Result {
        public final double value;
        public final boolean hasOperation;
        public final char decimalSeparator;
        /** Finished text for results that are not a plain number. */
        public final String display;

        Result(double value, boolean hasOperation, char decimalSeparator) {
            this(value, hasOperation, decimalSeparator, null);
        }

        Result(double value, boolean hasOperation, char decimalSeparator, String display) {
            this.value = value;
            this.hasOperation = hasOperation;
            this.decimalSeparator = decimalSeparator;
            this.display = display;
        }
    }

    private interface Unary {
        double apply(double value);
    }

    private interface Binary {
        double apply(double left, double right, boolean percent);
    }

    private interface Variadic {
        double apply(double[] args);
    }

    private static final class Infix {
        final String[] symbols;
        final int precedence;
        final boolean rightAssociative;
        final Binary apply;

        Infix(String[] symbols, int precedence, boolean rightAssociative, Binary apply) {
            this.symbols = symbols;
            this.precedence = precedence;
            this.rightAssociative = rightAssociative;
            this.apply = apply;
        }
    }

    private static final class Prefix {
        final String[] symbols;
        final int precedence;
        final boolean operation;
        final Unary apply;

        Prefix(String[] symbols, int precedence, boolean operation, Unary apply) {
            this.symbols = symbols;
            this.precedence = precedence;
            this.operation = operation;
            this.apply = apply;
        }
    }

    private static final class Postfix {
        final String[] symbols;
        final boolean percent;
        final Unary apply;

        Postfix(String[] symbols, boolean percent, Unary apply) {
            this.symbols = symbols;
            this.percent = percent;
            this.apply = apply;
        }
    }

    private static final class Function {
        final int minArgs;
        final int maxArgs;
        final Variadic apply;

        Function(int minArgs, int maxArgs, Variadic apply) {
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
            this.apply = apply;
        }
    }

    private static final class ParseError extends RuntimeException {
        ParseError() {
            super(null, null, false, false);
        }
    }

    private static final ParseError ERROR = new ParseError();

    private static final Infix[] INFIX = {
            new Infix(new String[]{"+"}, 10, false, (a, b, percent) -> percent ? a + b * a : a + b),
            new Infix(new String[]{"-", "−"}, 10, false, (a, b, percent) -> percent ? a - b * a : a - b),
            new Infix(new String[]{"*", "×"}, 20, false, (a, b, percent) -> a * b),
            new Infix(new String[]{"/", "÷"}, 20, false, (a, b, percent) -> a / b),
            new Infix(new String[]{"^"}, 40, true, (a, b, percent) -> Math.pow(a, b)),
    };

    private static final Prefix[] PREFIX = {
            new Prefix(new String[]{"-", "−"}, 30, false, v -> -v),
            new Prefix(new String[]{"+"}, 30, false, v -> v),
            new Prefix(new String[]{"√"}, 40, true, Math::sqrt),
    };

    private static final Postfix[] POSTFIX = {
            new Postfix(new String[]{"%"}, true, v -> v / 100.0),
            new Postfix(new String[]{"!"}, false, MathExpression::factorial),
    };

    private static final Map<String, Function> FUNCTIONS = new HashMap<>();
    private static final Map<String, Double> CONSTANTS = new HashMap<>();
    private static final Map<String, Double> MULTIPLIERS = new HashMap<>();
    private static final Set<String> GLUED_MULTIPLIERS = new HashSet<>(Arrays.asList("к", "k", "кк", "kk", "ккк", "kkk"));
    private static final Set<String> PERCENT_OF = new HashSet<>(Arrays.asList("от", "of", "від"));
    private static final Set<String> CONVERSIONS = new HashSet<>(Arrays.asList("в", "во", "у", "to", "in"));
    private static final Set<String> TRIG = new HashSet<>(Arrays.asList("sin", "cos", "tan"));
    private static final Set<String> INVERSE_TRIG = new HashSet<>(Arrays.asList("asin", "acos", "atan", "atan2"));
    private static final Map<String, Integer> BASES = new HashMap<>();
    private static final String[] SYMBOLS;
    private static final Set<Character> SYMBOL_CHARS = new HashSet<>();

    static {
        FUNCTIONS.put("sqrt", new Function(1, 1, a -> Math.sqrt(a[0])));
        FUNCTIONS.put("cbrt", new Function(1, 1, a -> Math.cbrt(a[0])));
        FUNCTIONS.put("abs", new Function(1, 1, a -> Math.abs(a[0])));
        FUNCTIONS.put("sign", new Function(1, 1, a -> Math.signum(a[0])));
        FUNCTIONS.put("round", new Function(1, 1, a -> (double) Math.round(a[0])));
        FUNCTIONS.put("floor", new Function(1, 1, a -> Math.floor(a[0])));
        FUNCTIONS.put("ceil", new Function(1, 1, a -> Math.ceil(a[0])));
        FUNCTIONS.put("fact", new Function(1, 1, a -> factorial(a[0])));
        FUNCTIONS.put("min", new Function(1, Integer.MAX_VALUE, a -> extreme(a, false)));
        FUNCTIONS.put("max", new Function(1, Integer.MAX_VALUE, a -> extreme(a, true)));
        FUNCTIONS.put("log", new Function(1, 1, a -> Math.log10(a[0])));
        FUNCTIONS.put("lg", new Function(1, 1, a -> Math.log10(a[0])));
        FUNCTIONS.put("ln", new Function(1, 1, a -> Math.log(a[0])));
        FUNCTIONS.put("exp", new Function(1, 1, a -> Math.exp(a[0])));
        FUNCTIONS.put("sin", new Function(1, 1, a -> Math.sin(a[0])));
        FUNCTIONS.put("cos", new Function(1, 1, a -> Math.cos(a[0])));
        FUNCTIONS.put("tan", new Function(1, 1, a -> Math.tan(a[0])));
        FUNCTIONS.put("asin", new Function(1, 1, a -> Math.asin(a[0])));
        FUNCTIONS.put("acos", new Function(1, 1, a -> Math.acos(a[0])));
        FUNCTIONS.put("atan", new Function(1, 1, a -> Math.atan(a[0])));
        FUNCTIONS.put("atan2", new Function(2, 2, a -> Math.atan2(a[0], a[1])));
        FUNCTIONS.put("sinh", new Function(1, 1, a -> Math.sinh(a[0])));
        FUNCTIONS.put("cosh", new Function(1, 1, a -> Math.cosh(a[0])));
        FUNCTIONS.put("tanh", new Function(1, 1, a -> Math.tanh(a[0])));

        addMultiplier(1e3, "к", "k", "тыс", "тысяча", "тысячи", "тысяч", "тис", "тисяча", "тисячі", "тисяч", "thousand", "thousands");
        addMultiplier(1e6, "кк", "kk", "млн", "миллион", "миллиона", "миллионов", "мільйон", "мільйона", "мільйонів", "million", "millions");
        addMultiplier(1e9, "ккк", "kkk", "млрд", "миллиард", "миллиарда", "миллиардов", "мільярд", "мільярда", "мільярдів", "billion", "billions");
        BASES.put("bin", 2);
        BASES.put("oct", 8);
        BASES.put("hex", 16);

        CONSTANTS.put("pi", Math.PI);
        CONSTANTS.put("π", Math.PI);
        CONSTANTS.put("e", Math.E);

        ArrayList<String> symbols = new ArrayList<>();
        for (Infix op : INFIX) {
            addSymbols(symbols, op.symbols);
        }
        for (Prefix op : PREFIX) {
            addSymbols(symbols, op.symbols);
        }
        for (Postfix op : POSTFIX) {
            addSymbols(symbols, op.symbols);
        }
        symbols.sort((a, b) -> Integer.compare(b.length(), a.length()));
        SYMBOLS = symbols.toArray(new String[0]);
        for (String symbol : SYMBOLS) {
            for (int i = 0; i < symbol.length(); i++) {
                SYMBOL_CHARS.add(symbol.charAt(i));
            }
        }
        SYMBOL_CHARS.add('(');
        SYMBOL_CHARS.add(')');
        SYMBOL_CHARS.add(';');
    }

    private static void addMultiplier(double value, String... names) {
        for (String name : names) {
            MULTIPLIERS.put(name, value);
        }
    }

    private static void addSymbols(ArrayList<String> target, String[] symbols) {
        for (String symbol : symbols) {
            if (!target.contains(symbol)) {
                target.add(symbol);
            }
        }
    }

    private MathExpression() {
    }

    private static double extreme(double[] values, boolean max) {
        double result = values[0];
        for (int i = 1; i < values.length; i++) {
            result = max ? Math.max(result, values[i]) : Math.min(result, values[i]);
        }
        return result;
    }

    private static double logBase(double value, double base) {
        if (base <= 0 || base == 1) {
            return Double.NaN;
        }
        if (base == 10) {
            return Math.log10(value);
        }
        return Math.log(value) / Math.log(base);
    }

    private static Function logFunction(String name) {
        if (name.length() < 4 || name.length() > 9 || !name.startsWith("log")) {
            return null;
        }
        for (int i = 3; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return null;
            }
        }
        int base = Integer.parseInt(name.substring(3));
        return new Function(1, 1, a -> logBase(a[0], base));
    }

    private static double factorial(double value) {
        if (value < 0 || value != Math.floor(value) || value > 170) {
            return Double.NaN;
        }
        double result = 1;
        for (int i = 2; i <= (int) value; i++) {
            result *= i;
        }
        return result;
    }

    private static Infix infixFor(String symbol) {
        for (Infix op : INFIX) {
            for (String s : op.symbols) {
                if (s.equals(symbol)) {
                    return op;
                }
            }
        }
        return null;
    }

    private static Prefix prefixFor(String symbol) {
        for (Prefix op : PREFIX) {
            for (String s : op.symbols) {
                if (s.equals(symbol)) {
                    return op;
                }
            }
        }
        return null;
    }

    private static Postfix postfixFor(String symbol) {
        for (Postfix op : POSTFIX) {
            for (String s : op.symbols) {
                if (s.equals(symbol)) {
                    return op;
                }
            }
        }
        return null;
    }

    private static String matchSymbol(CharSequence source, int from) {
        for (String symbol : SYMBOLS) {
            int end = from + symbol.length();
            if (end > source.length()) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < symbol.length(); i++) {
                if (source.charAt(from + i) != symbol.charAt(i)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return symbol;
            }
        }
        return null;
    }

    static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == ' ';
    }

    static boolean isSymbolChar(char c) {
        return SYMBOL_CHARS.contains(c);
    }

    private static boolean isExpressionChar(char c) {
        return Character.isDigit(c) || Character.isLetter(c) || c == '.' || c == ',' || isBlank(c) || SYMBOL_CHARS.contains(c)
                || c == '°' || c == '²' || InlineUnits.symbol(c) != null || InlineCurrencies.isSymbol(c);
    }

    private static final int NUMBER = 0;
    private static final int SYMBOL = 1;
    private static final int OPEN = 2;
    private static final int CLOSE = 3;
    private static final int IDENTIFIER = 4;
    private static final int SEPARATOR = 5;
    private static final int END = 6;
    private static final int MEASURE = 7;

    private static final class Token {
        final int type;
        final double number;
        final String text;
        /** Identifier as typed, case decides a few currency codes. */
        final String raw;
        final boolean glued;
        /** {@link InlineUnits.Unit} or a currency code. */
        final Object measure;

        Token(int type, double number, String text) {
            this(type, number, text, text, false, null);
        }

        Token(int type, double number, String text, String raw, boolean glued, Object measure) {
            this.type = type;
            this.number = number;
            this.text = text;
            this.raw = raw;
            this.glued = glued;
            this.measure = measure;
        }
    }

    private static final class Operand {
        final double value;
        final boolean percent;
        final Object measure;

        Operand(double value, boolean percent) {
            this(value, percent, null);
        }

        Operand(double value, boolean percent, Object measure) {
            this.value = value;
            this.percent = percent;
            this.measure = measure;
        }
    }

    private static final class Parser {
        private final CharSequence source;
        private final char defaultSeparator;
        private final InlineOptions options;
        private final ArrayList<Token> tokens = new ArrayList<>();
        private int position;
        private int depth;
        private boolean hasOperation;
        private char separator;

        Parser(CharSequence source, char defaultSeparator, InlineOptions options) {
            this.source = source;
            this.defaultSeparator = defaultSeparator;
            this.options = options;
        }

        private char separator() {
            return separator != 0 ? separator : defaultSeparator;
        }

        Result parse() {
            tokenize();
            if (options != null && tokens.size() > 2 && tokens.get(0).type == IDENTIFIER && tokens.get(1).type == OPEN
                    && BASES.containsKey(tokens.get(0).text)) {
                return parseBase();
            }
            Operand operand = parseExpression(0);
            Object target = null;
            if (options != null && peek().type == IDENTIFIER && CONVERSIONS.contains(peek().text)) {
                next();
                target = readTarget(operand.measure);
            }
            expect(END);
            if (options == null) {
                return new Result(operand.value, hasOperation, separator());
            }
            if (operand.measure == null) {
                if (!options.arithmetic) {
                    throw ERROR;
                }
                return new Result(operand.value, hasOperation, separator());
            }
            String display = operand.measure instanceof String
                    ? moneyResult(operand.value, (String) operand.measure, (String) target)
                    : unitResult(operand.value, (InlineUnits.Unit) operand.measure, (InlineUnits.Unit) target);
            return new Result(operand.value, display != null, separator(), display);
        }

        private Result parseBase() {
            int radix = BASES.get(next().text);
            expect(OPEN);
            Operand operand = parseExpression(0);
            expect(CLOSE);
            expect(END);
            String display = operand.measure != null || !options.arithmetic ? null : formatBase(operand.value, radix);
            if (display == null) {
                throw ERROR;
            }
            return new Result(operand.value, true, separator(), display);
        }

        private Object readTarget(Object source) {
            Token token = next();
            Object target = token.type == MEASURE ? token.measure : null;
            if (token.type == IDENTIFIER) {
                if (source instanceof String) {
                    target = currencyWord(token.raw);
                } else if (options.units) {
                    target = InlineUnits.word(token.raw);
                }
            }
            if (target == null || source == null || target.getClass() != source.getClass()) {
                throw ERROR;
            }
            return target;
        }

        private String currencyWord(String raw) {
            String code = InlineCurrencies.word(raw);
            if (code == null) {
                // "фунт" and "pound" mean mass unless money is already involved
                InlineUnits.Unit unit = InlineUnits.word(raw);
                if (unit != null && unit.code.equals("lb")) {
                    code = "GBP";
                }
            }
            return code != null && options.rates != null && options.rates.supports(code) ? code : null;
        }

        private Object measureWord(Token token) {
            if (options.units) {
                InlineUnits.Unit unit = InlineUnits.word(token.raw);
                if (unit != null) {
                    return unit;
                }
            }
            String code = InlineCurrencies.word(token.raw);
            return code != null && options.rates != null && options.rates.supports(code) ? code : null;
        }

        private double convert(double value, Object from, Object to) {
            if (from.equals(to)) {
                return value;
            }
            if (from instanceof String && to instanceof String) {
                Double rate = options.rates == null ? null : options.rates.rate((String) from, (String) to);
                if (rate == null) {
                    throw ERROR;
                }
                return value * rate;
            }
            if (from instanceof InlineUnits.Unit && to instanceof InlineUnits.Unit
                    && ((InlineUnits.Unit) from).kind == ((InlineUnits.Unit) to).kind) {
                return ((InlineUnits.Unit) to).fromBase(((InlineUnits.Unit) from).toBase(value));
            }
            throw ERROR;
        }

        private String withLabel(String number, String label) {
            if (number == null) {
                throw ERROR;
            }
            return number + ' ' + label;
        }

        private String unitResult(double value, InlineUnits.Unit unit, InlineUnits.Unit target) {
            char mark = separator();
            boolean cyrillic = options.cyrillicLabels;
            if (target != null) {
                if (target == unit && !hasOperation) {
                    return null;
                }
                return withLabel(formatMeasure(convert(value, unit, target), mark, options.grouping), target.label(cyrillic));
            }
            if (InlineUnits.canBreakDown(unit)) {
                String duration = InlineUnits.formatDuration(value, unit, options);
                if (duration != null) {
                    return duration;
                }
            }
            if (hasOperation) {
                return withLabel(formatMeasure(value, mark, options.grouping), unit.label(cyrillic));
            }
            InlineUnits.Unit implicit = InlineUnits.implicitTarget(unit, value, options.imperial);
            if (implicit == null) {
                return null;
            }
            return withLabel(formatMeasure(convert(value, unit, implicit), mark, options.grouping), implicit.label(cyrillic));
        }

        private String moneyResult(double value, String code, String target) {
            String to = target != null ? target : options.localCurrency;
            if (to == null || (to.equals(code) && !hasOperation)) {
                return null;
            }
            String number = formatMoney(convert(value, code, to), InlineCurrencies.fractionDigits(to), separator(), options.grouping);
            return withLabel(number, InlineCurrencies.label(to));
        }

        private Operand combine(Infix op, Operand left, Operand right) {
            if (left.measure == null && right.measure == null) {
                return new Operand(op.apply.apply(left.value, right.value, right.percent), false);
            }
            char symbol = op.symbols[0].charAt(0);
            if (symbol == '^') {
                throw ERROR;
            }
            if (symbol == '+' || symbol == '-') {
                if (right.percent && right.measure == null) {
                    return new Operand(op.apply.apply(left.value, right.value, true), false, left.measure);
                }
                if (left.measure == null || right.measure == null) {
                    throw ERROR;
                }
                return new Operand(op.apply.apply(left.value, convert(right.value, right.measure, left.measure), false), false, left.measure);
            }
            if (symbol == '*') {
                if (left.measure != null && right.measure != null) {
                    throw ERROR;
                }
                return new Operand(left.value * right.value, false, left.measure != null ? left.measure : right.measure);
            }
            if (left.measure == null) {
                throw ERROR;
            }
            if (right.measure == null) {
                return new Operand(left.value / right.value, false, left.measure);
            }
            return new Operand(left.value / convert(right.value, right.measure, left.measure), false);
        }

        private Token peek() {
            return tokens.get(position);
        }

        private Token next() {
            return tokens.get(position++);
        }

        private Token expect(int type) {
            Token token = next();
            if (token.type != type) {
                throw ERROR;
            }
            return token;
        }

        private void tokenize() {
            int i = 0;
            int length = source.length();
            while (i < length) {
                char c = source.charAt(i);
                if (isBlank(c)) {
                    i++;
                } else if (options != null && measureSymbol(i) != null) {
                    tokens.add(new Token(MEASURE, 0, "", "", false, measureSymbol(i)));
                    i += c == '°' ? 2 : 1;
                } else if (options != null && c == ',' && (i + 1 >= length || isBlank(source.charAt(i + 1)))) {
                    tokens.add(new Token(SEPARATOR, 0, ""));
                    i++;
                } else if (options != null && c == '0' && radixPrefix(i) != 0) {
                    i = readPrefixedNumber(i);
                } else if (Character.isDigit(c) || ((c == '.' || c == ',') && i + 1 < length && Character.isDigit(source.charAt(i + 1)))) {
                    i = readNumber(i);
                } else if (c == '(') {
                    tokens.add(new Token(OPEN, 0, ""));
                    i++;
                } else if (c == ')') {
                    tokens.add(new Token(CLOSE, 0, ""));
                    i++;
                } else if (c == ';') {
                    tokens.add(new Token(SEPARATOR, 0, ""));
                    i++;
                } else if (Character.isLetter(c)) {
                    int end = i;
                    while (end < length && (Character.isLetter(source.charAt(end)) || Character.isDigit(source.charAt(end))
                            || (options != null && source.charAt(end) == '²'))) {
                        end++;
                    }
                    String raw = source.subSequence(i, end).toString();
                    String name = raw.toLowerCase(Locale.ROOT);
                    if (options != null && name.equals("dec") && readDec(end)) {
                        i = decEnd;
                        continue;
                    }
                    if (options != null && options.units) {
                        int compoundEnd = readCompound(raw, end);
                        if (compoundEnd >= 0) {
                            i = compoundEnd;
                            continue;
                        }
                    }
                    boolean glued = i > 0 && !isBlank(source.charAt(i - 1));
                    tokens.add(new Token(IDENTIFIER, 0, name, raw, glued, null));
                    i = end;
                } else {
                    String symbol = matchSymbol(source, i);
                    if (symbol == null) {
                        throw ERROR;
                    }
                    tokens.add(new Token(SYMBOL, 0, symbol));
                    i += symbol.length();
                }
            }
            tokens.add(new Token(END, 0, ""));
        }

        private Object measureSymbol(int at) {
            char c = source.charAt(at);
            if (c == '°') {
                if (!options.units || at + 1 >= source.length()) {
                    return null;
                }
                return InlineUnits.temperature(source.charAt(at + 1));
            }
            if (InlineUnits.symbol(c) != null) {
                return options.units ? InlineUnits.symbol(c) : null;
            }
            String code = InlineCurrencies.symbol(c);
            return code != null && options.rates != null && options.rates.supports(code) ? code : null;
        }

        private int radixPrefix(int at) {
            if (at + 2 >= source.length()) {
                return 0;
            }
            int radix;
            switch (Character.toLowerCase(source.charAt(at + 1))) {
                case 'x':
                    radix = 16;
                    break;
                case 'o':
                    radix = 8;
                    break;
                case 'b':
                    radix = 2;
                    break;
                default:
                    return 0;
            }
            return Character.digit(source.charAt(at + 2), radix) >= 0 ? radix : 0;
        }

        private int readPrefixedNumber(int from) {
            int radix = radixPrefix(from);
            int i = from + 2;
            while (i < source.length() && Character.digit(source.charAt(i), radix) >= 0) {
                i++;
            }
            tokens.add(new Token(NUMBER, parseDigits(source.subSequence(from + 2, i).toString(), radix), ""));
            hasOperation = true;
            return i;
        }

        private double parseDigits(String digits, int radix) {
            try {
                return Long.parseLong(digits, radix);
            } catch (NumberFormatException e) {
                throw ERROR;
            }
        }

        private int decEnd;

        /** dec(ff, 16): the first argument is digits of the given radix, not an expression. */
        private boolean readDec(int from) {
            int open = from;
            while (open < source.length() && isBlank(source.charAt(open))) {
                open++;
            }
            if (open >= source.length() || source.charAt(open) != '(') {
                return false;
            }
            int close = open + 1;
            while (close < source.length() && source.charAt(close) != ')') {
                close++;
            }
            if (close >= source.length()) {
                throw ERROR;
            }
            String[] parts = source.subSequence(open + 1, close).toString().trim().split("[;,]");
            if (parts.length == 0 || parts.length > 2) {
                throw ERROR;
            }
            String digits = parts[0].trim();
            int radix = 10;
            if (parts.length == 2) {
                try {
                    radix = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    throw ERROR;
                }
                if (radix < 2 || radix > 36) {
                    throw ERROR;
                }
            } else if (digits.length() > 2 && digits.charAt(0) == '0') {
                switch (Character.toLowerCase(digits.charAt(1))) {
                    case 'x':
                        radix = 16;
                        break;
                    case 'o':
                        radix = 8;
                        break;
                    case 'b':
                        radix = 2;
                        break;
                    default:
                        break;
                }
            }
            if (digits.length() > 2 && digits.charAt(0) == '0' && Character.digit(digits.charAt(1), radix) < 0) {
                digits = digits.substring(2);
            }
            tokens.add(new Token(NUMBER, parseDigits(digits, radix), ""));
            hasOperation = true;
            decEnd = close + 1;
            return true;
        }

        private int readCompound(String first, int end) {
            int length = source.length();
            if (end < length && source.charAt(end) == '/') {
                int second = end + 1;
                int stop = second;
                while (stop < length && Character.isLetter(source.charAt(stop))) {
                    stop++;
                }
                InlineUnits.Unit unit = stop > second ? InlineUnits.compound(first, '/', source.subSequence(second, stop).toString()) : null;
                if (unit != null) {
                    tokens.add(new Token(MEASURE, 0, "", "", false, unit));
                    return stop;
                }
            } else if (end < length && isBlank(source.charAt(end))) {
                int second = end + 1;
                int stop = second;
                while (stop < length && Character.isLetter(source.charAt(stop))) {
                    stop++;
                }
                InlineUnits.Unit unit = stop > second ? InlineUnits.compound(first, ' ', source.subSequence(second, stop).toString()) : null;
                if (unit != null) {
                    tokens.add(new Token(MEASURE, 0, "", "", false, unit));
                    return stop;
                }
            }
            return -1;
        }

        private int readNumber(int from) {
            int length = source.length();
            int i = from;
            while (i < length && Character.isDigit(source.charAt(i))) {
                i++;
            }
            int integerDigits = i - from;
            int fractionDigits = 0;
            char mark = i < length ? source.charAt(i) : ' ';
            if ((mark == '.' || mark == ',') && i + 1 < length && Character.isDigit(source.charAt(i + 1))) {
                int fractionStart = i + 1;
                i = fractionStart;
                while (i < length && Character.isDigit(source.charAt(i))) {
                    i++;
                }
                fractionDigits = i - fractionStart;
                if (mark == ',' && fractionDigits == 3 && integerDigits >= 1 && integerDigits <= 3 && source.charAt(from) != '0') {
                    throw ERROR;
                }
                if (separator == 0) {
                    separator = mark;
                }
            }
            if (integerDigits == 0 && fractionDigits == 0) {
                throw ERROR;
            }
            try {
                tokens.add(new Token(NUMBER, Double.parseDouble(source.subSequence(from, i).toString().replace(',', '.')), ""));
            } catch (NumberFormatException e) {
                throw ERROR;
            }
            return i;
        }

        private Operand parseExpression(int minPrecedence) {
            if (++depth > 32) {
                throw ERROR;
            }
            try {
                Operand left = parseUnary();
                while (true) {
                    Token token = peek();
                    Infix op = token.type == SYMBOL ? infixFor(token.text) : null;
                    if (op == null || op.precedence < minPrecedence) {
                        break;
                    }
                    next();
                    Operand right = parseExpression(op.rightAssociative ? op.precedence : op.precedence + 1);
                    hasOperation = true;
                    left = combine(op, left, right);
                }
                return left;
            } finally {
                depth--;
            }
        }

        private Operand parseUnary() {
            Token token = peek();
            Prefix op = token.type == SYMBOL ? prefixFor(token.text) : null;
            if (op == null) {
                return parsePostfix();
            }
            next();
            Operand operand = parseExpression(op.precedence);
            if (op.operation) {
                hasOperation = true;
                if (operand.measure != null) {
                    throw ERROR;
                }
            }
            return new Operand(op.apply.apply(operand.value), operand.percent, operand.measure);
        }

        private Operand parsePostfix() {
            Object prefixMeasure = null;
            if (options != null && peek().type == MEASURE && peek().measure instanceof String) {
                prefixMeasure = next().measure;
            }
            Operand primary = parsePrimary();
            double value = primary.value;
            Object measure = primary.measure != null ? primary.measure : prefixMeasure;
            if (options != null) {
                value = applyMultipliers(value);
            }
            boolean percent = false;
            while (true) {
                Token token = peek();
                Postfix op = token.type == SYMBOL ? postfixFor(token.text) : null;
                if (op == null) {
                    break;
                }
                if (measure != null) {
                    throw ERROR;
                }
                next();
                value = op.apply.apply(value);
                hasOperation = true;
                percent = op.percent;
            }
            if (options == null) {
                return new Operand(value, percent);
            }
            if (percent && peek().type == IDENTIFIER && PERCENT_OF.contains(peek().text)) {
                next();
                Operand whole = parseExpression(30);
                return new Operand(value * whole.value, false, whole.measure);
            }
            if (measure == null && !percent) {
                Token token = peek();
                if (token.type == MEASURE) {
                    next();
                    measure = token.measure;
                } else if (token.type == IDENTIFIER) {
                    Object word = measureWord(token);
                    if (word != null) {
                        next();
                        measure = word;
                    }
                }
            }
            return new Operand(value, percent, measure);
        }

        private double applyMultipliers(double value) {
            while (peek().type == IDENTIFIER) {
                Token token = peek();
                Double multiplier = MULTIPLIERS.get(token.text);
                if (multiplier == null || (GLUED_MULTIPLIERS.contains(token.text) && !token.glued)) {
                    break;
                }
                next();
                value *= multiplier;
                hasOperation = true;
            }
            return value;
        }

        private Operand parsePrimary() {
            Token token = next();
            switch (token.type) {
                case NUMBER:
                    return new Operand(token.number, false);
                case OPEN: {
                    Operand operand = parseExpression(0);
                    expect(CLOSE);
                    return new Operand(operand.value, false, operand.measure);
                }
                case IDENTIFIER:
                    return new Operand(parseIdentifier(token.text), false);
                default:
                    throw ERROR;
            }
        }

        private double parseIdentifier(String name) {
            Double constant = CONSTANTS.get(name);
            if (constant != null) {
                hasOperation = true;
                return constant;
            }
            Function function = FUNCTIONS.get(name);
            if (function == null) {
                function = logFunction(name);
            }
            if (function == null) {
                throw ERROR;
            }
            expect(OPEN);
            ArrayList<Double> args = new ArrayList<>(2);
            if (peek().type != CLOSE) {
                args.add(plainArgument());
                while (peek().type == SEPARATOR) {
                    next();
                    args.add(plainArgument());
                }
            }
            expect(CLOSE);
            if (args.size() < function.minArgs || args.size() > function.maxArgs) {
                throw ERROR;
            }
            hasOperation = true;
            boolean degrees = options != null && options.degrees;
            double[] values = new double[args.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = degrees && TRIG.contains(name) ? Math.toRadians(args.get(i)) : args.get(i);
            }
            double result = function.apply.apply(values);
            if (degrees) {
                if (INVERSE_TRIG.contains(name)) {
                    result = Math.toDegrees(result);
                } else if (TRIG.contains(name) && Math.abs(result) < 1e-12) {
                    // cos(90) lands on 6e-17 instead of 0
                    result = 0;
                }
            }
            return result;
        }

        private double plainArgument() {
            Operand operand = parseExpression(0);
            if (operand.measure != null) {
                throw ERROR;
            }
            return operand.value;
        }
    }

    public static Result evaluate(CharSequence expression, char decimalSeparator) {
        try {
            return new Parser(expression, decimalSeparator, null).parse();
        } catch (ParseError | IndexOutOfBoundsException e) {
            return null;
        }
    }

    public static Result evaluate(CharSequence expression, InlineOptions options) {
        try {
            return new Parser(expression, options.decimalSeparator, options).parse();
        } catch (ParseError | IndexOutOfBoundsException e) {
            return null;
        }
    }

    public static String format(double value, char decimalSeparator) {
        return format(value, decimalSeparator, false);
    }

    private static boolean outOfRange(double value) {
        double abs = Math.abs(value);
        return Double.isNaN(value) || Double.isInfinite(value) || abs >= 1e12 || (abs != 0 && abs < 1e-9);
    }

    public static String format(double value, char decimalSeparator, boolean grouping) {
        if (outOfRange(value)) {
            return null;
        }
        return finish(BigDecimal.valueOf(value).round(new MathContext(12)), decimalSeparator, grouping);
    }

    static String formatMeasure(double value, char decimalSeparator, boolean grouping) {
        if (outOfRange(value)) {
            return null;
        }
        BigDecimal decimal = BigDecimal.valueOf(value);
        return finish(Math.abs(value) >= 1 ? decimal.setScale(2, RoundingMode.HALF_UP) : decimal.round(new MathContext(3)), decimalSeparator, grouping);
    }

    static String formatMoney(double value, int fractionDigits, char decimalSeparator, boolean grouping) {
        if (outOfRange(value)) {
            return null;
        }
        BigDecimal decimal = BigDecimal.valueOf(value);
        if (fractionDigits >= 0) {
            BigDecimal scaled = decimal.setScale(fractionDigits, RoundingMode.HALF_UP);
            if (scaled.signum() != 0) {
                return finish(scaled, decimalSeparator, grouping);
            }
        }
        return finish(decimal.round(new MathContext(6)), decimalSeparator, grouping);
    }

    static String formatBase(double value, int radix) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.floor(value) || Math.abs(value) > 9007199254740992d) {
            return null;
        }
        long number = (long) value;
        String prefix = radix == 2 ? "0b" : radix == 8 ? "0o" : "0x";
        return (number < 0 ? "-" : "") + prefix + Long.toString(Math.abs(number), radix).toUpperCase(Locale.ROOT);
    }

    private static String finish(BigDecimal value, char decimalSeparator, boolean grouping) {
        String text = value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
        if (text.length() > 24) {
            return null;
        }
        if (grouping) {
            text = group(text);
        }
        return decimalSeparator == '.' ? text : text.replace('.', decimalSeparator);
    }

    private static String group(String plain) {
        int start = plain.startsWith("-") ? 1 : 0;
        int dot = plain.indexOf('.');
        int end = dot < 0 ? plain.length() : dot;
        if (end - start <= 3) {
            return plain;
        }
        StringBuilder out = new StringBuilder(plain.length() + 4);
        out.append(plain, 0, start);
        for (int i = start; i < end; i++) {
            if (i > start && (end - i) % 3 == 0) {
                out.append(' ');
            }
            out.append(plain.charAt(i));
        }
        return out.append(plain, end, plain.length()).toString();
    }

    static int equalsIndexAt(CharSequence text, int caret) {
        if (caret <= 0 || caret > text.length() || text.charAt(caret - 1) != '=') {
            return -1;
        }
        int i = caret;
        while (i < text.length() && isBlank(text.charAt(i))) {
            i++;
        }
        if (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == ',') {
                return -1;
            }
        }
        return caret - 1;
    }

    static String insertTextFor(CharSequence text, int caret, String value) {
        return caret >= 2 && isBlank(text.charAt(caret - 2)) ? " " + value : value;
    }

    private static int expressionStart(CharSequence text, int equalsIndex) {
        int limit = Math.max(0, equalsIndex - 64);
        int i = equalsIndex - 1;
        while (i >= limit && isExpressionChar(text.charAt(i))) {
            i--;
        }
        i++;
        while (i < equalsIndex && isBlank(text.charAt(i))) {
            i++;
        }
        if (i >= equalsIndex) {
            return -1;
        }
        if (i > 0) {
            char before = text.charAt(i - 1);
            if (Character.isLetterOrDigit(before) || before == '_') {
                return -1;
            }
        }
        return i;
    }

    static int nextCandidate(CharSequence text, int from, int end) {
        while (from < end) {
            char c = text.charAt(from++);
            if (c == '(') {
                return from;
            }
            if (isBlank(c)) {
                while (from < end && isBlank(text.charAt(from))) {
                    from++;
                }
                if (from < end) {
                    return from;
                }
            }
        }
        return -1;
    }

    private static boolean looksLikeDate(CharSequence text, int from, int to) {
        while (from < to && isBlank(text.charAt(from))) {
            from++;
        }
        while (to > from && isBlank(text.charAt(to - 1))) {
            to--;
        }
        if (to - from < 6) {
            return false;
        }
        int[] groups = new int[3];
        int groupCount = 0;
        int digits = 0;
        char mark = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                digits++;
                continue;
            }
            if (c != '/' && c != '.' && c != '-') {
                return false;
            }
            if (mark == 0) {
                mark = c;
            } else if (mark != c) {
                return false;
            }
            if (digits == 0 || groupCount == 2) {
                return false;
            }
            groups[groupCount++] = digits;
            digits = 0;
        }
        if (digits == 0 || groupCount != 2) {
            return false;
        }
        groups[2] = digits;
        if (mark == '-') {
            return groups[0] == 4 && groups[1] <= 2 && groups[2] <= 2;
        }
        return groups[0] <= 2 && groups[1] <= 2 && (groups[2] == 2 || groups[2] == 4);
    }

    private static final String OPERATORS = "+-−*×/÷^";

    private static boolean followsOperator(CharSequence text, int at) {
        int i = at - 1;
        while (i >= 0 && isBlank(text.charAt(i))) {
            i--;
        }
        return i >= 0 && OPERATORS.indexOf(text.charAt(i)) >= 0;
    }

    public static Suggestion suggestionAt(CharSequence text, int caret, InlineOptions options) {
        int equalsIndex = equalsIndexAt(text, caret);
        if (equalsIndex < 0) {
            return null;
        }
        String time = InlineTime.valueAt(text, equalsIndex, options);
        if (time != null) {
            return new Suggestion(caret, insertTextFor(text, caret, time), time);
        }
        int start = expressionStart(text, equalsIndex);
        int first = start;
        while (start >= 0) {
            if (start != first && (followsOperator(text, start) || OPERATORS.indexOf(text.charAt(start)) >= 0)) {
                // "5 kg + 3 km" failed as a whole, don't answer for its tail
                return null;
            }
            if (!looksLikeDate(text, start, equalsIndex)) {
                Result result = evaluate(text.subSequence(start, equalsIndex), options);
                if (result != null) {
                    if (!result.hasOperation) {
                        return null;
                    }
                    String value = result.display != null ? result.display : format(result.value, result.decimalSeparator, options.grouping);
                    return value == null ? null : new Suggestion(caret, insertTextFor(text, caret, value), value);
                }
            }
            start = nextCandidate(text, start, equalsIndex);
        }
        return null;
    }

    public static Suggestion suggestionAt(CharSequence text, int caret, char decimalSeparator) {
        int equalsIndex = equalsIndexAt(text, caret);
        if (equalsIndex < 0) {
            return null;
        }
        int start = expressionStart(text, equalsIndex);
        while (start >= 0) {
            if (!looksLikeDate(text, start, equalsIndex)) {
                Result result = evaluate(text.subSequence(start, equalsIndex), decimalSeparator);
                if (result != null) {
                    if (!result.hasOperation) {
                        return null;
                    }
                    String value = format(result.value, result.decimalSeparator);
                    return value == null ? null : new Suggestion(caret, insertTextFor(text, caret, value), value);
                }
            }
            start = nextCandidate(text, start, equalsIndex);
        }
        return null;
    }
}
