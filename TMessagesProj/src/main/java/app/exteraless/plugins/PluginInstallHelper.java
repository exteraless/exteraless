package app.exteraless.plugins;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import app.exteraless.plugins.ui.PluginInstallSheet;
import app.exteraless.plugins.ui.PluginPermissionsActivity;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public static final long MAX_PLUGIN_BYTES = 64L * 1024L * 1024L;

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
        AndroidUtilities.runOnUIThread(() -> confirmAndInstall(activity, file));
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
        File cached = copyToUniqueStaging(activity, uri, ext);
        if (cached == null) {
            AndroidUtilities.runOnUIThread(() -> showError(activity,
                    LocaleController.getString(R.string.PluginsInstallReadError)));
            return true;
        }
        AndroidUtilities.runOnUIThread(() -> confirmAndInstall(activity, cached));
        return true;
    }

    public static File copyToUniqueStaging(Context context, Uri uri, String ext) {
        if (context == null || uri == null || !isSupportedStagingExtension(ext)) {
            return null;
        }
        File directory = new File(context.getCacheDir(), "plugin_install");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        File target = null;
        try {
            target = File.createTempFile("incoming_", ext.toLowerCase(Locale.ROOT), directory);
            InputStream input;
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                input = new java.io.FileInputStream(new File(uri.getPath()));
            } else {
                input = context.getContentResolver().openInputStream(uri);
            }
            if (input == null) {
                target.delete();
                return null;
            }
            try (InputStream in = input; FileOutputStream out = new FileOutputStream(target)) {
                copyBounded(in, out);
            }
            return target;
        } catch (Throwable t) {
            if (target != null) {
                target.delete();
            }
            FileLog.e("PluginInstallHelper: cannot stage " + uri, t);
            return null;
        }
    }

    private static boolean isSupportedStagingExtension(String ext) {
        if (ext == null) {
            return false;
        }
        String normalized = ext.toLowerCase(Locale.ROOT);
        return PluginsConstants.PLUGIN_EXT.equals(normalized)
                || PluginsConstants.PLUGIN_EXT_PY.equals(normalized)
                || PluginsConstants.PLUGIN_EXT_ELYX.equals(normalized)
                || PluginsConstants.PLUGIN_EXT_EAF.equals(normalized);
    }

    private static String stagingExtensionOf(File file) {
        if (file == null) {
            return null;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        String[] supported = {
                PluginsConstants.PLUGIN_EXT,
                PluginsConstants.PLUGIN_EXT_PY,
                PluginsConstants.PLUGIN_EXT_ELYX,
                PluginsConstants.PLUGIN_EXT_EAF
        };
        for (String ext : supported) {
            if (name.endsWith(ext)) {
                return ext;
            }
        }
        return null;
    }

    private static File immutableInstallSnapshot(Context context, File source) {
        if (context == null || source == null) {
            return null;
        }
        try {
            File managed = new File(context.getCacheDir(), "plugin_install").getCanonicalFile();
            File parent = source.getParentFile();
            if (parent != null && managed.equals(parent.getCanonicalFile())) {
                return source;
            }
        } catch (IOException ignored) {
        }
        String ext = stagingExtensionOf(source);
        return ext == null ? null : copyToUniqueStaging(context, Uri.fromFile(source), ext);
    }

    private static void copyBounded(InputStream in, FileOutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > MAX_PLUGIN_BYTES) {
                throw new IOException("plugin exceeds " + MAX_PLUGIN_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
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
        confirmAndInstall(activity, file, null);
    }

    public static void confirmAndInstall(Activity activity, File file,
                                         PluginsController.InstallCallback completion) {
        PluginsController controller = PluginsController.getInstance();
        if (!controller.isEngineEnabled()) {
            // Не отказываем молча: движок выключен по умолчанию, и пользователю
            // иначе неоткуда узнать, где его включить.
            java.util.concurrent.atomic.AtomicBoolean proceeding =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString(R.string.PluginsInstallTitle))
                    .setMessage(LocaleController.getString(R.string.PluginsEngineDisabledHint))
                    .setPositiveButton(LocaleController.getString(R.string.PluginsEngineEnableAndInstall),
                             (d, which) -> {
                                 proceeding.set(true);
                                 controller.setEngineEnabled(true);
                                 confirmAndInstall(activity, file, completion);
                             })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create();
            dialog.setOnDismissListener(d -> {
                if (!proceeding.get()) {
                    cleanupManagedStaging(file);
                    if (completion != null) {
                        completion.onResult(false, "engine disabled, cancelled by user", null);
                    }
                }
            });
            dialog.show();
            return;
        }
        File snapshot = immutableInstallSnapshot(activity, file);
        if (snapshot == null) {
            showError(activity, LocaleController.getString(R.string.PluginsInstallReadError));
            cleanupManagedStaging(file);
            if (completion != null) {
                completion.onResult(false, "cannot create immutable install snapshot", null);
            }
            return;
        }
        controller.readMetadataAsync(snapshot, plugin -> {
            final Map<String, List<String>> capabilities;
            final Map<String, List<String>> offered;
            final String sha256;
            try {
                if (plugin == null || TextUtils.isEmpty(plugin.id)) {
                    throw new IllegalArgumentException("failed to read plugin metadata");
                }
                capabilities = PluginCapabilityScan.scan(snapshot);
                offered = offeredPermissions(plugin, capabilities);
                sha256 = PluginsController.sha256(snapshot);
            } catch (Throwable t) {
                FileLog.e("PluginInstallHelper: cannot prepare install candidate", t);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!activity.isFinishing()) {
                        showError(activity, t.getMessage() != null ? t.getMessage()
                                : LocaleController.getString(R.string.PluginsInstallReadError));
                    }
                    cleanupManagedStaging(snapshot);
                    if (completion != null) {
                        completion.onResult(false, t.getMessage(), null);
                    }
                });
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (activity.isFinishing()) {
                    cleanupManagedStaging(snapshot);
                    if (completion != null) {
                        completion.onResult(false, "activity is no longer available", null);
                    }
                    return;
                }
                showConsentSheet(activity, snapshot, plugin, offered, capabilities,
                        sha256, completion);
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
    private static void showConsentSheet(Activity activity, File file, Plugin plugin,
                                         Map<String, List<String>> offered,
                                         Map<String, List<String>> capabilities,
                                         String sha256,
                                         PluginsController.InstallCallback completion) {
        AtomicBoolean submitted = new AtomicBoolean(false);
        PluginInstallSheet sheet = new PluginInstallSheet(activity, file, plugin, offered,
                (granted, enableAfterInstall) -> {
                    submitted.set(true);
                    ConsentState previousConsent = ConsentState.capture(plugin.id);
                    grantOnConsent(plugin, granted);
                    install(activity, file, plugin.id, plugin.version, sha256,
                            enableAfterInstall, capabilities, previousConsent, completion);
                });
        sheet.setOnDismissListener(() -> {
            if (submitted.compareAndSet(false, true)) {
                cleanupManagedStaging(file);
                if (completion != null) {
                    completion.onResult(false, "cancelled", null);
                }
            }
        });
        sheet.show();
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
                                String consentedVersion, String consentedSha256,
                                boolean enableAfterInstall,
                                Map<String, List<String>> capabilities,
                                ConsentState previousConsent,
                                PluginsController.InstallCallback completion) {
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setMessage(LocaleController.getString(R.string.PluginsInstalling));
        progress.setCanCancel(false);
        progress.show();
        PluginsController.getInstance().installPlugin(file, enableAfterInstall,
                consentedSha256, consentedId, consentedVersion, (ok, error, plugin) ->
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        progress.dismiss();
                    } catch (Throwable ignored) {
                    }
                    if (!ok) {
                        previousConsent.restore();
                        // Установка сорвалась — согласие, записанное авансом, ни к чему
                        // не относится. Стираем, но только если плагина и правда нет:
                        // при перезаписи существующего файл мог уже подмениться.
                        if (consentedId != null
                                && PluginsController.getInstance().getPlugin(consentedId) == null) {
                            PluginPermissions.clear(consentedId);
                        }
                        if (!activity.isFinishing()) {
                            showError(activity, humanError(error, consentedId),
                                    plugin == null ? null : plugin.loadDebug);
                        }
                        cleanupManagedStaging(file);
                        if (completion != null) {
                            completion.onResult(false, error, plugin);
                        }
                        return;
                    }
                    if (plugin != null && plugin.id != null) {
                        PluginCapabilityScan.store(plugin.id, capabilities);
                        if (enableAfterInstall) {
                            PluginsController.getInstance().setPluginEnabled(plugin.id, true);
                        }
                    }
                    cleanupManagedStaging(file);
                    if (completion != null) {
                        completion.onResult(true, null, plugin);
                    }
                    if (!activity.isFinishing()) {
                        showInstalled(plugin);
                    }
                }));
    }

    private static void cleanupManagedStaging(File file) {
        if (file == null || file.getParentFile() == null
                || !"plugin_install".equals(file.getParentFile().getName())) {
            return;
        }
        try {
            File expected = new File(
                    org.telegram.messenger.ApplicationLoader.applicationContext.getCacheDir(),
                    "plugin_install").getCanonicalFile();
            if (expected.equals(file.getParentFile().getCanonicalFile())) {
                file.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class ConsentState {
        final String pluginId;
        final boolean hadPermissions;
        final List<String> permissions;
        final boolean hadLevel;
        final int level;

        private ConsentState(String pluginId, boolean hadPermissions,
                             List<String> permissions, boolean hadLevel, int level) {
            this.pluginId = pluginId;
            this.hadPermissions = hadPermissions;
            this.permissions = permissions;
            this.hadLevel = hadLevel;
            this.level = level;
        }

        static ConsentState capture(String pluginId) {
            SharedPreferences prefs = PluginsController.getInstance().getPreferences();
            String levelKey = PluginTrustLevel.prefsKey(pluginId);
            return new ConsentState(pluginId, PluginPermissions.hasRecord(pluginId),
                    PluginPermissions.getStored(pluginId), prefs.contains(levelKey),
                    prefs.getInt(levelKey, PluginTrustLevel.DEFAULT));
        }

        void restore() {
            if (hadPermissions) {
                PluginPermissions.setGranted(pluginId, permissions);
            } else {
                PluginPermissions.clear(pluginId);
            }
            SharedPreferences prefs = PluginsController.getInstance().getPreferences();
            SharedPreferences.Editor editor = prefs.edit();
            if (hadLevel) {
                editor.putInt(PluginTrustLevel.prefsKey(pluginId), level);
            } else {
                editor.remove(PluginTrustLevel.prefsKey(pluginId));
            }
            editor.apply();
        }
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
    private static void showInstalled(Plugin plugin) {
        org.telegram.ui.ActionBar.BaseFragment fragment =
                org.telegram.ui.LaunchActivity.getSafeLastFragment();
        CharSequence text = LocaleController.formatString(R.string.PluginsInstalled,
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
