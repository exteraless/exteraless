package app.exteraless.pillstack;

/**
 * Типы пилюль. Идентификаторы совпадают с оригиналом из exteraGram,
 * чтобы раскладка читалась одинаково.
 */
public enum PillType {
    WEATHER(1),
    GRAM(2),
    BTC(3),
    USD(4),
    CACHE(5),
    PROXY(6),
    /** Идентификатора в exteraGram нет — пилюля наша, поэтому номер после последнего чужого. */
    GHOST(7),
    // Собственные пилюли exteraless, номера дальше по порядку.
    RAM(8),
    CPU(9),
    NET_SPEED(10),
    DC_PING(11),
    GOLD(12),
    ETH(13),
    EUR(14);

    public final int id;

    PillType(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}
