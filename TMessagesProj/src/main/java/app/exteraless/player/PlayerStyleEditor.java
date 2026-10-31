package app.exteraless.player;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.transition.AutoTransition;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import app.exteraless.appearance.AppearanceConfig;
import tw.nekomimi.nekogram.config.ConfigItem;

@SuppressLint("ViewConstructor")
public class PlayerStyleEditor extends LinearLayout {

    public static final int TARGET_NONE = -1;
    public static final int TARGET_COVER = 0;
    public static final int TARGET_TITLE = 1;
    public static final int TARGET_PROGRESS = 2;
    public static final int TARGET_CONTROLS = 3;
    public static final int TARGET_ACTIONS = 4;

    public interface Listener {
        void onStyleChanged();
    }

    private interface Part {
        void bind(PlayerColors colors);

        void refresh();
    }

    private final Listener listener;
    private final ArrayList<Part> parts = new ArrayList<>();
    private PlayerColors colors;
    private View sizeRow;
    private View radiusRow;
    private View spinRow;
    private View swipeRow;
    private View positionGroup;
    private View backgroundGroup;
    private ElementCard titleCard;
    private final ElementCard[] blockCard = new ElementCard[3];
    private LinearLayout blockCards;
    private PlayerMiniView miniPreview;
    private View extendRow;
    private ChipGroup coverChips;
    private final LinearLayout[] sections = new LinearLayout[3];
    private LinearLayout section;
    private static int selectedSection;

    public PlayerStyleEditor(Context context, PlayerColors colors, Listener listener) {
        super(context);
        this.colors = colors;
        this.listener = listener;
        setOrientation(VERTICAL);
        setPadding(0, 0, 0, dp(16));

        Segmented tabs = new Segmented(context, new String[]{
                getString(R.string.OEPlayerStyleCover), getString(R.string.OEPlayerStyleElements), getString(R.string.OEPlayerStyleMini)
        }, () -> selectedSection, this::selectSection);
        addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 16, 8, 16, 4));
        parts.add(tabs);
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new LinearLayout(context);
            sections[i].setOrientation(VERTICAL);
            addView(sections[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        section = sections[0];
        addSegmented(new String[]{
                getString(R.string.OEPlayerCoverCard), getString(R.string.OEPlayerCoverWide),
                getString(R.string.OEPlayerCoverFull), getString(R.string.OEPlayerCoverDisc)
        }, PlayerStyle::coverStyle, value -> AppearanceConfig.playerCoverStyle.setConfigInt(value));
        sizeRow = addSlider(R.string.OEPlayerCoverSize, PlayerStyle.COVER_SIZE_MIN, PlayerStyle.COVER_SIZE_MAX, 5, "%",
                () -> Math.round(PlayerStyle.coverSize() * 100), value -> AppearanceConfig.playerCoverSize.setConfigInt(value));
        radiusRow = addSlider(R.string.OEPlayerCoverRadius, 0, PlayerStyle.COVER_RADIUS_MAX, 2, "",
                PlayerStyle::coverRadiusDp, value -> AppearanceConfig.playerCoverRadius.setConfigInt(value));
        coverChips = addChips();
        spinRow = addChip(coverChips, R.string.OEPlayerDiscSpin, AppearanceConfig.playerDiscSpin);
        swipeRow = addChip(coverChips, R.string.OEPlayerCoverSwipe, AppearanceConfig.playerCoverSwipe);
        extendRow = addChip(coverChips, R.string.OEPlayerCoverExtend, AppearanceConfig.playerCoverExtend);
        addHeader(R.string.OEPlayerStyleColors);
        addSegmented(new String[]{getString(R.string.OEPlayerColorsCover), getString(R.string.OEPlayerColorsTheme)},
                () -> PlayerStyle.themeColors() ? 1 : 0, value -> AppearanceConfig.playerColors.setConfigInt(value));
        backgroundGroup = addSegmented(new String[]{
                getString(R.string.OEPlayerBackgroundSolid), getString(R.string.OEPlayerBackgroundGradient), getString(R.string.OEPlayerBackgroundBlur)
        }, PlayerStyle::background, value -> AppearanceConfig.playerBackground.setConfigInt(value));

        section = sections[1];
        addInfo(R.string.OEPlayerEditorTapHint);
        titleCard = new ElementCard(context, TARGET_TITLE, getString(R.string.OEPlayerStyleTitleSection), null, false);
        section.addView(titleCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));
        section = titleCard.body;
        addSegmented(new String[]{getString(R.string.OEPlayerAlignStart), getString(R.string.OEPlayerAlignCenter)},
                () -> PlayerStyle.titleCentered() ? 1 : 0, value -> AppearanceConfig.playerTitleAlign.setConfigInt(value));
        addSegmented(new String[]{
                getString(R.string.OEPlayerSizeSmall), getString(R.string.OEPlayerSizeMedium), getString(R.string.OEPlayerSizeLarge)
        }, PlayerStyle::titleSize, value -> AppearanceConfig.playerTitleSize.setConfigInt(value));
        positionGroup = addSegmented(new String[]{getString(R.string.OEPlayerTitleBelow), getString(R.string.OEPlayerTitleAboveCover)},
                () -> AppearanceConfig.playerTitleAbove.Bool() ? 1 : 0, value -> AppearanceConfig.playerTitleAbove.setConfigBool(value == 1));
        ChipGroup titleChips = addChips();
        addChip(titleChips, R.string.OEPlayerShowLike, AppearanceConfig.playerShowLike);
        addChip(titleChips, R.string.OEPlayerShowSource, AppearanceConfig.playerShowSource);

        blockCards = new LinearLayout(context);
        blockCards.setOrientation(VERTICAL);
        sections[1].addView(blockCards, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 12, 16, 0));

        blockCard[PlayerStyle.BLOCK_PROGRESS] = new ElementCard(context, TARGET_PROGRESS, getString(R.string.OEPlayerBlockProgress), null, true);
        section = blockCard[PlayerStyle.BLOCK_PROGRESS].body;
        ChipGroup progressChips = addChips();
        addChip(progressChips, R.string.OEPlayerShowWave, AppearanceConfig.playerWavy);

        blockCard[PlayerStyle.BLOCK_CONTROLS] = new ElementCard(context, TARGET_CONTROLS, getString(R.string.OEPlayerBlockControls), null, true);
        section = blockCard[PlayerStyle.BLOCK_CONTROLS].body;
        addSegmented(new String[]{
                getString(R.string.OEPlayerControlsCompact), getString(R.string.OEPlayerControlsNormal), getString(R.string.OEPlayerControlsLarge)
        }, () -> {
            int value = AppearanceConfig.playerControlsSize.Int();
            return value < 0 || value > 2 ? 1 : value;
        }, value -> AppearanceConfig.playerControlsSize.setConfigInt(value));
        ChipGroup controlChips = addChips();
        addChip(controlChips, R.string.OEPlayerShowModes, AppearanceConfig.playerShowModes);

        blockCard[PlayerStyle.BLOCK_ACTIONS] = new ElementCard(context, TARGET_ACTIONS, getString(R.string.OEPlayerBlockActions),
                AppearanceConfig.playerShowActions, true);
        section = blockCard[PlayerStyle.BLOCK_ACTIONS].body;
        addSegmented(new String[]{getString(R.string.OEPlayerActionsButtons), getString(R.string.OEPlayerActionsIsland)},
                () -> PlayerStyle.actionsIsland() ? 1 : 0, value -> AppearanceConfig.playerActionsStyle.setConfigInt(value));
        layoutBlockCards();

        section = sections[1];
        addInfo(R.string.OEPlayerLayoutInfo);

        section = sections[2];
        miniPreview = PlayerMiniView.preview(context);
        section.addView(miniPreview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, PlayerMiniView.HEIGHT_DP + 16));
        addInfo(R.string.OEPlayerMiniMoveHint);
        addInfo(R.string.OEPlayerStyleCover);
        addSegmented(new String[]{
                getString(R.string.OEPlayerMiniCoverRounded), getString(R.string.OEPlayerMiniCoverRound), getString(R.string.OEPlayerMiniCoverHidden)
        }, PlayerStyle::miniCover, value -> AppearanceConfig.playerMiniCover.setConfigInt(value));
        addInfo(R.string.OEPlayerMiniShape);
        addSegmented(new String[]{
                getString(R.string.OEPlayerMiniShapeRounded), getString(R.string.OEPlayerMiniShapePill), getString(R.string.OEPlayerMiniShapeSquare)
        }, PlayerStyle::miniShape, value -> AppearanceConfig.playerMiniShape.setConfigInt(value));
        addInfo(R.string.OEPlayerMiniProgress);
        addSegmented(new String[]{
                getString(R.string.OEPlayerMiniProgressRing), getString(R.string.OEPlayerMiniProgressLine),
                getString(R.string.OEPlayerMiniProgressBorder), getString(R.string.OEPlayerMiniProgressNone)
        }, () -> {
            int value = PlayerStyle.miniProgress();
            return value == PlayerStyle.MINI_PROGRESS_BORDER ? 2 : value == PlayerStyle.MINI_PROGRESS_NONE ? 3 : value;
        }, value -> AppearanceConfig.playerMiniProgress.setConfigInt(
                value == 2 ? PlayerStyle.MINI_PROGRESS_BORDER : value == 3 ? PlayerStyle.MINI_PROGRESS_NONE : value));
        addInfo(R.string.OEPlayerMiniColors);
        addSegmented(new String[]{getString(R.string.OEPlayerColorsCover), getString(R.string.OEPlayerColorsTheme)},
                () -> PlayerStyle.miniThemeColors() ? 1 : 0, value -> AppearanceConfig.playerMiniColors.setConfigInt(value));
        ChipGroup miniChips = addChips();
        addChip(miniChips, R.string.OEPlayerMiniCompact, AppearanceConfig.playerMiniCompact);
        addChip(miniChips, R.string.OEPlayerMiniArtist, AppearanceConfig.playerMiniArtist);
        addChip(miniChips, R.string.OEPlayerMiniPrevious, AppearanceConfig.playerMiniPrevious);
        addChip(miniChips, R.string.OEPlayerMiniNext, AppearanceConfig.playerMiniNext);
        addChip(miniChips, R.string.OEPlayerMiniClose, AppearanceConfig.playerMiniClose);

        selectSection(selectedSection);
        refreshVisibility();
        setColors(colors);
    }

    public void focus(int target) {
        if (target == TARGET_COVER) {
            selectSection(0);
            return;
        }
        ElementCard card = target == TARGET_TITLE ? titleCard : target == TARGET_PROGRESS ? blockCard[PlayerStyle.BLOCK_PROGRESS]
                : target == TARGET_CONTROLS ? blockCard[PlayerStyle.BLOCK_CONTROLS]
                : target == TARGET_ACTIONS ? blockCard[PlayerStyle.BLOCK_ACTIONS] : null;
        if (card == null) {
            return;
        }
        if (selectedSection != 1) {
            selectSection(1);
        }
        expand(card, true);
        postDelayed(() -> scrollToCard(card), 260);
    }

    private void scrollToCard(View card) {
        if (!(getParent() instanceof ScrollView)) {
            return;
        }
        int y = 0;
        for (View view = card; view != null && view != this; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            y += view.getTop();
        }
        ((ScrollView) getParent()).smoothScrollTo(0, Math.max(0, y - dp(8)));
    }

    private void expand(ElementCard card, boolean animated) {
        if (animated && isLaidOut()) {
            TransitionManager.beginDelayedTransition(this, new AutoTransition().setDuration(220));
        }
        for (ElementCard item : new ElementCard[]{titleCard, blockCard[0], blockCard[1], blockCard[2]}) {
            if (item != null) {
                item.setExpanded(item == card);
                item.bindShape();
            }
        }
    }

    private static int blockOf(int target) {
        return target == TARGET_PROGRESS ? PlayerStyle.BLOCK_PROGRESS
                : target == TARGET_CONTROLS ? PlayerStyle.BLOCK_CONTROLS : PlayerStyle.BLOCK_ACTIONS;
    }

    private void moveBlock(int block, int delta) {
        int[] order = PlayerStyle.order();
        int index = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == block) {
                index = i;
            }
        }
        int target = index + delta;
        if (index < 0 || target < 0 || target >= order.length) {
            return;
        }
        int tmp = order[index];
        order[index] = order[target];
        order[target] = tmp;
        PlayerStyle.setOrder(order);
        if (isLaidOut()) {
            TransitionManager.beginDelayedTransition(blockCards, new ChangeBounds().setDuration(220));
        }
        layoutBlockCards();
        changed();
    }

    private void layoutBlockCards() {
        if (blockCards == null) {
            return;
        }
        int[] order = PlayerStyle.order();
        blockCards.removeAllViews();
        for (int i = 0; i < order.length; i++) {
            ElementCard card = blockCard[order[i]];
            blockCards.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, i == 0 ? 0 : 2, 0, 0));
            card.setPosition(i, order.length);
        }
    }

    private void selectSection(int index) {
        selectedSection = Math.max(0, Math.min(sections.length - 1, index));
        for (int i = 0; i < sections.length; i++) {
            sections[i].setVisibility(i == selectedSection ? VISIBLE : GONE);
        }
        if (getParent() instanceof ScrollView) {
            ((ScrollView) getParent()).scrollTo(0, 0);
        }
    }

    public void setColors(PlayerColors colors) {
        this.colors = colors;
        for (Part part : parts) {
            part.bind(colors);
        }
    }

    public void refresh() {
        for (Part part : parts) {
            part.refresh();
        }
        layoutBlockCards();
        refreshVisibility();
        refreshMini();
    }

    private void refreshMini() {
        if (miniPreview != null) {
            miniPreview.applyStyle(true);
        }
        PlayerMiniView.styleChanged();
    }

    private void changed() {
        refreshVisibility();
        refreshMini();
        if (listener != null) {
            listener.onStyleChanged();
        }
    }

    private void refreshVisibility() {
        int style = PlayerStyle.coverStyle();
        boolean framed = style == PlayerStyle.COVER_CARD || style == PlayerStyle.COVER_DISC;
        setVisible(sizeRow, framed);
        setVisible(radiusRow, style == PlayerStyle.COVER_CARD);
        setVisible(spinRow, style == PlayerStyle.COVER_DISC);
        setVisible(swipeRow, framed);
        setVisible(extendRow, style == PlayerStyle.COVER_WIDE);
        setVisible(coverChips, style != PlayerStyle.COVER_FULL);
        setVisible(positionGroup, framed);
        setVisible(backgroundGroup, style != PlayerStyle.COVER_FULL);
    }

    private static void setVisible(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? VISIBLE : GONE);
        }
    }

    private void addHeader(int textRes) {
        TextView header = new TextView(getContext());
        header.setText(getString(textRes));
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        header.setTypeface(AndroidUtilities.bold());
        section.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 20, 24, 8));
        parts.add(new Part() {
            @Override
            public void bind(PlayerColors c) {
                header.setTextColor(c.primary);
            }

            @Override
            public void refresh() {
            }
        });
    }

    private void addInfo(int textRes) {
        TextView info = new TextView(getContext());
        info.setText(getString(textRes));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        section.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 6, 24, 4));
        parts.add(new Part() {
            @Override
            public void bind(PlayerColors c) {
                info.setTextColor(c.onSurfaceVariant);
            }

            @Override
            public void refresh() {
            }
        });
    }

    private View addSegmented(String[] options, IntSupplier getter, IntConsumer setter) {
        Segmented segmented = new Segmented(getContext(), options, getter, value -> {
            setter.accept(value);
            changed();
        });
        segmented.inset = section.getParent() instanceof ElementCard;
        section.addView(segmented, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 16, 4, 16, 4));
        parts.add(segmented);
        return segmented;
    }

    private View addSlider(int labelRes, int min, int max, int step, String suffix, IntSupplier getter, IntConsumer setter) {
        SliderRow row = new SliderRow(getContext(), getString(labelRes), min, max, step, suffix, getter, value -> {
            setter.accept(value);
            changed();
        });
        section.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));
        parts.add(row);
        return row;
    }

    private ChipGroup addChips() {
        ChipGroup group = new ChipGroup(getContext());
        section.addView(group, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 10, 16, 6));
        return group;
    }

    private View addChip(ChipGroup group, int labelRes, ConfigItem item) {
        Chip chip = new Chip(getContext(), getString(labelRes), item::Bool, value -> {
            item.setConfigBool(value);
            changed();
        });
        group.addView(chip);
        parts.add(chip);
        return chip;
    }

    private interface BoolSupplier {
        boolean get();
    }

    private interface BoolConsumer {
        void accept(boolean value);
    }

    private static class Segmented extends LinearLayout implements Part {

        private final TextView[] items;
        private final GradientDrawable[] backgrounds;
        private final IntSupplier getter;
        private PlayerColors colors;
        boolean inset;

        Segmented(Context context, String[] options, IntSupplier getter, IntConsumer onSelect) {
            super(context);
            this.getter = getter;
            setOrientation(HORIZONTAL);
            items = new TextView[options.length];
            backgrounds = new GradientDrawable[options.length];
            for (int i = 0; i < options.length; i++) {
                final int index = i;
                TextView item = new TextView(context);
                item.setText(options[i]);
                item.setGravity(Gravity.CENTER);
                item.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                item.setTypeface(AndroidUtilities.bold());
                item.setSingleLine(true);
                item.setEllipsize(TextUtils.TruncateAt.END);
                item.setPadding(dp(6), 0, dp(6), 0);
                GradientDrawable bg = new GradientDrawable();
                float outer = dp(22);
                float inner = dp(6);
                float l = i == 0 ? outer : inner;
                float r = i == options.length - 1 ? outer : inner;
                bg.setCornerRadii(new float[]{l, l, r, r, r, r, l, l});
                backgrounds[i] = bg;
                item.setBackground(bg);
                item.setOnClickListener(v -> {
                    if (getter.getAsInt() != index) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        onSelect.accept(index);
                        refresh();
                    }
                });
                items[i] = item;
                addView(item, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, i == 0 ? 0 : 2, 0, 0, 0));
            }
        }

        @Override
        public void bind(PlayerColors c) {
            colors = c;
            refresh();
        }

        @Override
        public void refresh() {
            if (colors == null) {
                return;
            }
            int selected = getter.getAsInt();
            for (int i = 0; i < items.length; i++) {
                boolean on = i == selected;
                backgrounds[i].setColor(on ? colors.secondaryContainer : inset ? colors.surfaceContainer : colors.surfaceHigh);
                items[i].setTextColor(on ? colors.onSecondaryContainer : colors.onSurfaceVariant);
                items[i].setSelected(on);
            }
        }
    }

    private static class SliderRow extends LinearLayout implements Part {

        private final TextView label;
        private final TextView valueView;
        private final WavySeekBar bar;
        private final int min;
        private final int max;
        private final int step;
        private final String suffix;
        private final IntSupplier getter;

        SliderRow(Context context, String text, int min, int max, int step, String suffix, IntSupplier getter, IntConsumer onChange) {
            super(context);
            this.min = min;
            this.max = max;
            this.step = step;
            this.suffix = suffix;
            this.getter = getter;
            setOrientation(VERTICAL);
            LinearLayout top = new LinearLayout(context);
            top.setOrientation(HORIZONTAL);
            label = new TextView(context);
            label.setText(text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            top.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            valueView = new TextView(context);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            valueView.setTypeface(AndroidUtilities.bold());
            valueView.setFontFeatureSettings("tnum");
            top.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            bar = new WavySeekBar(context);
            bar.setMaxAmplitude(0);
            bar.setContentDescription(text);
            bar.setDelegate(new WavySeekBar.Delegate() {
                private int last = Integer.MIN_VALUE;

                @Override
                public void onSeekStart() {
                    last = getter.getAsInt();
                }

                @Override
                public void onSeekMove(float progress) {
                    apply(progress, false);
                }

                @Override
                public void onSeekEnd(float progress, boolean commit) {
                    apply(progress, true);
                }

                private void apply(float progress, boolean end) {
                    int value = snap(progress);
                    valueView.setText(format(value));
                    if (value != last) {
                        last = value;
                        bar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        onChange.accept(value);
                    }
                    if (end) {
                        bar.setProgress(fraction(value));
                    }
                }
            });
            addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));
            refresh();
        }

        private int snap(float progress) {
            float raw = min + (max - min) * progress;
            int value = Math.round(raw / step) * step;
            return Math.max(min, Math.min(max, value));
        }

        private float fraction(int value) {
            return (value - min) / (float) Math.max(1, max - min);
        }

        private String format(int value) {
            return suffix.isEmpty() ? String.valueOf(value) : value + suffix;
        }

        @Override
        public void bind(PlayerColors c) {
            label.setTextColor(c.onSurface);
            valueView.setTextColor(c.primary);
            bar.setColors(c.primary, c.secondaryContainer);
        }

        @Override
        public void refresh() {
            int value = getter.getAsInt();
            valueView.setText(format(value));
            bar.setProgress(fraction(value));
        }
    }

    private static class ChipGroup extends ViewGroup {

        ChipGroup(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int gap = dp(8);
            int x = 0;
            int y = 0;
            int rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(dp(32), MeasureSpec.EXACTLY));
                int childWidth = child.getMeasuredWidth();
                if (x > 0 && x + childWidth > width) {
                    x = 0;
                    y += rowHeight + gap;
                    rowHeight = 0;
                }
                x += childWidth + gap;
                rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + rowHeight);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int gap = dp(8);
            int x = 0;
            int y = 0;
            int rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                int childWidth = child.getMeasuredWidth();
                int childHeight = child.getMeasuredHeight();
                if (x > 0 && x + childWidth > width) {
                    x = 0;
                    y += rowHeight + gap;
                    rowHeight = 0;
                }
                int left = LocaleController.isRTL ? width - x - childWidth : x;
                child.layout(left, y, left + childWidth, y + childHeight);
                x += childWidth + gap;
                rowHeight = Math.max(rowHeight, childHeight);
            }
        }
    }

    private static class Chip extends TextView implements Part {

        private final BoolSupplier getter;
        private final GradientDrawable background = new GradientDrawable();
        private final PlayerIcon check = PlayerIcon.stroke(PlayerIcon.CHECK, 18);
        private PlayerColors colors;

        Chip(Context context, String text, BoolSupplier getter, BoolConsumer onChange) {
            super(context);
            this.getter = getter;
            setText(text);
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            setTypeface(AndroidUtilities.bold());
            setGravity(Gravity.CENTER_VERTICAL);
            setSingleLine(true);
            setEllipsize(TextUtils.TruncateAt.END);
            setCompoundDrawablePadding(dp(8));
            background.setCornerRadius(dp(8));
            setOnClickListener(v -> {
                boolean value = !getter.get();
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                onChange.accept(value);
                refresh();
            });
        }

        @Override
        public void bind(PlayerColors c) {
            colors = c;
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(dp(8));
            mask.setColor(0xffffffff);
            setBackground(new RippleDrawable(ColorStateList.valueOf(c.ripple()), background, mask));
            refresh();
        }

        @Override
        public void refresh() {
            if (colors == null) {
                return;
            }
            boolean on = getter.get();
            setSelected(on);
            background.setColor(on ? colors.secondaryContainer : 0);
            background.setStroke(on ? 0 : dp(1), colors.outlineVariant);
            int textColor = on ? colors.onSecondaryContainer : colors.onSurfaceVariant;
            setTextColor(textColor);
            if (on) {
                check.setColor(textColor);
                setCompoundDrawablesRelativeWithIntrinsicBounds(check, null, null, null);
                setPaddingRelative(dp(8), 0, dp(16), 0);
            } else {
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
                setPaddingRelative(dp(16), 0, dp(16), 0);
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.CheckBox");
            info.setCheckable(true);
            info.setChecked(getter.get());
        }
    }

    private class ElementCard extends LinearLayout implements Part {

        private final int target;
        private final ConfigItem visibility;
        private final FrameLayout header;
        private final TextView title;
        private final MorphButton eye;
        private final MorphButton up;
        private final MorphButton down;
        private final PlayerIcon chevronDown = PlayerIcon.stroke(PlayerIcon.CHEVRON_DOWN, 18);
        private final PlayerIcon chevronUp = PlayerIcon.stroke(PlayerIcon.CHEVRON_UP, 18);
        private final GradientDrawable background = new GradientDrawable();
        final LinearLayout body;
        private PlayerColors colors;
        private boolean expanded;
        private int position;
        private int count = 1;

        ElementCard(Context context, int target, String text, ConfigItem visibility, boolean movable) {
            super(context);
            this.target = target;
            this.visibility = visibility;
            setOrientation(VERTICAL);
            setBackground(background);
            header = new FrameLayout(context);
            header.setOnClickListener(v -> expand(expanded ? null : this, true));
            title = new TextView(context);
            title.setText(text);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setCompoundDrawablePadding(dp(6));
            int buttons = (movable ? 2 : 0) + (visibility != null ? 1 : 0);
            header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL | Gravity.LEFT, 16, 0, 12 + 44 * buttons, 0));
            int right = 8;
            if (movable) {
                down = new MorphButton(context, PlayerIcon.stroke(PlayerIcon.ARROW_DOWN, 20));
                down.setRadius(dp(20), false);
                down.setContentDescription(getString(R.string.OEPlayerMoveDown));
                down.setOnClickListener(v -> moveBlock(blockOf(target), 1));
                header.addView(down, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, right, 0));
                right += 44;
                up = new MorphButton(context, PlayerIcon.stroke(PlayerIcon.ARROW_UP, 20));
                up.setRadius(dp(20), false);
                up.setContentDescription(getString(R.string.OEPlayerMoveUp));
                up.setOnClickListener(v -> moveBlock(blockOf(target), -1));
                header.addView(up, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, right, 0));
                right += 44;
            } else {
                up = null;
                down = null;
            }
            if (visibility != null) {
                eye = new MorphButton(context, PlayerIcon.stroke(PlayerIcon.EYE, 20));
                eye.setAltIcon(PlayerIcon.stroke(PlayerIcon.EYE_OFF, 20));
                eye.setRadius(dp(20), false);
                eye.setOnClickListener(v -> {
                    visibility.setConfigBool(!visibility.Bool());
                    refresh();
                    changed();
                });
                header.addView(eye, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, right, 0));
            } else {
                eye = null;
            }
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
            body = new LinearLayout(context);
            body.setOrientation(VERTICAL);
            body.setPadding(0, 0, 0, dp(12));
            body.setVisibility(GONE);
            addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            parts.add(this);
            refresh();
        }

        void setExpanded(boolean value) {
            expanded = value;
            body.setVisibility(value ? VISIBLE : GONE);
            title.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, value ? chevronUp : chevronDown, null);
        }

        void setPosition(int position, int count) {
            this.position = position;
            this.count = count;
            if (up != null) {
                up.setEnabled(position > 0);
                up.setAlpha(position > 0 ? 1f : 0.38f);
                down.setEnabled(position < count - 1);
                down.setAlpha(position < count - 1 ? 1f : 0.38f);
            }
            bindShape();
        }

        void bindShape() {
            int topDp = position == 0 ? 20 : 6;
            int bottomDp = position == count - 1 ? 20 : 6;
            float top = dp(topDp);
            float bottom = dp(bottomDp);
            background.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
            if (colors != null) {
                header.setBackground(Theme.createRadSelectorDrawable(colors.ripple(), topDp, expanded ? 0 : bottomDp));
            }
        }

        @Override
        public void bind(PlayerColors c) {
            colors = c;
            background.setColor(c.surfaceHigh);
            title.setTextColor(c.onSurface);
            chevronDown.setColor(c.onSurfaceVariant);
            chevronUp.setColor(c.onSurfaceVariant);
            for (MorphButton button : new MorphButton[]{eye, up, down}) {
                if (button != null) {
                    button.setColors(c.surfaceContainer, c.onSurfaceVariant, c.surfaceContainer, c.onSurfaceVariant);
                }
            }
            bindShape();
            refresh();
        }

        @Override
        public void refresh() {
            boolean shown = visibility == null || visibility.Bool();
            title.setAlpha(shown ? 1f : 0.5f);
            if (eye != null) {
                eye.setAlt(!shown, isLaidOut());
                eye.setContentDescription(getString(shown ? R.string.Hide : R.string.Show));
            }
            setExpanded(expanded);
            bindShape();
        }
    }
}
