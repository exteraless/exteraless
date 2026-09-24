package app.exteraless.plugins;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import app.exteraless.plugins.ui.PluginInstallBottomSheet;
import app.exteraless.plugins.ui.PluginPermissionsActivity;
import java.util.Map;

/**
 * Установка плагина из файла, открытого снаружи: тап по .plugin в чате, файловый
 * менеджер, «Поделиться».
 *
 * Раньше такого пути не было вовсе — система показывала обычный выбор
 * приложения, и поставить плагин было неоткуда, кроме как через
 * «Установить из файла» в настройках. exteraGram объявляет intent-filter на
 * {@code .plugin} и разбирает интент в IntentsController; здесь то же самое,
 * только точка входа — {@code LaunchActivity.handleIntent}.
 */
public final class PluginInstallHelper {

    /** Расширения, которые движок умеет ставить. */
    private static final String[] EXTENSIONS = {
            PluginsConstants.PLUGIN_EXT,       // .plugin
            PluginsConstants.PLUGIN_EXT_ELYX,  // .elyx
            PluginsConstants.PLUGIN_EXT_EAF,   // .eaf
    };

    private PluginInstallHelper() {
    }

    /** Расширение файла из ссылки: сперва имя из ContentResolver, потом сам путь. */
    private static String extensionOf(Context context, Uri uri) {
        String name = null;
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }
        } catch (Throwable ignored) {
            // content://-провайдер может не отдавать метаданные — упадём на путь.
        }
        if (TextUtils.isEmpty(name)) {
            name = uri.getLastPathSegment();
        }
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        name = name.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (name.endsWith(ext)) {
                return ext;
            }
        }
        return null;
    }

    /** Похоже ли содержимое ссылки на файл плагина. */
    public static boolean isPluginUri(Context context, Uri uri) {
        return context != null && uri != null && extensionOf(context, uri) != null;
    }

    /**
     * Тап по документу в чате или в общих файлах.
     *
     * Обязан вызываться ДО встроенного просмотрщика: в этом форке
     * {@code MarkdownUtils} регистрирует {@code .plugin} как исходник Python
     * ({@code addLanguage("python", "py", "pyw", "plugin")}), поэтому файл
     * плагина открывался в подсветке кода, и установить его было неоткуда.
     * У exteraGram порядок такой же — {@code isPlugin} проверяется раньше
     * {@code canPreviewDocument} и {@code MarkdownParser.isMarkdown}
     * (SharedMediaLayout.java:7999).
     *
     * @return true, если сообщение содержит плагин и показан диалог установки.
     */
    public static boolean handleMessageTap(Activity activity, MessageObject message) {
        if (activity == null || message == null) {
            return false;
        }
        String name = message.getDocumentName();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        boolean isPlugin = false;
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext)) {
                isPlugin = true;
                break;
            }
        }
        if (!isPlugin) {
            return false;
        }
        File file = FileLoader.getInstance(UserConfig.selectedAccount)
                .getPathToMessage(message.messageOwner);
        if (file == null || !file.exists() || file.length() == 0) {
            // Ещё не скачан — пусть отработает штатная загрузка.
            return false;
        }
        AndroidUtilities.runOnUIThread(() -> PluginsController.getInstance()
                .showInstallDialog(null, file.getAbsolutePath(), false));
        return true;
    }

    /**
     * Обработать открытие файла плагина.
     *
     * @return true, если ссылка вела на плагин и обработка взята на себя.
     */
    public static boolean handleViewIntent(Activity activity, Uri uri) {
        if (activity == null || uri == null) {
            return false;
        }
        String ext = extensionOf(activity, uri);
        if (ext == null) {
            return false;
        }
        File cached = copyToCache(activity, uri, ext);
        if (cached == null) {
            AndroidUtilities.runOnUIThread(() -> showError(activity,
                    LocaleController.getString(R.string.PluginsInstallReadError)));
            return true;
        }
        AndroidUtilities.runOnUIThread(() -> PluginsController.getInstance()
                .showInstallDialog(null, cached.getAbsolutePath(), false));
        return true;
    }

    private static File copyToCache(Activity activity, Uri uri, String ext) {
        File target = new File(activity.getCacheDir(), "plugin_incoming" + ext);
        // file:// ContentResolver не открывает — интент из файлового менеджера
        // приходил именно так и упирался в «не удалось прочитать файл».
        // Такой путь читаем напрямую, если он доступен процессу.
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File direct = new File(uri.getPath());
            if (direct.canRead()) {
                try (InputStream in = new java.io.FileInputStream(direct);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    return target;
                } catch (Throwable t) {
                    FileLog.e("PluginInstallHelper: cannot read " + direct, t);
                    return null;
                }
            }
        }
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                return null;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return target;
        } catch (Throwable t) {
            FileLog.e("PluginInstallHelper: cannot read " + uri, t);
            return null;
        }
    }

    /**
     * Спросить подтверждение, показав то, что удалось прочитать из метаданных
     * (имя, версия, автор) и что плагин просит уметь. Метаданные читаются
     * AST-разбором, без выполнения кода плагина, — до подтверждения ничего
     * чужого не запускается.
     *
     * Нажатие «Установить» и есть согласие на перечисленное: оно пишется
     * в prefs (PluginPermissions.setGranted) прежде установки. Отказ —
     * установки нет, ничего не записывается.
     */
    /**
     * Показать согласие и установить. Публичный, потому что через него обязаны
     * идти ВСЕ пути установки: тап по файлу в чате, внешний интент и выбор
     * файла на экране плагинов. Установка мимо этого метода означала бы выдачу
     * разрешений без ведома пользователя.
     */
    public static void confirmAndInstall(Activity activity, File file) {
        confirmAndInstall(activity, new com.exteragram.messenger.plugins.ui.components
                .InstallPluginBottomSheet.PluginInstallParams(file.getAbsolutePath(), false));
    }

    public static void confirmAndInstall(Activity activity,
                                         com.exteragram.messenger.plugins.ui.components
                                                 .InstallPluginBottomSheet.PluginInstallParams params) {
        final File file = params.toFile();
        if (file == null) {
            return;
        }
        PluginsController controller = PluginsController.getInstance();
        if (!controller.isEngineEnabled()) {
            // Не отказываем молча: движок выключен по умолчанию, и пользователю
            // иначе неоткуда узнать, где его включить.
            new AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString(R.string.PluginsInstallTitle))
                    .setMessage(LocaleController.getString(R.string.PluginsEngineDisabledHint))
                    .setPositiveButton(LocaleController.getString(R.string.PluginsEngineEnableAndInstall),
                            (dialog, which) -> {
                                controller.setEngineEnabled(true);
                                confirmAndInstall(activity, params);
                            })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
            return;
        }
        controller.readMetadataAsync(file, plugin -> {
            // Разбор исходника — на фоновом потоке: это чтение файла и AST.
            final Map<String, List<String>> capabilities = PluginCapabilityScan.scan(file);
            final Map<String, List<String>> offered = offeredPermissions(plugin, capabilities);
            AndroidUtilities.runOnUIThread(() -> {
                if (activity.isFinishing()) {
                    return;
                }
                showConsentSheet(activity, file, params, plugin, offered, capabilities);
            });
        });
    }

    /**
     * Что показать галочками.
     *
     * Обычный плагин спрашивает по уликам разбора. У обфусцированного улик нет
     * по построению — имена переписаны, ни один маркер не совпадает, — и
     * короткий список читался бы как «плагин почти ничего не умеет». Поэтому
     * для него перечисляем всё: разбор не смог сказать ничего, решает человек.
     */
    private static Map<String, List<String>> offeredPermissions(
            Plugin plugin, Map<String, List<String>> capabilities) {
        final boolean obfuscated = PluginCapabilityScan.isObfuscated(capabilities);
        if (!capabilities.isEmpty() && !obfuscated) {
            return capabilities;
        }
        List<String> fallback = !obfuscated && plugin != null && plugin.permissionsDeclared
                ? PluginPermissions.getRequested(plugin)
                : PluginPermissions.REQUESTABLE;
        Map<String, List<String>> offered = new LinkedHashMap<>();
        for (String permission : PluginPermissions.sanitize(fallback)) {
            offered.put(permission, PluginCapabilityScan.evidenceOf(capabilities, permission));
        }
        if (obfuscated) {
            offered.put(PluginCapabilityScan.KEY_OBFUSCATION,
                    PluginCapabilityScan.obfuscationEvidence(capabilities));
        }
        return offered;
    }

    /**
     * Лист установки: карточка плагина и галочки найденного.
     *
     * Разбор перечисляет не `__permissions__` (их объявляет меньшинство из 512
     * плагинов двух каталогов), а то, что нашлось в исходнике; каждая находка —
     * галочка, и у каждой раскрывашка с именами, по которым она нашлась.
     *
     * Галочки по умолчанию сняты. Плагин ставится ровно с тем, что отметили:
     * ничего не отметили — уровень «Изоляция», отметили что-то — «Ограниченный»,
     * отметили переписывание кода — «Доверенный».
     */
    private static void showConsentSheet(Activity activity, File file,
                                         com.exteragram.messenger.plugins.ui.components
                                                 .InstallPluginBottomSheet.PluginInstallParams params,
                                         Plugin plugin,
                                         Map<String, List<String>> offered,
                                         Map<String, List<String>> capabilities) {
        new PluginInstallBottomSheet(activity, file, params, plugin, offered,
                (granted, enableAfterInstall) -> {
                    grantOnConsent(plugin, granted);
                    if (plugin != null && plugin.id != null) {
                        // Разбор нужен и потом — на экране разрешений, где
                        // спрашивают «почему приложение решило, что плагину
                        // нужна сеть». Перечитывать файл ради этого нельзя.
                        PluginCapabilityScan.store(plugin.id, capabilities);
                    }
                    install(activity, file, plugin != null ? plugin.id : null, enableAfterInstall);
                }).show();
    }

    /**
     * Зафиксировать выбор пользователя: отмеченные разрешения и уровень под них.
     *
     * Запись делается всегда, даже пустая: именно её наличие отличает плагин,
     * поставленный при модели разрешений, от старого, которому иначе достался
     * бы режим совместимости со всеми правами сразу.
     */
    private static void grantOnConsent(Plugin plugin, List<String> granted) {
        if (plugin == null || TextUtils.isEmpty(plugin.id)) {
            return;
        }
        PluginPermissions.setGranted(plugin.id, granted);
        final int level;
        if (granted.isEmpty()) {
            level = PluginTrustLevel.ISOLATED;
        } else if (granted.contains(PluginPermissions.HOOKS)
                || granted.contains(PluginPermissions.NATIVE)) {
            // Хуки живут только на доверенном уровне: там про это и сказано.
            level = PluginTrustLevel.TRUSTED;
        } else {
            level = PluginTrustLevel.GATED;
        }
        PluginTrustLevel.setLevel(plugin.id, level);
    }

    /**
     * @param enableAfterInstall галочка «включить после установки» из листа:
     *                           плагин без включения молчит, и без неё после
     *                           установки надо идти его искать и включать.
     */
    private static void install(Activity activity, File file, String consentedId,
                                boolean enableAfterInstall) {
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setMessage(LocaleController.getString(R.string.PluginsInstalling));
        progress.setCanCancel(false);
        progress.show();
        final boolean update = consentedId != null
                && PluginsController.getInstance().getPlugin(consentedId) != null;
        PluginsController.getInstance().installPlugin(file, enableAfterInstall, (ok, error, plugin) ->
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        progress.dismiss();
                    } catch (Throwable ignored) {
                    }
                    if (activity.isFinishing()) {
                        return;
                    }
                    if (!ok) {
                        Plugin registered = plugin;
                        if (registered == null && consentedId != null) {
                            registered = PluginsController.getInstance().getPlugin(consentedId);
                        }
                        if (enableAfterInstall && isPermissionDenial(error)
                                && registered != null && registered.id != null) {
                            PluginsController.getInstance().setPluginEnabled(registered.id, true);
                            showError(activity, humanError(error, consentedId),
                                    plugin == null ? null : plugin.loadDebug);
                            return;
                        }
                        // Установка сорвалась — согласие, записанное авансом, ни к чему
                        // не относится. Стираем, но только если плагина и правда нет:
                        // при перезаписи существующего файл мог уже подмениться.
                        if (consentedId != null
                                && PluginsController.getInstance().getPlugin(consentedId) == null) {
                            PluginPermissions.clear(consentedId);
                        }
                        showError(activity, humanError(error, consentedId),
                                plugin == null ? null : plugin.loadDebug);
                        return;
                    }
                    if (enableAfterInstall && plugin != null && plugin.id != null) {
                        PluginsController.getInstance().setPluginEnabled(plugin.id, true);
                    }
                    showInstalled(plugin, update);
                }));
    }

    /**
     * Ошибка установки человеческим языком.
     *
     * Плагин, которому не хватило разрешения, падал с текстом вида
     * «PermissionError: plugin 'quotecreate' is not allowed to modify files
     * (/storage/.../cache/quotecreate): missing the 'files' permission» — это
     * сообщение для разработчика, а не для того, кто ставит плагин. Разбираем
     * его обратно в понятное: чего не хватило и что с этим делать.
     *
     * Остальные ошибки оставляем как есть: там текст обычно и есть суть
     * (битый архив, нет метаданных), а прятать её было бы хуже.
     */
    private static boolean isPermissionDenial(CharSequence error) {
        if (error == null) {
            return false;
        }
        String text = error.toString();
        return text.contains("PermissionError") || text.contains("missing the");
    }

    private static CharSequence humanError(CharSequence error, String pluginId) {
        if (error == null) {
            return LocaleController.getString(R.string.PluginsInstallError);
        }
        if (!isPermissionDenial(error)) {
            return error;
        }
        String text = error.toString();
        String permission = null;
        for (String candidate : PluginPermissions.ALL) {
            if (text.contains("'" + candidate + "'")) {
                permission = candidate;
                break;
            }
        }
        if (permission == null) {
            return LocaleController.getString(R.string.PluginsInstallDeniedGeneric);
        }
        return LocaleController.formatString(R.string.PluginsInstallDenied,
                PluginPermissionsActivity.titleOf(permission));
    }

    /**
     * Итог установки — плашкой, а не диалогом: лист уже закрылся, и ещё одно
     * окно поверх списка человек закрывает не читая.
     */
    private static void showInstalled(Plugin plugin, boolean update) {
        org.telegram.ui.ActionBar.BaseFragment fragment =
                org.telegram.ui.LaunchActivity.getSafeLastFragment();
        CharSequence text = LocaleController.formatString(update ? R.string.PluginsUpdated : R.string.PluginsInstalled,
                plugin != null ? plugin.getDisplayName() : "");
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        org.telegram.ui.Components.BulletinFactory.of(fragment)
                .createSimpleBulletin(R.raw.contact_check, text)
                .show();
    }

    private static void showError(Activity activity, CharSequence message) {
        showError(activity, message, null);
    }

    /**
     * Ошибка установки с кнопкой «копировать».
     *
     * Текст в диалоге короткий и для человека, а разбираться с плагином будет
     * его автор в другом чате — ему нужен traceback и версии. Поэтому полный
     * отчёт не показывается, а кладётся в буфер по кнопке.
     */
    private static void showError(Activity activity, CharSequence message, String debug) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.PluginsInstallError))
                .setMessage(message)
                .setPositiveButton(LocaleController.getString(R.string.OK), null);
        final String report = debug != null && !debug.isEmpty()
                ? debug : (message == null ? null : message.toString());
        if (report != null && !report.isEmpty()) {
            builder.setNeutralButton(LocaleController.getString(R.string.PluginsInstallCopyReport),
                    (dialog, which) -> {
                        AndroidUtilities.addToClipboard(report);
                        if (!activity.isFinishing()) {
                            Toast.makeText(activity, LocaleController.getString(R.string.TextCopied),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
        }
        builder.show();
    }
}
