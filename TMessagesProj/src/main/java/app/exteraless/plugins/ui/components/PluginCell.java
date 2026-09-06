package app.exteraless.plugins.ui.components;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import app.exteraless.plugins.Plugin;
import app.exteraless.plugins.PluginsController;
import com.exteragram.messenger.utils.text.LocaleUtils;

import app.exteraless.plugins.ui.PluginIcons;

/**
 * Карточка плагина: иконка, имя, версия с автором, описание и ряд действий.
 *
 * Перенос {@code plugins/ui/components/PluginCell}. До этого плагин занимал
 * обычную строку списка с тумблером: описание не помещалось, иконки не было
 * вовсе, а всё, кроме включения, пряталось в меню по долгому нажатию — то есть
 * было невидимо. exteraGram показывает карточку, где действия лежат прямо под
 * описанием, и это единственное место, где их вообще видно.
 *
 * Компактный режим ({@link PluginsController#isCompactView()}) сжимает карточку
 * до строки с иконкой слева, как у exteraGram.
 */
public class PluginCell extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final View card;
    private final BackupImageView imageView;
    private final LinearLayout headerLayout;
    private final LinearLayout textsLayout;
    private final TextView nameView;
    private final TextView subtitleView;
    private final LinkSpanDrawable.LinksTextView descriptionView;
    private final View divider;
    private final ImageView shareButton;
    private final ImageView pinButton;
    private final ImageView settingsButton;
    private final ImageView permissionsButton;
    private final ImageView deleteButton;
    private final Switch switchView;

    private String pluginId;
    private String pluginIcon;
    private PluginCellDelegate delegate;
    private boolean compact;
    private boolean pluginEnabled;

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsUnregistered);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsUnregistered);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.pluginSettingsRegistered && id != NotificationCenter.pluginSettingsUnregistered) {
            return;
        }
        if (args == null || args.length == 0 || !(args[0] instanceof String)
                || pluginId == null || !pluginId.equals(args[0])) {
            return;
        }
        settingsButton.setVisibility(id == NotificationCenter.pluginSettingsRegistered && pluginEnabled ? VISIBLE : GONE);
    }

    public static final class Model {
        public final Plugin plugin;
        public final String id;
        final String name;
        final String subtitle;
        final String description;
        final String loadError;
        final String icon;
        final boolean enabled;
        final boolean hasSettings;
        final boolean pinned;
        final boolean compact;

        Model(Plugin plugin, boolean pinned, boolean compact) {
            this.plugin = plugin;
            id = plugin.id;
            name = plugin.getDisplayName();
            subtitle = plugin.getSubtitle();
            description = plugin.description;
            loadError = plugin.loadError;
            icon = plugin.icon;
            enabled = plugin.enabled && plugin.loadError == null;
            hasSettings = plugin.hasSettings;
            this.pinned = pinned;
            this.compact = compact;
        }

        boolean sameContent(Model other) {
            return other != null
                    && TextUtils.equals(name, other.name)
                    && TextUtils.equals(subtitle, other.subtitle)
                    && TextUtils.equals(description, other.description)
                    && TextUtils.equals(loadError, other.loadError)
                    && TextUtils.equals(icon, other.icon)
                    && enabled == other.enabled
                    && hasSettings == other.hasSettings
                    && pinned == other.pinned
                    && compact == other.compact;
        }
    }

    public static final class Factory extends UItem.UItemFactory<PluginCell> {
        static {
            setup(new Factory());
        }

        @Override
        public PluginCell createView(Context context, RecyclerListView listView, int currentAccount,
                                     int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new PluginCell(context);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            PluginCell cell = (PluginCell) view;
            cell.setDelegate((PluginCellDelegate) item.object2);
            cell.setModel((Model) item.object);
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            Model a = first.object instanceof Model ? (Model) first.object : null;
            Model b = second.object instanceof Model ? (Model) second.object : null;
            return a != null && b != null && TextUtils.equals(a.id, b.id);
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            Model a = first.object instanceof Model ? (Model) first.object : null;
            Model b = second.object instanceof Model ? (Model) second.object : null;
            return a != null && a.sameContent(b);
        }

        public static UItem asPlugin(Plugin plugin, PluginCellDelegate delegate) {
            PluginsController controller = PluginsController.getInstance();
            return of(plugin, controller.isPluginPinned(plugin.id),
                    controller.isCompactView(), delegate);
        }

        static UItem of(Plugin plugin, boolean pinned, boolean compact, PluginCellDelegate delegate) {
            UItem item = UItem.ofFactory(Factory.class);
            item.object = new Model(plugin, pinned, compact);
            item.object2 = delegate;
            item.transparent = true;
            return item;
        }
    }

    public PluginCell(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        // Фон рисуем сами: секция списка округляет плашку по своей высоте, и
        // высокая карточка превращалась в «таблетку» с полукруглыми боками.
        card = new View(context);
        addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.FILL, 12, 0, 12, 8));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.FILL_HORIZONTAL, 24, 16, 24, 16));

        // Строка шапки: слева иконка с текстами, справа тумблер. Раньше тумблер
        // висел отдельным ребёнком поверх карточки, и от её скруглённого края
        // его отделяло то, что осталось от отступа после вычета вылета карточки
        // — на глаз «тумблер не помещается». Заодно длинная строка автора
        // больше не может затечь под него: у колонки текстов своя ширина.
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        headerLayout = new LinearLayout(context);
        headerLayout.setOrientation(LinearLayout.VERTICAL);
        headerRow.addView(headerLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        imageView = new BackupImageView(context);
        imageView.setVisibility(GONE);
        imageView.setRoundRadius(AndroidUtilities.dp(12));
        headerLayout.addView(imageView, LayoutHelper.createLinear(56, 56, Gravity.LEFT,
                0, 0, 0, 12));

        textsLayout = new LinearLayout(context);
        textsLayout.setOrientation(LinearLayout.VERTICAL);
        headerLayout.addView(textsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textsLayout.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        textsLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        descriptionView = new LinkSpanDrawable.LinksTextView(context);
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        root.addView(descriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        root.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1,
                0, 12, 0, 8));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        shareButton = createButton(context, R.drawable.msg_share, false,
                R.string.PluginsShare,
                v -> callDelegate(Action.SHARE));
        actions.addView(shareButton, LayoutHelper.createLinear(48, 48, Gravity.LEFT, 0, 0, 4, 0));
        pinButton = createButton(context, R.drawable.msg_pin, false,
                R.string.PluginsPinned,
                v -> callDelegate(Action.PIN));
        actions.addView(pinButton, LayoutHelper.createLinear(48, 48, Gravity.LEFT, 0, 0, 4, 0));
        permissionsButton = createButton(context, R.drawable.msg_permissions, false,
                R.string.PluginPermissions,
                v -> callDelegate(Action.PERMISSIONS));
        actions.addView(permissionsButton, LayoutHelper.createLinear(48, 48, Gravity.LEFT, 0, 0, 4, 0));
        settingsButton = createButton(context, R.drawable.msg_settings, false,
                R.string.PluginsMenuOpenSettings,
                v -> callDelegate(Action.SETTINGS));
        settingsButton.setVisibility(GONE);
        actions.addView(settingsButton, LayoutHelper.createLinear(48, 48, Gravity.LEFT, 0, 0, 4, 0));

        // Распорка: удаление уезжает к правому краю карточки, но остаётся в
        // общем ряду — так у него те же отступы, что и у остальных кнопок.
        View spacer = new View(context);
        actions.addView(spacer, LayoutHelper.createLinear(0, 1, 1f));

        // Соседство с остальными кнопками означало бы промах пальцем ценой
        // удалённого плагина, поэтому оно одно у противоположного края.
        deleteButton = createButton(context, R.drawable.msg_delete, true,
                R.string.PluginsMenuDelete,
                v -> callDelegate(Action.DELETE));
        actions.addView(deleteButton, LayoutHelper.createLinear(48, 48, Gravity.RIGHT));
        root.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        switchView = new Switch(context);
        switchView.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked,
                Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        switchView.setFocusable(false);
        switchView.setOnClickListener(v -> callDelegate(Action.TOGGLE));
        // 4dp справа: у Switch трек уже своей вьюхи, и без этого он оказывается
        // ближе к краю карточки, чем текст слева.
        headerRow.addView(switchView, LayoutHelper.createLinear(37, 40, Gravity.TOP,
                12, 0, 4, 0));
        updateCardBackground();
    }

    /** Радиус карточки — общий для приложения (настройка «скругление секций»). */
    private void updateCardBackground() {
        card.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(app.exteraless.appearance.AppearanceConfig.sectionRadius()),
                Theme.getColor(Theme.key_windowBackgroundWhite)));
    }

    private enum Action { TOGGLE, SHARE, PIN, SETTINGS, PERMISSIONS, DELETE }

    private void callDelegate(Action action) {
        if (delegate == null || pluginId == null) {
            return;
        }
        switch (action) {
            case TOGGLE: delegate.togglePlugin(switchView); break;
            case SHARE: delegate.sharePlugin(); break;
            case PIN: delegate.pinPlugin(pinButton); break;
            case SETTINGS: delegate.openPluginSettings(); break;
            case DELETE: delegate.deletePlugin(); break;
            case PERMISSIONS:
                if (delegate instanceof PluginPermissionsDelegate) {
                    ((PluginPermissionsDelegate) delegate).openPluginPermissions();
                }
                break;
        }
    }

    /** Кнопка действия: круглый селектор и «пружинка» на нажатие, как у exteraGram. */
    private ImageView createButton(Context context, int iconRes, boolean red, int descriptionRes,
                                   OnClickListener listener) {
        ImageView button = new ImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(iconRes);
        button.setColorFilter(new PorterDuffColorFilter(Theme.getColor(red
                ? Theme.key_text_RedRegular
                : Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        button.setBackground(Theme.createSelectorDrawable(red
                        ? Theme.multAlpha(Theme.getColor(Theme.key_text_RedRegular), 0.12f)
                        : Theme.getColor(Theme.key_dialogButtonSelector),
                1, AndroidUtilities.dp(20)));
        button.setContentDescription(context.getString(descriptionRes));
        button.setFocusable(true);
        button.setOnClickListener(listener);
        button.setOnTouchListener(PluginCell::animateTouch);
        return button;
    }

    private static boolean animateTouch(View view, MotionEvent event) {
        boolean animate = SharedConfig.animationsEnabled()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || ValueAnimator.areAnimatorsEnabled());
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                view.setPressed(true);
                if (animate) {
                    view.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).start();
                }
                return true;
            case MotionEvent.ACTION_UP:
                view.setPressed(false);
                if (event.getX() >= 0 && event.getX() <= view.getWidth()
                        && event.getY() >= 0 && event.getY() <= view.getHeight()) {
                    view.performClick();
                }
                if (animate) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(350)
                            .setInterpolator(new OvershootInterpolator(1.5f)).start();
                } else {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                view.setPressed(false);
                if (animate) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(350)
                            .setInterpolator(new OvershootInterpolator(1.5f)).start();
                } else {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                }
                return true;
            default:
                return false;
        }
    }

    public void setDelegate(PluginCellDelegate delegate) {
        this.delegate = delegate;
        permissionsButton.setVisibility(
                delegate instanceof PluginPermissionsDelegate ? VISIBLE : GONE);
    }

    public void set(Plugin plugin, PluginCellDelegate delegate) {
        setDelegate(delegate);
        PluginsController controller = PluginsController.getInstance();
        setModel(new Model(plugin, controller.isPluginPinned(plugin.id),
                controller.isCompactView()));
    }

    private void setModel(Model model) {
        if (model == null || model.plugin == null) {
            pluginId = null;
            pluginIcon = null;
            return;
        }
        boolean updateIcon = !TextUtils.equals(pluginId, model.id)
                || !TextUtils.equals(pluginIcon, model.icon);
        pluginId = model.id;
        pluginIcon = model.icon;
        compact = model.compact;
        pluginEnabled = model.enabled;

        if (updateIcon) {
            imageView.setTag(null);
            imageView.setImageDrawable(null);
            imageView.setVisibility(GONE);
            PluginIcons.apply(imageView, model.plugin, this::requestLayout);
        }

        nameView.setText(model.name);
        subtitleView.setText(model.subtitle);

        if (model.loadError != null) {
            // Ошибка вытесняет описание: если плагин не поднялся, всё остальное
            // про него сейчас неважно.
            descriptionView.setText(model.loadError);
            descriptionView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            descriptionView.setTypeface(AndroidUtilities.getTypeface("fonts/rmono.ttf"));
            descriptionView.setVisibility(VISIBLE);
        } else if (!TextUtils.isEmpty(model.description)) {
            descriptionView.setText(LocaleUtils.fullyFormatText(model.description));
            descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            descriptionView.setTypeface(android.graphics.Typeface.DEFAULT);
            descriptionView.setVisibility(VISIBLE);
        } else {
            descriptionView.setVisibility(GONE);
        }

        pinButton.setImageResource(model.pinned
                ? R.drawable.msg_unpin : R.drawable.msg_pin);
        pinButton.setContentDescription(getContext().getString(model.pinned
                ? R.string.PluginsUnpin : R.string.PluginsPinned));
        settingsButton.setVisibility(model.enabled && model.hasSettings ? VISIBLE : GONE);
        switchView.setChecked(model.enabled, false);
        switchView.setContentDescription(model.name);

        updateLayout();
    }

    /**
     * Компактный режим: иконка уезжает влево, тексты — в одну строку.
     * exteraGram: {@code PluginCell.updateLayout}.
     */
    private void updateLayout() {
        headerLayout.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams iconParams = (LinearLayout.LayoutParams) imageView.getLayoutParams();
        iconParams.width = iconParams.height = AndroidUtilities.dp(compact ? 49 : 56);
        iconParams.rightMargin = compact ? AndroidUtilities.dp(16) : 0;
        iconParams.bottomMargin = compact ? 0 : AndroidUtilities.dp(12);
        LinearLayout.LayoutParams textParams = (LinearLayout.LayoutParams) textsLayout.getLayoutParams();
        textParams.gravity = compact ? Gravity.CENTER_VERTICAL : Gravity.LEFT;
        nameView.setSingleLine(compact);
        subtitleView.setSingleLine(compact);
        divider.setVisibility(compact ? GONE : VISIBLE);
        requestLayout();
    }

    public void setChecked(boolean checked) {
        switchView.setChecked(checked, true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
