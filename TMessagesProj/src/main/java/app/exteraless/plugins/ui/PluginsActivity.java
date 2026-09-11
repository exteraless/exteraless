package app.exteraless.plugins.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.exteraless.plugins.Plugin;
import app.exteraless.plugins.PluginInstallHelper;
import app.exteraless.plugins.PluginsController;
import app.exteraless.plugins.PythonPluginsEngine;
import app.exteraless.plugins.PluginsWatchdog;
import app.exteraless.plugins.ui.components.PluginCell;
import app.exteraless.plugins.ui.components.PluginCellDelegate;
import app.exteraless.plugins.ui.components.PluginPermissionsDelegate;

/**
 * Экран «Plugins». Порт
 * {@code com/exteragram/messenger/plugins/ui/PluginsActivity.java}.
 *
 * Устройство повторяет exteraGram: в шапке — поиск и кнопка (i), ведущая на
 * {@link PluginsInfoActivity}; в списке — крупный переключатель движка
 * ({@code UItem.asRippleCheck}) и сами плагины, а при пустом списке —
 * подсказка со ссылкой на канал. Настройки движка (developer/safe/compatibility)
 * живут на втором экране, а не свалены сюда: раньше всё было одним списком, и
 * это ощутимо расходилось с оригиналом.
 */
public class PluginsActivity extends BaseFragment {

    private static final int MENU_SEARCH = 0;
    private static final int MENU_INFO = 1;

    private static final int ID_ENGINE_TOGGLE = -1;
    private static final int ID_CATALOG = -2;

    private static final int REQUEST_CODE_PICK_PLUGIN = 9781;

    private static final class CatalogEntryFactory
            extends UItem.UItemFactory<TextDetailSettingsCell> {
        static { setup(new CatalogEntryFactory()); }

        @Override
        public TextDetailSettingsCell createView(Context context, RecyclerListView listView,
                                                  int currentAccount, int classGuid,
                                                  Theme.ResourcesProvider resourcesProvider) {
            TextDetailSettingsCell cell = new TextDetailSettingsCell(context);
            cell.setMultilineDetail(true);
            return cell;
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            ((TextDetailSettingsCell) view).setTextAndValueAndIcon(
                    item.text.toString(), item.subtext, item.iconResId, divider);
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            return first.id == second.id;
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            return first.id == second.id
                    && first.iconResId == second.iconResId
                    && TextUtils.equals(first.text, second.text)
                    && TextUtils.equals(first.subtext, second.subtext);
        }

        static UItem asEntry(int id, int icon, CharSequence title, CharSequence subtitle) {
            UItem item = UItem.ofFactory(CatalogEntryFactory.class);
            item.id = id;
            item.iconResId = icon;
            item.text = title;
            item.subtext = subtitle;
            return item;
        }
    }

    private UniversalRecyclerView listView;
    private final List<Plugin> plugins = new ArrayList<>();
    private PluginsEmptyCell emptyCell;
    private String searchQuery;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.OpenExteraPlugins));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_INFO) {
                    presentFragment(new PluginsInfoActivity());
                }
            }
        });

        org.telegram.ui.ActionBar.ActionBarMenuItem search =
                actionBar.createMenu().addItem(MENU_SEARCH, R.drawable.ic_ab_search_solar)
                        .setIsSearchField(true)
                        .setActionBarMenuItemSearchListener(
                                new org.telegram.ui.ActionBar.ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                                    @Override
                                    public void onSearchCollapse() {
                                        searchQuery = null;
                                        updateRows();
                                    }

                                    @Override
                                    public void onTextChanged(android.widget.EditText editText) {
                                        searchQuery = editText.getText().toString();
                                        updateRows();
                                    }
                                });
        search.setSearchFieldHint(getString(R.string.Search));
        actionBar.createMenu().addItem(MENU_INFO, R.drawable.msg_info);

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        refreshPlugins(true);
        emptyCell = new PluginsEmptyCell(context, getCurrentAccount());
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick,
                this::onItemLongClick) {
            @Override
            protected boolean canHighlightChildAt(View child, float x, float y) {
                return !(child instanceof PluginCell)
                        && super.canHighlightChildAt(child, x, y);
            }
        };
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        contentView.addView(listView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        fragmentView = contentView;
        return fragmentView;
    }

    // ---------- список ----------

    private void refreshPlugins(boolean rescan) {
        PluginsController controller = PluginsController.getInstance();
        if (rescan && controller.isEngineEnabled()) {
            // Движок стартует асинхронно; rescan сам выйдет, если он ещё не поднялся.
            controller.rescanPlugins();
        }
        plugins.clear();
        plugins.addAll(controller.getPluginsSnapshot());
        plugins.sort((a, b) -> {
            // Закреплённые — наверх: список плагинов растёт, и нужные иначе
            // тонут среди остальных по алфавиту.
            boolean pinnedA = controller.isPluginPinned(a.id);
            boolean pinnedB = controller.isPluginPinned(b.id);
            if (pinnedA != pinnedB) {
                return pinnedA ? -1 : 1;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(a.getDisplayName(), b.getDisplayName());
        });
    }

    private List<Plugin> visiblePlugins() {
        if (TextUtils.isEmpty(searchQuery)) {
            return plugins;
        }
        String query = searchQuery.toLowerCase(Locale.ROOT);
        List<Plugin> filtered = new ArrayList<>();
        for (Plugin plugin : plugins) {
            if (plugin.getDisplayName().toLowerCase(Locale.ROOT).contains(query)
                    || (plugin.id != null && plugin.id.toLowerCase(Locale.ROOT).contains(query))) {
                filtered.add(plugin);
            }
        }
        return filtered;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PluginsController controller = PluginsController.getInstance();
        boolean engineEnabled = controller.isEngineEnabled();

        items.add(PluginUiItem.engineToggle(ID_ENGINE_TOGGLE,
                getString(R.string.EnablePluginsEngine), engineEnabled));
        if (!engineEnabled) {
            return;
        }
        items.add(UItem.asSpace(dp(8)));
        items.add(CatalogEntryFactory.asEntry(ID_CATALOG, R.drawable.msg_download,
                getString(R.string.PluginCatalogTitle),
                getString(R.string.PluginCatalogBrowseSummary)));
        items.add(UItem.asShadow(null));

        List<Plugin> visible = visiblePlugins();
        if (visible.isEmpty()) {
            CharSequence hint = TextUtils.isEmpty(searchQuery)
                    ? withUsernameLink(getString(R.string.PluginsEmptyHint))
                    : getString(R.string.PluginsNotFound);
            emptyCell.setState(engineEnabled, hint, listView != null);
            items.add(PluginUiItem.fullscreen(emptyCell, dp(74), true));
            return;
        }
        for (Plugin plugin : visible) {
            items.add(PluginCell.Factory.asPlugin(plugin, new PluginRowDelegate(plugin)));
        }
    }

    /**
     * Делает @username в подсказке кликабельной ссылкой на канал.
     * exteraGram зовёт свой LocaleUtils.formatWithUsernames; у нас такого хелпера
     * нет, а тянуть его целиком ради одной строки незачем.
     */
    private CharSequence withUsernameLink(String text) {
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        Matcher matcher = Pattern.compile("@([A-Za-z][A-Za-z0-9_]{3,31})").matcher(text);
        while (matcher.find()) {
            final String username = matcher.group(1);
            builder.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    Browser.openUrl(getParentActivity(), "https://t.me/" + username);
                }

                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setUnderlineText(false);
                }
            }, matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }


    // ---------- действия карточки ----------

    /**
     * Кнопки под описанием плагина. Раньше всё это жило в меню по долгому
     * нажатию: пункт, о существовании которого нельзя догадаться.
     */
    private final class PluginRowDelegate implements PluginCellDelegate, PluginPermissionsDelegate {

        private final Plugin plugin;

        PluginRowDelegate(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void togglePlugin(View view) {
            PluginsActivity.this.togglePlugin(plugin);
        }

        @Override
        public void sharePlugin() {
            PluginsActivity.this.sharePlugin(plugin);
        }

        @Override
        public void pinPlugin(View view) {
            PluginsController controller = PluginsController.getInstance();
            boolean pinned = !controller.isPluginPinned(plugin.id);
            controller.setPluginPinned(plugin.id, pinned);
            refreshPlugins(false);
            updateRows();
            if (getContext() != null) {
                org.telegram.ui.Components.BulletinFactory.of(PluginsActivity.this)
                        .createSimpleBulletin(pinned ? R.raw.ic_pin : R.raw.ic_unpin,
                                getString(pinned ? R.string.PluginsPinned : R.string.PluginsUnpinned))
                        .show();
            }
        }

        @Override
        public void openPluginSettings() {
            PythonPluginsEngine.getInstance().openPluginSettings(plugin, PluginsActivity.this);
        }

        @Override
        public void openPluginPermissions() {
            presentFragment(new PluginPermissionsActivity(plugin.id));
        }

        @Override
        public void deletePlugin() {
            showDeleteDialog(plugin);
        }

        @Override
        public void openInExternalApp() {
        }

        @Override
        public boolean canOpenInExternalApp() {
            return false;
        }
    }

    /**
     * Отдать файл плагина наружу.
     *
     * Копируем в кэш, а не отдаём из filesDir/plugins: FileProvider наружу
     * этот каталог не публикует, а расширять ему видимость ради «поделиться»
     * значит открыть чужим приложениям всю папку плагинов.
     */
    private void sharePlugin(Plugin plugin) {
        Activity activity = getParentActivity();
        if (activity == null || plugin == null || plugin.path == null) {
            return;
        }
        try {
            File source = new File(plugin.path);
            if (!source.exists()) {
                return;
            }
            File dir = new File(activity.getCacheDir(), "share");
            dir.mkdirs();
            String ext = source.getName().contains(".")
                    ? source.getName().substring(source.getName().lastIndexOf('.'))
                    : ".plugin";
            File copy = new File(dir, plugin.id + ext);
            try (InputStream in = new java.io.FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(copy)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(activity,
                    org.telegram.messenger.ApplicationLoader.getApplicationId() + ".provider", copy);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent,
                    getString(R.string.PluginsShare)));
        } catch (Exception e) {
            FileLog.e("PluginsActivity: cannot share plugin", e);
        }
    }

    /**
     * Включить или выключить плагин.
     *
     * setPluginEnabled возвращает «получилось ли», а не новое состояние,
     * поэтому спрашиваем сам плагин: при неудачной загрузке он останется
     * выключенным, и тумблер должен показать именно это.
     */
    private void togglePlugin(Plugin plugin) {
        PluginsController controller = PluginsController.getInstance();
        if (!controller.isEngineEnabled()) {
            return;
        }
        controller.setPluginEnabled(plugin.id, !plugin.enabled);
        Plugin updated = controller.getPlugin(plugin.id);
        boolean enabled = updated != null && updated.enabled;
        updateRows();
        if (enabled && updated.loadError != null) {
            // Плагин уже падал: покажем, на чём именно, иначе включение
            // выглядит как «щёлкнул и ничего».
            showPluginInfo(updated);
        }
    }

    private void updateRows() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    // ---------- клики ----------

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        PluginsController controller = PluginsController.getInstance();
        if (item.id == ID_ENGINE_TOGGLE) {
            boolean enabling = !controller.isEngineEnabled();
            controller.setEngineEnabled(enabling);
            updateRows();
            if (enabling) {
                // Python поднимается асинхронно (около 240 мс на Pixel 7), а
                // rescanPlugins до его старта выходит рано. Без обновления по
                // готовности пользователь видит пустой список при уже лежащих
                // плагинах, и помогает только выйти и вернуться.
                PythonPluginsEngine.getInstance().ensureStarted(
                        org.telegram.messenger.ApplicationLoader.applicationContext,
                        ok -> AndroidUtilities.runOnUIThread(() -> {
                            if (getParentActivity() != null) {
                                refreshPlugins(true);
                                updateRows();
                            }
                        }));
            }
            return;
        }
        if (item.id == ID_CATALOG) {
            presentFragment(new app.exteraless.plugins.ui.catalog.PluginCatalogActivity());
            return;
        }
        // По карточке кликов не ждём: у неё свои кнопки и свой тумблер.
        // Когда здесь стояло переключение, один тап по тумблеру доходил и до
        // него, и до строки списка — плагин включался и тут же выключался
        // обратно, а в prefs оставалось false при уже загруженном модуле.
    }

    private boolean onItemLongClick(UItem item, View view, int position, float x, float y) {
        Plugin plugin = pluginOf(item);
        if (plugin == null) {
            return false;
        }
        showPluginMenu(plugin);
        return true;
    }

    private Plugin pluginOf(UItem item) {
        if (!(item.object instanceof PluginCell.Model)) {
            return null;
        }
        String id = ((PluginCell.Model) item.object).id;
        return PluginsController.getInstance().getPlugin(id);
    }

    // ---------- диалоги ----------

    private void showPluginInfo(Plugin plugin) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        SpannableStringBuilder message = new SpannableStringBuilder();
        if (!TextUtils.isEmpty(plugin.description)) {
            message.append(com.exteragram.messenger.utils.text.LocaleUtils
                    .fullyFormatText(plugin.description)).append("\n\n");
        }
        message.append(plugin.getSubtitle());
        if (plugin.requirements != null && !plugin.requirements.isEmpty()) {
            message.append('\n').append(getString(R.string.PluginsInfoRequirements))
                    .append(": ").append(TextUtils.join(", ", plugin.requirements));
        }
        if (plugin.loadError != null) {
            message.append('\n').append(plugin.loadError);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(plugin.getDisplayName())
                .setMessage(message)
                .setPositiveButton(getString(R.string.OK), null);
        // Разбираться с падением будет автор плагина в другом чате — отдаём
        // ему traceback целиком, а на экране оставляем короткую строку.
        final String report = plugin.loadDebug != null ? plugin.loadDebug : plugin.loadError;
        if (report != null && !report.isEmpty()) {
            builder.setNeutralButton(getString(R.string.PluginsInstallCopyReport),
                    (dialog, which) -> {
                        AndroidUtilities.addToClipboard(report);
                        BulletinFactory.of(this).createCopyBulletin(
                                getString(R.string.TextCopied)).show();
                    });
        }
        showDialog(builder.create());
    }

    private void showPluginMenu(Plugin plugin) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        PluginsController controller = PluginsController.getInstance();
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Integer> actions = new ArrayList<>();
        if (plugin.hasSettings && plugin.loaded) {
            labels.add(getString(R.string.PluginsMenuOpenSettings));
            actions.add(0);
        }
        if (controller.isDeveloperMode()) {
            labels.add(getString(R.string.PluginsMenuReload));
            actions.add(1);
        }
        // Разрешения — рядом с настройками плагина: это второе, что о плагине
        // хотят узнать после того, что он умеет настраивать.
        labels.add(getString(R.string.PluginPermissions));
        actions.add(4);
        labels.add(getString(R.string.PluginsMenuInfo));
        actions.add(5);
        labels.add(getString(R.string.PluginsMenuCopyId));
        actions.add(2);
        labels.add(getString(R.string.PluginsMenuDelete));
        actions.add(3);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(plugin.getDisplayName());
        builder.setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
            int action = actions.get(which);
            if (action == 0) {
                PythonPluginsEngine.getInstance().openPluginSettings(plugin, this);
            } else if (action == 1) {
                controller.reloadPlugin(plugin.id);
                refreshPlugins(false);
                updateRows();
            } else if (action == 2) {
                AndroidUtilities.addToClipboard(plugin.id);
            } else if (action == 3) {
                showDeleteDialog(plugin);
            } else if (action == 4) {
                presentFragment(new PluginPermissionsActivity(plugin.id));
            } else if (action == 5) {
                showPluginInfo(plugin);
            }
        });
        showDialog(builder.create());
    }

    private void showDeleteDialog(Plugin plugin) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.PluginsMenuDelete));
        builder.setMessage(LocaleController.formatString(R.string.PluginsDeleteConfirm,
                plugin.getDisplayName()));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            PluginsController.getInstance().uninstallPlugin(plugin.id);
            refreshPlugins(false);
            updateRows();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    // ---------- установка из файла ----------

    /** Зовётся и отсюда, и с экрана движка: пункт «Установить из файла» живёт там. */
    static void openPluginPicker(BaseFragment fragment) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            fragment.startActivityForResult(intent, REQUEST_CODE_PICK_PLUGIN);
        } catch (Exception e) {
            FileLog.e("PluginsActivity: no document picker", e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE_PICK_PLUGIN || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        installFromUri(data.getData());
    }

    /** content:// из системного пикера копируем в кэш — движку нужен обычный читаемый файл. */
    private void installFromUri(Uri uri) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        // Расширение обязано пережить копирование: движок по нему отличает
        // .elyx/.eaf (ZIP-архивы) от обычного .py-модуля.
        String name = resolveFileName(activity, uri);
        String ext = ".py";
        for (String candidate : new String[]{".elyx", ".eaf", ".plugin", ".py"}) {
            if (name != null && name.toLowerCase(Locale.ROOT).endsWith(candidate)) {
                ext = candidate;
                break;
            }
        }
        File tmp = PluginInstallHelper.copyToUniqueStaging(activity, uri, ext);
        if (tmp == null) {
            showDialog(new AlertDialog.Builder(activity)
                    .setTitle(getString(R.string.PluginsInstallError))
                    .setMessage(getString(R.string.PluginsInstallReadError))
                    .setPositiveButton(getString(R.string.OK), null)
                    .create());
            return;
        }
        // Через диалог согласия, а не installPlugin напрямую: иначе выбор файла
        // на этом экране выдавал бы плагину все объявленные разрешения молча,
        // в обход единственного места, где пользователь их видит.
        PluginInstallHelper.confirmAndInstall(activity, tmp);
    }

    /** Имя файла за content://-ссылкой; нужно только ради расширения. */
    private static String resolveFileName(Activity activity, Uri uri) {
        try (android.database.Cursor cursor = activity.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception e) {
            FileLog.e("PluginsActivity: cannot resolve file name", e);
        }
        return uri.getLastPathSegment();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Однократное уведомление о плагине, отключённом watchdog'ом после падений.
        PluginsWatchdog watchdog = PluginsController.getInstance().getWatchdog();
        String crashed = watchdog != null ? watchdog.consumeCrashedPlugin() : null;
        if (crashed != null && getParentActivity() != null) {
            showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(getString(R.string.PluginsCrashedTitle))
                    .setMessage(LocaleController.formatString(R.string.PluginsCrashedMessage, crashed))
                    .setPositiveButton(getString(R.string.OK), null)
                    .create());
        }
        refreshPlugins(true);
        updateRows();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom);
            listView.setClipToPadding(false);
        }
    }
}
