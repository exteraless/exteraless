package app.exteraless.drawer;

/**
 * Реестр пунктов главного меню: один и тот же список используется меню «⋮»
 * в {@code DialogsActivity} и боковой шторкой {@link DrawerContainer}.
 *
 * Значения id хранятся в настройках, поэтому менять их нельзя.
 */
public enum MainMenuItem {

    /** Разделитель между группами пунктов, а не сам пункт. */
    DIVIDER(-1),
    PROFILE(18),
    ARCHIVE(14),
    /** Разворачивается в список attach-menu-ботов, у которых {@code show_in_side_menu}. */
    BOTS(105),
    NEW_GROUP(2),
    CONTACTS(6),
    NEW_CHANNEL(3),
    CALLS(10),
    SAVED(11),
    SETTINGS(8),
    BROWSER(101),
    PLUGINS(102),
    QR(17),
    FEED(106),
    /** Пункт наш, а не из exteraGram, поэтому id взят выше их диапазона. */
    GHOST_MODE(107),
    RECENT_CHATS(108);

    private final int id;

    MainMenuItem(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /** Линейный поиск по значениям. */
    public static MainMenuItem getById(int id) {
        for (MainMenuItem item : values()) {
            if (item.id == id) {
                return item;
            }
        }
        return null;
    }
}
