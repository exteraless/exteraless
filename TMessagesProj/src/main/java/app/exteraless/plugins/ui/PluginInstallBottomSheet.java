package app.exteraless.plugins.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.exteraless.ai.AiController;
import app.exteraless.ai.ui.AiSettingsActivity;
import app.exteraless.player.PlayerColors;
import app.exteraless.plugins.Plugin;
import app.exteraless.plugins.PluginCapabilityScan;
import app.exteraless.plugins.PluginPermissions;
import app.exteraless.plugins.ui.components.PluginAiReview;
import app.exteraless.plugins.ui.components.PluginFileViewer;

/**
 * Лист установки плагина.
 *
 * Раньше здесь был системный AlertDialog со списком галочек: он не показывал ни
 * иконки, ни описания, а на длинном списке разрешений упирался в собственную
 * высоту. exteraGram ({@code plugins/ui/components/InstallPluginBottomSheet}) на
 * этом месте показывает карточку — иконка, имя, версия и автор, описание, — и
 * ставит рядом с кнопкой отдельную галочку «включить после установки».
 *
 * Наше отличие от exteraGram одно и намеренное: в списке под описанием стоят
 * разрешения, которые нашёл статический разбор, и у каждого своя раскрывашка с
 * именами из исходника. exteraGram вместо этого показывает бейдж «источник
 * неизвестен»; бейдж говорит о канале, а не о плагине, и ничего не решает.
 */
public class PluginInstallBottomSheet extends com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet {

    public interface Delegate {
        /**
         * @param granted           отмеченные разрешения
         * @param enableAfterInstall включать ли плагин сразу после установки
         */
        void onInstall(List<String> granted, boolean enableAfterInstall);
    }

    private final List<PluginPermissionCell> cells = new ArrayList<>();
    private boolean enableAfterInstall = true;
    private final Delegate delegate;
    private final Plugin plugin;
    private final PlayerColors colors;
    private ScrollView scroll;

    public PluginInstallBottomSheet(Activity activity, File file,
                                    com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet.PluginInstallParams params,
                                    Plugin plugin,
                                    Map<String, List<String>> capabilities, Delegate delegate) {
        super(activity, false);
        this.params = params;
        this.delegate = delegate;
        this.plugin = plugin;
        this.colors = PlayerColors.fromSeed(Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.isCurrentThemeDark());
        setApplyBottomPadding(false);
        setApplyTopPadding(false);
        fixNavigationBar(colors.surfaceLow);

        final Context context = activity;
        final List<String> permissions = PluginCapabilityScan.ordered(capabilities);
        final boolean obfuscated = PluginCapabilityScan.isObfuscated(capabilities);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), 0, dp(24), dp(BAR_HEIGHT + 8));

        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setCornerRadius(dp(2));
        handleBg.setColor(ColorUtils.setAlphaComponent(colors.onSurfaceVariant, 0x73));
        handle.setBackground(handleBg);
        content.addView(handle, LayoutHelper.createLinear(32, 4, Gravity.CENTER_HORIZONTAL, 0, 14, 0, 0));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 0, 0));

        header.addView(createIcon(context, plugin, colors), LayoutHelper.createLinear(64, 64, Gravity.CENTER_VERTICAL));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(colors.onSurface);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(plugin != null ? plugin.getDisplayName() : file.getName());
        titles.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinkSpanDrawable.LinksTextView subtitle = new LinkSpanDrawable.LinksTextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(colors.onSurfaceVariant);
        subtitle.setLinkTextColor(colors.primary);
        subtitle.setText(com.exteragram.messenger.utils.text.LocaleUtils.formatWithUsernames(buildSubtitle(plugin),
                org.telegram.ui.LaunchActivity.getSafeLastFragment(), this::dismiss));
        titles.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        if (plugin != null && !TextUtils.isEmpty(plugin.description)) {
            LinkSpanDrawable.LinksTextView description = new LinkSpanDrawable.LinksTextView(context);
            description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            description.setLineSpacing(dp(2), 1f);
            description.setTextColor(colors.onSurfaceVariant);
            description.setLinkTextColor(colors.primary);
            description.setText(com.exteragram.messenger.utils.text.LocaleUtils.fullyFormatText(plugin.description));
            content.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));
        }

        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        if (PluginFileViewer.canOpen(file)) {
            TextView source = createChip(context, R.drawable.baseline_code_24, getString(R.string.PluginsViewSource));
            source.setOnClickListener(v -> {
                BaseFragment fragment = LaunchActivity.getLastFragment();
                if (fragment != null) {
                    PluginFileViewer.open(fragment, file, plugin == null ? null : plugin.name);
                }
            });
            chips.addView(source, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
        }
        if (PluginAiReview.canReview(file)) {
            TextView review = createChip(context, R.drawable.input_ai_star, getString(R.string.PluginsAiReview));
            review.setOnClickListener(v -> {
                if (!AiController.canUseAI() && !PluginAiReview.isCached(file)) {
                    BulletinFactory.of(container, resourcesProvider).createSimpleBulletin(
                            R.raw.chats_infotip, getString(R.string.OEAiNotConfigured),
                            getString(R.string.Settings), this::openAiSettings).show();
                    return;
                }
                PluginAiReview.review(context, resourcesProvider, file, plugin, capabilities);
            });
            chips.addView(review, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));
        }
        if (chips.getChildCount() > 0) {
            content.addView(chips, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 16, 0, 0));
        }

        TextView section = new TextView(context);
        section.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        section.setTypeface(AndroidUtilities.bold());
        section.setTextColor(colors.primary);
        section.setText(getString(R.string.PluginPermissions));
        content.addView(section, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 28, 4, 0));

        TextView note = new TextView(context);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setTextColor(colors.onSurfaceVariant);
        note.setText(getString(plugin == null || TextUtils.isEmpty(plugin.id)
                ? R.string.PluginsInstallUnknownConfirm
                : obfuscated
                    ? R.string.PluginsInstallObfuscatedChoice
                    : permissions.isEmpty()
                        ? R.string.PluginsInstallNothingFound
                        : R.string.PluginsInstallScanned));
        content.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 4, 4, 0));

        if (obfuscated) {
            content.addView(createMd3Warning(context, PluginCapabilityScan.obfuscationEvidence(capabilities)),
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        }

        PluginCapabilityScan.DexInfo dex = PluginCapabilityScan.dexInfo(capabilities);
        if (dex != null) {
            content.addView(createDexCard(context, dex),
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        }

        if (!permissions.isEmpty()) {
            final List<String> stored = plugin != null ? PluginPermissions.getStored(plugin.id) : null;
            final List<String> previouslyGranted = stored != null ? stored : new ArrayList<>();
            LinearLayout group = createCard(context);
            group.setPadding(0, dp(6), 0, dp(6));
            for (String permission : permissions) {
                PluginPermissionCell cell = new PluginPermissionCell(context, PluginPermissionCell.TYPE_SWITCH);
                cell.set(permission,
                        PluginPermissionsActivity.shortTitleOf(permission),
                        PluginPermissionsActivity.infoOf(permission),
                        PluginCapabilityScan.evidenceOf(capabilities, permission),
                        false);
                cell.applyColors(colors.onSurface, colors.onSurfaceVariant, colors.onSurfaceVariant,
                        ColorUtils.setAlphaComponent(colors.onSurfaceVariant, 0x1A));
                cell.setChecked(previouslyGranted.contains(permission)
                        || !obfuscated && !PluginPermissions.isDangerous(permission), false);
                cell.setOnToggle(() -> cell.setChecked(!cell.isChecked(), true));
                cell.setBackground(Theme.createSelectorDrawable(ColorUtils.setAlphaComponent(colors.onSurface, 0x14), 2));
                cells.add(cell);
                group.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            content.addView(group, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        }

        content.addView(createEnableAfterInstall(context),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);

        final boolean update = plugin != null && !TextUtils.isEmpty(plugin.id)
                && app.exteraless.plugins.PluginsController.getInstance().getPlugin(plugin.id) != null;
        isUpdate = update;
        TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(colors.onPrimary);
        button.setGravity(Gravity.CENTER);
        button.setText(getString(update ? R.string.PluginsUpdateAction : R.string.PluginsInstallAction));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(28), colors.primary,
                ColorUtils.blendARGB(colors.primary, colors.onPrimary, 0.16f)));
        button.setOnClickListener(v -> onInstallClick());

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(colors.surfaceLow);
        bar.setPadding(0, 0, 0, dp(16));
        bar.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 24, 12, 24, 0));
        bar.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            final int padding = v.getHeight() + dp(8);
            if (content.getPaddingBottom() != padding) {
                content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), padding);
            }
        });
        this.button = button;
        bottomContainer = bar;

        GradientDrawable background = new GradientDrawable();
        background.setColor(colors.surfaceLow);
        background.setCornerRadii(new float[]{dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
        FrameLayout root = new FrameLayout(context);
        root.setBackground(background);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        containerView = root;
        applyButtonState(false);
    }

    private static final int BAR_HEIGHT = 84;

    @Override
    protected void performInstall() {
        dismiss();
        if (delegate != null) {
            delegate.onInstall(checkedPermissions(), enableAfterInstall);
        }
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return scroll == null || !scroll.canScrollVertically(-1);
    }

    private LinearLayout createCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(dp(24), colors.surfaceContainer));
        card.setClipToOutline(true);
        return card;
    }

    private TextView createChip(Context context, int iconRes, CharSequence text) {
        TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setTextColor(colors.onSecondaryContainer);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setSingleLine(true);
        chip.setText(text);
        chip.setPadding(dp(iconRes != 0 ? 12 : 16), 0, dp(16), 0);
        if (iconRes != 0) {
            android.graphics.drawable.Drawable icon = context.getResources().getDrawable(iconRes).mutate();
            icon.setColorFilter(new PorterDuffColorFilter(colors.onSecondaryContainer, PorterDuff.Mode.SRC_IN));
            icon.setBounds(0, 0, dp(18), dp(18));
            chip.setCompoundDrawables(icon, null, null, null);
            chip.setCompoundDrawablePadding(dp(8));
        }
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), colors.secondaryContainer,
                ColorUtils.blendARGB(colors.secondaryContainer, colors.onSecondaryContainer, 0.12f)));
        return chip;
    }

    private View createMd3Warning(Context context, List<String> evidence) {
        final int error = Theme.getColor(Theme.key_text_RedBold);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(Theme.createRoundRectDrawable(dp(20), ColorUtils.blendARGB(colors.surfaceLow, error, 0.14f)));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(error);
        title.setText(getString(R.string.PluginsObfuscated));
        box.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(colors.onSurface);
        info.setText(getString(R.string.PluginsObfuscatedInfo));
        box.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        if (evidence != null && !evidence.isEmpty()) {
            TextView signs = new TextView(context);
            signs.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            signs.setTextColor(colors.onSurfaceVariant);
            signs.setText(LocaleController.formatString(R.string.PluginsObfuscatedEvidence, TextUtils.join(", ", evidence)));
            box.addView(signs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        }
        return box;
    }

    private View createDexCard(Context context, PluginCapabilityScan.DexInfo dex) {
        final int accent = dex.loaded ? Theme.getColor(Theme.key_text_RedBold) : colors.onSurfaceVariant;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(Theme.createRoundRectDrawable(dp(20), dex.loaded
                ? ColorUtils.blendARGB(colors.surfaceLow, accent, 0.10f)
                : colors.surfaceContainer));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(dex.loaded ? accent : colors.onSurface);
        title.setText(getString(R.string.PluginsDexTitle));
        box.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(colors.onSurface);
        info.setText(LocaleController.formatString(dex.loaded ? R.string.PluginsDexLoaded : R.string.PluginsDexUnused,
                AndroidUtilities.formatFileSize(dex.size)));
        box.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        if (!dex.classes.isEmpty()) {
            String names = TextUtils.join(", ", dex.classes);
            if (dex.moreClasses > 0) {
                names += " +" + dex.moreClasses;
            }
            TextView classes = new TextView(context);
            classes.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            classes.setTextColor(colors.onSurfaceVariant);
            classes.setText(LocaleController.formatString(R.string.PluginsDexClasses, names));
            box.addView(classes, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        }
        return box;
    }

    private void openAiSettings() {
        dismiss();
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment != null) {
            fragment.presentFragment(new AiSettingsActivity());
        }
    }

    /**
     * Предупреждение о нечитаемом коде.
     *
     * Стоит отдельным блоком, а не строкой в списке разрешений: разбор выше
     * перечисляет то, что нашлось, а здесь речь о том, что искать бесполезно.
     */
    public static android.view.View createObfuscationWarning(Context context, List<String> evidence) {
        return createWarningBox(context, getString(R.string.PluginsObfuscated),
                getString(R.string.PluginsObfuscatedInfo),
                evidence == null || evidence.isEmpty() ? null
                        : LocaleController.formatString(R.string.PluginsObfuscatedEvidence,
                                TextUtils.join(", ", evidence)));
    }

    public static android.view.View createWarningBox(Context context, CharSequence titleText,
                                                     CharSequence infoText, CharSequence footText) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10),
                AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        box.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10),
                ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_text_RedBold), 30)));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        title.setText(titleText);
        box.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        info.setText(infoText);
        box.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        if (!TextUtils.isEmpty(footText)) {
            TextView signs = new TextView(context);
            signs.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            signs.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
            signs.setText(footText);
            box.addView(signs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }
        return box;
    }

    /**
     * Иконка: стикер из набора плагина, если он его указал, иначе наш значок.
     *
     * Загрузка идёт сетевым запросом за набором стикеров, поэтому картинка
     * появляется позже остального листа — это нормально и лучше, чем держать
     * лист закрытым до её приезда.
     */
    private static android.view.View createIcon(Context context, Plugin plugin, PlayerColors colors) {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackground(Theme.createRoundRectDrawable(dp(20), colors.primaryContainer));
        org.telegram.ui.Components.BackupImageView image =
                new org.telegram.ui.Components.BackupImageView(context);
        ImageView fallback = new ImageView(context);
        fallback.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fallback.setImageResource(R.drawable.msg_plugins);
        fallback.setColorFilter(new PorterDuffColorFilter(colors.onPrimaryContainer, PorterDuff.Mode.SRC_IN));
        frame.addView(fallback, LayoutHelper.createFrame(36, 36, Gravity.CENTER));
        image.setRoundRadius(dp(20));
        frame.addView(image, LayoutHelper.createFrame(64, 64, Gravity.CENTER));
        image.setVisibility(android.view.View.GONE);
        // Наш значок стоит до тех пор, пока не приедет иконка плагина: она
        // может и не приехать, а пустое место вместо неё — хуже заглушки.
        PluginIcons.apply(image, plugin, () -> {
            fallback.setVisibility(android.view.View.GONE);
            frame.setBackground(null);
        });
        return frame;
    }

    private static CharSequence buildSubtitle(Plugin plugin) {
        if (plugin == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(LocaleController.formatString(R.string.PluginsInstallVersion,
                plugin.version != null ? plugin.version : "1.0"));
        if (!TextUtils.isEmpty(plugin.author)) {
            sb.append(" • ").append(plugin.author);
        }
        return sb;
    }

    private android.view.View createEnableAfterInstall(Context context) {
        LinearLayout row = createCard(context);
        row.setClipToOutline(false);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), 0, dp(14));

        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        text.setTextColor(colors.onSurface);
        text.setText(getString(R.string.PluginsEnableAfterInstallation));
        row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        Switch toggle = new Switch(context);
        toggle.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked,
                Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        toggle.setChecked(enableAfterInstall, false);
        row.addView(toggle, LayoutHelper.createLinear(37, 40, Gravity.CENTER_VERTICAL, 12, 0, 21, 0));

        row.setForeground(Theme.createSelectorDrawable(ColorUtils.setAlphaComponent(colors.onSurface, 0x14), 2));
        row.setOnClickListener(v -> {
            enableAfterInstall = !enableAfterInstall;
            toggle.setChecked(enableAfterInstall, true);
        });
        return row;
    }

    private List<String> checkedPermissions() {
        List<String> granted = new ArrayList<>();
        for (PluginPermissionCell cell : cells) {
            if (cell.isChecked() && cell.getPermission() != null) {
                granted.add(cell.getPermission());
            }
        }
        return granted;
    }
}
