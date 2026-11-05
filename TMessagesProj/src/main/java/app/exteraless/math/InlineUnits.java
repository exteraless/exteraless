package app.exteraless.math;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Unit table and the rules for picking a target when the user did not name one. */
final class InlineUnits {

    enum Kind {LENGTH, MASS, VOLUME, AREA, SPEED, TEMPERATURE, DURATION}

    static final class Unit {
        final String code;
        final String label;
        final String cyrillicLabel;
        final Kind kind;
        final double scale;
        final double offset;

        Unit(String code, String label, String cyrillicLabel, Kind kind, double scale, double offset) {
            this.code = code;
            this.label = label;
            this.cyrillicLabel = cyrillicLabel;
            this.kind = kind;
            this.scale = scale;
            this.offset = offset;
        }

        double toBase(double value) {
            return value * scale + offset;
        }

        double fromBase(double value) {
            return (value - offset) / scale;
        }

        String label(boolean cyrillic) {
            return cyrillic ? cyrillicLabel : label;
        }
    }

    private static final class Ladder {
        final boolean imperial;
        final List<String> codes;

        Ladder(boolean imperial, String... codes) {
            this.imperial = imperial;
            this.codes = Arrays.asList(codes);
        }
    }

    private static final Map<String, Unit> UNITS = new HashMap<>();
    private static final Map<String, String> WORDS = new HashMap<>();
    private static final Map<String, String> COUNTERPARTS = new HashMap<>();
    private static final Map<String, String> COMPOUNDS = new HashMap<>();
    private static final Map<String, Ladder> LADDERS = new HashMap<>();
    private static final String[] BREAKDOWN = {"d", "h", "min", "s"};

    private static void unit(String code, String label, String cyrillic, Kind kind, double scale, double offset, String counterpart, String... words) {
        UNITS.put(code, new Unit(code, label, cyrillic, kind, scale, offset));
        if (counterpart != null) {
            COUNTERPARTS.put(code, counterpart);
        }
        for (String word : words) {
            WORDS.put(word, code);
        }
    }

    private static void ladder(boolean imperial, String... codes) {
        Ladder ladder = new Ladder(imperial, codes);
        for (String code : codes) {
            LADDERS.put(code, ladder);
        }
    }

    static {
        unit("mm", "mm", "мм", Kind.LENGTH, 0.001, 0, "in", "mm", "мм", "миллиметр", "миллиметра", "миллиметров", "миллиметрах", "міліметр", "міліметра", "міліметрів", "millimeter", "millimeters", "millimetre", "millimetres");
        unit("cm", "cm", "см", Kind.LENGTH, 0.01, 0, "in", "cm", "см", "сантиметр", "сантиметра", "сантиметров", "сантиметрах", "сантиметрів", "centimeter", "centimeters", "centimetre", "centimetres");
        unit("m", "m", "м", Kind.LENGTH, 1, 0, "ft", "m", "м", "метр", "метра", "метров", "метрах", "метрів", "meter", "meters", "metre", "metres");
        unit("km", "km", "км", Kind.LENGTH, 1000, 0, "mi", "km", "км", "километр", "километра", "километров", "километрах", "кілометр", "кілометра", "кілометрів", "kilometer", "kilometers", "kilometre", "kilometres");
        unit("in", "in", "in", Kind.LENGTH, 0.0254, 0, "cm", "inch", "inches", "дюйм", "дюйма", "дюймов", "дюймах", "дюймів");
        unit("ft", "ft", "ft", Kind.LENGTH, 0.3048, 0, "m", "ft", "foot", "feet", "фут", "фута", "футов", "футах", "футів");
        unit("yd", "yd", "yd", Kind.LENGTH, 0.9144, 0, "m", "yd", "yard", "yards", "ярд", "ярда", "ярдов", "ярдах", "ярдів");
        unit("mi", "mi", "mi", Kind.LENGTH, 1609.344, 0, "km", "mi", "mile", "miles", "миля", "мили", "миль", "милях", "милі");

        unit("mg", "mg", "мг", Kind.MASS, 1e-6, 0, null, "mg", "мг", "миллиграмм", "миллиграмма", "миллиграммов", "міліграм", "міліграмів", "milligram", "milligrams");
        unit("g", "g", "г", Kind.MASS, 0.001, 0, "oz", "g", "г", "гр", "грамм", "грамма", "граммов", "граммах", "gramme", "grammes");
        unit("kg", "kg", "кг", Kind.MASS, 1, 0, "lb", "kg", "кг", "килограмм", "килограмма", "килограммов", "килограммах", "кілограм", "кілограма", "кілограмів", "kilogram", "kilograms", "kilo", "kilos");
        unit("t", "t", "т", Kind.MASS, 1000, 0, "lb", "t", "т", "тонна", "тонны", "тонн", "тоннах", "тонни", "tonne", "tonnes");
        unit("oz", "oz", "oz", Kind.MASS, 0.028349523125, 0, "g", "oz", "ounce", "ounces", "унция", "унции", "унций", "унциях", "унція", "унцій");
        unit("lb", "lb", "lb", Kind.MASS, 0.45359237, 0, "kg", "lb", "lbs", "pound", "pounds", "фунт", "фунта", "фунтов", "фунтах", "фунтів");

        unit("ml", "ml", "мл", Kind.VOLUME, 0.001, 0, "floz", "ml", "мл", "миллилитр", "миллилитра", "миллилитров", "миллилитрах", "мілілітр", "мілілітрів", "milliliter", "milliliters", "millilitre", "millilitres");
        unit("l", "l", "л", Kind.VOLUME, 1, 0, "gal", "l", "л", "литр", "литра", "литров", "литрах", "літр", "літра", "літрів", "liter", "liters", "litre", "litres");
        unit("floz", "fl oz", "fl oz", Kind.VOLUME, 0.0295735295625, 0, "ml", "floz");
        unit("gal", "gal", "gal", Kind.VOLUME, 3.785411784, 0, "l", "gal", "gallon", "gallons", "галлон", "галлона", "галлонов", "галлонах", "галон", "галонів");

        unit("m2", "m²", "м²", Kind.AREA, 1, 0, "ft2", "m2", "m²", "м2", "м²", "sqm");
        unit("km2", "km²", "км²", Kind.AREA, 1e6, 0, "mi2", "km2", "km²", "км2", "км²");
        unit("ha", "ha", "га", Kind.AREA, 1e4, 0, "acre", "ha", "га", "гектар", "гектара", "гектаров", "гектарах", "гектарів", "hectare", "hectares");
        unit("ft2", "ft²", "ft²", Kind.AREA, 0.09290304, 0, "m2", "ft2", "ft²", "sqft");
        unit("mi2", "mi²", "mi²", Kind.AREA, 2589988.110336, 0, "km2", "mi2", "mi²");
        unit("acre", "ac", "ac", Kind.AREA, 4046.8564224, 0, "ha", "ac", "acre", "acres", "акр", "акра", "акров", "акрах", "акрів");

        unit("kmh", "km/h", "км/ч", Kind.SPEED, 1 / 3.6, 0, "mph", "kmh", "kph");
        unit("ms", "m/s", "м/с", Kind.SPEED, 1, 0, "kmh", "mps");
        unit("mph", "mph", "mph", Kind.SPEED, 0.44704, 0, "kmh", "mph");
        unit("knot", "kn", "уз", Kind.SPEED, 1852 / 3600d, 0, "kmh", "knot", "knots", "узел", "узла", "узлов", "узлах", "вузол", "вузлів");

        unit("C", "°C", "°C", Kind.TEMPERATURE, 1, 273.15, "F", "celsius", "цельсий", "цельсия", "цельсию", "цельсиях");
        unit("F", "°F", "°F", Kind.TEMPERATURE, 5 / 9d, 273.15 - 160 / 9d, "C", "fahrenheit", "фаренгейт", "фаренгейта", "фаренгейту", "фаренгейтах");
        unit("K", "K", "K", Kind.TEMPERATURE, 1, 0, "C", "kelvin", "kelvins", "кельвин", "кельвина", "кельвинов", "кельвинах");

        unit("msec", "ms", "мс", Kind.DURATION, 0.001, 0, null, "ms", "мс", "millisecond", "milliseconds", "миллисекунда", "миллисекунды", "миллисекунд");
        unit("s", "s", "с", Kind.DURATION, 1, 0, null, "s", "sec", "secs", "second", "seconds", "с", "сек", "секунда", "секунды", "секунд", "секундах", "секунду", "секунди");
        unit("min", "min", "мин", Kind.DURATION, 60, 0, null, "min", "mins", "minute", "minutes", "мин", "минута", "минуты", "минут", "минутах", "минуту", "хв", "хвилина", "хвилини", "хвилин");
        unit("h", "h", "ч", Kind.DURATION, 3600, 0, null, "h", "hr", "hrs", "hour", "hours", "ч", "час", "часа", "часов", "часах", "година", "години", "годин");
        unit("d", "d", "д", Kind.DURATION, 86400, 0, null, "d", "day", "days", "д", "дн", "день", "дня", "дней", "днях", "сут", "сутки", "суток", "днів", "доба", "доби");
        unit("w", "w", "нед", Kind.DURATION, 604800, 0, null, "w", "wk", "week", "weeks", "нед", "неделя", "недели", "недель", "неделях", "неделю", "тиждень", "тижні", "тижнів");
        unit("mo", "mo", "мес", Kind.DURATION, 2592000, 0, null, "mo", "month", "months", "мес", "месяц", "месяца", "месяцев", "месяцах", "місяць", "місяці", "місяців");
        unit("y", "y", "г.", Kind.DURATION, 31536000, 0, null, "y", "yr", "yrs", "year", "years", "год", "года", "лет", "годах", "рік", "роки", "років");

        COMPOUNDS.put("km/h", "kmh");
        COMPOUNDS.put("км/ч", "kmh");
        COMPOUNDS.put("км/год", "kmh");
        COMPOUNDS.put("m/s", "ms");
        COMPOUNDS.put("м/с", "ms");
        COMPOUNDS.put("fl oz", "floz");

        ladder(false, "mm", "cm", "m", "km");
        ladder(false, "mg", "g", "kg", "t");
        ladder(false, "ml", "l");
        ladder(false, "m2", "ha", "km2");
        ladder(true, "in", "ft", "mi");
        LADDERS.put("yd", LADDERS.get("ft"));
        ladder(true, "oz", "lb");
        ladder(true, "floz", "gal");
        ladder(true, "ft2", "acre", "mi2");
    }

    private InlineUnits() {
    }

    static Unit word(String word) {
        String code = WORDS.get(word.toLowerCase(Locale.ROOT));
        return code == null ? null : UNITS.get(code);
    }

    static Unit compound(String first, char separator, String second) {
        String code = COMPOUNDS.get((first + separator + second).toLowerCase(Locale.ROOT));
        return code == null ? null : UNITS.get(code);
    }

    static Unit temperature(char scale) {
        switch (Character.toLowerCase(scale)) {
            case 'c':
            case 'с':
                return UNITS.get("C");
            case 'f':
            case 'ф':
                return UNITS.get("F");
            case 'k':
            case 'к':
                return UNITS.get("K");
            default:
                return null;
        }
    }

    static Unit symbol(char c) {
        return c == '℃' ? UNITS.get("C") : c == '℉' ? UNITS.get("F") : null;
    }

    static Unit implicitTarget(Unit unit, double value, boolean imperial) {
        Ladder ladder = LADDERS.get(unit.code);
        if (ladder != null && ladder.imperial == imperial) {
            Unit rescaled = rescale(ladder, unit, Math.abs(unit.toBase(value)));
            if (rescaled != null) {
                return rescaled;
            }
        }
        String counterpart = COUNTERPARTS.get(unit.code);
        return counterpart == null ? null : UNITS.get(counterpart);
    }

    private static Unit rescale(Ladder ladder, Unit unit, double base) {
        for (int i = ladder.codes.size() - 1; i >= 0; i--) {
            String code = ladder.codes.get(i);
            double value = UNITS.get(code).fromBase(base);
            if (!code.equals(unit.code) && value >= 0.1 && value < 1000) {
                return UNITS.get(code);
            }
        }
        int index = ladder.codes.indexOf(unit.code);
        if (index <= 0) {
            return null;
        }
        Unit lower = UNITS.get(ladder.codes.get(index - 1));
        double value = lower.fromBase(base);
        return value >= 1 && value < 100000 ? lower : null;
    }

    static boolean canBreakDown(Unit unit) {
        return Arrays.asList(BREAKDOWN).contains(unit.code);
    }

    /** "9900 s" becomes "2 h 45 min". Null when a single part in the same unit would add nothing. */
    static String formatDuration(double value, Unit unit, InlineOptions options) {
        double seconds = value * unit.scale;
        double abs = Math.abs(seconds);
        if (Double.isNaN(abs) || abs >= 1e12) {
            return null;
        }
        List<String> parts = new ArrayList<>(4);
        String only = null;
        if (abs < 60) {
            String text = MathExpression.format(abs, options.decimalSeparator, false);
            if (text == null) {
                return null;
            }
            only = "s";
            parts.add(text + ' ' + UNITS.get("s").label(options.cyrillicLabels));
        } else {
            long left = Math.round(abs);
            for (String code : BREAKDOWN) {
                Unit part = UNITS.get(code);
                long scale = (long) part.scale;
                long count = left / scale;
                if (count > 0) {
                    left -= count * scale;
                    only = code;
                    parts.add(count + " " + part.label(options.cyrillicLabels));
                }
            }
        }
        if (parts.isEmpty() || (parts.size() == 1 && unit.code.equals(only))) {
            return null;
        }
        return (seconds < 0 ? "-" : "") + String.join(" ", parts);
    }
}
