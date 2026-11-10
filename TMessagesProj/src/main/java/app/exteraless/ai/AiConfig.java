package app.exteraless.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Objects;

import app.exteraless.ai.data.Message;
import app.exteraless.ai.data.Provider;
import app.exteraless.ai.data.Role;
import app.exteraless.ai.data.Service;
import app.exteraless.ai.data.Suggestions;

public final class AiConfig {

    public static final Service DEFAULT_SERVICE = new Service("default",
            Provider.PRESETS.get(0).getUrl(), Provider.PRESETS.get(0).getModel(), null);

    private static final String KEY_HISTORY = "history";
    private static final String KEY_ROLES = "roles";
    private static final String KEY_SERVICES = "services";
    private static final String KEY_SAVE_HISTORY = "saveHistory";
    private static final String KEY_RESPONSE_STREAMING = "responseStreaming";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_SHOW_RESPONSE_ONLY = "showResponseOnly";
    private static final String KEY_INSERT_AS_QUOTE = "insertAsQuote";
    private static final String KEY_DISABLE_TOOLS = "disableTools";
    private static final String KEY_SELECTED_SERVICE_ID = "selectedServiceId";
    private static final String KEY_SELECTED_SERVICE_HASH = "selectedService";
    private static final String KEY_SELECTED_ROLE = "selectedRole";
    private static final String KEY_REPLACE_TELEGRAM_EDITOR = "replaceTelegramEditor";
    private static final String KEY_REPLACE_TELEGRAM_SUMMARIES = "replaceTelegramSummaries";

    private static final Gson GSON = new Gson();

    private AiConfig() {
    }

    public static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("aiConfig", Context.MODE_PRIVATE);
    }

    private static SharedPreferences.Editor getEditor() {
        return getPreferences().edit();
    }

    public static boolean getSaveHistory() {
        return getPreferences().getBoolean(KEY_SAVE_HISTORY, true);
    }

    public static void setSaveHistory(boolean value) {
        getEditor().putBoolean(KEY_SAVE_HISTORY, value).apply();
    }

    public static boolean getResponseStreaming() {
        return getPreferences().getBoolean(KEY_RESPONSE_STREAMING, true);
    }

    public static void setResponseStreaming(boolean value) {
        getEditor().putBoolean(KEY_RESPONSE_STREAMING, value).apply();
    }

    public static boolean getDisableTools() {
        return getPreferences().getBoolean(KEY_DISABLE_TOOLS, false);
    }

    public static void setDisableTools(boolean value) {
        getEditor().putBoolean(KEY_DISABLE_TOOLS, value).apply();
    }

    public static int getTemperature() {
        return getPreferences().getInt(KEY_TEMPERATURE, 10);
    }

    public static void setTemperature(int value) {
        getEditor().putInt(KEY_TEMPERATURE, value).apply();
    }

    public static boolean getShowResponseOnly() {
        return getPreferences().getBoolean(KEY_SHOW_RESPONSE_ONLY, false);
    }

    public static void setShowResponseOnly(boolean value) {
        getEditor().putBoolean(KEY_SHOW_RESPONSE_ONLY, value).apply();
    }

    public static boolean getInsertAsQuote() {
        return getPreferences().getBoolean(KEY_INSERT_AS_QUOTE, true);
    }

    public static void setInsertAsQuote(boolean value) {
        getEditor().putBoolean(KEY_INSERT_AS_QUOTE, value).apply();
    }

    public static boolean getReplaceTelegramEditor(int account) {
        return getPreferences().getBoolean(KEY_REPLACE_TELEGRAM_EDITOR, !UserConfig.getInstance(account).isPremium());
    }

    public static void setReplaceTelegramEditor(boolean value) {
        getEditor().putBoolean(KEY_REPLACE_TELEGRAM_EDITOR, value).apply();
    }

    public static boolean getReplaceTelegramSummaries(int account) {
        return getPreferences().getBoolean(KEY_REPLACE_TELEGRAM_SUMMARIES, !UserConfig.getInstance(account).isPremium());
    }

    public static void setReplaceTelegramSummaries(boolean value) {
        getEditor().putBoolean(KEY_REPLACE_TELEGRAM_SUMMARIES, value).apply();
    }

    public static String getSelectedServiceId() {
        return getPreferences().getString(KEY_SELECTED_SERVICE_ID, null);
    }

    public static void setSelectedServiceId(String value) {
        getEditor().putString(KEY_SELECTED_SERVICE_ID, value).apply();
    }

    private static int getSelectedServiceHash() {
        return getPreferences().getInt(KEY_SELECTED_SERVICE_HASH, DEFAULT_SERVICE.getLegacyHash());
    }

    private static void setSelectedServiceHash(int value) {
        getEditor().putInt(KEY_SELECTED_SERVICE_HASH, value).apply();
    }

    public static String getSelectedRole() {
        return getPreferences().getString(KEY_SELECTED_ROLE,
                Suggestions.ASSISTANT.getRole().getName());
    }

    public static void setSelectedRole(String value) {
        getEditor().putString(KEY_SELECTED_ROLE, value).apply();
    }

    public static void setSelectedAiRole(Role role) {
        setSelectedRole(role == null ? null : role.getName());
    }

    public static ArrayList<Service> getServices() {
        ArrayList<Service> services = read(KEY_SERVICES,
                new TypeToken<ArrayList<Service>>() {}.getType());
        boolean changed = false;
        for (Service service : services) {
            if (service.ensureId()) {
                changed = true;
            }
        }
        if (changed) {
            saveServices(services);
        }
        return services;
    }

    public static void saveServices(ArrayList<Service> services) {
        getEditor().putString(KEY_SERVICES, GSON.toJson(services)).apply();
    }

    public static ArrayList<Role> getRoles() {
        return read(KEY_ROLES, new TypeToken<ArrayList<Role>>() {}.getType());
    }

    public static void saveRoles(ArrayList<Role> roles) {
        getEditor().putString(KEY_ROLES, GSON.toJson(roles)).apply();
    }

    public static ArrayList<Message> getConversationHistory() {
        return read(KEY_HISTORY, new TypeToken<ArrayList<Message>>() {}.getType());
    }

    public static void saveConversationHistory(ArrayList<Message> history) {
        getEditor().putString(KEY_HISTORY, GSON.toJson(history)).apply();
    }

    public static void clearConversationHistory() {
        getEditor().remove(KEY_HISTORY).apply();
    }

    public static void removeLastFromHistory() {
        ArrayList<Message> history = getConversationHistory();
        if (history.isEmpty()) {
            return;
        }
        history.remove(history.size() - 1);
        saveConversationHistory(history);
    }

    public static Service getSelectedService() {
        ArrayList<Service> services = getServices();
        if (services.isEmpty()) {
            return DEFAULT_SERVICE;
        }
        String selectedId = getSelectedServiceId();
        if (!TextUtils.isEmpty(selectedId)) {
            for (Service service : services) {
                if (Objects.equals(service.getId(), selectedId)) {
                    return available(service);
                }
            }
        }
        int legacyHash = getSelectedServiceHash();
        for (Service service : services) {
            if (service.getLegacyHash() == legacyHash) {
                setSelectedServiceId(service.getId());
                return available(service);
            }
        }
        return available(services.get(0));
    }

    private static Service available(Service service) {
        return service.isChatGpt() && !app.exteraless.ai.chatgpt.ChatGptAuth.getInstance().isSignedIn()
                ? DEFAULT_SERVICE : service;
    }

    public static void setSelectedServices(Service service) {
        if (service == null) {
            clearSelectedService();
            return;
        }
        getEditor()
                .putString(KEY_SELECTED_SERVICE_ID, service.getId())
                .putInt(KEY_SELECTED_SERVICE_HASH, service.getLegacyHash())
                .apply();
    }

    public static void clearSelectedService() {
        getEditor().remove(KEY_SELECTED_SERVICE_ID).remove(KEY_SELECTED_SERVICE_HASH).apply();
    }

    private static <T> ArrayList<T> read(String key, java.lang.reflect.Type type) {
        String raw = getPreferences().getString(key, null);
        if (TextUtils.isEmpty(raw)) {
            return new ArrayList<>();
        }
        try {
            ArrayList<T> parsed = GSON.fromJson(raw, type);
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (Exception e) {
            FileLog.e("AiConfig: cannot read " + key, e);
            return new ArrayList<>();
        }
    }
}
