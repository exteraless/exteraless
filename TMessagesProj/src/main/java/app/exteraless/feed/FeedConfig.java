package app.exteraless.feed;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Настройки ленты для одного аккаунта: показывать ли каналы из архива и какие каналы исключены.
 * Хранится в отдельном SharedPreferences-файле на аккаунт, счётчик поколений растёт при любом
 * изменении и служит сигналом к пересборке ленты.
 */
public final class FeedConfig {

    private static final String PREFERENCES_PREFIX = "feedconfig";
    private static final String KEY_INCLUDE_ARCHIVED = "includeArchived";
    private static final String KEY_EXCLUDED_CHANNELS = "excludedChannels";
    private static final String KEY_ADD_NEW_CHANNELS = "addNewChannels";
    private static final String KEY_KNOWN_CHANNELS = "knownChannels";
    private static final String KEY_CATEGORIES = "categories";
    private static final String KEY_SELECTED_CATEGORY = "selectedCategory";
    private static final String KEY_NEXT_CATEGORY_ID = "nextCategoryId";
    private static final String KEY_SCROLL_POSITION = "scrollPosition_";
    private static final int FIRST_CATEGORY_ID = 2;

    private static final FeedConfig[] instances = new FeedConfig[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int account = 0; account < lockObjects.length; account++) {
            lockObjects[account] = new Object();
        }
    }

    private final SharedPreferences preferences;
    private final AtomicInteger generation = new AtomicInteger();

    private volatile Set<Long> excludedChannels;
    private volatile Set<Long> knownChannels;
    private volatile boolean includeArchived;
    private volatile boolean addNewChannels;
    private volatile List<Category> categories;
    private volatile Category selectedCategory;
    private volatile int categoriesVersion;
    private int nextCategoryId;

    public static final class Category {
        private final int id;
        private final String title;
        private final long emojiId;
        private final Set<Long> channels;
        private final boolean hidden;

        Category(int id, String title, long emojiId, Set<Long> channels, boolean hidden) {
            this.id = id;
            this.title = title;
            this.emojiId = emojiId;
            this.channels = Collections.unmodifiableSet(new HashSet<>(channels));
            this.hidden = hidden;
        }

        public int getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public long getEmojiId() {
            return emojiId;
        }

        public Set<Long> getChannels() {
            return channels;
        }

        public boolean isHidden() {
            return hidden;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Category)) {
                return false;
            }
            Category category = (Category) other;
            return id == category.id && emojiId == category.emojiId && hidden == category.hidden
                    && title.equals(category.title) && channels.equals(category.channels);
        }

        @Override
        public int hashCode() {
            return id;
        }
    }

    private FeedConfig(int account) {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES_PREFIX + account, 0);
        includeArchived = preferences.getBoolean(KEY_INCLUDE_ARCHIVED, false);
        addNewChannels = preferences.getBoolean(KEY_ADD_NEW_CHANNELS, true);
        excludedChannels = readIds(KEY_EXCLUDED_CHANNELS);
        knownChannels = readIds(KEY_KNOWN_CHANNELS);
        categories = Collections.unmodifiableList(readCategories(preferences.getString(KEY_CATEGORIES, null)));
        int selectedId = preferences.getInt(KEY_SELECTED_CATEGORY, 0);
        int maxId = FIRST_CATEGORY_ID - 1;
        for (Category category : categories) {
            maxId = Math.max(maxId, category.id);
            if (category.id == selectedId && !category.hidden) {
                selectedCategory = category;
            }
        }
        nextCategoryId = Math.max(preferences.getInt(KEY_NEXT_CATEGORY_ID, FIRST_CATEGORY_ID), maxId + 1);
    }

    public static FeedConfig getInstance(int num) {
        FeedConfig cached = instances[num];
        if (cached != null) {
            return cached;
        }
        synchronized (lockObjects[num]) {
            cached = instances[num];
            if (cached == null) {
                cached = new FeedConfig(num);
                instances[num] = cached;
            }
        }
        return cached;
    }

    private Set<Long> readIds(String key) {
        Set<String> stored = preferences.getStringSet(key, null);
        if (stored == null) {
            return Collections.emptySet();
        }
        HashSet<Long> parsed = new HashSet<>();
        for (String value : stored) {
            try {
                parsed.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return Collections.unmodifiableSet(parsed);
    }

    private static HashSet<String> toStrings(Set<Long> ids) {
        HashSet<String> stored = new HashSet<>();
        for (Long dialogId : ids) {
            stored.add(String.valueOf(dialogId.longValue()));
        }
        return stored;
    }

    private void applyExcluded(Set<Long> updated) {
        excludedChannels = Collections.unmodifiableSet(updated);
        generation.incrementAndGet();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putStringSet(KEY_EXCLUDED_CHANNELS, toStrings(updated));
        editor.apply();
    }

    private void applyKnown(Set<Long> updated) {
        knownChannels = Collections.unmodifiableSet(updated);
        generation.incrementAndGet();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putStringSet(KEY_KNOWN_CHANNELS, toStrings(updated));
        editor.apply();
    }

    private void markKnown(Collection<Long> ids) {
        if (addNewChannels) {
            return;
        }
        HashSet<Long> updated = new HashSet<>(knownChannels);
        if (updated.addAll(ids)) {
            applyKnown(updated);
        }
    }

    public boolean getAddNewChannels() {
        return addNewChannels;
    }

    public void setAddNewChannels(boolean value, Collection<Long> currentChannels) {
        if (addNewChannels == value) {
            return;
        }
        addNewChannels = value;
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(KEY_ADD_NEW_CHANNELS, value);
        editor.apply();
        if (value) {
            generation.incrementAndGet();
        } else {
            applyKnown(new HashSet<>(currentChannels));
        }
    }

    public boolean getIncludeArchived() {
        return includeArchived;
    }

    public void setIncludeArchived(boolean value) {
        if (includeArchived == value) {
            return;
        }
        includeArchived = value;
        generation.incrementAndGet();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(KEY_INCLUDE_ARCHIVED, value);
        editor.apply();
    }

    public boolean isExcluded(long dialogId) {
        Category category = selectedCategory;
        return category != null ? !category.channels.contains(dialogId) : isHiddenFromAll(dialogId);
    }

    public boolean isHiddenFromAll(long dialogId) {
        return excludedChannels.contains(dialogId) || !addNewChannels && !knownChannels.contains(dialogId);
    }

    public void setExcluded(long dialogId, boolean excluded) {
        if (!excluded) {
            markKnown(Collections.singleton(dialogId));
        }
        HashSet<Long> updated = new HashSet<>(excludedChannels);
        boolean changed = excluded ? updated.add(dialogId) : updated.remove(dialogId);
        if (changed) {
            applyExcluded(updated);
        }
    }

    /**
     * Неизменяемый снимок исключённых каналов: можно читать с любого потока,
     * пересборка ленты работает именно с ним, а не с живой коллекцией.
     */
    public Set<Long> getExcludedSnapshot() {
        return excludedChannels;
    }

    public void removeExcluded(Set<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        HashSet<Long> updated = new HashSet<>(excludedChannels);
        if (updated.removeAll(ids)) {
            applyExcluded(updated);
        }
    }

    public void includeAll(Collection<Long> ids) {
        markKnown(ids);
        if (excludedChannels.isEmpty()) {
            return;
        }
        applyExcluded(new HashSet<Long>());
    }

    public void excludeAll(Collection<Long> ids) {
        HashSet<Long> updated = new HashSet<>(excludedChannels);
        if (updated.addAll(ids)) {
            applyExcluded(updated);
        }
    }

    /**
     * Номер поколения настроек: меняется при каждой правке состава ленты.
     * Потребители сравнивают его со своим сохранённым и пересобирают выборку при расхождении.
     */
    public int getGeneration() {
        return generation.get();
    }

    public List<Category> getCategories() {
        return categories;
    }

    public List<Category> getVisibleCategories() {
        ArrayList<Category> visible = new ArrayList<>();
        for (Category category : categories) {
            if (!category.hidden) {
                visible.add(category);
            }
        }
        return visible;
    }

    public Category getCategory(int id) {
        for (Category category : categories) {
            if (category.id == id) {
                return category;
            }
        }
        return null;
    }

    public Category getSelectedCategory() {
        return selectedCategory;
    }

    public int getSelectedCategoryId() {
        Category category = selectedCategory;
        return category != null ? category.id : 0;
    }

    public int getCategoriesVersion() {
        return categoriesVersion;
    }

    public String getScrollPosition() {
        return preferences.getString(KEY_SCROLL_POSITION + getSelectedCategoryId(), null);
    }

    public void setScrollPosition(String value) {
        preferences.edit().putString(KEY_SCROLL_POSITION + getSelectedCategoryId(), value).apply();
    }

    public synchronized boolean selectCategory(int id) {
        Category category = id != 0 ? getCategory(id) : null;
        if (id != 0 && (category == null || category.hidden)) {
            return false;
        }
        if (getSelectedCategoryId() == id) {
            return false;
        }
        selectedCategory = category;
        generation.incrementAndGet();
        categoriesVersion++;
        preferences.edit().putInt(KEY_SELECTED_CATEGORY, id).apply();
        return true;
    }

    public synchronized Category saveCategory(int id, String title, long emojiId, Set<Long> channels) {
        Category existing = getCategory(id);
        int categoryId = existing != null ? id : nextCategoryId++;
        Category saved = new Category(categoryId, title, emojiId, channels, existing != null && existing.hidden);
        ArrayList<Category> updated = new ArrayList<>(categories);
        if (existing != null) {
            updated.set(updated.indexOf(existing), saved);
        } else {
            updated.add(saved);
        }
        applyCategories(updated);
        return saved;
    }

    public synchronized boolean setCategoryHidden(int id, boolean hidden) {
        Category category = getCategory(id);
        if (category == null || category.hidden == hidden) {
            return false;
        }
        ArrayList<Category> updated = new ArrayList<>(categories);
        updated.set(updated.indexOf(category), new Category(id, category.title, category.emojiId, category.channels, hidden));
        applyCategories(updated);
        return true;
    }

    public synchronized void deleteCategory(int id) {
        Category category = getCategory(id);
        if (category == null) {
            return;
        }
        ArrayList<Category> updated = new ArrayList<>(categories);
        updated.remove(category);
        applyCategories(updated);
    }

    public synchronized void reorderCategories(List<Integer> ids) {
        ArrayList<Category> updated = new ArrayList<>();
        for (Integer id : ids) {
            Category category = getCategory(id);
            if (category != null) {
                updated.add(category);
            }
        }
        for (Category category : categories) {
            if (!ids.contains(category.id)) {
                updated.add(category);
            }
        }
        if (!updated.equals(categories)) {
            applyCategories(updated);
        }
    }

    public synchronized void removeFromCategories(Set<Long> ids) {
        boolean changed = false;
        ArrayList<Category> updated = new ArrayList<>(categories.size());
        for (Category category : categories) {
            HashSet<Long> channels = new HashSet<>(category.channels);
            if (channels.removeAll(ids)) {
                changed = true;
                updated.add(new Category(category.id, category.title, category.emojiId, channels, category.hidden));
            } else {
                updated.add(category);
            }
        }
        if (changed) {
            applyCategories(updated);
        }
    }

    private void applyCategories(List<Category> updated) {
        Category previous = selectedCategory;
        Category current = null;
        if (previous != null) {
            for (Category category : updated) {
                if (category.id == previous.id && !category.hidden) {
                    current = category;
                    break;
                }
            }
        }
        categories = Collections.unmodifiableList(updated);
        selectedCategory = current;
        if (previous != null && (current == null || !current.channels.equals(previous.channels))) {
            generation.incrementAndGet();
        }
        categoriesVersion++;
        preferences.edit()
                .putString(KEY_CATEGORIES, writeCategories(updated))
                .putInt(KEY_SELECTED_CATEGORY, current != null ? current.id : 0)
                .putInt(KEY_NEXT_CATEGORY_ID, nextCategoryId)
                .apply();
    }

    private static List<Category> readCategories(String json) {
        ArrayList<Category> result = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                HashSet<Long> channels = new HashSet<>();
                JSONArray ids = object.optJSONArray("channels");
                if (ids != null) {
                    for (int j = 0; j < ids.length(); j++) {
                        channels.add(ids.getLong(j));
                    }
                }
                result.add(new Category(object.getInt("id"), object.optString("title"), object.optLong("emoji"),
                        channels, object.optBoolean("hidden")));
            }
        } catch (JSONException e) {
            FileLog.e(e);
            result.clear();
        }
        return result;
    }

    private static String writeCategories(List<Category> list) {
        JSONArray array = new JSONArray();
        try {
            for (Category category : list) {
                JSONArray channels = new JSONArray();
                for (Long id : category.channels) {
                    channels.put(id.longValue());
                }
                JSONObject object = new JSONObject();
                object.put("id", category.id);
                object.put("title", category.title);
                object.put("emoji", category.emojiId);
                object.put("channels", channels);
                object.put("hidden", category.hidden);
                array.put(object);
            }
        } catch (JSONException e) {
            FileLog.e(e);
        }
        return array.toString();
    }
}
