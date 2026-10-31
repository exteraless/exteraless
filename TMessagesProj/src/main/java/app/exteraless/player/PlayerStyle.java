package app.exteraless.player;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import app.exteraless.appearance.AppearanceConfig;
import tw.nekomimi.nekogram.config.ConfigItem;

public final class PlayerStyle {

    public static final int COVER_CARD = 0;
    public static final int COVER_WIDE = 1;
    public static final int COVER_FULL = 2;
    public static final int COVER_DISC = 3;

    public static final int BLOCK_PROGRESS = 0;
    public static final int BLOCK_CONTROLS = 1;
    public static final int BLOCK_ACTIONS = 2;

    public static final int ACTIONS_BUTTONS = 0;
    public static final int ACTIONS_ISLAND = 1;

    public static final int COLORS_COVER = 0;
    public static final int COLORS_THEME = 1;

    public static final int BACKGROUND_SOLID = 0;
    public static final int BACKGROUND_GRADIENT = 1;
    public static final int BACKGROUND_BLUR = 2;

    public static final int MINI_COVER_ROUNDED = 0;
    public static final int MINI_COVER_ROUND = 1;
    public static final int MINI_COVER_HIDDEN = 2;

    public static final int MINI_SHAPE_ROUNDED = 0;
    public static final int MINI_SHAPE_PILL = 1;
    public static final int MINI_SHAPE_SQUARE = 2;

    public static final int MINI_PROGRESS_RING = 0;
    public static final int MINI_PROGRESS_LINE = 1;
    public static final int MINI_PROGRESS_NONE = 2;
    public static final int MINI_PROGRESS_BORDER = 3;

    public static final int COVER_SIZE_MIN = 60;
    public static final int COVER_SIZE_MAX = 100;
    public static final int COVER_RADIUS_MAX = 48;

    private static final int[] DEFAULT_ORDER = {BLOCK_PROGRESS, BLOCK_CONTROLS, BLOCK_ACTIONS};

    private PlayerStyle() {
    }

    private static ConfigItem item(ConfigItem item) {
        AppearanceConfig.ensureLoaded();
        return item;
    }

    public static int coverStyle() {
        int value = item(AppearanceConfig.playerCoverStyle).Int();
        return value < COVER_CARD || value > COVER_DISC ? COVER_CARD : value;
    }

    public static float coverSize() {
        int value = item(AppearanceConfig.playerCoverSize).Int();
        return Math.max(COVER_SIZE_MIN, Math.min(COVER_SIZE_MAX, value)) / 100f;
    }

    public static int coverRadiusDp() {
        int value = item(AppearanceConfig.playerCoverRadius).Int();
        return Math.max(0, Math.min(COVER_RADIUS_MAX, value));
    }

    public static boolean discSpin() {
        return item(AppearanceConfig.playerDiscSpin).Bool();
    }

    public static boolean coverSwipe() {
        return item(AppearanceConfig.playerCoverSwipe).Bool();
    }

    public static boolean coverExtend() {
        return item(AppearanceConfig.playerCoverExtend).Bool();
    }

    public static boolean titleCentered() {
        return item(AppearanceConfig.playerTitleAlign).Int() == 1;
    }

    public static int titleSize() {
        int value = item(AppearanceConfig.playerTitleSize).Int();
        return Math.max(0, Math.min(2, value));
    }

    public static boolean titleAbove() {
        int style = coverStyle();
        return (style == COVER_CARD || style == COVER_DISC) && item(AppearanceConfig.playerTitleAbove).Bool();
    }

    public static int[] order() {
        String raw = item(AppearanceConfig.playerOrder).String();
        if (TextUtils.isEmpty(raw)) {
            return DEFAULT_ORDER.clone();
        }
        List<Integer> result = new ArrayList<>(3);
        for (String part : raw.split(",")) {
            try {
                int block = Integer.parseInt(part.trim());
                if (block >= BLOCK_PROGRESS && block <= BLOCK_ACTIONS && !result.contains(block)) {
                    result.add(block);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        for (int block : DEFAULT_ORDER) {
            if (!result.contains(block)) {
                result.add(block);
            }
        }
        int[] out = new int[result.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = result.get(i);
        }
        return out;
    }

    public static void setOrder(int[] order) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < order.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(order[i]);
        }
        String value = sb.toString();
        item(AppearanceConfig.playerOrder).setConfigString("0,1,2".equals(value) ? "" : value);
    }

    public static boolean showActions() {
        return item(AppearanceConfig.playerShowActions).Bool();
    }

    public static boolean actionsIsland() {
        return item(AppearanceConfig.playerActionsStyle).Int() == ACTIONS_ISLAND;
    }

    public static boolean showModes() {
        return item(AppearanceConfig.playerShowModes).Bool();
    }

    public static boolean showLike() {
        return item(AppearanceConfig.playerShowLike).Bool();
    }

    public static boolean showSource() {
        return item(AppearanceConfig.playerShowSource).Bool();
    }

    public static boolean wavy() {
        return item(AppearanceConfig.playerWavy).Bool();
    }

    public static float controlsScale() {
        switch (item(AppearanceConfig.playerControlsSize).Int()) {
            case 0:
                return 0.84f;
            case 2:
                return 1.14f;
            default:
                return 1f;
        }
    }

    public static boolean themeColors() {
        return item(AppearanceConfig.playerColors).Int() == COLORS_THEME;
    }

    public static int background() {
        int value = item(AppearanceConfig.playerBackground).Int();
        return value < BACKGROUND_SOLID || value > BACKGROUND_BLUR ? BACKGROUND_SOLID : value;
    }

    private static int choice(ConfigItem item, int max) {
        int value = item(item).Int();
        return value < 0 || value > max ? 0 : value;
    }

    public static int miniCover() {
        return choice(AppearanceConfig.playerMiniCover, MINI_COVER_HIDDEN);
    }

    public static int miniShape() {
        return choice(AppearanceConfig.playerMiniShape, MINI_SHAPE_SQUARE);
    }

    public static int miniProgress() {
        return choice(AppearanceConfig.playerMiniProgress, MINI_PROGRESS_BORDER);
    }

    public static boolean miniThemeColors() {
        return item(AppearanceConfig.playerMiniColors).Int() != COLORS_COVER;
    }

    public static boolean miniCompact() {
        return item(AppearanceConfig.playerMiniCompact).Bool();
    }

    public static boolean miniArtist() {
        return item(AppearanceConfig.playerMiniArtist).Bool();
    }

    public static boolean miniPrevious() {
        return item(AppearanceConfig.playerMiniPrevious).Bool();
    }

    public static boolean miniNext() {
        return item(AppearanceConfig.playerMiniNext).Bool();
    }

    public static boolean miniClose() {
        return item(AppearanceConfig.playerMiniClose).Bool();
    }

    public static boolean miniTop() {
        return item(AppearanceConfig.playerMiniTop).Bool();
    }

    public static void reset() {
        for (ConfigItem item : AppearanceConfig.playerStyleItems()) {
            switch (item.type) {
                case ConfigItem.configTypeBool:
                    item.setConfigBool((Boolean) item.defaultValue);
                    break;
                case ConfigItem.configTypeInt:
                    item.setConfigInt((Integer) item.defaultValue);
                    break;
                case ConfigItem.configTypeString:
                    item.setConfigString((String) item.defaultValue);
                    break;
            }
        }
    }
}
