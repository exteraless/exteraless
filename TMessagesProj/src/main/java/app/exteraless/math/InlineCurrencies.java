package app.exteraless.math;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Names people use for money. Rates themselves come through {@link InlineOptions.Rates}. */
final class InlineCurrencies {

    private static final Map<Character, String> SYMBOLS = new HashMap<>();
    private static final Map<String, String> LABELS = new HashMap<>();
    private static final Map<String, String> WORDS = new HashMap<>();
    /** Codes that also read as plain words, so they only count in upper case. */
    private static final Set<String> UPPER_ONLY = new HashSet<>(Arrays.asList("TRY", "NOT", "SOL", "TON"));

    private static void symbol(char symbol, String code, boolean label) {
        SYMBOLS.put(symbol, code);
        if (label) {
            LABELS.put(code, String.valueOf(symbol));
        }
    }

    private static void words(String code, String... words) {
        WORDS.put(code.toLowerCase(Locale.ROOT), code);
        for (String word : words) {
            WORDS.put(word, code);
        }
    }

    static {
        symbol('$', "USD", true);
        symbol('€', "EUR", true);
        symbol('₽', "RUB", true);
        symbol('£', "GBP", true);
        symbol('¥', "JPY", true);
        symbol('₸', "KZT", true);
        symbol('₴', "UAH", true);
        symbol('₺', "TRY", true);
        symbol('₹', "INR", true);
        symbol('₪', "ILS", true);
        symbol('₩', "KRW", true);
        symbol('₾', "GEL", true);
        symbol('₼', "AZN", true);
        symbol('֏', "AMD", true);
        symbol('₫', "VND", true);
        symbol('฿', "THB", true);
        symbol('₱', "PHP", true);
        symbol('₦', "NGN", true);
        symbol('₿', "BTC", false);
        symbol('⭐', "XTR", true);
        symbol('★', "XTR", false);

        words("USD", "доллар", "доллара", "долларов", "доллары", "долларах", "долл", "долар", "долара", "доларів", "долари", "бакс", "бакса", "баксов", "баксы", "баксах", "dollar", "dollars", "buck", "bucks");
        words("EUR", "евро", "євро", "euro", "euros");
        words("RUB", "рубль", "рубля", "рублей", "рубли", "рублях", "руб", "р", "рублів", "ruble", "rubles", "rouble", "roubles");
        words("GBP", "фунтов стерлингов", "pound sterling", "quid");
        words("KZT", "тенге", "tenge");
        words("UAH", "гривна", "гривны", "гривен", "гривнах", "гривня", "гривні", "гривень", "грн", "hryvnia", "hryvnias");
        words("BYN", "бун");
        words("CNY", "юань", "юаня", "юаней", "юани", "юанях", "юанів", "yuan");
        words("JPY", "иена", "иены", "иен", "иенах", "йена", "йены", "йен", "йенах", "єна", "єни", "єн", "yen");
        words("TRY", "лира", "лиры", "лир", "лирах", "ліра", "ліри", "лір", "lira", "liras");
        words("PLN", "злотый", "злотого", "злотых", "злотые", "злотий", "злотих", "zloty", "zlotys", "zł");
        words("CZK", "kč");
        words("INR", "рупия", "рупии", "рупий", "рупиях", "rupee", "rupees");
        words("ILS", "шекель", "шекеля", "шекелей", "шекели", "шекелях", "шекелів", "shekel", "shekels");
        words("CHF", "франк", "франка", "франков", "франки", "франках", "франків", "franc", "francs");
        words("AED", "дирхам", "дирхама", "дирхамов", "дирхамы", "дирхамах", "dirham", "dirhams");
        words("THB", "бат", "бата", "батов", "баты", "батах", "baht");
        words("GEL", "лари", "lari");
        words("AMD", "драм", "драмов", "драмы", "драмах", "dram", "drams");
        words("UZS", "сум", "сумов", "сумы", "сумах");
        words("KGS", "сом", "сомов", "сомах", "som");
        words("BTC", "биткоин", "биткоина", "биткоинов", "биткоины", "биткоинах", "биткойн", "биткойна", "биткойнов", "биткойны", "биток", "біткоїн", "біткоїнів", "bitcoin", "bitcoins");
        words("ETH", "эфириум", "эфириума", "эфириумов", "эфириумах", "ефіріум", "ethereum", "ether");
        words("GRAM", "gram", "grams", "грам", "грама", "грамов", "грамы", "грамах", "ton", "тон", "тона", "тонов", "тонах", "toncoin", "toncoins", "тонкоин", "тонкоїн");
        words("USDT", "tether");
        words("XTR", "звезда", "звезды", "звёзды", "звезд", "звёзд", "звездах", "звёздах", "звездами", "звёздами", "звезду", "зірка", "зірки", "зірок", "star", "stars");
        for (String code : new String[]{"AUD", "CAD", "HKD", "HUF", "KRW", "MXN", "NOK", "RON", "SAR", "SEK", "SGD", "DKK", "BRL", "VND", "AZN", "SOL", "TON", "USDC"}) {
            WORDS.put(code.toLowerCase(Locale.ROOT), code);
        }
        WORDS.remove("ton");
        words("GRAM", "ton");
    }

    private InlineCurrencies() {
    }

    static String symbol(char c) {
        return SYMBOLS.get(c);
    }

    static boolean isSymbol(char c) {
        return SYMBOLS.containsKey(c);
    }

    /** {@code raw} keeps the case the user typed. */
    static String word(String raw) {
        if (UPPER_ONLY.contains(raw.toUpperCase(Locale.ROOT)) && !raw.equals(raw.toUpperCase(Locale.ROOT))) {
            return null;
        }
        String code = WORDS.get(raw.toLowerCase(Locale.ROOT));
        return code != null ? code : WORDS.get(raw.toLowerCase(Locale.ROOT).replace('ё', 'е'));
    }

    static String label(String code) {
        String label = LABELS.get(code);
        return label != null ? label : code;
    }

    /** Stablecoins and most fiat read best with two digits, crypto needs more. */
    static int fractionDigits(String code) {
        switch (code) {
            case "BTC":
            case "ETH":
            case "SOL":
                return -1;
            case "XTR":
                return 0;
            default:
                return 2;
        }
    }
}
