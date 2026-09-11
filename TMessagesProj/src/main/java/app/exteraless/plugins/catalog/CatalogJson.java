package app.exteraless.plugins.catalog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CatalogJson {
    private CatalogJson() {}

    static CatalogException protocol(String message) {
        return new CatalogException(CatalogException.Kind.PROTOCOL, message);
    }

    static String requiredString(JSONObject json, String key, int max) throws CatalogException {
        String value = nullableString(json, key, max);
        if (value == null || value.trim().isEmpty()) throw protocol("Missing field: " + key);
        return value;
    }

    static String string(JSONObject json, String key, String fallback, int max)
            throws CatalogException {
        String value = nullableString(json, key, max);
        return value == null ? fallback : value;
    }

    static String nullableString(JSONObject json, String key, int max) throws CatalogException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof String)) throw protocol("Invalid string field: " + key);
        String string = (String) value;
        if (string.length() > max) throw protocol("Field too long: " + key);
        return string;
    }

    static long requiredPositiveLong(JSONObject json, String key) throws CatalogException {
        long value = longValue(json.opt(key), -1, key);
        if (value <= 0) throw protocol("Invalid positive integer: " + key);
        return value;
    }

    static long nonNegativeLong(JSONObject json, String key, long fallback) throws CatalogException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) return fallback;
        long parsed = longValue(value, fallback, key);
        if (parsed < 0) throw protocol("Invalid non-negative integer: " + key);
        return parsed;
    }

    static int nonNegativeInt(JSONObject json, String key, int fallback) throws CatalogException {
        long value = nonNegativeLong(json, key, fallback);
        if (value > Integer.MAX_VALUE) throw protocol("Integer is too large: " + key);
        return (int) value;
    }

    static Long nullableLong(JSONObject json, String key) throws CatalogException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        return longValue(value, -1, key);
    }

    static double finiteDouble(JSONObject json, String key, double fallback) throws CatalogException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) return fallback;
        final double parsed;
        try {
            parsed = value instanceof Number ? ((Number) value).doubleValue()
                    : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw protocol("Invalid number: " + key);
        }
        if (!Double.isFinite(parsed)) throw protocol("Non-finite number: " + key);
        return parsed;
    }

    static Boolean nullableBoolean(JSONObject json, String key) throws CatalogException {
        Object value = json.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof Boolean)) throw protocol("Invalid boolean field: " + key);
        return (Boolean) value;
    }

    static String requiredSha256(JSONObject json, String key) throws CatalogException {
        String value = requiredString(json, key, 64).toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) throw protocol("Invalid SHA-256: " + key);
        return value;
    }

    static List<String> stringList(Object wireValue, int maxItems, int maxItemLength)
            throws CatalogException {
        JSONArray array;
        if (wireValue == null || wireValue == JSONObject.NULL) return new ArrayList<>();
        if (wireValue instanceof JSONArray) {
            array = (JSONArray) wireValue;
        } else if (wireValue instanceof String) {
            String string = ((String) wireValue).trim();
            if (string.isEmpty()) return new ArrayList<>();
            try {
                array = new JSONArray(string);
            } catch (JSONException e) {
                throw protocol("Invalid JSON list");
            }
        } else {
            throw protocol("Invalid list field");
        }
        if (array.length() > maxItems) throw protocol("Too many list items");
        ArrayList<String> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof String)) throw protocol("Invalid list item");
            String string = (String) item;
            if (string.length() > maxItemLength) throw protocol("List item too long");
            result.add(string);
        }
        return result;
    }

    private static long longValue(Object value, long fallback, String key) throws CatalogException {
        if (value == null || value == JSONObject.NULL) return fallback;
        if (value instanceof Number) {
            Number number = (Number) value;
            double decimal = number.doubleValue();
            long integer = number.longValue();
            if (!Double.isFinite(decimal) || decimal != integer) {
                throw protocol("Invalid integer: " + key);
            }
            return integer;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw protocol("Invalid integer: " + key);
        }
    }
}
