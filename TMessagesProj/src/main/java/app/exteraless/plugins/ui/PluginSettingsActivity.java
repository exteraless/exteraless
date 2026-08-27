package app.exteraless.plugins.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.List;

import app.exteraless.plugins.Plugin;
import app.exteraless.plugins.PluginPermissions;
import app.exteraless.plugins.PluginsController;
import app.exteraless.plugins.PluginsWatchdog;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

/**
 * Экран настроек плагина, строящийся из JSON-описания (ui.settings Python SDK).
 * Поддерживает вложенные подстраницы (sub_page) — тогда JSON приходит готовым,
 * без обращения к движку.
 *
 * Список собирается через {@link #fillItems(ArrayList, UniversalAdapter)} —
 * ровно как у exteraGram. Плагины каталога хукают этот метод, чтобы дорисовать
 * себе шапку, и ищут у фрагмента приватное поле {@code plugin}; и то и другое
 * здесь есть намеренно.
 *
 * Строка «Разрешения» добавляется НЕ внутри {@code fillItems}, а после него, в
 * {@link #fillItemsWithPermissions}: иначе плагин, заменивший или очистивший
 * список в своём хуке, убрал бы у пользователя вход в разрешения.
 */
public class PluginSettingsActivity extends BasePreferencesActivity {

    private static final int ID_PERMISSIONS = -1;
    private static final int ID_NOT_LOADED = -2;
    private static final int ID_PERMISSIONS_SHADOW = -3;
    private static final int ID_WATCHDOG_WARNINGS = -4;

    static {
        UItem.UItemFactory.setup(new PluginCustomRowFactory());
    }

    private String pluginId;

    /**
     * Плагин этого экрана. Держим объектом, а не только id: плагины каталога
     * достают поле с этим именем рефлексией и зовут у него {@code getId()},
     * чтобы понять, свой ли экран они украшают.
     */
    private Plugin plugin;

    private String targetSetting;

    private String subPageJson;
    private Object createSubFragmentCallback;
    private String subPageTitle;
    private int[] subPageIndex;
    private String[] subPageOwners;

    /** Строки экрана в исходном виде — как их отдал Python-SDK. */
    private final ArrayList<JSONObject> rows = new ArrayList<>();

    public PluginSettingsActivity() {
    }

    /** Конструктор для шима внешнего API: {@code PluginSettingsActivity(plugin)}. */
    public PluginSettingsActivity(String pluginId) {
        this.pluginId = pluginId;
    }

    /** Плагины каталога передают сюда сам объект плагина, а не его id. */
    public PluginSettingsActivity(Plugin plugin) {
        this.pluginId = plugin == null ? null : plugin.id;
        this.plugin = plugin;
    }

    public PluginSettingsActivity(Plugin plugin, String targetSetting) {
        this(plugin);
        this.targetSetting = targetSetting;
    }

    public static PluginSettingsActivity newInstance(String pluginId) {
        PluginSettingsActivity fragment = new PluginSettingsActivity();
        fragment.pluginId = pluginId;
        return fragment;
    }

    public static PluginSettingsActivity newInstance(String pluginId, String targetSetting) {
        PluginSettingsActivity fragment = newInstance(pluginId);
        fragment.targetSetting = targetSetting;
        return fragment;
    }

    public static PluginSettingsActivity newSubPage(String pluginId, String json, String title,
                                                    int[] index, String[] owners) {
        PluginSettingsActivity fragment = new PluginSettingsActivity();
        fragment.pluginId = pluginId;
        fragment.subPageJson = json;
        fragment.createSubFragmentCallback = json;
        fragment.subPageTitle = title;
        fragment.subPageIndex = index;
        fragment.subPageOwners = owners;
        return fragment;
    }

    private final Runnable reloadListener = this::rebuildFromEngine;
    private ActionBarMenuItem resetItem;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getTitle());
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

        // Список наполняет обёртка, а не сам fillItems: строка разрешений должна
        // пережить хук плагина, а хук срабатывает на возврате из fillItems.
        listView = new UniversalRecyclerView(this, this::fillItemsWithPermissions,
                this::onClick, this::onLongClick);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        layoutManager = (LinearLayoutManager) listView.getLayoutManager();
        contentView.addView(listView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        // Кнопка сброса — как у exteraGram: только на корневом экране плагина и
        // только когда есть что сбрасывать.
        if (subPageIndex == null) {
            resetItem = actionBar.createMenu().addItem(0, R.drawable.msg_reset);
            resetItem.setContentDescription(getString(R.string.PluginsResetSettings));
            resetItem.setOnClickListener(v -> showResetDialog());
            updateResetVisibility(false);
        }

        fragmentView = contentView;
        return fragmentView;
    }

    private void updateResetVisibility(boolean animated) {
        if (resetItem == null) {
            return;
        }
        AndroidUtilities.updateViewVisibilityAnimated(resetItem,
                PluginsController.getInstance().hasPluginSettingsPreferences(pluginId),
                0.5f, animated);
    }

    private void showResetDialog() {
        Activity activity = getParentActivity();
        Plugin target = resolvePlugin();
        if (activity == null || target == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.PluginsResetSettings));
        builder.setMessage(AndroidUtilities.replaceTags(
                LocaleController.formatString(R.string.PluginsResetSettingsInfo,
                        target.getDisplayName())));
        builder.setPositiveButton(getString(R.string.Reset), (dialog, which) -> {
            PluginsController.getInstance().clearPluginSettingsPreferences(pluginId);
            rebuildFromEngine();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    @Override
    public boolean onFragmentCreate() {
        PluginsController.getInstance().addSettingsReloadListener(pluginId, reloadListener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        PluginsController.getInstance().removeSettingsReloadListener(pluginId, reloadListener);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Возврат с подстраницы: там могли переключить то, от чего зависит состав
        // строк на этом экране.
        rebuildFromEngine();
    }

    /**
     * Перестроить экран из движка. Публичный метод — его зовут и плагины:
     * экранов у exteraGram два (свой и наш), и плагины ищут у фрагмента именно
     * {@code updateItems()}, а не найдя — лезут чистить приватные поля рефлексией.
     */
    public void updateItems() {
        AndroidUtilities.runOnUIThread(this::rebuildFromEngine);
    }

    /** Движок просит перестроить экран (плагин изменил настройки из кода). Зовётся на UI-потоке. */
    private void rebuildFromEngine() {
        if (listView == null || listView.adapter == null) {
            return;
        }
        // Плагин удалили или выгрузили — экран его настроек больше ни о чём.
        if (subPageIndex == null && PluginsController.getInstance().getPlugin(pluginId) == null) {
            finishFragment();
            return;
        }
        listView.adapter.update(true);
        updateResetVisibility(true);
        ensureListVisible();
    }

    /** Анимации плагинов короткие (десятые доли секунды) — проверяем после них. */
    private void scheduleVisibilityCheck() {
        AndroidUtilities.runOnUIThread(this::ensureListVisible, 600);
    }

    /**
     * Вернуть список в видимое состояние.
     *
     * Плагины обновляют экран с анимацией: гасят список в прозрачность, в
     * withEndAction перестраивают его и возвращают обратно. Если их код между
     * этими шагами бросит исключение (а ловят они молча), список навсегда
     * остаётся прозрачным — экран выглядит пустым, хотя строки на месте.
     */
    private void ensureListVisible() {
        if (listView == null) {
            return;
        }
        if (listView.getAlpha() < 1f || listView.getTranslationY() != 0f) {
            listView.animate().cancel();
            listView.setAlpha(1f);
            listView.setTranslationY(0f);
        }
    }

    private Plugin resolvePlugin() {
        Plugin current = PluginsController.getInstance().getPlugin(pluginId);
        if (current != null) {
            plugin = current;
        }
        return plugin;
    }

    /**
     * Итоговый список: сначала строки плагина (их правит его же хук на
     * {@code fillItems}), потом наши — вход в разрешения.
     */
    private void fillItemsWithPermissions(ArrayList<UItem> items, UniversalAdapter adapter) {
        resolvePlugin();
        try {
            fillItems(items, adapter);
        } catch (Throwable t) {
            // Упасть здесь — значит показать пустой экран: fillItems исполняет и
            // чужой код, приделанный хуком.
            FileLog.e("PluginSettingsActivity: fillItems failed for " + pluginId, t);
        }
        appendPermissionsRow(items);
        if (targetSetting != null) {
            AndroidUtilities.runOnUIThread(this::scrollToTargetSetting);
        }
    }

    private void scrollToTargetSetting() {
        if (targetSetting == null || listView == null || listView.adapter == null
                || layoutManager == null) {
            return;
        }
        final int id = targetSetting.hashCode() & 0x7FFFFFFF;
        for (int i = 0; i < listView.adapter.getItemCount(); i++) {
            UItem item = listView.adapter.getItem(i);
            if (item != null && item.id == id) {
                layoutManager.scrollToPositionWithOffset(i, AndroidUtilities.dp(48));
                targetSetting = null;
                return;
            }
        }
    }

    /**
     * Строки самого плагина. Точка расширения: плагины каталога хукают именно
     * этот метод и вставляют в {@code items} свою шапку.
     */
    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        reloadRows();
        if (rows.isEmpty()) {
            items.add(UItem.asShadow(ID_NOT_LOADED, getString(R.string.PluginsNotLoaded)));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            UItem item = toUItem(rows.get(i), i);
            if (item != null) {
                items.add(item);
            }
        }
    }

    /**
     * Вход в разрешения — только на корневом экране плагина: подстраница
     * (sub_page) принадлежит самому плагину, разрешениям там не место.
     */
    private void appendPermissionsRow(ArrayList<UItem> items) {
        if (subPageIndex != null) {
            return;
        }
        // Разделитель нужен, только когда выше стоит карточка настроек плагина:
        // строка «плагин не загружен» уже рисует тень под собой сама.
        if (!endsWithShadow(items)) {
            items.add(UItem.asShadow(ID_PERMISSIONS_SHADOW, null));
        }
        items.add(UItem.asButton(ID_PERMISSIONS, getString(R.string.PluginPermissions),
                permissionsValue()).onBind(view -> {
            if (view instanceof TextCell) {
                applyTextCellGeometry((TextCell) view, null);
            }
        }));
        items.add(UItem.asCheck(ID_WATCHDOG_WARNINGS, getString(R.string.PluginWatchdogWarnings))
                .setChecked(!PluginsController.getInstance().getWatchdog().isWarningMuted(pluginId)));
        items.add(UItem.asShadow(-5, getString(R.string.PluginWatchdogWarningsInfo)));
    }

    private static boolean endsWithShadow(ArrayList<UItem> items) {
        if (items.isEmpty()) {
            return false;
        }
        return UniversalAdapter.isShadow(items.get(items.size() - 1).viewType);
    }

    /** Свежий JSON экрана в {@link #rows}. */
    private void reloadRows() {
        ArrayList<JSONObject> previous = new ArrayList<>(rows);
        rows.clear();
        String json;
        if (subPageIndex != null) {
            String resolved = resolveSubPageJson();
            if (resolved != null) {
                subPageJson = resolved;
            }
            json = subPageJson;
        } else {
            json = PluginsController.getInstance().getPluginSettingsJson(pluginId);
        }
        if (json != null && !"null".equals(json)) {
            try {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.optJSONObject(i);
                    if (obj != null) {
                        rows.add(obj);
                    }
                }
            } catch (JSONException e) {
                FileLog.e(e);
            }
        }
        // Пересборка вернула пустоту там, где только что были строки: движок мог
        // не ответить, плагин — не пережить перезагрузку. Стереть экран хуже, чем
        // оставить прошлый список.
        if (rows.isEmpty() && !previous.isEmpty()) {
            FileLog.e("PluginSettingsActivity: empty rebuild for " + pluginId
                    + ", keeping " + previous.size() + " previous rows");
            rows.addAll(previous);
        }
    }

    /**
     * Свежий JSON этой подстраницы: она рисует срез корневого списка, а он
     * пересобирается при каждом изменении настроек. Идём по запомненному пути
     * от корня, сверяя заголовки, — состав строк по дороге мог измениться.
     */
    private String resolveSubPageJson() {
        String rootJson = PluginsController.getInstance().getPluginSettingsJson(pluginId);
        if (rootJson == null || "null".equals(rootJson)) {
            return null;
        }
        try {
            JSONArray array = new JSONArray(rootJson);
            for (int step = 0; step < subPageIndex.length; step++) {
                String owner = subPageOwners != null && step < subPageOwners.length
                        ? subPageOwners[step] : null;
                JSONObject holder = subPageHolder(array, subPageIndex[step], owner);
                if (holder == null) {
                    return null;
                }
                array = holder.optJSONArray("sub_page");
                if (array == null) {
                    return null;
                }
            }
            return array.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    private static JSONObject subPageHolder(JSONArray array, int index, String text) {
        JSONObject candidate = array.optJSONObject(index);
        if (holdsSubPage(candidate, text)) {
            return candidate;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (holdsSubPage(obj, text)) {
                return obj;
            }
        }
        return null;
    }

    private static boolean holdsSubPage(JSONObject obj, String text) {
        return obj != null && obj.optJSONArray("sub_page") != null
                && (text == null || text.equals(obj.optString("text")));
    }

    /**
     * Путь до строки на этом экране, считая от корневого списка плагина.
     *
     * Считаем по самому объекту, а не по позиции в списке: плагин мог вставить
     * туда свои строки, и позиция уже не равна индексу в JSON.
     */
    private int[] pathTo(JSONObject row) {
        int index = rows.indexOf(row);
        int[] parent = subPageIndex == null ? new int[0] : subPageIndex;
        int[] out = java.util.Arrays.copyOf(parent, parent.length + 1);
        out[parent.length] = index;
        return out;
    }

    private String[] ownersTo(String text) {
        String[] parent = subPageOwners == null ? new String[0] : subPageOwners;
        String[] out = java.util.Arrays.copyOf(parent, parent.length + 1);
        out[parent.length] = text;
        return out;
    }

    @Override
    public String getTitle() {
        if (subPageTitle != null) {
            return subPageTitle;
        }
        Plugin target = resolvePlugin();
        return target != null ? target.getDisplayName() : getString(R.string.PluginSettingsTitle);
    }

    /**
     * Значение строки «Разрешения»: сколько из запрошенного сейчас выдано.
     * Цифрами, а не словами, — строка справа узкая, а перевода не требует.
     */
    private String permissionsValue() {
        List<String> requested = PluginPermissionsActivity.requestedFor(resolvePlugin());
        if (requested.isEmpty()) {
            return "";
        }
        int granted = 0;
        for (String perm : requested) {
            if (PluginPermissions.has(pluginId, perm)) {
                granted++;
            }
        }
        return granted + "/" + requested.size();
    }

    // ---------- строка JSON -> UItem ----------

    /**
     * Идентификатор строки для diff-а адаптера. По ключу настройки, а не по
     * позиции: она уезжает, как только плагин вставит в список своё.
     */
    private static int rowId(JSONObject item, int index) {
        String base = optNonEmpty(item, "key");
        if (base == null) {
            base = optNonEmpty(item, "text");
        }
        if (base == null) {
            base = item.optString("type") + '#' + index;
        }
        return base.hashCode() & 0x7FFFFFFF;
    }

    private UItem toUItem(JSONObject row, int index) {
        final int id = rowId(row, index);
        final String type = row.optString("type");
        final UItem item;
        switch (type) {
            case "header":
                item = UItem.asHeader(row.optString("text"));
                break;
            case "divider":
                item = UItem.asShadow(id, optNonEmpty(row, "text"));
                break;
            case "switch": {
                final String subtext = optNonEmpty(row, "subtext");
                item = subtext != null
                        ? UItem.asCheck(id, row.optString("text"), subtext, true)
                        : UItem.asCheck(id, row.optString("text"));
                item.checked = row.optBoolean("value");
                item.iconResId = resolveIcon(row);
                break;
            }
            case "custom":
                return customRow(row, id);
            default:
                item = textRow(row, id, type);
                break;
        }
        item.object = row;
        return item;
    }

    /** Строка-текст: selector, input, edittext и просто text. */
    private UItem textRow(JSONObject row, int id, String type) {
        final boolean isText = "text".equals(type);
        final String subtext = optNonEmpty(row, "subtext");
        final String value = isText ? null : rowValue(row);
        UItem item = UItem.asButton(id, resolveIcon(row), rowTitle(row), value);
        item.red = row.optBoolean("red");
        item.accent = row.optBoolean("accent");
        return item.onBind(view -> {
            if (view instanceof TextCell) {
                applyTextCellGeometry((TextCell) view, subtext);
            }
        });
    }

    /**
     * Подпись живёт под заголовком, а не справа: у плагинов это предложение
     * целиком, справа от него остаётся многоточие. Геометрия иконки — как у
     * TextSettingsCell и TextCheckCell: на одном экране строки разных типов
     * идут вперемешку, и штатные 58dp у TextCell дают рваный левый край.
     */
    private static void applyTextCellGeometry(TextCell cell, String subtext) {
        cell.setImageLeft(21);
        cell.setOffsetFromImage(71);
        cell.setSubtitle(subtext);
        cell.heightDp = subtext != null ? 60 : 50;
    }

    private UItem customRow(JSONObject row, int id) {
        UItem item = UItem.ofFactory(PluginCustomRowFactory.class);
        item.id = id;
        item.object = row;
        String viewId = optNonEmpty(row, "view_id");
        if (viewId != null) {
            item.view = PluginsController.getInstance()
                    .getPluginSettingsCustomView(pluginId, viewId, getContext());
        }
        item.enabled = optNonEmpty(row, "callback_id") != null
                || row.optJSONArray("sub_page") != null;
        return item;
    }

    /**
     * Строка с вьюхой самого плагина.
     *
     * Своя фабрика, а не {@code UItem.asCustom}: у штатного VIEW_TYPE_CUSTOM
     * нажатие не ловится вовсе, а строке {@code ui.settings.Custom(on_click=...)}
     * оно нужно. Тождество строки считаем по id, а не по вьюхе: плагин отдаёт
     * её заново на каждой пересборке, и по вьюхе diff перекладывал бы строку
     * целиком при каждом обновлении.
     */
    public static class PluginCustomRowFactory extends UItem.UItemFactory<FrameLayout> {

        @Override
        public FrameLayout createView(Context context, RecyclerListView listView, int currentAccount,
                                      int classGuid, Theme.ResourcesProvider resourcesProvider) {
            FrameLayout container = new FrameLayout(context);
            container.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return container;
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            FrameLayout container = (FrameLayout) view;
            if (container.getChildCount() == 1 && container.getChildAt(0) == item.view) {
                return;
            }
            container.removeAllViews();
            if (item.view == null) {
                return;
            }
            // Вьюха живёт в объекте плагина и переживает переработку строки:
            // тот же экземпляр может ещё висеть в прошлом контейнере, и addView
            // без этого бросит IllegalState.
            AndroidUtilities.removeFromParent(item.view);
            container.addView(item.view, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.id == b.id && a.view == b.view && a.enabled == b.enabled;
        }
    }

    /** Иконки приезжают именем drawable («msg_settings»); неизвестные молча пропускаем. */
    private static int resolveIcon(JSONObject item) {
        String name = app.exteraless.plugins.JsonUtils.optStringOrNull(item, "icon");
        if (TextUtils.isEmpty(name)) {
            return 0;
        }
        try {
            return R.drawable.class.getField(name).getInt(null);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Заголовок строки: у EditText его нет — там подпись живёт в hint. */
    private static String rowTitle(JSONObject item) {
        String text = optNonEmpty(item, "text");
        if (text != null) {
            return text;
        }
        String hint = optNonEmpty(item, "hint");
        return hint != null ? hint : "";
    }

    /** Правая колонка строки: выбранный пункт для selector, значение для остальных. */
    private static String rowValue(JSONObject item) {
        if ("selector".equals(item.optString("type"))) {
            JSONArray options = item.optJSONArray("items");
            if (options == null || options.length() == 0) {
                return "";
            }
            int selected = item.optInt("value");
            if (selected < 0 || selected >= options.length()) {
                selected = item.optInt("default", 0);
            }
            if (selected < 0 || selected >= options.length()) {
                selected = 0;
            }
            return options.optString(selected);
        }
        return item.optString("value");
    }

    private static String optNonEmpty(JSONObject item, String key) {
        String value = app.exteraless.plugins.JsonUtils.optStringOrNull(item, key);
        return TextUtils.isEmpty(value) ? null : value;
    }

    // ---------- нажатия ----------

    /** JSON-строка, стоящая за элементом списка, или null, если элемент чужой. */
    private static JSONObject rowOf(UItem item) {
        return item != null && item.object instanceof JSONObject ? (JSONObject) item.object : null;
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        JSONObject row = rowOf(item);
        String callbackId = row == null ? null : optNonEmpty(row, "long_callback_id");
        if (callbackId == null) {
            return false;
        }
        PluginsController.getInstance().dispatchSettingClick(pluginId, callbackId, view);
        return true;
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item != null && item.id == ID_PERMISSIONS && rowOf(item) == null) {
            presentFragment(new PluginPermissionsActivity(pluginId));
            return;
        }
        if (item != null && item.id == ID_WATCHDOG_WARNINGS && rowOf(item) == null) {
            PluginsWatchdog watchdog = PluginsController.getInstance().getWatchdog();
            boolean warn = watchdog.isWarningMuted(pluginId);
            watchdog.setWarningMuted(pluginId, !warn);
            item.checked = warn;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(warn);
            }
            return;
        }
        JSONObject row = rowOf(item);
        if (row == null) {
            return;
        }
        String type = row.optString("type");
        String key = optNonEmpty(row, "key");
        String callbackId = optNonEmpty(row, "callback_id");
        PluginsController controller = PluginsController.getInstance();
        switch (type) {
            case "switch": {
                boolean newValue = !row.optBoolean("value");
                try {
                    row.put("value", newValue);
                } catch (JSONException ignore) {
                }
                item.checked = newValue;
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(newValue);
                }
                if (key != null) {
                    controller.notifySettingChanged(pluginId, key, String.valueOf(newValue));
                    scheduleVisibilityCheck();
                }
                break;
            }
            case "selector":
                showSelectorDialog(row, key);
                break;
            case "input":
                showInputDialog(row, key, false);
                break;
            case "edittext":
                showInputDialog(row, key, true);
                break;
            case "custom": {
                JSONArray customSubPage = row.optJSONArray("sub_page");
                if (customSubPage != null) {
                    presentFragment(newSubPage(pluginId, customSubPage.toString(), getTitle(),
                            pathTo(row), ownersTo(null)));
                } else if (callbackId != null) {
                    controller.dispatchSettingClick(pluginId, callbackId, view);
                }
                break;
            }
            case "text": {
                JSONArray subPage = row.optJSONArray("sub_page");
                if (subPage != null) {
                    presentFragment(newSubPage(pluginId, subPage.toString(), row.optString("text"),
                            pathTo(row), ownersTo(row.optString("text"))));
                } else if (callbackId != null) {
                    controller.dispatchSettingClick(pluginId, callbackId, view);
                }
                break;
            }
        }
    }

    private void refreshItems() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    /**
     * Выбор из списка — как у exteraGram
     * ({@code plugins/ui/PluginSettingsActivity.showSelectorDialog}): строки с
     * радиокнопками и отмеченным текущим значением, а не голый список.
     */
    private void showSelectorDialog(JSONObject item, String key) {
        Activity activity = getParentActivity();
        JSONArray options = item.optJSONArray("items");
        if (activity == null || options == null) {
            return;
        }
        int selected = item.optInt("value");
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        final AlertDialog[] dialog = new AlertDialog[1];
        for (int i = 0; i < options.length(); i++) {
            final int index = i;
            RadioColorCell cell = new RadioColorCell(activity);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(options.optString(i), selected == i);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            cell.setOnClickListener(v -> {
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
                try {
                    item.put("value", index);
                } catch (JSONException ignore) {
                }
                if (key != null) {
                    PluginsController.getInstance().notifySettingChanged(pluginId, key,
                            String.valueOf(index));
                    scheduleVisibilityCheck();
                }
                refreshItems();
            });
            content.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(rowTitle(item));
        builder.setView(content);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        dialog[0] = builder.create();
        showDialog(dialog[0]);
    }

    /**
     * Диалог ввода значения — как у exteraGram
     * ({@code plugins/ui/PluginSettingsActivity.showStringInputDialog}):
     * подпись строки над полем, поле EditTextBoldCursor с подчёркиванием и
     * курсором темы, ширина 292dp, кнопка «Готово» и клавиатура сразу.
     */
    private void showInputDialog(JSONObject item, String key, boolean multiline) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);

        String subtext = optNonEmpty(item, "subtext");
        if (subtext != null) {
            TextView description = new TextView(activity);
            description.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            description.setText(subtext);
            content.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, 24, 5, 24, 12));
        }

        final EditTextBoldCursor input = new EditTextBoldCursor(activity);
        input.lineYFix = true;
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setText(item.optString("value"));
        input.setSelection(input.getText().length());
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintColor(Theme.getColor(Theme.key_groupcreate_hintText));
        String hint = optNonEmpty(item, "hint");
        input.setHintText(hint != null ? hint : getString(R.string.PluginsEnterValue));
        input.setFocusable(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (multiline) {
            input.setMinLines(3);
            input.setGravity(Gravity.TOP | Gravity.START);
        }
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        input.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedRegular));
        input.setBackground(null);
        input.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        int maxLength = item.optInt("max_length", 0);
        if (maxLength > 0) {
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        }
        content.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 24, 0, 24, 10));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(rowTitle(item));
        builder.makeCustomMaxHeight();
        builder.setView(content);
        builder.setWidth(AndroidUtilities.dp(292));
        builder.setPositiveButton(getString(R.string.Done), (dialog, which) -> {
            String value = input.getText().toString();
            try {
                item.put("value", value);
            } catch (JSONException ignore) {
            }
            if (key != null) {
                PluginsController.getInstance().notifySettingChanged(pluginId, key, JSONObject.quote(value));
                scheduleVisibilityCheck();
            }
            refreshItems();
            dialog.dismiss();
        });
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        dialog.setOnDismissListener(d -> AndroidUtilities.hideKeyboard(input));
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        });
        showDialog(dialog);
    }
}
