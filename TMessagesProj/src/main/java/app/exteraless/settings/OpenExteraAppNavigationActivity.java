package app.exteraless.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.appearance.AvatarCornersSeekBar;
import app.exteraless.drawer.MainMenuHelper;
import app.exteraless.drawer.MainMenuItem;
import app.exteraless.drawer.MainMenuLayout;
import app.exteraless.utils.UtilsConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsLayout;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

/**
 * Экран «App Navigation».
 *
 * Собран на {@link UniversalRecyclerView}, а не на {@code BaseNekoSettingsActivity}:
 * только здесь есть перетаскивание строк, без которого редактор порядка пунктов
 * бокового меню не собрать.
 *
 * Режим нижней панели трёхпозиционный, как в exteraGram: «Показать», «Скрыть»,
 * «Плавающая». Хранит его {@link MainTabsLayout}.
 */
public class OpenExteraAppNavigationActivity extends BaseFragment {

    // id строк-настроек. Отрицательные, чтобы не пересечься с id пунктов меню,
    // которые совпадают с id самих MainMenuItem.
    private static final int ID_TABLET_MODE = -101;
    private static final int ID_BACK_ANIMATION = -102;
    private static final int ID_BOTTOM_NAVIGATION_BAR = -106;
    private static final int ID_PREDICTIVE_INTENSITY = -103;
    private static final int ID_DRAWER = -104;
    private static final int ID_IMMERSIVE = -105;

    /** Кнопка «добавить разделитель». */
    private static final int ID_ADD_DIVIDER = -200;

    /**
     * Разделителей в списке может быть несколько, а id у них общий (-1), поэтому адаптеру
     * нужны различимые: раздаём стабильные id от -2000 вниз.
     */
    private static final int DIVIDER_ID_BASE = -2000;

    private UniversalRecyclerView listView;
    private ActionBarMenuItem resetItem;

    private final ArrayList<Integer> stableDividerIds = new ArrayList<>();
    private int nextDividerId = DIVIDER_ID_BASE;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.OEAppNavigation));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        listView.allowReorder(true);
        // Именно на списке, а не на адаптере: ItemTouchHelper создаётся и цепляется
        // только здесь, у адаптера одноимённый метод лишь запоминает колбэк.
        listView.listenReorder(this::onReordered);
        listView.setReorderClampToSection(true);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        // Кнопка сброса раскладки меню: появляется, только когда раскладка отличается
        // от дефолтной, и гаснет обратно после сброса.
        resetItem = actionBar.createMenu().addItem(0, R.drawable.msg_reset);
        resetItem.setContentDescription(getString(R.string.Reset));
        resetItem.setOnClickListener(v -> resetMenuLayout());
        updateResetButtonVisibility(false);

        fragmentView = contentView;
        return fragmentView;
    }

    private void resetMenuLayout() {
        MainMenuLayout.reset();
        stableDividerIds.clear();
        nextDividerId = DIVIDER_ID_BASE;
        update();
    }

    private void updateResetButtonVisibility(boolean animated) {
        if (resetItem == null) {
            return;
        }
        final boolean show = !MainMenuLayout.getLayout().equals(MainMenuLayout.getDefaultLayout());
        if (show && resetItem.getVisibility() != View.VISIBLE) {
            AndroidUtilities.updateViewVisibilityAnimated(resetItem, true, 0.5f, animated);
        } else if (!show && resetItem.getVisibility() == View.VISIBLE) {
            AndroidUtilities.updateViewVisibilityAnimated(resetItem, false, 0.5f, animated);
        }
    }

    // ---- содержимое ----

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.OEGeneral)));
        items.add(UItem.asButton(ID_TABLET_MODE, getString(R.string.OETabletMode),
                tabletModes()[clamp(NekoConfig.tabletMode.Int(), 3)]));
        items.add(UItem.asButton(ID_BOTTOM_NAVIGATION_BAR, getString(R.string.OEBottomNavigationBarMode),
                bottomNavigationModes()[MainTabsLayout.getBottomNavigationMode()]));
        // Вместо переключателя Spring Animations здесь трёхпозиционный
        // NaConfig.backAnimationStyle: он покрывает и Spring, и Classic.
        items.add(UItem.asButton(ID_BACK_ANIMATION, getString(R.string.OEBackAnimation),
                backAnimations()[clamp(NaConfig.INSTANCE.getBackAnimationStyle().Int(), backAnimations().length)]));
        items.add(UItem.asShadow(getString(R.string.OEBackAnimationInfo)));

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            items.add(UItem.asCustom(ID_PREDICTIVE_INTENSITY, createIntensitySlider()));
            items.add(UItem.asShadow(getString(R.string.OEPredictiveBackInfo)));
        }

        items.add(UItem.asHeader(getString(R.string.OEAppNavigation)));
        items.add(UItem.asCheck(ID_DRAWER, getString(R.string.OENavigationDrawer))
                .setChecked(AppearanceConfig.navigationDrawer()));
        if (AppearanceConfig.navigationDrawer()) {
            items.add(UItem.asCheck(ID_IMMERSIVE, getString(R.string.OENavigationDrawerImmersive))
                    .setChecked(AppearanceConfig.immersiveDrawerAnimation()));
        }
        items.add(UItem.asShadow(getString(R.string.OENavigationDrawerInfo)));

        addMenuSection(items, adapter, getString(R.string.OEMainMenuItems), MainMenuLayout.getLayout(), true);
        items.add(UItem.asShadow(getString(R.string.OEMainMenuItemsInfo)));

        final List<Integer> hidden = MainMenuLayout.getHiddenItems();
        if (!hidden.isEmpty()) {
            addMenuSection(items, adapter, getString(R.string.OEMainMenuHiddenItems), hidden, false);
            items.add(UItem.asShadow(null));
        }
    }

    /**
     * Секция пунктов меню.
     *
     * @param reorderable для видимых пунктов true — им раздаются стабильные id разделителей
     *                    и добавляется кнопка «добавить разделитель»
     */
    private void addMenuSection(ArrayList<UItem> items, UniversalAdapter adapter,
                                String title, List<Integer> ids, boolean reorderable) {
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(title));
        adapter.reorderSectionStart();

        int dividerIndex = 0;
        for (int id : ids) {
            if (id == MainMenuItem.DIVIDER.getId()) {
                if (reorderable) {
                    while (stableDividerIds.size() <= dividerIndex) {
                        stableDividerIds.add(nextDividerId--);
                    }
                    items.add(createMenuItem(stableDividerIds.get(dividerIndex), null));
                } else {
                    items.add(createMenuItem(MainMenuItem.DIVIDER.getId(), null));
                }
                dividerIndex++;
                continue;
            }
            final MainMenuHelper.MenuItemInfo info = MainMenuHelper.describeItem(id);
            if (info != null) {
                items.add(createMenuItem(id, info));
            }
        }

        adapter.reorderSectionEnd();
        if (reorderable) {
            items.add(UItem.asButton(ID_ADD_DIVIDER, R.drawable.msg_add,
                    getString(R.string.OEMainMenuAddDivider)).accent());
        }
        adapter.whiteSectionEnd();
    }

    /**
     * Иконку-ремешок повесить некуда: {@code uItem.object2} у нашего UniversalAdapter
     * занят под кнопку «открыть» у ботов. Перетаскивание при этом работает — его даёт
     * allowReorder(true) по долгому нажатию, — поэтому строки оставлены без ремешка.
     */
    private UItem createMenuItem(int id, MainMenuHelper.MenuItemInfo info) {
        if (info == null) {
            return UItem.asButton(id, R.drawable.msg_block, getString(R.string.OEMainMenuDivider));
        }
        return UItem.asButton(id, info.iconRes(), info.text());
    }

    private View createIntensitySlider() {
        final AvatarCornersSeekBar slider = new AvatarCornersSeekBar(getContext(),
                value -> UtilsConfig.predictiveBackIntensity.setConfigInt(value),
                0, 200,
                getString(R.string.OEPredictiveBackIntensity),
                getString(R.string.OEPredictiveBackOff),
                getString(R.string.OEPredictiveBackMax));
        slider.setValueSuffix("%");
        slider.setValue(UtilsConfig.predictiveBackIntensity.Int());
        return slider;
    }

    // ---- обработка ----

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        final int id = item.id;

        if (id == ID_TABLET_MODE) {
            showChoice(getString(R.string.OETabletMode), tabletModes(),
                    NekoConfig.tabletMode.Int(), which -> {
                        NekoConfig.tabletMode.setConfigInt(which);
                        update();
                    });
            return;
        }
        if (id == ID_BOTTOM_NAVIGATION_BAR) {
            showChoice(getString(R.string.OEBottomNavigationBarMode), bottomNavigationModes(),
                    MainTabsLayout.getBottomNavigationMode(), which -> {
                        MainTabsLayout.setBottomNavigationMode(which);
                        if (which == MainTabsLayout.BOTTOM_NAVIGATION_MODE_FLOATING) {
                            AppearanceConfig.newNavigationBarStyle.setConfigBool(false);
                        }
                        // Со скрытой панелью «Настройки» доступны только из меню; следить за этим
                        // умеет сама раскладка (MainMenuLayout.ensureSettingsVisibility), поэтому
                        // достаточно перечитать список и пересобрать вьюхи.
                        update();
                        if (getParentLayout() != null) {
                            getParentLayout().rebuildAllFragmentViews(false, false);
                        }
                    });
            return;
        }
        if (id == ID_BACK_ANIMATION) {
            showChoice(getString(R.string.OEBackAnimation), backAnimations(),
                    NaConfig.INSTANCE.getBackAnimationStyle().Int(), which -> {
                        NaConfig.INSTANCE.getBackAnimationStyle().setConfigInt(which);
                        // Пружинные переходы не работают с выключенными анимациями
                        // интерфейса, поэтому их взводим принудительно.
                        if (which == BACK_ANIMATION_SPRING) {
                            MessagesController.getGlobalMainSettings().edit()
                                    .putBoolean("view_animations", true).apply();
                            SharedConfig.setAnimationsEnabled(true);
                        }
                        update();
                    });
            return;
        }
        if (id == ID_DRAWER) {
            AppearanceConfig.navigationDrawer.setConfigBool(!AppearanceConfig.navigationDrawer());
            // Шторка создаётся один раз при старте активити, поэтому её надо пересобрать,
            // иначе флаг применится только после перезапуска приложения.
            if (getParentActivity() instanceof LaunchActivity launchActivity) {
                launchActivity.syncDrawerContainerEnabled();
            }
            getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
            update();
            if (getParentLayout() != null) {
                getParentLayout().rebuildFragments(0);
            }
            return;
        }
        if (id == ID_IMMERSIVE) {
            AppearanceConfig.immersiveDrawerAnimation.setConfigBool(!AppearanceConfig.immersiveDrawerAnimation());
            update();
            return;
        }
        if (id == ID_ADD_DIVIDER) {
            stableDividerIds.add(nextDividerId--);
            final ArrayList<Integer> layout = MainMenuLayout.getLayoutMutable();
            layout.add(MainMenuItem.DIVIDER.getId());
            MainMenuLayout.save(layout, MainMenuLayout.getHiddenItems());
            update();
            return;
        }

        // Разделитель из видимой секции: у него стабильный отрицательный id, надо найти,
        // какой по счёту разделитель в раскладке ему соответствует.
        if (id <= DIVIDER_ID_BASE) {
            final int index = stableDividerIds.indexOf(id);
            if (index < 0) {
                return;
            }
            final ArrayList<Integer> layout = MainMenuLayout.getLayoutMutable();
            int seen = 0;
            for (int i = 0; i < layout.size(); i++) {
                if (layout.get(i) == MainMenuItem.DIVIDER.getId()) {
                    if (seen == index) {
                        stableDividerIds.remove(index);
                        layout.remove(i);
                        MainMenuLayout.save(layout, MainMenuLayout.getHiddenItems());
                        update();
                        return;
                    }
                    seen++;
                }
            }
            return;
        }

        toggleMenuItem(id);
    }

    /** Перенос пункта между «видимыми» и «скрытыми». */
    private void toggleMenuItem(int id) {
        final ArrayList<Integer> layout = MainMenuLayout.getLayoutMutable();
        final ArrayList<Integer> hidden = MainMenuLayout.getHiddenItemsMutable();

        if (id == MainMenuItem.DIVIDER.getId()) {
            hidden.remove(Integer.valueOf(id));
            MainMenuLayout.save(layout, hidden);
            update();
            return;
        }

        // «Настройки» нельзя убрать, если их неоткуда больше открыть.
        if (id == MainMenuItem.SETTINGS.getId() && layout.contains(id) && !hasBottomTabs()) {
            BulletinFactory.of(this)
                    .createErrorBulletin(getString(R.string.OEMainMenuRemoveSettingsInfo))
                    .show();
            return;
        }

        if (layout.contains(Integer.valueOf(id))) {
            layout.remove(Integer.valueOf(id));
            if (!hidden.contains(Integer.valueOf(id))) {
                hidden.add(0, id);
            }
        } else if (hidden.contains(Integer.valueOf(id))) {
            hidden.remove(Integer.valueOf(id));
            layout.add(id);
        }
        MainMenuLayout.save(layout, hidden);
        update();
    }

    /**
     * Перетаскивание. Адаптер отдаёт номер секции и её строки в новом порядке
     *. Секция 0 — видимые пункты, 1 — скрытые.
     */
    private void onReordered(int section, ArrayList<UItem> reordered) {
        final ArrayList<Integer> ids = new ArrayList<>(reordered.size());
        for (UItem item : reordered) {
            ids.add(item.id <= DIVIDER_ID_BASE ? MainMenuItem.DIVIDER.getId() : item.id);
        }
        if (section == 0) {
            // Стабильные id разделителей раздаются заново по новому порядку.
            stableDividerIds.clear();
            for (UItem item : reordered) {
                if (item.id <= DIVIDER_ID_BASE) {
                    stableDividerIds.add(item.id);
                }
            }
            MainMenuLayout.save(ids, MainMenuLayout.getHiddenItems());
        } else {
            MainMenuLayout.save(MainMenuLayout.getLayout(), ids);
        }
        updateResetButtonVisibility(true);
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    // ---- мелочи ----

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        updateResetButtonVisibility(true);
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    private void showChoice(String title, CharSequence[] options, int selected,
                            Utilities.Callback<Integer> onSelected) {
        if (getParentActivity() == null) {
            return;
        }
        final org.telegram.ui.ActionBar.AlertDialog.Builder builder =
                new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setItems(options, (dialog, which) -> onSelected.run(which));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private boolean hasBottomTabs() {
        // Со скрытой нижней панелью «Настройки» — единственный вход в настройки,
        // и прятать их нельзя.
        return MainTabsLayout.isBottomNavigationVisible();
    }

    private CharSequence[] tabletModes() {
        return new CharSequence[]{
                getString(R.string.OETabletModeAuto),
                getString(R.string.OETabletModeOn),
                getString(R.string.OETabletModeOff),
        };
    }

    /**
     * Порядок совпадает с константами {@code MainTabsLayout.BOTTOM_NAVIGATION_MODE_*}:
     * индекс выбранного пункта и есть режим.
     */
    private CharSequence[] bottomNavigationModes() {
        return new CharSequence[]{
                getString(R.string.OEBottomNavigationModeShow),
                getString(R.string.OEBottomNavigationModeHide),
                getString(R.string.OEBottomNavigationModeFloating),
        };
    }

    /** Значение NaConfig.backAnimationStyle, соответствующее пружинным переходам. */
    private static final int BACK_ANIMATION_SPRING = 1;

    private CharSequence[] backAnimations() {
        if (android.os.Build.VERSION.SDK_INT < 34) {
            return new CharSequence[]{
                    getString(R.string.OEBackAnimationClassic),
                    getString(R.string.OEBackAnimationSpring),
                    getString(R.string.OEBackAnimationPredictive),
            };
        }
        return new CharSequence[]{
                getString(R.string.OEBackAnimationClassic),
                getString(R.string.OEBackAnimationSpring),
                getString(R.string.OEBackAnimationPredictive),
                getString(R.string.OEBackAnimationSlide),
        };
    }

    private static int clamp(int value, int size) {
        return value < 0 || value >= size ? 0 : value;
    }
}
