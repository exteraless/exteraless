package app.exteraless.math;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "18:00 utc =", "6pm est =", "12:00 в токио =", "сейчас в нью-йорке =". */
final class InlineTime {

    private static final Set<String> TARGET_MARKERS = new HashSet<>(Arrays.asList("в", "во", "у", "to", "in"));
    private static final Set<String> SOURCE_MARKERS = new HashSet<>(Arrays.asList("по", "за"));
    private static final Set<String> NOW_WORDS = new HashSet<>(Arrays.asList("сейчас", "зараз", "now"));
    private static final Set<String> MERIDIEMS = new HashSet<>(Arrays.asList("am", "pm", "a.m.", "p.m."));
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})(?:[:.](\\d{2}))?(am|pm|a\\.m\\.|p\\.m\\.)?$");
    private static final Pattern OFFSET = Pattern.compile("^(utc|gmt|msk|мск) ?([+-]) ?(\\d{1,2})(?::?(\\d{2}))?$");
    private static final Map<String, String> ZONES = new HashMap<>();

    private static void add(String zone, String... names) {
        for (String name : names) {
            ZONES.put(normalize(name), zone);
        }
    }

    static {
        add("UTC", "utc");
        add("GMT", "gmt");
        add("Europe/Kyiv", "киев", "киеве", "київ", "києві", "kyiv", "kiev", "харьков", "харькове", "харків", "харкові", "kharkiv", "одесса", "одессе", "одеса", "одесі", "odesa", "львов", "львове", "львів", "львові", "lviv", "днепр", "днепре", "дніпро", "дніпрі", "dnipro");
        add("Europe/Moscow", "msk", "мск", "москва", "москве", "moscow", "питер", "питере", "спб", "петербург", "петербурге", "санкт-петербург", "санкт-петербурге", "st petersburg", "saint petersburg");
        add("Europe/Kaliningrad", "калининград", "калининграде", "kaliningrad");
        add("Europe/Samara", "самара", "самаре", "samara");
        add("Asia/Yekaterinburg", "екб", "екатеринбург", "екатеринбурге", "yekaterinburg");
        add("Asia/Omsk", "омск", "омске", "omsk");
        add("Asia/Novosibirsk", "нск", "новосибирск", "новосибирске", "novosibirsk");
        add("Asia/Krasnoyarsk", "красноярск", "красноярске", "krasnoyarsk");
        add("Asia/Irkutsk", "иркутск", "иркутске", "irkutsk");
        add("Asia/Yakutsk", "якутск", "якутске", "yakutsk");
        add("Asia/Vladivostok", "владивосток", "владивостоке", "vladivostok");
        add("Asia/Magadan", "магадан", "магадане", "magadan");
        add("Asia/Kamchatka", "камчатка", "камчатке", "kamchatka");
        add("Europe/Minsk", "минск", "минске", "мінск", "minsk");
        add("Asia/Almaty", "алматы", "almaty", "астана", "астане", "astana");
        add("Asia/Tashkent", "ташкент", "ташкенте", "tashkent");
        add("Asia/Bishkek", "бишкек", "бишкеке", "bishkek");
        add("Asia/Dushanbe", "душанбе", "dushanbe");
        add("Asia/Tbilisi", "тбилиси", "tbilisi");
        add("Asia/Yerevan", "ереван", "ереване", "yerevan");
        add("Asia/Baku", "баку", "baku");
        add("Europe/Chisinau", "кишинев", "кишиневе", "chisinau");
        add("Europe/Riga", "рига", "риге", "riga");
        add("Europe/Vilnius", "вильнюс", "вильнюсе", "vilnius");
        add("Europe/Tallinn", "таллин", "таллине", "tallinn");
        add("Europe/Helsinki", "eet", "eest", "хельсинки", "helsinki");
        add("Europe/Warsaw", "варшава", "варшаве", "варшава", "варшаві", "warsaw");
        add("Europe/Prague", "прага", "праге", "празі", "prague");
        add("Europe/Berlin", "cet", "cest", "берлин", "берлине", "берлін", "берліні", "berlin");
        add("Europe/Paris", "париж", "париже", "парижі", "paris");
        add("Europe/Rome", "рим", "риме", "римі", "rome");
        add("Europe/Madrid", "мадрид", "мадриде", "мадриді", "madrid", "барселона", "барселоне", "барселоні", "barcelona");
        add("Europe/Amsterdam", "амстердам", "амстердаме", "амстердамі", "amsterdam");
        add("Europe/London", "bst", "лондон", "лондоне", "лондоні", "london");
        add("Europe/Lisbon", "wet", "west", "лиссабон", "лиссабоне", "лісабон", "лісабоні", "lisbon");
        add("Europe/Istanbul", "стамбул", "стамбуле", "стамбулі", "istanbul", "анталья", "анталье", "antalya");
        add("Asia/Jerusalem", "тель-авив", "тель-авиве", "tel aviv", "иерусалим", "иерусалиме", "jerusalem");
        add("Asia/Dubai", "дубай", "дубае", "дубаї", "dubai");
        add("Africa/Cairo", "каир", "каире", "cairo");
        add("Asia/Kolkata", "ist", "дели", "delhi", "new delhi", "мумбаи", "mumbai");
        add("Asia/Bangkok", "бангкок", "бангкоке", "bangkok", "пхукет", "пхукете", "phuket");
        add("Asia/Makassar", "бали", "bali");
        add("Asia/Singapore", "sgt", "сингапур", "сингапуре", "singapore");
        add("Asia/Hong_Kong", "hkt", "гонконг", "гонконге", "hong kong");
        add("Asia/Shanghai", "пекин", "пекине", "beijing", "шанхай", "шанхае", "shanghai");
        add("Asia/Seoul", "kst", "сеул", "сеуле", "seoul");
        add("Asia/Tokyo", "jst", "токио", "токіо", "tokyo");
        add("Australia/Perth", "awst", "перт", "перте", "perth");
        add("Australia/Sydney", "aest", "aedt", "сидней", "сиднее", "sydney");
        add("Australia/Melbourne", "мельбурн", "мельбурне", "melbourne");
        add("Pacific/Auckland", "nzst", "nzdt", "окленд", "окленде", "auckland");
        add("America/New_York", "est", "edt", "нью-йорк", "нью-йорке", "нью-йорку", "new york", "nyc", "вашингтон", "вашингтоне", "washington", "майами", "miami");
        add("America/Toronto", "торонто", "toronto");
        add("America/Chicago", "cst", "cdt", "чикаго", "chicago");
        add("America/Denver", "mst", "mdt", "денвер", "денвере", "denver");
        add("America/Los_Angeles", "pst", "pdt", "лос-анджелес", "лос-анджелесе", "los angeles", "сан-франциско", "san francisco", "сиэтл", "сиэтле", "seattle");
        add("America/Vancouver", "ванкувер", "ванкувере", "vancouver");
        add("America/Anchorage", "akst", "akdt", "анкоридж", "анкоридже", "anchorage");
        add("Pacific/Honolulu", "hst", "гонолулу", "honolulu");
        add("America/Mexico_City", "мехико", "mexico city");
        add("America/Sao_Paulo", "сан-паулу", "sao paulo");
        add("America/Argentina/Buenos_Aires", "буэнос-айрес", "буэнос-айресе", "buenos aires");
    }

    private InlineTime() {
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('-', ' ');
    }

    private static ZoneId zoneFor(List<String> words, int from, int to) {
        String joined = String.join(" ", words.subList(from, to));
        String id = ZONES.get(normalize(joined));
        if (id != null) {
            try {
                return ZoneId.of(id);
            } catch (DateTimeException e) {
                // older tzdata calls Kyiv Kiev
                if (id.equals("Europe/Kyiv")) {
                    return ZoneId.of("Europe/Kiev");
                }
                return null;
            }
        }
        Matcher matcher = OFFSET.matcher(joined);
        if (!matcher.matches()) {
            return null;
        }
        boolean moscow = "msk".equals(matcher.group(1)) || "мск".equals(matcher.group(1));
        int hours = Integer.parseInt(matcher.group(3));
        int minutes = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
        if (hours > 18 || minutes > 59) {
            return null;
        }
        int total = (moscow ? 180 : 0) + ("-".equals(matcher.group(2)) ? -1 : 1) * (hours * 60 + minutes);
        try {
            return ZoneOffset.ofTotalSeconds(total * 60);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String format(LocalTime time, String meridiem) {
        String minutes = String.format(Locale.ROOT, "%02d", time.getMinute());
        if (meridiem == null) {
            return String.format(Locale.ROOT, "%02d", time.getHour()) + ":" + minutes;
        }
        return (time.getHour() + 11) % 12 + 1 + ":" + minutes + (time.getHour() < 12 ? " am" : " pm");
    }

    static String valueAt(CharSequence text, int equalsIndex, InlineOptions options) {
        if (options.localZone == null) {
            return null;
        }
        int start = Math.max(0, equalsIndex - 64);
        for (int i = equalsIndex - 1; i >= start; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '=') {
                start = i + 1;
                break;
            }
        }
        String lower = text.subSequence(start, equalsIndex).toString().toLowerCase(Locale.ROOT).replace('ё', 'е').replace('−', '-');
        List<String> words = new ArrayList<>();
        for (String word : lower.split("[ \\t\\u00A0]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        int size = words.size();

        ZoneId target = null;
        for (int count = 3; count > 0 && target == null; count--) {
            int from = size - count;
            if (from >= 1 && TARGET_MARKERS.contains(words.get(from - 1))) {
                target = zoneFor(words, from, size);
                if (target != null) {
                    size = from - 1;
                }
            }
        }
        ZoneId source = null;
        for (int count = 3; count > 0 && source == null; count--) {
            int from = size - count;
            if (from >= 1) {
                source = zoneFor(words, from, size);
                if (source != null) {
                    size = from > 1 && SOURCE_MARKERS.contains(words.get(from - 1)) ? from - 1 : from;
                }
            }
        }
        if (size < 1) {
            return null;
        }
        String token = words.get(size - 1);
        if (MERIDIEMS.contains(token) && size >= 2) {
            token = words.get(size - 2) + token;
        }
        ZoneId local = options.localZone;
        ZonedDateTime now = options.now != null ? options.now : ZonedDateTime.now(local);
        if (NOW_WORDS.contains(token)) {
            if (target == null || source != null) {
                return null;
            }
            return format(now.withZoneSameInstant(target).toLocalTime(), null);
        }
        if (source == null && target == null) {
            // a bare "6pm =" is still a useful 24 hour conversion, a bare "18:00 =" is not
            boolean meridiem = false;
            for (String m : MERIDIEMS) {
                meridiem |= token.endsWith(m);
            }
            if (!meridiem) {
                return null;
            }
        }
        Matcher matcher = TIME.matcher(token);
        if (!matcher.matches()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        String meridiem = matcher.group(3);
        if (matcher.group(2) == null && meridiem == null && source == null) {
            return null;
        }
        if (minute > 59) {
            return null;
        }
        if (meridiem != null) {
            if (hour < 1 || hour > 12) {
                return null;
            }
            hour = hour % 12 + (meridiem.startsWith("p") ? 12 : 0);
        } else if (hour > 23) {
            return null;
        }
        if (source == null && target == null) {
            return format(LocalTime.of(hour, minute), null);
        }
        ZoneId from = source != null ? source : local;
        ZoneId to = target != null ? target : local;
        ZonedDateTime sourceTime = ZonedDateTime.of(now.withZoneSameInstant(from).toLocalDate(), LocalTime.of(hour, minute), from);
        ZonedDateTime targetTime = sourceTime.withZoneSameInstant(to);
        if (targetTime.getOffset().equals(sourceTime.getOffset())) {
            return null;
        }
        long days = targetTime.toLocalDate().toEpochDay() - sourceTime.toLocalDate().toEpochDay();
        String value = format(targetTime.toLocalTime(), meridiem);
        if (days > 0) {
            return value + " (+" + days + ")";
        }
        return days < 0 ? value + " (" + days + ")" : value;
    }
}
