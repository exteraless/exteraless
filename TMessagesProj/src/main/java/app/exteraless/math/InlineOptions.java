package app.exteraless.math;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** What the inline results may do. Kept free of Android so the engine can run in plain unit tests. */
public final class InlineOptions {

    public interface Rates {
        boolean supports(String code);

        /** Price of one {@code from} in {@code to}, or null while rates are not available. */
        Double rate(String from, String to);
    }

    public char decimalSeparator = '.';
    public boolean arithmetic = true;
    public boolean units;
    public boolean degrees;
    public boolean grouping;
    public boolean imperial;
    public boolean cyrillicLabels;
    public Rates rates;
    public String localCurrency;
    /** Null turns time zones off. */
    public ZoneId localZone;
    /** Fixed clock for tests, null means the real one. */
    public ZonedDateTime now;
}
