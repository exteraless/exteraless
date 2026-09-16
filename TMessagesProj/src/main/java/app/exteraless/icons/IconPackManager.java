package app.exteraless.icons;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import tw.nekomimi.nekogram.ui.icons.IconsResources;

/**
 * Применение паков иконок (порт com.exteragram.messenger.icons.IconManager,
 * без корутин и без редактора паков).
 *
 * Точка подмены — {@link IconsResources}: она при каждом запросе drawable спрашивает
 * менеджер, есть ли замена для этого ресурса.
 */
public class IconPackManager {

    private static final IconPackManager instance = new IconPackManager();

    public static IconPackManager getInstance() {
        return instance;
    }

    /** Активные паки в порядке приоритета: индекс 0 — самый приоритетный. */
    private final CopyOnWriteArrayList<IconPack> activePacks = new CopyOnWriteArrayList<>();
    /** Имя ресурса -> пак, который его заменяет. */
    private final ConcurrentHashMap<String, IconPack> iconOwnerMap = new ConcurrentHashMap<>();
    /** Кэш имён ресурсов, чтобы не дёргать getResourceEntryName на каждый вызов. */
    private final ConcurrentHashMap<Integer, String> resourceNames = new ConcurrentHashMap<>();
    /** Ресурсы, для которых замены точно нет — чтобы не искать повторно. */
    private final Set<Integer> missing = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final LruCache<Long, Bitmap> resolvedCache;

    private volatile boolean initialized;
    private volatile boolean hasReplacements;

    private IconPackManager() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        resolvedCache = new LruCache<Long, Bitmap>(Math.max(2048, maxMemory / 16)) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    // ---- Жизненный цикл ----

    /** Ленивая инициализация: вызывается из точки подмены иконок. */
    public void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            initialized = true;
        }
        reloadInternal();
    }

    /** Перечитывает список активных паков (в фоне) и сбрасывает кэши. */
    public void reload() {
        initialized = true;
        Utilities.globalQueue.postRunnable(this::reloadInternal);
    }

    /** Синхронная перезагрузка. */
    public void reloadInternal() {
        try {
            IconPacksConfig.loadConfig(false);
            List<IconPack> packs = new ArrayList<>();
            if (IconPacksConfig.enabled()) {
                for (String id : IconPacksConfig.getActivePackIds()) {
                    IconPack pack = IconPackStorage.findPackById(id);
                    if (pack != null) {
                        packs.add(pack);
                    }
                }
            }
            synchronized (this) {
                activePacks.clear();
                activePacks.addAll(packs);
                rebuildOwnerMap();
                resolvedCache.evictAll();
                missing.clear();
            }
            // прогрев: декодируем иконки заранее, чтобы первый экран не тормозил
            launchPrewarm();
        } catch (Throwable t) {
            FileLog.e("openExtera: failed to load icon packs", t);
        }
    }

    private void rebuildOwnerMap() {
        iconOwnerMap.clear();
        // идём с конца, чтобы паки с меньшим индексом перезаписали более поздние
        for (int i = activePacks.size() - 1; i >= 0; i--) {
            IconPack pack = activePacks.get(i);
            for (Map.Entry<String, String> entry : pack.getIcons().entrySet()) {
                if (isBlacklisted(entry.getKey())) {
                    continue;
                }
                iconOwnerMap.put(entry.getKey(), pack);
            }
        }
        hasReplacements = !iconOwnerMap.isEmpty();
    }

    public List<IconPack> getActivePacks() {
        return new ArrayList<>(activePacks);
    }

    public boolean hasReplacements() {
        return hasReplacements;
    }

    // ---- Подмена ----

    /**
     * Возвращает drawable из активного пака или null, если замены нет.
     * Вызывается из {@link IconsResources}; ресурсы передаются, чтобы получить оригинал
     * без рекурсии.
     */
    @Nullable
    public Drawable getDrawable(IconsResources resources, int resId, int density, @Nullable Resources.Theme theme) {
        if (!initialized) {
            ensureInitialized();
        }
        // режим точечной замены: запоминаем, какие иконки реально просит текущий экран
        app.exteraless.icons.picker.IconObserver.log(resId);
        // Порядок разбора: сначала пользовательский пак,
        // потом встроенный набор (Solar/Remix), потом оригинал.
        Drawable fromPack = getUserPackDrawable(resources, resId, density, theme);
        if (fromPack != null) {
            return fromPack;
        }
        return getBasePackDrawable(resources, resId, density, theme);
    }

    /**
     * Подмена из встроенного набора ({@link BaseIconPacks}) — порт
     * {@code IconManager.getIcon(resId)} ({@code IconManager.java:1741}).
     * Возвращает null, если набор не выбран или для ресурса подмены нет; тогда
     * {@link IconsResources} отдаёт оригинал, как и раньше.
     */
    @Nullable
    private Drawable getBasePackDrawable(@Nullable IconsResources resources, int resId,
                                         int density, @Nullable Resources.Theme theme) {
        if (resources == null || resId == 0) {
            return null;
        }
        int mapped = BaseIconPacks.getIcon(resId);
        if (mapped == resId || mapped == 0) {
            return null;
        }
        try {
            // getOriginalDrawableForDensity не рекурсивен: он идёт сразу в базовый Resources.
            return resources.getOriginalDrawableForDensity(mapped, density, theme);
        } catch (Throwable t) {
            FileLog.e("openExtera: base icon pack lookup failed", t);
            return null;
        }
    }

    /** Подмена из установленного пользователем пака или null. */
    @Nullable
    private Drawable getUserPackDrawable(IconsResources resources, int resId, int density,
                                         @Nullable Resources.Theme theme) {
        if (!hasReplacements || resId == 0) {
            return null;
        }
        if (missing.contains(resId)) {
            return null;
        }
        try {
            if (density == 0) {
                density = AndroidUtilities.displayMetrics.densityDpi;
            }
            long cacheKey = cacheKey(resId, density);
            Bitmap cached;
            synchronized (this) {
                cached = resolvedCache.get(cacheKey);
            }
            if (cached != null && !cached.isRecycled()) {
                return new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), cached);
            }

            String name = getResourceName(resources, resId);
            if (name == null) {
                missing.add(resId);
                return null;
            }
            IconPack pack = iconOwnerMap.get(name);
            if (pack == null) {
                missing.add(resId);
                return null;
            }
            String relative = pack.getIcons().get(name);
            if (relative == null) {
                missing.add(resId);
                return null;
            }
            File file = IconPackStorage.resolveIconFile(pack, relative);
            if (file == null) {
                missing.add(resId);
                return null;
            }
            Bitmap bitmap = createBitmapFromFile(resources, file.getAbsolutePath(), resId, density, theme);
            if (bitmap == null) {
                missing.add(resId);
                return null;
            }
            synchronized (this) {
                resolvedCache.put(cacheKey, bitmap);
            }
            return new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap);
        } catch (Throwable t) {
            FileLog.e("openExtera: icon pack lookup failed", t);
            return null;
        }
    }

    /** Предпросмотр иконки конкретного пака (для экрана настроек). */
    @Nullable
    public Drawable getPackIconDrawable(IconPack pack, int resId) {
        try {
            Resources res = ApplicationLoader.applicationContext.getResources();
            IconsResources iconsResources = res instanceof IconsResources ? (IconsResources) res : null;
            String name = getResourceName(iconsResources, resId);
            if (name == null) {
                return null;
            }
            String relative = pack.getIcons().get(name);
            if (relative == null) {
                return null;
            }
            File file = IconPackStorage.resolveIconFile(pack, relative);
            if (file == null) {
                return null;
            }
            Bitmap bitmap = createBitmapFromFile(iconsResources, file.getAbsolutePath(), resId,
                    AndroidUtilities.displayMetrics.densityDpi, null);
            return bitmap == null ? null : new BitmapDrawable(res, bitmap);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private String getResourceName(@Nullable IconsResources resources, int resId) {
        String cachedName = resourceNames.get(resId);
        if (cachedName != null) {
            return cachedName;
        }
        try {
            Resources res = resources != null ? resources : ApplicationLoader.applicationContext.getResources();
            String name = res.getResourceEntryName(resId);
            if (name != null) {
                resourceNames.put(resId, name);
            }
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    private static long cacheKey(int resId, int density) {
        return ((long) resId << 32) | (density & 0xffffffffL);
    }

    /**
     * Декодирует файл иконки и масштабирует его под размеры оригинального drawable,
     * чтобы подменённая иконка не «прыгала» в вёрстке.
     */
    @Nullable
    private Bitmap createBitmapFromFile(@Nullable IconsResources resources, String path, int originalResId,
                                        int density, @Nullable Resources.Theme theme) {
        try {
            Drawable original = null;
            if (resources != null) {
                original = resources.getOriginalDrawableForDensity(originalResId, density, theme);
            }
            int width = Math.max(1, original != null ? original.getIntrinsicWidth() : AndroidUtilities.dp(24));
            int height = Math.max(1, original != null ? original.getIntrinsicHeight() : AndroidUtilities.dp(24));

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            int outWidth = options.outWidth;
            int outHeight = options.outHeight;
            if (outWidth <= 0 || outHeight <= 0 || (long) outWidth * outHeight > 100_000_000L) {
                return null;
            }

            options.inSampleSize = 1;
            if (outHeight > height || outWidth > width) {
                int halfHeight = outHeight / 2;
                int halfWidth = outWidth / 2;
                while (halfHeight / options.inSampleSize >= height && halfWidth / options.inSampleSize >= width) {
                    options.inSampleSize *= 2;
                }
            }
            long maxPixels = Math.max((long) width * height, 1024L * 1024L);
            while (true) {
                int sample = options.inSampleSize;
                long w = (outWidth + sample - 1L) / sample;
                long h = (outHeight + sample - 1L) / sample;
                if (w * h <= maxPixels) {
                    break;
                }
                options.inSampleSize = sample * 2;
            }
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeFile(path, options);
            if (decoded == null) {
                return null;
            }
            if (decoded.getWidth() == width && decoded.getHeight() == height) {
                decoded.setDensity(density);
                return decoded;
            }
            Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
            if (scaled != decoded) {
                decoded.recycle();
            }
            scaled.setDensity(density);
            return scaled;
        } catch (OutOfMemoryError e) {
            FileLog.e("openExtera: out of memory loading icon " + path);
            return null;
        } catch (Throwable t) {
            FileLog.e("openExtera: error loading icon " + path, t);
            return null;
        }
    }

    // ---- Операции с паками ----

    /** Устанавливает пак из архива в фоне; колбэк вызывается на UI-потоке. */
    public void installPack(File archive, InstallCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            IconPackStorageError error = IconPackStorage.installPack(archive);
            if (error == null) {
                reloadInternal();
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (callback != null) {
                    callback.onResult(error);
                }
            });
        });
    }

    public void deletePack(String packId) {
        IconPacksConfig.forgetPack(packId);
        Utilities.globalQueue.postRunnable(() -> {
            IconPackStorage.deletePack(packId);
            reloadInternal();
        });
    }

    public interface InstallCallback {
        /** error == null означает успех. */
        void onResult(@Nullable IconPackStorageError error);
    }

    // ---- Точечная замена иконок (редактирование пака) ----

    /** Имя ресурса drawable по его id или null. */
    @Nullable
    public String getResourceName(int resId) {
        return getResourceName((IconsResources) null, resId);
    }

    /** id ресурса drawable по имени или 0. */
    /**
     * Имена иконок exteraGram, которых у нас нет под тем же именем.
     *
     * Паки собирают под exteraGram, и часть иконок там называется иначе: у них
     * `ic_ab_search`, у нас `outline_header_search`. Без сопоставления такая
     * иконка молча остаётся стоковой — пак применён, а пара картинок нет.
     *
     * Соответствия выведены из BaseIconPacks: там уже записано, какой наш
     * ресурс какому их варианту (`*_remix`, `*_solar`) соответствует. Проверено
     * на 18 настоящих паках: с ними совпадает 92-100% имён, эти девять
     * добирают самые ходовые из оставшихся.
     */
    private static final java.util.Map<String, String> RESOURCE_ALIASES = new java.util.HashMap<>();

    static {
        RESOURCE_ALIASES.put("ic_ab_search", "outline_header_search");
        RESOURCE_ALIASES.put("message", "filled_profile_message_24");
        RESOURCE_ALIASES.put("mute", "filled_profile_mute_24");
        RESOURCE_ALIASES.put("unmute", "filled_profile_unmute_24");
        RESOURCE_ALIASES.put("video", "filled_profile_video_24");
        RESOURCE_ALIASES.put("block", "filled_profile_stop_24");
        RESOURCE_ALIASES.put("join", "filled_profile_member_24");
        RESOURCE_ALIASES.put("story", "filled_profile_story");
        RESOURCE_ALIASES.put("msg_bots", "msg_bot");
    }

    public int getResourceId(String name) {
        if (name == null) {
            return 0;
        }
        int id = resolveResourceId(name);
        if (id != 0) {
            return id;
        }
        String alias = RESOURCE_ALIASES.get(name);
        return alias == null ? 0 : resolveResourceId(alias);
    }

    private int resolveResourceId(String name) {
        try {
            return ApplicationLoader.applicationContext.getResources()
                    .getIdentifier(name, "drawable", ApplicationLoader.applicationContext.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Кэш «имя ресурса → id» по всем drawable приложения; строится один раз за процесс. */
    private volatile java.util.LinkedHashMap<String, Integer> systemIcons;

    /**
     * Все иконки приложения, которые можно заменить: имя ресурса → id, по алфавиту.
     * Порт {@code IconManager.systemIcons} ({@code IconManager.java:105}); тело корутины,
     * которая наполняет карту в exteraGram, в декомпиляте не читается, поэтому способ обхода —
     * свой: перебор id внутри типа drawable нашего пакета.
     *
     * Рефлексия по {@code R.drawable} тут не годится: в релизе {@code minifyEnabled = true}
     * ({@code TMessagesProj/build.gradle:156}), поля R инлайнятся и класс выкидывается.
     * Идентификаторы же лежат в resources.arsc и доступны всегда: у типа они идут подряд
     * с нуля, поэтому идём от {@code 0x7fXX0000} до первого длинного разрыва.
     * Блокирующий вызов (~4 тысячи запросов в resources.arsc): звать не с UI-потока.
     */
    public java.util.Map<String, Integer> getSystemIcons() {
        java.util.LinkedHashMap<String, Integer> cached = systemIcons;
        if (cached != null) {
            return cached;
        }
        java.util.TreeMap<String, Integer> collected = new java.util.TreeMap<>();
        try {
            Resources res = ApplicationLoader.applicationContext.getResources();
            // старший полуслово id — пакет и тип; берём с заведомо существующей иконки
            int typeMask = org.telegram.messenger.R.drawable.msg_edit & 0xffff0000;
            int misses = 0;
            for (int entry = 0; entry < 0x10000 && misses < 64; entry++) {
                String name;
                try {
                    name = res.getResourceEntryName(typeMask | entry);
                } catch (Throwable notFound) {
                    misses++;
                    continue;
                }
                misses = 0;
                if (name == null || isBlacklisted(name)) {
                    continue;
                }
                collected.put(name, typeMask | entry);
            }
        } catch (Throwable t) {
            FileLog.e("openExtera: cannot enumerate app icons", t);
        }
        java.util.LinkedHashMap<String, Integer> result = new java.util.LinkedHashMap<>(collected);
        if (!result.isEmpty()) {
            systemIcons = result;
        }
        return result;
    }

    /**
     * Кладёт файл {@code source} в каталог пака и прописывает его в metadata.json как замену
     * для ресурса {@code resId}. Блокирующий вызов, запускать не на UI-потоке.
     *
     * @return true, если пак обновлён
     */
    public boolean saveCustomIcon(String packId, int resId, File source) {
        IconPack pack = IconPackStorage.findPackById(packId);
        String resourceName = getResourceName(resId);
        if (pack == null || source == null || !source.isFile() || resourceName == null) {
            return false;
        }
        if (isBlacklisted(resourceName)) {
            return false;
        }
        try {
            String extension = extensionOf(source.getName());
            File packDir = new File(IconPackStorage.getIconPacksDirectory(), pack.getId());
            //noinspection ResultOfMethodCallIgnored
            packDir.mkdirs();
            File destination = new File(packDir, resourceName + "." + extension);

            String previous = pack.getIcons().get(resourceName);
            copy(source, destination);

            Map<String, String> icons = new java.util.LinkedHashMap<>(pack.getIcons());
            icons.put(resourceName, destination.getName());
            IconPack updated = new IconPack(pack.getId(), pack.getName(), pack.getAuthor(), pack.getVersion(), icons, pack.getLocation());
            if (!IconPackStorage.saveIconPackMetadata(updated)) {
                return false;
            }
            // старый файл замены больше не нужен, если сменилось расширение
            if (previous != null && !previous.equals(destination.getName())) {
                File old = IconPackStorage.resolveIconFile(pack, previous);
                if (old != null) {
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                }
            }
            invalidateIconCaches(resId);
            reloadInternal();
            return true;
        } catch (Throwable t) {
            FileLog.e("openExtera: failed to save custom icon", t);
            return false;
        }
    }

    /** Убирает замену иконки из пака вместе с файлом. Блокирующий вызов. */
    public boolean resetCustomIcon(String packId, int resId) {
        IconPack pack = IconPackStorage.findPackById(packId);
        String resourceName = getResourceName(resId);
        if (pack == null || resourceName == null) {
            return false;
        }
        String relative = pack.getIcons().get(resourceName);
        if (relative == null) {
            return false;
        }
        try {
            File file = IconPackStorage.resolveIconFile(pack, relative);
            Map<String, String> icons = new java.util.LinkedHashMap<>(pack.getIcons());
            icons.remove(resourceName);
            IconPack updated = new IconPack(pack.getId(), pack.getName(), pack.getAuthor(), pack.getVersion(), icons, pack.getLocation());
            if (!IconPackStorage.saveIconPackMetadata(updated)) {
                return false;
            }
            if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            invalidateIconCaches(resId);
            reloadInternal();
            return true;
        } catch (Throwable t) {
            FileLog.e("openExtera: failed to reset custom icon", t);
            return false;
        }
    }

    /** Сбрасывает кэши, относящиеся к одному ресурсу (все плотности). */
    public void invalidateIconCaches(int resId) {
        missing.remove(resId);
        synchronized (this) {
            // ключ кэша — (resId, density); плотностей единицы, дешевле почистить всё
            resolvedCache.evictAll();
        }
    }

    /** Создаёт пустой пак и делает его редактируемым. Блокирующий вызов. */
    @Nullable
    public IconPack createPack(String packId, String name, String author, String version) {
        if (!IconPackStorage.isValidPackId(packId)) {
            return null;
        }
        IconPack pack = new IconPack(packId, name, author, version,
                java.util.Collections.emptyMap(), new File(IconPackStorage.getIconPacksDirectory(), packId));
        if (!IconPackStorage.saveIconPackMetadata(pack)) {
            return null;
        }
        reloadInternal();
        return IconPackStorage.findPackById(packId);
    }

    private static String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        String ext = dot > 0 ? fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
        if (!Arrays.asList("png", "webp", "jpg", "jpeg", "svg").contains(ext)) {
            ext = "png";
        }
        return ext;
    }

    private static void copy(File from, File to) throws java.io.IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    // ---- Прогрев кэша ----

    private volatile boolean prewarmScheduled;

    /**
     * Прогрев кэша: заранее декодирует иконки активных паков в фоне, чтобы первое открытие
     * экрана не тормозило на синхронном декодировании десятков файлов.
     * Порт {@code IconManager.launchPrewarm} / {@code prefetchCustomPacks}.
     */
    public void launchPrewarm() {
        if (prewarmScheduled || !IconPacksConfig.enabled()) {
            return;
        }
        prewarmScheduled = true;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                ensureInitialized();
                Resources res = ApplicationLoader.applicationContext.getResources();
                IconsResources iconsResources = res instanceof IconsResources ? (IconsResources) res : null;
                int density = AndroidUtilities.displayMetrics.densityDpi;
                int warmed = 0;
                for (String name : new ArrayList<>(iconOwnerMap.keySet())) {
                    int resId = getResourceId(name);
                    if (resId == 0) {
                        continue;
                    }
                    long key = cacheKey(resId, density);
                    synchronized (this) {
                        if (resolvedCache.get(key) != null) {
                            continue;
                        }
                    }
                    IconPack pack = iconOwnerMap.get(name);
                    if (pack == null) {
                        continue;
                    }
                    String relative = pack.getIcons().get(name);
                    File file = relative == null ? null : IconPackStorage.resolveIconFile(pack, relative);
                    if (file == null) {
                        continue;
                    }
                    Bitmap bitmap = createBitmapFromFile(iconsResources, file.getAbsolutePath(), resId, density, null);
                    if (bitmap == null) {
                        continue;
                    }
                    synchronized (this) {
                        resolvedCache.put(key, bitmap);
                    }
                    warmed++;
                    if (warmed % 32 == 0) {
                        // не занимать очередь надолго
                        try {
                            Thread.sleep(4);
                        } catch (InterruptedException ignore) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                FileLog.e("openExtera: icon prewarm failed", t);
            } finally {
                prewarmScheduled = false;
            }
        });
    }

    // ---- Иконка уведомлений ----

    /**
     * Растеризует файл иконки под размер {@code originalResId}. Нужен
     * {@link IconPackProvider}: системе уведомлений отдаётся PNG, а не Drawable.
     */
    @Nullable
    public Bitmap decodeForNotification(String path, int originalResId, int density) {
        Resources res = ApplicationLoader.applicationContext.getResources();
        IconsResources iconsResources = res instanceof IconsResources ? (IconsResources) res : null;
        return createBitmapFromFile(iconsResources, path, originalResId, density, null);
    }

    /**
     * Иконка уведомлений из активного пака или null (тогда используется стоковая).
     * Порт {@code IconManager.getNotificationIcon}.
     */
    @Nullable
    public androidx.core.graphics.drawable.IconCompat getNotificationIcon() {
        if (!IconPacksConfig.enabled() || !IconPacksConfig.customNotificationIcon()) {
            return null;
        }
        try {
            ensureInitialized();
            String name = getResourceName(org.telegram.messenger.R.drawable.notification);
            if (name == null) {
                return null;
            }
            IconPack pack = iconOwnerMap.get(name);
            if (pack == null) {
                return null;
            }
            android.net.Uri uri = IconPackProvider.getIconUri(pack.getId(), name);
            return uri == null ? null : androidx.core.graphics.drawable.IconCompat.createWithContentUri(uri);
        } catch (Throwable t) {
            FileLog.e("openExtera: failed to build notification icon", t);
            return null;
        }
    }

    // ---- Пак, пришедший файлом в чате ----

    /** Похоже ли вложение сообщения на пак иконок (.icons). */
    public static boolean isIconPack(org.telegram.messenger.MessageObject messageObject) {
        if (messageObject == null || messageObject.getDocument() == null) {
            return false;
        }
        String name = messageObject.getDocumentName();
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(IconPackStorage.PACK_EXTENSION);
    }

    /**
     * Нажатие на файл-пак в чате: разбор архива в фоне и лист с превью.
     * Вызывать с UI-потока.
     */
    public void handleIconPack(org.telegram.ui.ActionBar.BaseFragment fragment, File file) {
        if (fragment == null || fragment.getParentActivity() == null || file == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            IconPack parsed = IconPackStorage.parsePackFromArchive(file);
            AndroidUtilities.runOnUIThread(() -> {
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (parsed == null) {
                    org.telegram.ui.Components.BulletinFactory.of(fragment)
                            .createErrorBulletin(org.telegram.messenger.LocaleController.getString(
                                    org.telegram.messenger.R.string.IconPackErrorInvalidArchive))
                            .show();
                    return;
                }
                // Лист с превью вместо строки «имя, N иконок»: иконки меняют весь
                // интерфейс, и до установки надо видеть, что именно ставишь
                InstallIconPackBottomSheet.show(fragment, file, parsed);
            });
        });
    }

    // ---- Чёрный список ----

    private static final Set<String> BLACKLIST = new HashSet<>(Arrays.asList(
            "bar_selector", "blockpanel", "circle", "circle_big", "dice", "dino_pic",
            "camera_btn", "cancel_big", "etg_splash", "ev_minus", "ev_plus",
            "chats_archive_arrow", "chats_archive_box", "chats_archive_muted", "chats_archive_pin",
            "chats_widget_preview", "contacts_widget_preview", "fast_scroll_empty", "field_carret_empty"
    ));

    /**
     * Некоторые ресурсы нельзя подменять картинкой: это маски, градиенты, анимированные vector
     * drawable и прочее, что ломается при замене на bitmap. Список — из оригинала.
     */
    public static boolean isBlacklisted(String name) {
        if (name == null) {
            return true;
        }
        if (BLACKLIST.contains(name)) {
            return true;
        }
        return name.contains("avd")
                || name.startsWith("abc_")
                || name.endsWith("_solar")
                || name.endsWith("_remix")
                || name.contains("$")
                || name.contains("animationpin")
                || name.contains("googlepay")
                || name.contains("shadow")
                || name.startsWith("ic_monochrome")
                || name.startsWith("nocover")
                || name.startsWith("gradient_")
                || name.startsWith("stickers_back_")
                || name.startsWith("media_doc_")
                || name.startsWith("loading_animation")
                || name.startsWith("intro_")
                || name.startsWith("minibubble_")
                || name.startsWith("book_")
                || name.startsWith("call_")
                || name.startsWith("groupsintro")
                || name.startsWith("profile_level")
                || name.startsWith("widget_")
                || name.startsWith("zoom_slide")
                || name.startsWith("zoom_round")
                || name.startsWith("popup_fixed_alert")
                || name.startsWith("search_dark")
                || name.startsWith("bar_selector");
    }
}
