package app.exteraless.plugins.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SlideChooseView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.exteraless.plugins.Plugin;
import app.exteraless.plugins.PluginCapabilityScan;
import app.exteraless.plugins.PluginPermissions;
import app.exteraless.plugins.PluginTrustLevel;
import app.exteraless.plugins.PluginsController;

/**
 * Разрешения установленного плагина: что он просил и что ему сейчас можно.
 *
 * Не перенос: у exteraGram exteraGram 12.9.0 модели разрешений нет вовсе, ссылаться
 * не на что. Экран собран соседом к {@link PluginsActivity} — тот же
 * UniversalRecyclerView, те же ячейки.
 *
 * Тексты пишем последствием, а не именем ключа: человек решает по тому, что
 * плагин сможет с ним сделать, а не по строке «messages.send».
 */
public class PluginPermissionsActivity extends BaseFragment {

    /** id строк-переключателей; индекс в {@link #switchable} = id минус эта база. */
    private static final int ID_PERM_BASE = 100;
    /** Ниже базы переключателей: id строк-переключателей начинаются со 100. */
    private static final int ID_ACTIVITY_LOG = 1;
    private static final int ID_OBFUSCATION = 2;
    private static final int ID_UNSAFE = 3;

    private final String pluginId;

    private UniversalRecyclerView listView;
    /** Блок «код обфусцирован»: держим один, чтобы список не переанимировал строку. */
    private android.view.View obfuscationView;
    private android.view.View unsafeView;
    /** Какие строки раскрыты — переживает пересборку списка. */
    private final java.util.Set<String> expanded = new java.util.HashSet<>();
    /** Вьюхи строк по разрешению: новая вьюха на каждую пересборку = анимация строки. */
    private final java.util.HashMap<String, PluginPermissionCell> cells = new java.util.HashMap<>();
    private SlideChooseView slider;
    /** Разрешения с переключателями в порядке отрисовки — по ним ищем ключ по id строки. */
    private final List<String> switchable = new ArrayList<>();

    public PluginPermissionsActivity(String pluginId) {
        this.pluginId = pluginId;
    }

    // ---------- тексты ----------

    /** Заголовок строки: что плагин сможет сделать. */
    public static CharSequence titleOf(String perm) {
        if (perm == null) {
            return "";
        }
        switch (perm) {
            case PluginPermissions.UI: return getString(R.string.PluginPermUi);
            case PluginPermissions.MESSAGES_READ: return getString(R.string.PluginPermMessagesRead);
            case PluginPermissions.MESSAGES_SEND: return getString(R.string.PluginPermMessagesSend);
            case PluginPermissions.NETWORK: return getString(R.string.PluginPermNetwork);
            case PluginPermissions.FILES: return getString(R.string.PluginPermFiles);
            case PluginPermissions.INTENTS: return getString(R.string.PluginPermIntents);
            case PluginPermissions.SETTINGS: return getString(R.string.PluginPermSettings);
            case PluginPermissions.HOOKS: return getString(R.string.PluginPermHooks);
            case PluginPermissions.NATIVE: return getString(R.string.PluginPermNative);
            // Неизвестный ключ до интерфейса не доходит (метапарсер бракует такие
            // метаданные), но пусть строка будет хоть какая-то, а не пустая.
            default: return PluginPermissions.describe(perm);
        }
    }

    /**
     * Короткая подпись для диалога установки.
     *
     * Полная («Send, edit and delete messages as you») в строке с уликой не
     * помещается: обрезается и она, и улика, и человек не видит ни того ни
     * другого. Здесь достаточно назвать область, подробности — на экране
     * разрешений.
     */
    public static CharSequence shortTitleOf(String perm) {
        if (perm == null) {
            return "";
        }
        switch (perm) {
            case PluginPermissions.MESSAGES_READ: return getString(R.string.PluginPermShortMessagesRead);
            case PluginPermissions.MESSAGES_SEND: return getString(R.string.PluginPermShortMessagesSend);
            case PluginPermissions.NETWORK: return getString(R.string.PluginPermShortNetwork);
            case PluginPermissions.FILES: return getString(R.string.PluginPermShortFiles);
            case PluginPermissions.INTENTS: return getString(R.string.PluginPermShortIntents);
            case PluginPermissions.SETTINGS: return getString(R.string.PluginPermShortSettings);
            case PluginPermissions.HOOKS: return getString(R.string.PluginPermShortHooks);
            case PluginPermissions.NATIVE: return getString(R.string.PluginPermShortNative);
            default: return titleOf(perm);
        }
    }

    /** Пояснение под заголовком: чем это оборачивается. */
    public static CharSequence infoOf(String perm) {
        if (perm == null) {
            return "";
        }
        switch (perm) {
            case PluginPermissions.UI: return getString(R.string.PluginPermUiInfo);
            case PluginPermissions.MESSAGES_READ: return getString(R.string.PluginPermMessagesReadInfo);
            case PluginPermissions.MESSAGES_SEND: return getString(R.string.PluginPermMessagesSendInfo);
            case PluginPermissions.NETWORK: return getString(R.string.PluginPermNetworkInfo);
            case PluginPermissions.FILES: return getString(R.string.PluginPermFilesInfo);
            case PluginPermissions.INTENTS: return getString(R.string.PluginPermIntentsInfo);
            case PluginPermissions.SETTINGS: return getString(R.string.PluginPermSettingsInfo);
            case PluginPermissions.HOOKS: return getString(R.string.PluginPermHooksInfo);
            case PluginPermissions.NATIVE: return getString(R.string.PluginPermNativeInfo);
            default: return "";
        }
    }

    /**
     * Есть ли в дереве точка, где отказ действительно что-то останавливает.
     *
     * network сюда больше не входит: с audit-гейтом (extera_utils/audit_gate.py)
     * отказ отменяет само действие — socket.connect, getaddrinfo, urllib.Request,
     * — а {@link app.exteraless.plugins.PluginSinkGate} закрывает Java-сторону
     * (java.net.URL, Socket.connect и резолв этих классов рефлексией). Проверено
     * на устройстве: connect отменяется исключением из хука.
     *
     * settings остаётся невыполнимым: API записи настроек приложения в Python-SDK
     * нет вовсе — PythonBridge.setSetting пишет только собственный
     * plugin_settings_&lt;id&gt;. Тумблер, который ничего не меняет, хуже
     * отсутствующей строки, поэтому такой ключ показываем текстом.
     */
    public static boolean isEnforced(String perm) {
        return !PluginPermissions.SETTINGS.equals(perm);
    }

    /**
     * Что показывать как запрошенное.
     *
     * Плагин, ничего не объявивший, работает в режиме совместимости и по факту
     * получает всё (PluginPermissions.getEffective) — значит и на экране у него
     * должен быть весь список, иначе экран врёт: «просит ничего», а может всё.
     */
    public static List<String> requestedFor(Plugin plugin) {
        if (plugin == null) {
            return new ArrayList<>();
        }
        final Map<String, List<String>> scan = PluginCapabilityScan.load(plugin.id);
        // Обфусцированному плагину диалог установки предлагает весь список:
        // объявленному в таком коде верить не на чем, и отзывать выданное надо
        // там же, где выдавали.
        List<String> requested = plugin.permissionsDeclared && !PluginCapabilityScan.isObfuscated(scan)
                ? PluginPermissions.getRequested(plugin)
                : new ArrayList<>(PluginPermissions.REQUESTABLE);
        // Объявленному верить целиком нельзя: диалог установки спрашивает по
        // уликам разбора, и без этого объединения выданное там разрешение
        // потом негде было бы увидеть и отозвать.
        for (String perm : PluginCapabilityScan.ordered(scan)) {
            if (!requested.contains(perm)) {
                requested.add(perm);
            }
        }
        return PluginPermissions.sanitize(requested);
    }

    // ---------- экран ----------

    @Override
    public View createView(Context context) {
        cells.clear();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PluginPermissions));
        Plugin plugin = PluginsController.getInstance().getPlugin(pluginId);
        if (plugin != null) {
            actionBar.setSubtitle(plugin.getDisplayName());
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        contentView.addView(listView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        fragmentView = contentView;
        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        switchable.clear();
        Plugin plugin = PluginsController.getInstance().getPlugin(pluginId);
        if (plugin == null) {
            // Плагин удалили из соседнего экрана, пока этот был открыт.
            items.add(UItem.asShadow(getString(R.string.PluginPermissionsGone)));
            return;
        }
        // Плагин мог быть поставлен до того, как появился разбор исходника:
        // тогда улик нет и раскрывать под строками нечего. Разберём сейчас.
        PluginCapabilityScan.ensureScanned(plugin, () -> {
            if (listView != null) {
                listView.adapter.update(true);
            }
        });
        boolean legacy = !plugin.permissionsDeclared;
        List<String> requested = requestedFor(plugin);
        int level = PluginTrustLevel.getLevel(pluginId);

        // Уровень идёт первым: он главнее отдельных разрешений и определяет,
        // какие из них вообще можно выдать.
        items.add(UItem.asHeader(getString(R.string.PluginLevelHeader)));
        items.add(UItem.asCustom(levelSlider(level)));
        items.add(UItem.asShadow(getString(levelFooter(level))));

        items.add(UItem.asHeader(getString(R.string.PluginPermissionsHeader)));
        if (requested.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.PluginPermissionsNothing)));
        }

        List<String> unenforced = new ArrayList<>();
        boolean hooks = false;
        // Улики разбора: по ним видно, откуда разрешение вообще взялось.
        // Читаются из записанного при установке, файл заново не разбирается.
        final Map<String, List<String>> capabilities = PluginCapabilityScan.load(pluginId);
        if (PluginCapabilityScan.isObfuscated(capabilities)) {
            if (obfuscationView == null) {
                android.widget.FrameLayout frame = new android.widget.FrameLayout(getContext());
                frame.addView(PluginInstallSheet.createObfuscationWarning(getContext(),
                                PluginCapabilityScan.obfuscationEvidence(capabilities)),
                        org.telegram.ui.Components.LayoutHelper.createFrame(
                                org.telegram.ui.Components.LayoutHelper.MATCH_PARENT,
                                org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT,
                                android.view.Gravity.TOP, 16, 4, 16, 12));
                obfuscationView = frame;
            }
            items.add(UItem.asCustom(ID_OBFUSCATION, obfuscationView));
        }
        if (PluginPermissions.isUnsafeMode()) {
            if (unsafeView == null) {
                android.widget.FrameLayout frame = new android.widget.FrameLayout(getContext());
                frame.addView(PluginInstallSheet.createWarningBox(getContext(),
                                getString(R.string.PluginsUnsafeMode),
                                getString(R.string.PluginsUnsafeModeActive), null),
                        org.telegram.ui.Components.LayoutHelper.createFrame(
                                org.telegram.ui.Components.LayoutHelper.MATCH_PARENT,
                                org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT,
                                android.view.Gravity.TOP, 16, 4, 16, 12));
                unsafeView = frame;
            }
            items.add(UItem.asCustom(ID_UNSAFE, unsafeView));
        }
        List<String> enforced = new ArrayList<>();
        for (String perm : requested) {
            if (!isEnforced(perm)) {
                unenforced.add(perm);
                continue;
            }
            enforced.add(perm);
        }
        for (int i = 0; i < enforced.size(); i++) {
            final String perm = enforced.get(i);
            hooks |= PluginPermissions.isDangerous(perm);
            final boolean allowed = PluginTrustLevel.allows(level, perm);
            // Вьюха на разрешение переживает пересборку списка: с новой каждый
            // раз DiffUtil считает строку удалённой и вставленной заново
            // (UItem.itemEquals сравнивает view), и RecyclerView проигрывает
            // анимацию — строка дёргается по высоте на каждое действие.
            PluginPermissionCell cell = cells.get(perm);
            if (cell == null) {
                cell = new PluginPermissionCell(getContext(), PluginPermissionCell.TYPE_SWITCH);
                cells.put(perm, cell);
            }
            final PluginPermissionCell row = cell;
            cell.set(perm, titleOf(perm), infoOf(perm),
                    PluginCapabilityScan.evidenceOf(capabilities, perm),
                    i < enforced.size() - 1);
            cell.setChecked(allowed && PluginPermissions.has(pluginId, perm), false);
            cell.setEnabledState(allowed);
            cell.setExpanded(expanded.contains(perm), false);
            cell.setOnExpandChanged(() -> {
                if (row.isExpanded()) {
                    expanded.add(perm);
                } else {
                    expanded.remove(perm);
                }
            });
            cell.setOnToggle(!allowed ? null : () -> {
                togglePermission(perm);
                row.setChecked(PluginPermissions.has(pluginId, perm), true);
            });
            items.add(UItem.asCustom(ID_PERM_BASE + switchable.size(), cell));
            switchable.add(perm);
        }

        // Под списком — одна строка, а не четыре подряд: три абзаца сносок
        // подряд читаются как простыня, и их перестают читать вовсе.
        // Порядок важности: опасное предупреждение, потом происхождение
        // списка, потом то, что приложение не умеет ограничить.
        CharSequence note = null;
        if (hooks) {
            note = getString(R.string.PluginPermissionsHooksWarning);
        } else if (legacy) {
            note = getString(R.string.PluginPermissionsLegacyInfo);
        } else if (!unenforced.isEmpty()) {
            List<CharSequence> titles = new ArrayList<>();
            for (String perm : unenforced) {
                titles.add(titleOf(perm));
            }
            note = LocaleController.formatString(
                    R.string.PluginPermissionsUnenforcedInfo, TextUtils.join(", ", titles));
        }
        items.add(UItem.asShadow(note));

        // Что плагин делал по факту — рядом с тем, что он просил.
        items.add(UItem.asHeader(getString(R.string.PluginActivityHeader)));
        items.add(UItem.asButton(ID_ACTIVITY_LOG, R.drawable.msg_log,
                getString(R.string.PluginActivityLog)));
        items.add(UItem.asShadow(getString(R.string.PluginActivityLogInfo)));
    }

    /**
     * Ползунок уровня: три положения с подписями, как у размера стикеров.
     *
     * Подписи — не только украшение: тап по подписи открывает всплывающее
     * объяснение уровня и предлагает выбрать его. Иначе объяснять три уровня
     * пришлось бы тремя абзацами прямо в списке, а он и без того длинный.
     * Смена положения самого ползунка меняет уровень сразу.
     */
    private View levelSlider(int level) {
        if (slider == null) {
            slider = new SlideChooseView(getContext(), getResourceProvider()) {
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    // Подписи нарисованы в верхней трети (SlideChooseView.onDraw:
                    // базовая линия dp(28) при высоте dp(74)), полоса — ниже.
                    if (event.getAction() == MotionEvent.ACTION_UP
                            && event.getY() < AndroidUtilities.dp(34)) {
                        showLevelInfo(labelIndexAt(this, event.getX()), this);
                        return true;
                    }
                    return super.onTouchEvent(event);
                }
            };
            slider.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            slider.setCallback(index -> {
                if (index == PluginTrustLevel.getLevel(pluginId)) {
                    return;
                }
                PluginTrustLevel.setLevel(pluginId, index);
                applyNow();
                if (listView != null) {
                    listView.adapter.update(true);
                }
            });
        }
        slider.setOptions(level,
                getString(R.string.PluginLevelIsolated),
                getString(R.string.PluginLevelGated),
                getString(R.string.PluginLevelTrusted));
        return slider;
    }

    /**
     * Подпись под пальцем. Считаем по равномерной сетке между боковыми
     * отступами — ровно так же, как SlideChooseView расставляет кружки
     * (onDraw: sideSide + шаг * индекс).
     */
    private static int labelIndexAt(View view, float x) {
        int side = AndroidUtilities.dp(22);
        int span = Math.max(1, view.getMeasuredWidth() - side * 2);
        int index = Math.round((x - side) / (span / 2f));
        return Math.max(PluginTrustLevel.ISOLATED, Math.min(PluginTrustLevel.TRUSTED, index));
    }

    private void showLevelInfo(int level, View anchor) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        int start = text.length();
        text.append(getString(levelTitle(level)));
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append("\n").append(getString(levelInfo(level)));

        ItemOptions options = ItemOptions.makeOptions(this, anchor)
                .addText(text, 13);
        if (level != PluginTrustLevel.getLevel(pluginId)) {
            options.add(R.drawable.msg_select, getString(R.string.PluginLevelApply), () -> {
                PluginTrustLevel.setLevel(pluginId, level);
                applyNow();
                if (listView != null) {
                    listView.adapter.update(true);
                }
            });
        }
        options.setGravity(Gravity.LEFT).show();
    }

    private static int levelTitle(int level) {
        switch (level) {
            case PluginTrustLevel.ISOLATED: return R.string.PluginLevelIsolated;
            case PluginTrustLevel.TRUSTED: return R.string.PluginLevelTrusted;
            default: return R.string.PluginLevelGated;
        }
    }

    private static int levelInfo(int level) {
        switch (level) {
            case PluginTrustLevel.ISOLATED: return R.string.PluginLevelIsolatedInfo;
            case PluginTrustLevel.TRUSTED: return R.string.PluginLevelTrustedInfo;
            default: return R.string.PluginLevelGatedInfo;
        }
    }

    /**
     * Подвал уровня.
     *
     * На «Доверенном» текст зависит от того, выдано ли переписывание кода:
     * пока оно выключено, остальные переключатели работают как обычно, и
     * называть их «вежливостью» было бы неправдой. Уровень лишь разрешает
     * выдать хуки — сам по себе он ничего не отменяет.
     */
    private int levelFooter(int level) {
        switch (level) {
            case PluginTrustLevel.ISOLATED:
                return R.string.PluginLevelIsolatedFooter;
            case PluginTrustLevel.TRUSTED:
                return PluginPermissions.has(pluginId, PluginPermissions.HOOKS)
                        || PluginPermissions.has(pluginId, PluginPermissions.NATIVE)
                        ? R.string.PluginLevelTrustedFooter
                        : R.string.PluginLevelTrustedFooterIdle;
            default:
                return R.string.PluginLevelGatedFooter;
        }
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ACTIVITY_LOG) {
            presentFragment(new PluginActivityLogActivity(pluginId));
            return;
        }
        int index = item.id - ID_PERM_BASE;
        if (index < 0 || index >= switchable.size()) {
            return;
        }
        togglePermission(switchable.get(index));
    }

    /**
     * Выдать или забрать разрешение.
     *
     * Список после этого не пересобираем: строка сама показывает новое
     * состояние, а пересборка схлопнула бы раскрытые улики соседних строк.
     */
    private void togglePermission(String perm) {
        if (!PluginTrustLevel.allows(pluginId, perm)) {
            // Сюда клик не доходит: строка нарисована выключенной, а
            // RecyclerListView не зовёт обработчик для таких (isEnabled,
            // UniversalAdapter:1171). Проверка на случай, если это изменится:
            // уровень не должен обходиться через интерфейс.
            return;
        }
        if (PluginPermissions.has(pluginId, perm)) {
            PluginPermissions.revoke(pluginId, perm);
        } else {
            PluginPermissions.grant(pluginId, perm);
        }
        if (PluginPermissions.isDangerous(perm) && listView != null) {
            // Единственное разрешение, от которого меняется текст под уровнем:
            // с выданными хуками остальные переключатели уже не соблюсти.
            listView.adapter.update(true);
        }
        applyNow();
    }

    /**
     * Отзыв обязан подействовать сразу, а не «после перезапуска приложения».
     *
     * Проверки стоят на регистрации хуков и обработчиков (PluginServices.hookMethod,
     * registerFileHandler, registerIntentHandler, PluginsController.registerRequestHook),
     * а не на каждом срабатывании: уже зарегистрированный хук отзыв сам по себе не
     * снимает. Поэтому загруженный плагин перезапускаем — reloadPlugin снимает его
     * хуки (unregisterPluginHooks) и грузит модуль заново, уже сквозь новые проверки.
     */
    private void applyNow() {
        PluginsController controller = PluginsController.getInstance();
        Plugin plugin = controller.getPlugin(pluginId);
        // Раньше здесь стояло условие plugin.loaded, и это било по самому
        // частому случаю: плагин не загрузился ИМЕННО потому, что ему не
        // хватало разрешения. Пользователь выдавал недостающее — и ничего не
        // происходило, приходилось жать «Перезагрузить» вручную.
        // reloadPlugin умеет и то и другое: снимет хуки, если загружен, и
        // загрузит заново в любом случае.
        if (plugin == null) {
            return;
        }
        if (!plugin.enabled) {
            if (getContext() != null) {
                BulletinFactory.of(this)
                        .createSimpleBulletin(R.raw.info,
                                getString(R.string.PluginPermissionsEnableFirst))
                        .show();
            }
            return;
        }
        controller.reloadPlugin(pluginId, () -> {
            if (getContext() != null) {
                BulletinFactory.of(this)
                        .createSimpleBulletin(R.raw.info, getString(R.string.PluginPermissionsApplied))
                        .show();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }
}
