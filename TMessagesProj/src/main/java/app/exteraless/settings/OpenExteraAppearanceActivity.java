package app.exteraless.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LaunchActivity;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.appearance.AvatarCornersPreviewCell;
import app.exteraless.appearance.AvatarCornersSeekBar;
import app.exteraless.appearance.ChatListPreviewCell;
import app.exteraless.appearance.FabShapeCell;
import app.exteraless.appearance.FoldersPreviewCell;
import app.exteraless.icons.IconPacksActivity;
import app.exteraless.pillstack.PillStackSettingsActivity;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import xyz.nextalone.nagram.NaConfig;

/**
 * Экран «Оформление» раздела openExtera — визуальный порт AppearancePreferencesActivity
 * из exteraGram. Живые превью (аватарки, список чатов, папки) портированы 1:1 из
 * exteraGram 10.10.1 в пакет {@link app.exteraless.appearance}.
 *
 * Настройки, у которых в NagramX уже есть аналог, привязаны к существующим ConfigItem
 * (NaConfig / NekoConfig). Чисто визуальные настройки, которых в NagramX нет,
 * хранятся в {@link AppearanceConfig} и помечены в отчёте как «только UI».
 */
public class OpenExteraAppearanceActivity extends BaseNekoSettingsActivity {

    private static final int TYPE_AVATAR_CORNERS = 100;
    private static final int TYPE_CHAT_LIST = 101;
    private static final int TYPE_FOLDERS = 102;
    private static final int TYPE_SECTION_SLIDER = 103;
    /** Сворачиваемая группа со счётчиком и шевроном. */
    private static final int TYPE_EXPANDABLE_SWITCH = 104;
    /** Круглая галочка внутри группы. */
    private static final int TYPE_ROUND_CHECK = 105;
    /** Две карточки-превью формы плавающей кнопки. */
    private static final int TYPE_FAB_SHAPE = 106;

    // Appearance
    private int appearanceHeaderRow;
    private int fabShapeRow;
    private int useSystemFontsRow;
    private int useSystemEmojiRow;
    private int gooeyAvatarRow;
    private int customThemesRow;
    private int appearanceDividerRow;

    // Sections (UI only)
    private int sectionsHeaderRow;
    private int sectionRadiusRow;
    private int separateHeadersRow;
    private int dividerStyleRow;
    private int sectionsDividerRow;

    // Blur
    private int blurHeaderRow;
    private int glassOutlineRow;
    private int glassMessageMenuRow;
    private int forceBlurRow;
    private int disableAvatarBlurRow;
    private int blurDividerRow;

    // Avatar corners
    private int avatarCornersPreviewRow;
    private int singleCornerRadiusRow;
    private int avatarsDividerRow;

    // Chat list
    private int chatListHeaderRow;
    private int chatListPreviewRow;
    private int forceSnowRow;
    private int hideActionBarStatusRow;
    private int centerTitleRow;
    // Material Design 3: сворачиваемая группа и пять вложенных стилей.
    private int md3GroupRow;
    private int md3LoadingRow;
    private int md3SliderRow;
    private int md3SwitchRow;
    private int md3ChatHeaderRow;
    private int md3NavBarRow;
    private boolean md3Expanded;
    private int iosGroupRow;
    private int iosNavBarRow;
    private int iosFolderTapRow;
    private int iosBackCounterRow;
    private boolean iosExpanded;
    // Скрытие апстримных AI-функций: своя сворачиваемая группа.
    private int hideAiGroupRow;
    private int hideAiEditorRow;
    private int hideAiSummaryRow;
    private int hideAiIvRow;
    private boolean hideAiExpanded;
    private int hideStoriesRow;
    private int hideFloatingButtonRow;
    private int hideSearchBarRow;
    private int senderMiniAvatarsRow;
    private int titleTextRow;
    private int chatListDividerRow;

    // Folders
    private int foldersHeaderRow;
    private int foldersPreviewRow;
    private int tabTitleStyleRow;
    private int tabCounterRow;
    private int hideAllChatsRow;
    private int foldersDividerRow;

    // Links
    private int appNavigationRow;
    private int iconPacksRow;
    private int pillStackRow;
    private int linksDividerRow;

    private AvatarCornersPreviewCell avatarCornersPreviewCell;
    private ChatListPreviewCell chatListPreviewCell;
    private FoldersPreviewCell foldersPreviewCell;
    private FabShapeCell fabShapeCell;

    /**
     * Отложенный rebuild после слайдера радиуса секций. exteraGram
     * (handleSectionRadiusChange :335-343) зовёт rebuildFragments на каждое изменение,
     * но у него слайдер — Material-виджет с редкими колбэками; наш SeekBarView отдаёт
     * значение на каждый dp, и пересборка всех фрагментов на каждом шаге даёт рывки.
     */
    private final Runnable sectionRadiusRebuild = this::rebuildAll;

    public OpenExteraAppearanceActivity() {
        super();
        AppearanceConfig.init();
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        avatarCornersPreviewRow = addRow("avatarCorners");
        singleCornerRadiusRow = addRow("singleCornerRadius");
        avatarsDividerRow = addRow();

        chatListHeaderRow = addRow("chatListHeader");
        chatListPreviewRow = addRow("chatListPreview");
        forceSnowRow = addRow("forceSnow");
        // Строка есть только у премиума — так же гейтит её exteraGram
        // (AppearancePreferencesActivity.fillItems :440-442).
        hideActionBarStatusRow = getUserConfig().isPremium() ? addRow("hideActionBarStatus") : -1;
        centerTitleRow = addRow("centerTitle");
        hideStoriesRow = addRow("hideStories");
        hideFloatingButtonRow = addRow("hideFloatingButton");
        hideSearchBarRow = addRow("hideSearchBar");
        senderMiniAvatarsRow = addRow("senderMiniAvatars");
        titleTextRow = addRow("titleText");
        chatListDividerRow = addRow();

        foldersHeaderRow = addRow("foldersHeader");
        foldersPreviewRow = addRow("foldersPreview");
        tabTitleStyleRow = addRow("tabTitleStyle");
        tabCounterRow = addRow("tabCounter");
        hideAllChatsRow = addRow("hideAllChats");
        foldersDividerRow = addRow();

        // Порядок как в 12.9.0: строки-переходы идут сразу после «Chat Folders»,
        // до секции общего вида.
        appNavigationRow = addRow("appNavigation");
        iconPacksRow = addRow("iconPacks");
        pillStackRow = addRow("pillStack");
        linksDividerRow = addRow();

        appearanceHeaderRow = addRow("appearanceHeader");
        fabShapeRow = addRow("fabShape");
        useSystemFontsRow = addRow("useSystemFonts");
        useSystemEmojiRow = addRow("useSystemEmoji");
        // Material Design 3 — сворачиваемая группа, как у exteraGram
        // (AppearancePreferencesActivity: asExteraExpandableSwitch + пять asRoundCheckbox).
        // Прежние отдельные селекторы «стиль переключателей» и «стиль слайдеров»
        // стали двумя галочками внутри неё.
        md3GroupRow = addRow("md3Styles");
        if (md3Expanded) {
            md3LoadingRow = addRow("md3Loading");
            md3SliderRow = addRow("md3Slider");
            md3SwitchRow = addRow("md3Switch");
            md3ChatHeaderRow = addRow("md3ChatHeader");
            md3NavBarRow = addRow("md3NavBar");
        } else {
            md3LoadingRow = md3SliderRow = md3SwitchRow = md3ChatHeaderRow = md3NavBarRow = -1;
        }
        iosGroupRow = addRow("iosStyles");
        if (iosExpanded) {
            iosNavBarRow = addRow("iosNavBar");
            iosBackCounterRow = addRow("iosBackCounter");
            iosFolderTapRow = addRow("iosFolderTap");
        } else {
            iosNavBarRow = iosBackCounterRow = iosFolderTapRow = -1;
        }
        hideAiGroupRow = addRow("hideAi");
        if (hideAiExpanded) {
            hideAiEditorRow = addRow("hideAiEditor");
            hideAiSummaryRow = addRow("hideAiSummary");
            hideAiIvRow = addRow("hideAiIv");
        } else {
            hideAiEditorRow = hideAiSummaryRow = hideAiIvRow = -1;
        }
        gooeyAvatarRow = addRow("gooeyAvatar");
        customThemesRow = addRow("customThemes");
        appearanceDividerRow = addRow();

        sectionsHeaderRow = addRow("sectionsHeader");
        sectionRadiusRow = addRow("sectionRadius");
        separateHeadersRow = addRow("separateHeaders");
        dividerStyleRow = addRow("dividerStyle");
        sectionsDividerRow = addRow();

        blurHeaderRow = addRow("blurHeader");
        glassOutlineRow = addRow("glassOutline");
        glassMessageMenuRow = addRow("glassMessageMenu");
        forceBlurRow = addRow("forceBlur");
        disableAvatarBlurRow = addRow("disableAvatarBlur");
        blurDividerRow = addRow();

    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.OEAppearanceTitle);
    }

    @Override
    public int getSearchGuid() {
        return 21000;
    }

    @Override
    public int getSearchIcon() {
        return R.drawable.msg_theme;
    }

    @Override
    public String getSearchPrefix() {
        return "OEAppearance";
    }

    @Override
    protected String getKey() {
        return "exteraless_appearance";
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    /**
     * Пересобрать список строк. Нужен там, где меняется их состав: базовый класс
     * зовёт updateRows() только в onFragmentCreate, поэтому одного
     * notifyDataSetChanged недостаточно.
     */
    private void rebuildRowsAndNotify() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void rebuildAll() {
        if (parentLayout != null) {
            parentLayout.rebuildAllFragmentViews(false, false);
        }
    }

    /**
     * Пересобрать чужие экраны и обновить этот.
     *
     * rebuildAllFragmentViews(false, ...) намеренно пропускает последний фрагмент
     * стека — то есть ровно тот, который открыт. Свои строки поэтому обновляем
     * сами: галочки внутри группы и счётчик «N/5» ставятся при привязке, а стиль
     * переключателей и слайдеров читается при отрисовке.
     */
    private void rebuildAllAndSelf(View clicked, boolean checked) {
        if (clicked instanceof org.telegram.ui.Cells.CheckBoxCell) {
            ((org.telegram.ui.Cells.CheckBoxCell) clicked).setChecked(checked, true);
        }
        if (listAdapter != null && md3GroupRow >= 0) {
            listAdapter.notifyItemChanged(md3GroupRow);
        }
        if (listAdapter != null && iosGroupRow >= 0) {
            listAdapter.notifyItemChanged(iosGroupRow);
        }
        if (listView != null) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                listView.getChildAt(i).invalidate();
            }
        }
        rebuildAll();
    }

    private void showRestartHint() {
        if (getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(this)
                .createSimpleBulletin(R.raw.info, getString(R.string.OEAppearanceNeedRestart),
                        getString(R.string.OEAppearanceRestartNow),
                        () -> {
                            Activity activity = getParentActivity();
                            if (activity != null) {
                                AppRestartHelper.triggerRebirth(activity,
                                        new Intent(activity, LaunchActivity.class));
                            }
                        })
                .show();
    }

    private void showSelector(int position, String title, CharSequence[] items, ConfigItem item, Runnable after) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setItems(items, (dialog, which) -> {
            item.setConfigInt(which);
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(position);
            }
            if (after != null) {
                after.run();
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private CharSequence[] titleTextOptions() {
        return new CharSequence[]{
                getString(R.string.OEAppearanceTitleTextApp),
                getString(R.string.OEAppearanceTitleTextUsername),
                getString(R.string.OEAppearanceTitleTextName),
                getString(R.string.FilterChats)
        };
    }

    /**
     * Порядок пунктов «Folder Title» — «Names with Icons», «Names only», «Icons only»;
     * у NekoConfig.tabsTitleType значения 0 TEXT, 1 ICON, 2 MIX.
     * Массив переводит индекс диалога в значение конфига.
     */
    private static final int[] TAB_TITLE_ORDER = {2, 0, 1};

    private CharSequence[] tabTitleOptions() {
        return new CharSequence[]{
                getString(R.string.OEAppearanceTabTitleStyleTextWithIcons),
                getString(R.string.OEAppearanceTabTitleStyleTextOnly),
                getString(R.string.OEAppearanceTabTitleStyleIconsOnly)
        };
    }

    /** Значение конфига -> индекс в {@link #tabTitleOptions()}. */
    private static int tabTitleIndex(int configValue) {
        for (int i = 0; i < TAB_TITLE_ORDER.length; i++) {
            if (TAB_TITLE_ORDER[i] == configValue) {
                return i;
            }
        }
        return 0;
    }

    private void onDividerStyleChanged() {
        // 0 — скрыт, 1 — линия, 2 — сегменты. Скрытый привязываем к реальному NaConfig.hideDividers,
        // чтобы не разъезжался экран NekoGeneralSettingsActivity.
        NaConfig.INSTANCE.getHideDividers().setConfigBool(AppearanceConfig.dividerStyle.Int() == 0);
        // Theme.getColor читает закешированное значение — сбросить кэш обязательно.
        AppearanceConfig.invalidateDividerStyle();
        // «Сегменты» рисуются раздельными карточками, и заголовок обязан быть своей карточкой,
        // иначе секция склеивается — настройка дожимается принудительно.
        if (AppearanceConfig.dividerStyle.Int() == AppearanceConfig.DIVIDER_SEGMENTS
                && !AppearanceConfig.separateHeaders.Bool()) {
            AppearanceConfig.separateHeaders.setConfigBool(true);
        }
        if (listAdapter != null) {
            listAdapter.notifyItemChanged(separateHeadersRow);
        }
        // Цвет разделителя лежит в общей теме,
        // без applyCommonTheme новые значения не подхватят уже созданные ячейки.
        Theme.applyCommonTheme();
        if (listView != null) {
            listView.invalidate();
            listView.invalidateItemDecorations();
        }
        invalidatePreviews();
        rebuildAll();
    }

    /**
     * Новый радиус надо занести в декоратор
     * списка и перерисовать его, иначе на текущем экране ничего не меняется.
     */
    private void onSectionRadiusChanged(int value) {
        AppearanceConfig.sectionRadius.setConfigInt(value);
        if (listView != null) {
            listView.setSections(AndroidUtilities.dp(12), AndroidUtilities.dp(value), true);
            listView.invalidate();
            listView.invalidateItemDecorations();
        }
        AndroidUtilities.cancelRunOnUIThread(sectionRadiusRebuild);
        AndroidUtilities.runOnUIThread(sectionRadiusRebuild, 350);
    }

    /** Все живые превью экрана — их надо дёргать на смене темы и стиля разделителя. */
    private void invalidatePreviews() {
        if (avatarCornersPreviewCell != null) avatarCornersPreviewCell.invalidate();
        if (chatListPreviewCell != null) chatListPreviewCell.invalidate();
        if (foldersPreviewCell != null) foldersPreviewCell.invalidate();
        if (fabShapeCell != null) fabShapeCell.invalidate();
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == iconPacksRow) {
            presentFragment(new IconPacksActivity());
            return;
        } else if (position == pillStackRow) {
            presentFragment(new PillStackSettingsActivity());
            return;
        } else if (position == appNavigationRow) {
            presentFragment(new OpenExteraAppNavigationActivity());
            return;
        } else if (position == md3GroupRow) {
            md3Expanded = !md3Expanded;
            rebuildRowsAndNotify();
            return;
        } else if (position == md3LoadingRow) {
            AppearanceConfig.newLoadingStyle.setConfigBool(!AppearanceConfig.newLoadingStyle.Bool());
            rebuildAllAndSelf(view, AppearanceConfig.newLoadingStyle.Bool());
            return;
        } else if (position == md3SliderRow) {
            // Стиль слайдера читается в SeekBarView.getEffectiveSliderStyle() на каждой отрисовке,
            // стиль переключателя — в Switch на каждой; перезапуск не нужен, хватает пересборки
            // вьюх — так же делает NekoGeneralSettingsActivity.
            NaConfig.INSTANCE.getSliderStyle().setConfigInt(
                    isMd3(NaConfig.INSTANCE.getSliderStyle().Int()) ? 0 : STYLE_MD3);
            if (avatarCornersPreviewCell != null) {
                avatarCornersPreviewCell.invalidate();
            }
            rebuildAllAndSelf(view, isMd3(NaConfig.INSTANCE.getSliderStyle().Int()));
            return;
        } else if (position == md3SwitchRow) {
            NaConfig.INSTANCE.getSwitchStyle().setConfigInt(
                    isMd3(NaConfig.INSTANCE.getSwitchStyle().Int()) ? 0 : STYLE_MD3);
            rebuildAllAndSelf(view, isMd3(NaConfig.INSTANCE.getSwitchStyle().Int()));
            return;
        } else if (position == md3ChatHeaderRow) {
            AppearanceConfig.newChatHeaderStyle.setConfigBool(!AppearanceConfig.newChatHeaderStyle.Bool());
            rebuildAllAndSelf(view, AppearanceConfig.newChatHeaderStyle.Bool());
            return;
        } else if (position == md3NavBarRow) {
            boolean enable = !AppearanceConfig.newNavigationBarStyle.Bool();
            AppearanceConfig.newNavigationBarStyle.setConfigBool(enable);
            if (enable) {
                AppearanceConfig.iosNavigationBarStyle.setConfigBool(false);
            }
            rebuildAllAndSelf(view, enable);
            return;
        } else if (position == iosGroupRow) {
            iosExpanded = !iosExpanded;
            rebuildRowsAndNotify();
            return;
        } else if (position == iosNavBarRow) {
            boolean enable = !AppearanceConfig.iosNavigationBarStyle.Bool();
            AppearanceConfig.iosNavigationBarStyle.setConfigBool(enable);
            if (enable) {
                AppearanceConfig.newNavigationBarStyle.setConfigBool(false);
            }
            rebuildAllAndSelf(view, enable);
            return;
        } else if (position == iosBackCounterRow) {
            AppearanceConfig.iosBackCounter.setConfigBool(!AppearanceConfig.iosBackCounter.Bool());
            rebuildAllAndSelf(view, AppearanceConfig.iosBackCounter.Bool());
            return;
        } else if (position == iosFolderTapRow) {
            AppearanceConfig.iosFirstFolderOnTabTap.setConfigBool(!AppearanceConfig.iosFirstFolderOnTabTap.Bool());
            rebuildAllAndSelf(view, AppearanceConfig.iosFirstFolderOnTabTap.Bool());
            return;
        } else if (position == hideAiGroupRow) {
            hideAiExpanded = !hideAiExpanded;
            rebuildRowsAndNotify();
            return;
        } else if (position == hideAiEditorRow) {
            toggleHideAi(view, AppearanceConfig.hideAiEditor);
            return;
        } else if (position == hideAiSummaryRow) {
            toggleHideAi(view, AppearanceConfig.hideMessageSummary);
            return;
        } else if (position == hideAiIvRow) {
            toggleHideAi(view, AppearanceConfig.hideIvSummary);
            return;
        } else if (position == dividerStyleRow) {
            showSelector(position, getString(R.string.OEAppearanceDividerStyle), new CharSequence[]{
                    getString(R.string.OEAppearanceDividerHidden),
                    getString(R.string.OEAppearanceDividerLine),
                    getString(R.string.OEAppearanceDividerSegments)
            }, AppearanceConfig.dividerStyle, this::onDividerStyleChanged);
            return;
        } else if (position == glassOutlineRow) {
            showSelector(position, getString(R.string.OEAppearanceGlassOutline), new CharSequence[]{
                    getString(R.string.OEAppearanceGlassOutlineGlare),
                    getString(R.string.OEAppearanceGlassOutlineSolid),
                    getString(R.string.OEAppearanceGlassOutlineHidden)
            }, AppearanceConfig.glassOutlineStyle, null);
            return;
        } else if (position == tabTitleStyleRow) {
            if (getParentActivity() == null) {
                return;
            }
            // Порядок пунктов диалога свой, значения NekoConfig.tabsTitleType тоже
            // (0 TEXT, 1 ICON, 2 MIX),
            // поэтому индекс диалога отображается через TAB_TITLE_ORDER.
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(getString(R.string.OEAppearanceTabTitleStyle));
            builder.setItems(tabTitleOptions(), (dialog, which) -> {
                NekoConfig.tabsTitleType.setConfigInt(TAB_TITLE_ORDER[clamp(which, TAB_TITLE_ORDER.length)]);
                if (listAdapter != null) {
                    listAdapter.notifyItemChanged(position);
                }
                if (foldersPreviewCell != null) {
                    foldersPreviewCell.updateTabTitle(true);
                    foldersPreviewCell.updateTabIcons(true);
                }
                getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
            });
            builder.setNegativeButton(getString(R.string.Cancel), null);
            showDialog(builder.create());
            return;
        } else if (position == tabCounterRow) {
            showSelector(position, getString(R.string.OEAppearanceTabCounter), new CharSequence[]{
                    getString(R.string.OEAppearanceTabCounterAll),
                    getString(R.string.OEAppearanceTabCounterUnmuted),
                    getString(R.string.OEAppearanceTabCounterOff)
            }, NaConfig.INSTANCE.getIgnoreUnreadCount(), () -> {
                if (foldersPreviewCell != null) {
                    foldersPreviewCell.updateTabCounter(true);
                }
                showRestartHint();
            });
            return;
        } else if (position == centerTitleRow) {
            // Обычный переключатель, а не селектор из четырёх пунктов:
            // центровка либо есть, либо нет.
            AppearanceConfig.centerTitle.setConfigBool(!AppearanceConfig.INSTANCE.centerTitle());
            if (view instanceof org.telegram.ui.Cells.TextCheckCell) {
                ((org.telegram.ui.Cells.TextCheckCell) view)
                        .setChecked(AppearanceConfig.INSTANCE.centerTitle());
            }
            if (chatListPreviewCell != null) {
                chatListPreviewCell.updateCentered(true);
            }
            rebuildAll();
            return;
        } else if (position == titleTextRow) {
            showSelector(position, getString(R.string.OEAppearanceTitleText), titleTextOptions(),
                    AppearanceConfig.titleText, () -> {
                if (chatListPreviewCell != null) {
                    chatListPreviewCell.updateTitle(true);
                    // Эмодзи-статус стоит вплотную к заголовку, и его позиция зависит от длины
                    // текста.
                    chatListPreviewCell.updateStatus(true);
                }
                getNotificationCenter().postNotificationName(
                        NotificationCenter.currentUserPremiumStatusChanged);
                // Сам заголовок списка чатов ставится один раз в DialogsActivity.createView
                // (:3645), по уведомлению он не переустанавливается — нужна пересборка вьюх.
                rebuildAll();
            });
            return;
        } else if (position == hideActionBarStatusRow) {
            boolean hidden = AppearanceConfig.hideActionBarStatus.toggleConfigBool();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(hidden);
            }
            if (chatListPreviewCell != null) {
                chatListPreviewCell.updateStatus(true);
            }
            rebuildAll();
            return;
        } else if (position == forceSnowRow) {
            boolean enabled = NekoConfig.actionBarDecoration.Int() != 1;
            NekoConfig.actionBarDecoration.setConfigInt(enabled ? 1 : 0);
            NaConfig.INSTANCE.getChatDecoration().setConfigInt(enabled ? 1 : 0);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            rebuildAll();
            return;
        } else if (position == forceBlurRow) {
            SharedConfig.toggleChatBlur();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(SharedConfig.chatBlurEnabled());
            }
            rebuildAll();
            return;
        } else if (position == glassMessageMenuRow) {
            boolean enabled = AppearanceConfig.glassMessageMenu.toggleConfigBool();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            // Стеклянное меню рисуется поверх
            // блюра, и без него настройка не даёт ничего видимого, поэтому предлагаем включить.
            if (enabled && !SharedConfig.chatBlurEnabled() && getParentActivity() != null) {
                BulletinFactory.of(this)
                        .createSimpleBulletin(R.raw.info,
                                getString(R.string.OEAppearanceGlassMessageMenuBlurOff),
                                getString(R.string.Enable),
                                SharedConfig::toggleChatBlur)
                        .show();
            }
            return;
        } else if (position == separateHeadersRow
                && AppearanceConfig.dividerStyle.Int() == AppearanceConfig.DIVIDER_SEGMENTS) {
            // При «Сегментах» строка нарисована выключенной,
            // клик по ней ничего не меняет.
            return;
        }

        ConfigItem item = null;
        boolean rebuild = false;
        boolean restart = false;
        boolean clearTypefaces = false;

        if (position == useSystemFontsRow) {
            item = NekoConfig.typeface;
            restart = true;
            clearTypefaces = true;
        } else if (position == useSystemEmojiRow) {
            item = NekoConfig.useSystemEmoji;
            rebuild = true;
        } else if (position == gooeyAvatarRow) {
            item = AppearanceConfig.gooeyAvatarAnimation;
        } else if (position == customThemesRow) {
            item = AppearanceConfig.customThemes;
        } else if (position == separateHeadersRow) {
            item = AppearanceConfig.separateHeaders;
        } else if (position == disableAvatarBlurRow) {
            item = NaConfig.INSTANCE.getDisableAvatarBlur();
            rebuild = true;
        } else if (position == singleCornerRadiusRow) {
            item = AppearanceConfig.singleCornerRadius;
            rebuild = true;
        } else if (position == hideStoriesRow) {
            item = NaConfig.INSTANCE.getHideStoriesFromHeader();
            rebuild = true;
        } else if (position == hideFloatingButtonRow) {
            item = NaConfig.INSTANCE.getDisableDialogsFloatingButton();
            rebuild = true;
        } else if (position == hideSearchBarRow) {
            item = NaConfig.INSTANCE.getHideDialogsSearchField();
            rebuild = true;
        } else if (position == senderMiniAvatarsRow) {
            item = AppearanceConfig.senderMiniAvatars;
        } else if (position == hideAllChatsRow) {
            item = NekoConfig.hideAllTab;
            rebuild = true;
        }

        if (item == null) {
            return;
        }

        boolean value = item.toggleConfigBool();
        if (clearTypefaces) {
            AndroidUtilities.clearTypefaceCache();
        }
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
        if (position == hideAllChatsRow) {
            if (foldersPreviewCell != null) {
                foldersPreviewCell.updateAllChatsTabName(true);
            }
            // Вкладки пересобираются по уведомлению,
            // перезапуск не нужен (плюс rebuild ниже пересоздаёт сам список чатов).
            getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
            getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
        }
        if (rebuild) {
            rebuildAll();
        }
        if (restart) {
            showRestartHint();
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_EXPANDABLE_SWITCH:
                    view = new org.telegram.ui.Cells.TextCheckCell2(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_ROUND_CHECK: {
                    // Тип 4 — круглая галочка с отступом под вложенный пункт,
                    // ровно как у exteraGram в UniversalAdapter (view type 35).
                    org.telegram.ui.Cells.CheckBoxCell checkBoxCell =
                            new org.telegram.ui.Cells.CheckBoxCell(mContext, 4, 21, resourcesProvider);
                    checkBoxCell.getCheckBoxRound().setColor(Theme.key_switch2TrackChecked,
                            Theme.key_radioBackground, Theme.key_checkboxCheck);
                    checkBoxCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = checkBoxCell;
                    break;
                }
                case TYPE_AVATAR_CORNERS:
                    avatarCornersPreviewCell = new AvatarCornersPreviewCell(mContext,
                            OpenExteraAppearanceActivity.this::rebuildAll);
                    avatarCornersPreviewCell.setNeedDivider(true);
                    view = avatarCornersPreviewCell;
                    break;
                case TYPE_CHAT_LIST:
                    chatListPreviewCell = new ChatListPreviewCell(mContext);
                    view = chatListPreviewCell;
                    break;
                case TYPE_FOLDERS:
                    foldersPreviewCell = new FoldersPreviewCell(mContext, resourcesProvider);
                    view = foldersPreviewCell;
                    break;
                case TYPE_FAB_SHAPE:
                    fabShapeCell = new FabShapeCell(mContext,
                            OpenExteraAppearanceActivity.this::rebuildAll);
                    fabShapeCell.setNeedDivider(true);
                    view = fabShapeCell;
                    break;
                case TYPE_DETAIL_SETTINGS: {
                    // Базовый класс делает такую же ячейку, но многострочной; exteraGram
                    // (asButtonWithSubtext(..., 64, 60) :456-458) держит ровно 64 dp.
                    TextDetailSettingsCell detailCell = new TextDetailSettingsCell(mContext);
                    detailCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    detailCell.setMultilineDetail(false);
                    view = detailCell;
                    break;
                }
                case TYPE_SECTION_SLIDER:
                    AvatarCornersSeekBar slider = new AvatarCornersSeekBar(mContext,
                            OpenExteraAppearanceActivity.this::onSectionRadiusChanged,
                            0, AppearanceConfig.AVATAR_CORNERS_MAX,
                            getString(R.string.OEAppearanceSectionRadius),
                            getString(R.string.OEAppearanceSectionRadiusOff),
                            getString(R.string.OEAppearanceSectionRadiusMax));
                    slider.setValueSuffix("dp");
                    slider.setValue(AppearanceConfig.sectionRadius.Int());
                    slider.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = slider;
                    break;
                default:
                    return super.onCreateViewHolder(parent, viewType);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == appearanceHeaderRow) {
                        cell.setText(getString(R.string.OEAppearanceGeneral));
                    } else if (position == sectionsHeaderRow) {
                        cell.setText(getString(R.string.OEAppearanceSections));
                    } else if (position == blurHeaderRow) {
                        cell.setText(getString(R.string.OEAppearanceBlur));
                    } else if (position == chatListHeaderRow) {
                        cell.setText(getString(R.string.OEAppearanceChatList));
                    } else if (position == foldersHeaderRow) {
                        cell.setText(getString(R.string.OEAppearanceFolders));
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    // Ячейки переиспользуются, поэтому «включённость» надо возвращать явно:
                    // иначе строка, побывавшая заблокированной, останется полупрозрачной.
                    cell.setEnabled(position != separateHeadersRow
                            || AppearanceConfig.dividerStyle.Int() != AppearanceConfig.DIVIDER_SEGMENTS, null);
                    if (position == centerTitleRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceCenterTitle),
                                AppearanceConfig.INSTANCE.centerTitle(), true);
                    } else if (position == useSystemFontsRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceUseSystemFonts), NekoConfig.typeface.Bool(), true);
                    } else if (position == useSystemEmojiRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceUseSystemEmoji), NekoConfig.useSystemEmoji.Bool(), true);
                    } else if (position == gooeyAvatarRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceGooeyAvatar), AppearanceConfig.gooeyAvatarAnimation.Bool(), true);
                    } else if (position == customThemesRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceCustomThemes), AppearanceConfig.customThemes.Bool(), false);
                    } else if (position == separateHeadersRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceSeparateHeaders), AppearanceConfig.separateHeaders.Bool(), true);
                    } else if (position == glassMessageMenuRow) {
                        cell.setTextAndValueAndCheck(getString(R.string.OEAppearanceGlassMessageMenu), getString(R.string.OEAppearanceGlassMessageMenuInfo), AppearanceConfig.glassMessageMenu.Bool(), true, true);
                    } else if (position == forceBlurRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceForceBlur), SharedConfig.chatBlurEnabled(), true);
                    } else if (position == disableAvatarBlurRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceDisableAvatarBlur), NaConfig.INSTANCE.getDisableAvatarBlur().Bool(), false);
                    } else if (position == singleCornerRadiusRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceSingleCornerRadius), AppearanceConfig.singleCornerRadius.Bool(), false);
                    } else if (position == forceSnowRow) {
                        cell.setTextAndValueAndCheck(getString(R.string.OEAppearanceForceSnow), getString(R.string.OEAppearanceForceSnowInfo), NekoConfig.actionBarDecoration.Int() == 1, true, true);
                    } else if (position == hideActionBarStatusRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceHideActionBarStatus), AppearanceConfig.hideActionBarStatus.Bool(), true);
                    } else if (position == hideStoriesRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceHideStories), NaConfig.INSTANCE.getHideStoriesFromHeader().Bool(), true);
                    } else if (position == hideFloatingButtonRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceHideFloatingButton), NaConfig.INSTANCE.getDisableDialogsFloatingButton().Bool(), true);
                    } else if (position == hideSearchBarRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceHideSearchBar), NaConfig.INSTANCE.getHideDialogsSearchField().Bool(), true);
                    } else if (position == senderMiniAvatarsRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceSenderMiniAvatars), AppearanceConfig.senderMiniAvatars.Bool(), false);
                    } else if (position == hideAllChatsRow) {
                        cell.setTextAndCheck(LocaleController.formatString(R.string.OEAppearanceHideAllChats, getString(R.string.FilterAllChats)), NekoConfig.hideAllTab.Bool(), false);
                    }
                    break;
                }
                case TYPE_EXPANDABLE_SWITCH: {
                    org.telegram.ui.Cells.TextCheckCell2 cell =
                            (org.telegram.ui.Cells.TextCheckCell2) holder.itemView;
                    // Иначе выключенная группа горит красным: Switch по умолчанию
                    // идёт в «разрешительных» цветах экрана прав участника.
                    cell.useStandardSwitchColors();
                    if (position == hideAiGroupRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceHideAi),
                                hideAiSelectedCount() > 0, true);
                        cell.setCollapseArrow(hideAiSelectedCount() + "/" + HIDE_AI_COUNT, !hideAiExpanded,
                                OpenExteraAppearanceActivity.this::toggleAllHideAiFromCell);
                        break;
                    }
                    if (position == iosGroupRow) {
                        cell.setTextAndCheck(getString(R.string.OEAppearanceIosDesign),
                                iosSelectedCount() > 0, true);
                        cell.setCollapseArrow(iosSelectedCount() + "/" + IOS_STYLE_COUNT, !iosExpanded,
                                OpenExteraAppearanceActivity.this::toggleAllIosStyles);
                        break;
                    }
                    cell.setTextAndCheck(getString(R.string.OEAppearanceMaterialDesign3),
                            md3SelectedCount() > 0, true);
                    // Правая зона (76 dp за разделителем) — сам переключатель, как у exteraGram:
                    // туда уходит клик по мастер-тумблеру. Тело строки сворачивает группу.
                    cell.setCollapseArrow(md3SelectedCount() + "/" + MD3_STYLE_COUNT, !md3Expanded,
                            OpenExteraAppearanceActivity.this::toggleAllMd3StylesFromCell);
                    break;
                }
                case TYPE_ROUND_CHECK: {
                    org.telegram.ui.Cells.CheckBoxCell cell =
                            (org.telegram.ui.Cells.CheckBoxCell) holder.itemView;
                    if (position == md3LoadingRow) {
                        cell.setText(getString(R.string.OEAppearanceNewLoadingStyle), "",
                                AppearanceConfig.newLoadingStyle.Bool(), true, true);
                    } else if (position == md3SliderRow) {
                        cell.setText(getString(R.string.OEAppearanceSliderStyle), "",
                                isMd3(NaConfig.INSTANCE.getSliderStyle().Int()), true, true);
                    } else if (position == md3SwitchRow) {
                        cell.setText(getString(R.string.OEAppearanceSwitchStyle), "",
                                isMd3(NaConfig.INSTANCE.getSwitchStyle().Int()), true, true);
                    } else if (position == md3ChatHeaderRow) {
                        cell.setText(getString(R.string.OEAppearanceNewChatHeaderStyle), "",
                                AppearanceConfig.newChatHeaderStyle.Bool(), true, true);
                    } else if (position == md3NavBarRow) {
                        cell.setText(getString(R.string.OEAppearanceNewNavigationBarStyle), "",
                                AppearanceConfig.newNavigationBarStyle.Bool(), false, true);
                    } else if (position == iosNavBarRow) {
                        cell.setText(getString(R.string.OEAppearanceIosNavigationBarStyle), "",
                                AppearanceConfig.iosNavigationBarStyle.Bool(), true, true);
                    } else if (position == iosBackCounterRow) {
                        cell.setText(getString(R.string.OEAppearanceIosBackCounter), "",
                                AppearanceConfig.iosBackCounter.Bool(), true, true);
                    } else if (position == iosFolderTapRow) {
                        cell.setText(getString(R.string.OEAppearanceIosFirstFolderOnTabTap), "",
                                AppearanceConfig.iosFirstFolderOnTabTap.Bool(), false, true);
                    } else if (position == hideAiEditorRow) {
                        cell.setText(getString(R.string.OEAppearanceHideAiEditor), "",
                                AppearanceConfig.hideAiEditor.Bool(), true, true);
                    } else if (position == hideAiSummaryRow) {
                        cell.setText(getString(R.string.OEAppearanceHideAiSummary), "",
                                AppearanceConfig.hideMessageSummary.Bool(), true, true);
                    } else if (position == hideAiIvRow) {
                        cell.setText(getString(R.string.OEAppearanceHideAiIv), "",
                                AppearanceConfig.hideIvSummary.Bool(), false, true);
                    }
                    cell.setPad(1);
                    // По умолчанию ячейка этого типа красит текст серым; у exteraGram
                    // вложенные пункты того же цвета, что и обычные строки.
                    cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == dividerStyleRow) {
                        String[] v = {getString(R.string.OEAppearanceDividerHidden), getString(R.string.OEAppearanceDividerLine), getString(R.string.OEAppearanceDividerSegments)};
                        cell.setTextAndValue(getString(R.string.OEAppearanceDividerStyle), v[clamp(AppearanceConfig.dividerStyle.Int(), v.length)], false);
                    } else if (position == glassOutlineRow) {
                        String[] v = {getString(R.string.OEAppearanceGlassOutlineGlare), getString(R.string.OEAppearanceGlassOutlineSolid), getString(R.string.OEAppearanceGlassOutlineHidden)};
                        cell.setTextAndValue(getString(R.string.OEAppearanceGlassOutline), v[clamp(AppearanceConfig.glassOutlineStyle.Int(), v.length)], true);
                    } else if (position == tabTitleStyleRow) {
                        CharSequence[] v = tabTitleOptions();
                        cell.setTextAndValue(getString(R.string.OEAppearanceTabTitleStyle), v[tabTitleIndex(NekoConfig.tabsTitleType.Int())], true);
                    } else if (position == tabCounterRow) {
                        String[] v = {getString(R.string.OEAppearanceTabCounterAll),
                                getString(R.string.OEAppearanceTabCounterUnmuted),
                                getString(R.string.OEAppearanceTabCounterOff)};
                        cell.setTextAndValue(getString(R.string.OEAppearanceTabCounter), v[clamp(NaConfig.INSTANCE.getIgnoreUnreadCount().Int(), v.length)], true);
                    } else if (position == titleTextRow) {
                        String[] v = {getString(R.string.OEAppearanceTitleTextApp), getString(R.string.OEAppearanceTitleTextUsername), getString(R.string.OEAppearanceTitleTextName), getString(R.string.FilterChats)};
                        cell.setTextAndValue(getString(R.string.OEAppearanceTitleText), v[clamp(AppearanceConfig.titleText.Int(), v.length)], false);
                    }
                    break;
                }
                case TYPE_DETAIL_SETTINGS: {
                    // Иконка слева, подпись под заголовком.
                    TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                    if (position == appNavigationRow) {
                        cell.setTextAndValueAndIcon(getString(R.string.OEAppearanceNavigation), getString(R.string.OEAppearanceNavigationSub), R.drawable.msg_newphone, true);
                    } else if (position == iconPacksRow) {
                        cell.setTextAndValueAndIcon(getString(R.string.OEAppearanceIconPacks), getString(R.string.OEAppearanceIconPacksInfo), R.drawable.msg_sticker, true);
                    } else if (position == pillStackRow) {
                        cell.setTextAndValueAndIcon(getString(R.string.OEAppearancePillStack), getString(R.string.OEAppearancePillStackInfo), R.drawable.outline_header_search, false);
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    // Скруглённый «хвост» списка положен последней тени, а она у нас после блока
                    // Blur, а не после строк-переходов.
                    boolean bottom = position == blurDividerRow;
                    if (position == appearanceDividerRow) {
                        cell.setText(getString(R.string.OEAppearanceCustomThemesInfo));
                    } else if (position == blurDividerRow) {
                        cell.setText(getString(R.string.OEAppearanceBlurInfo));
                    } else if (position == avatarsDividerRow) {
                        cell.setText(getString(R.string.OEAppearanceSingleCornerRadiusInfo));
                    } else if (position == chatListDividerRow) {
                        cell.setText(getString(R.string.OEAppearanceChatListInfo));
                    } else if (position == foldersDividerRow) {
                        cell.setText(getString(R.string.OEAppearanceFoldersInfo));
                    } else {
                        cell.setText(null);
                    }
                    cell.setBackground(Theme.getThemedDrawable(mContext,
                            bottom ? R.drawable.greydivider_bottom : R.drawable.greydivider,
                            Theme.key_windowBackgroundGrayShadow));
                    break;
                }
            }
        }

        /**
         * Свои типы ячеек базовый адаптер считает некликабельными.
         *
         * Для превью это верно, а группа Material Design 3 и галочки внутри неё
         * обработчики имеют: RecyclerListView не только не доводил до них клик,
         * но и звал setEnabled(false) на строке (onChildAttachedToWindow) — из-за
         * чего вся группа выглядела погашенной.
         */
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int type = holder.getItemViewType();
            if (type == TYPE_EXPANDABLE_SWITCH || type == TYPE_ROUND_CHECK) {
                return true;
            }
            return super.isEnabled(holder);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == avatarCornersPreviewRow) {
                return TYPE_AVATAR_CORNERS;
            } else if (position == chatListPreviewRow) {
                return TYPE_CHAT_LIST;
            } else if (position == foldersPreviewRow) {
                return TYPE_FOLDERS;
            } else if (position == sectionRadiusRow) {
                return TYPE_SECTION_SLIDER;
            } else if (position == appearanceHeaderRow || position == sectionsHeaderRow
                    || position == blurHeaderRow || position == chatListHeaderRow
                    || position == foldersHeaderRow) {
                return TYPE_HEADER;
            } else if (position == appearanceDividerRow || position == sectionsDividerRow
                    || position == blurDividerRow || position == avatarsDividerRow
                    || position == chatListDividerRow || position == foldersDividerRow
                    || position == linksDividerRow) {
                return TYPE_INFO_PRIVACY;
            } else if (position == fabShapeRow) {
                return TYPE_FAB_SHAPE;
            } else if (position == appNavigationRow || position == iconPacksRow
                    || position == pillStackRow) {
                return TYPE_DETAIL_SETTINGS;
            } else if (position == md3GroupRow || position == hideAiGroupRow
                    || position == iosGroupRow) {
                return TYPE_EXPANDABLE_SWITCH;
            } else if (position == md3LoadingRow || position == md3SliderRow
                    || position == md3SwitchRow || position == md3ChatHeaderRow
                    || position == md3NavBarRow || position == hideAiEditorRow
                    || position == hideAiSummaryRow || position == hideAiIvRow
                    || position == iosNavBarRow || position == iosFolderTapRow
                    || position == iosBackCounterRow) {
                return TYPE_ROUND_CHECK;
            } else if (position == dividerStyleRow || position == glassOutlineRow
                    || position == tabTitleStyleRow
                    || position == tabCounterRow || position == titleTextRow) {
                return TYPE_SETTINGS;
            }
            return TYPE_CHECK;
        }
    }

    /** Сколько AI-функций спрятано. Счётчик «N/3» рядом с шевроном. */
    private static final int HIDE_AI_COUNT = 3;

    private int hideAiSelectedCount() {
        int n = 0;
        if (AppearanceConfig.hideAiEditor.Bool()) n++;
        if (AppearanceConfig.hideMessageSummary.Bool()) n++;
        if (AppearanceConfig.hideIvSummary.Bool()) n++;
        return n;
    }

    private void toggleHideAi(View clicked, tw.nekomimi.nekogram.config.ConfigItem item) {
        item.setConfigBool(!item.Bool());
        if (clicked instanceof org.telegram.ui.Cells.CheckBoxCell) {
            ((org.telegram.ui.Cells.CheckBoxCell) clicked).setChecked(item.Bool(), true);
        }
        if (listAdapter != null && hideAiGroupRow >= 0) {
            listAdapter.notifyItemChanged(hideAiGroupRow);
        }
    }

    private void toggleAllHideAiFromCell() {
        final boolean enable = hideAiSelectedCount() == 0;
        AppearanceConfig.hideAiEditor.setConfigBool(enable);
        AppearanceConfig.hideMessageSummary.setConfigBool(enable);
        AppearanceConfig.hideIvSummary.setConfigBool(enable);
        rebuildRowsAndNotify();
    }

    /** Сколько стилей MD3 включено. Счётчик «N/5» рядом с шевроном. */
    private static final int MD3_STYLE_COUNT = 5;
    /** Значение селектора NagramX, соответствующее Material Design 3. */
    private static final int STYLE_MD3 = 2;

    private static boolean isMd3(int styleValue) {
        return styleValue == STYLE_MD3;
    }

    private int md3SelectedCount() {
        int n = 0;
        if (AppearanceConfig.newLoadingStyle.Bool()) n++;
        if (isMd3(NaConfig.INSTANCE.getSliderStyle().Int())) n++;
        if (isMd3(NaConfig.INSTANCE.getSwitchStyle().Int())) n++;
        if (AppearanceConfig.newChatHeaderStyle.Bool()) n++;
        if (AppearanceConfig.newNavigationBarStyle.Bool()) n++;
        return n;
    }

    /**
     * Клик по мастер-переключателю группы: если включено хоть что-то — гасим всё,
     * иначе включаем всё. Так же ведёт себя handleMD3StylesSwitchClick exteraGram.
     */
    private void toggleAllMd3StylesFromCell() {
        toggleAllMd3Styles();
    }

    private static final int IOS_STYLE_COUNT = 3;

    private int iosSelectedCount() {
        int n = 0;
        if (AppearanceConfig.iosNavigationBarStyle.Bool()) n++;
        if (AppearanceConfig.iosBackCounter.Bool()) n++;
        if (AppearanceConfig.iosFirstFolderOnTabTap.Bool()) n++;
        return n;
    }

    private void toggleAllIosStyles() {
        boolean enable = iosSelectedCount() == 0;
        AppearanceConfig.iosNavigationBarStyle.setConfigBool(enable);
        AppearanceConfig.iosBackCounter.setConfigBool(enable);
        AppearanceConfig.iosFirstFolderOnTabTap.setConfigBool(enable);
        if (enable) {
            AppearanceConfig.newNavigationBarStyle.setConfigBool(false);
        }
        rebuildAll();
        rebuildRowsAndNotify();
    }

    private void toggleAllMd3Styles() {
        boolean enable = md3SelectedCount() == 0;
        AppearanceConfig.newLoadingStyle.setConfigBool(enable);
        AppearanceConfig.newChatHeaderStyle.setConfigBool(enable);
        AppearanceConfig.newNavigationBarStyle.setConfigBool(enable);
        if (enable) {
            AppearanceConfig.iosNavigationBarStyle.setConfigBool(false);
        }
        NaConfig.INSTANCE.getSliderStyle().setConfigInt(enable ? STYLE_MD3 : 0);
        NaConfig.INSTANCE.getSwitchStyle().setConfigInt(enable ? STYLE_MD3 : 0);
        if (avatarCornersPreviewCell != null) {
            avatarCornersPreviewCell.invalidate();
        }
        // Перезапуск не нужен: все пять стилей читаются при отрисовке или при создании вьюх.
        rebuildAll();
        rebuildRowsAndNotify();
    }

    private static int clamp(int value, int size) {
        if (value < 0) return 0;
        if (value >= size) return size - 1;
        return value;
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.runOnUIThread(this::invalidatePreviews);
    }

    @Override
    public void onFragmentDestroy() {
        // Отложенная пересборка после слайдера радиуса переживёт закрытие экрана, если её не снять.
        AndroidUtilities.cancelRunOnUIThread(sectionRadiusRebuild);
        super.onFragmentDestroy();
    }
}
