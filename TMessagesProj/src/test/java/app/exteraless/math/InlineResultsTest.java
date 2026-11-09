package app.exteraless.math;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

public class InlineResultsTest {

    private static final Map<String, Double> USD = new HashMap<>();

    static {
        USD.put("USD", 1d);
        USD.put("EUR", 1.1);
        USD.put("UAH", 0.025);
        USD.put("RUB", 0.0125);
        USD.put("GBP", 1.25);
        USD.put("GRAM", 5d);
        USD.put("XTR", 0.013);
    }

    private static InlineOptions options() {
        InlineOptions options = new InlineOptions();
        options.units = true;
        options.degrees = true;
        options.localCurrency = "UAH";
        options.localZone = ZoneId.of("Europe/Kiev");
        options.now = ZonedDateTime.of(2026, 11, 4, 12, 0, 0, 0, ZoneId.of("UTC"));
        options.rates = new InlineOptions.Rates() {
            @Override
            public boolean supports(String code) {
                return USD.containsKey(code);
            }

            @Override
            public Double rate(String from, String to) {
                return USD.get(from) / USD.get(to);
            }
        };
        return options;
    }

    private static String run(String text, InlineOptions options) {
        MathExpression.Suggestion suggestion = MathExpression.suggestionAt(text, text.length(), options);
        return suggestion == null ? null : suggestion.value.replace(' ', ' ');
    }

    private static String run(String text) {
        return run(text, options());
    }

    @Test
    public void arithmetic() {
        assertEquals("50", run("(120 + 80) / 4 ="));
        assertEquals("20", run("√144 + 2^3 ="));
        assertEquals("40", run("max(12, 40, 7) ="));
        assertEquals("40", run("max(12; 40; 7) ="));
        assertEquals("0.5", run("sin(30) ="));
        assertEquals("0", run("cos(90) ="));
        assertEquals("30", run("asin(0.5) ="));
    }

    @Test
    public void radians() {
        InlineOptions options = options();
        options.degrees = false;
        assertEquals("1", run("sin(pi / 2) =", options));
    }

    @Test
    public void bases() {
        assertEquals("0xFF", run("hex(255) ="));
        assertEquals("0b101", run("bin(5) ="));
        assertEquals("255", run("dec(ff, 16) ="));
        assertEquals("255", run("dec(0xff) ="));
        assertEquals("256", run("0xff + 1 ="));
    }

    @Test
    public void percentAndShorthands() {
        assertEquals("1955", run("2300 - 15% ="));
        assertEquals("144", run("12% of 1200 ="));
        assertEquals("144", run("12% от 1200 ="));
        assertEquals("25000", run("300k / 12 ="));
        assertEquals("25000", run("300к / 12 ="));
        assertEquals("3000000", run("3 млн ="));
        assertEquals("2000000", run("2кк ="));
    }

    @Test
    public void grouping() {
        InlineOptions options = options();
        options.grouping = true;
        assertEquals("1 234 567", run("1234000 + 567 =", options));
        assertEquals("-1 234.5", run("0 - 1234.5 =", options));
    }

    @Test
    public void unitsWithTarget() {
        assertEquals("11.02 lb", run("5 kg to lb ="));
        assertEquals("11.02 lb", run("5 кг в фунтах ="));
        assertEquals("62.14 mph", run("100 km/h to mph ="));
        assertEquals("62.14 mph", run("100 км/ч в mph ="));
        assertEquals("212 °F", run("100 °C to °F ="));
    }

    @Test
    public void unitsImplicit() {
        assertEquals("22.22 °C", run("72°F ="));
        assertEquals("16.09 km", run("10 miles ="));
        assertEquals("16.09 km", run("10 миль ="));
        assertEquals("500 cm", run("5 m ="));
    }

    @Test
    public void imperialUser() {
        InlineOptions options = options();
        options.imperial = true;
        assertEquals("6.21 mi", run("10 km =", options));
    }

    @Test
    public void durations() {
        assertEquals("1 h 30 min", run("90 min ="));
        assertEquals("2 h 45 min", run("2 h + 45 min ="));
        InlineOptions options = options();
        options.cyrillicLabels = true;
        assertEquals("2 ч 45 мин", run("2 ч + 45 мин =", options));
        assertNull(run("5 min ="));
    }

    @Test
    public void unitErrors() {
        assertNull(run("5 kg + 3 km ="));
        assertNull(run("5 kg to km ="));
        assertNull(run("2 km + 3 ="));
        InlineOptions options = options();
        options.units = false;
        assertNull(run("5 kg to lb =", options));
    }

    @Test
    public void currencies() {
        assertEquals("4400 ₴", run("100€ ="));
        assertEquals("4400 ₴", run("€100 ="));
        assertEquals("110 $", run("100 euro to $ ="));
        assertEquals("110 $", run("100 евро в долларах ="));
        assertEquals("90.91 €", run("100 dollars to euros ="));
        assertEquals("62.5 $", run("5k ₽ to $ ="));
        assertEquals("10 GRAM", run("50$ to GRAM ="));
        assertEquals("125 $", run("100£ to $ ="));
        assertEquals("80 £", run("100 $ to pounds ="));
        assertEquals("150 $", run("100$ + 50$ to $ ="));
        assertEquals("85 $", run("100$ - 15% to $ ="));
        assertNull(run("100 грн ="));
    }

    @Test
    public void currenciesWithoutRates() {
        InlineOptions options = options();
        options.rates = null;
        assertNull(run("100€ =", options));
    }

    @Test
    public void currencyRatePending() {
        InlineOptions options = options();
        InlineOptions.Rates real = options.rates;
        options.rates = new InlineOptions.Rates() {
            @Override
            public boolean supports(String code) {
                return real.supports(code);
            }

            @Override
            public Double rate(String from, String to) {
                return null;
            }
        };
        assertNull(run("100€ =", options));
    }

    @Test
    public void times() {
        // 4 Nov 2026: Kyiv is UTC+2, Tokyo +9, New York -5
        assertEquals("20:00", run("18:00 utc ="));
        assertEquals("1:00 am (+1)", run("6pm est ="));
        assertEquals("19:00", run("12:00 in tokyo ="));
        assertEquals("19:00", run("12:00 в токио ="));
        assertEquals("07:00", run("now in new york ="));
        assertEquals("07:00", run("сейчас в нью-йорке ="));
        assertEquals("18:00", run("6pm ="));
        assertNull(run("18:00 ="));
        assertNull(run("12:00 in kyiv ="));
    }

    @Test
    public void textAround() {
        assertEquals("4", run("ну типа 2+2 ="));
        assertNull(run("просто текст ="));
        assertNull(run("01.02.2026 ="));
    }
}
