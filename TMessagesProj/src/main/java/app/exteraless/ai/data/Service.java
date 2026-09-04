package app.exteraless.ai.data;

import android.text.TextUtils;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import app.exteraless.ai.AiConfig;

public class Service implements Serializable {

    public static final int REASONING_OFF = 0;
    public static final int REASONING_LOW = 1;
    public static final int REASONING_MEDIUM = 2;
    public static final int REASONING_HIGH = 3;

    private String id;
    private String name;
    private String url;
    private String model;
    private String key;
    private boolean reasoningEnabled;
    private Integer reasoningEffort;

    public Service(String id, String url, String model, String key) {
        this(id, url, model, key, false);
    }

    public Service(String id, String url, String model, String key, boolean reasoningEnabled) {
        this.id = id;
        this.url = url;
        this.model = model;
        this.key = key;
        this.reasoningEnabled = reasoningEnabled;
    }

    public Service(String url, String model, String key, boolean reasoningEnabled) {
        this(UUID.randomUUID().toString(), url, model, key, reasoningEnabled);
    }

    public boolean ensureId() {
        if (id != null && !id.isEmpty()) {
            return false;
        }
        id = UUID.randomUUID().toString();
        return true;
    }

    public String getId() {
        ensureId();
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        if (!TextUtils.isEmpty(name)) {
            return name;
        }
        Provider provider = Provider.matching(url);
        if (provider != null) {
            return provider.getTitle();
        }
        return getShortModel();
    }

    public Service copy() {
        Service copy = new Service(url, model, key, false);
        copy.setName(name);
        copy.setReasoningEffort(getReasoningEffort());
        return copy;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = normalizeUrl(url);
    }

    public static String normalizeUrl(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        int separator = trimmed.lastIndexOf("://");
        if (separator <= 0) {
            return "https://" + trimmed;
        }
        String scheme = trimmed.substring(0, separator).toLowerCase();
        String rest = trimmed.substring(separator + 3);
        return (scheme.endsWith("http") ? "http://" : "https://") + rest;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public boolean isReasoningEnabled() {
        return getReasoningEffort() > 0;
    }

    public void setReasoningEnabled(boolean reasoningEnabled) {
        this.reasoningEnabled = reasoningEnabled;
        this.reasoningEffort = reasoningEnabled ? REASONING_MEDIUM : REASONING_OFF;
    }

    public int getReasoningEffort() {
        if (reasoningEffort == null) {
            return reasoningEnabled ? REASONING_MEDIUM : REASONING_OFF;
        }
        return Math.max(REASONING_OFF, Math.min(REASONING_HIGH, reasoningEffort));
    }

    public void setReasoningEffort(int effort) {
        this.reasoningEffort = Math.max(REASONING_OFF, Math.min(REASONING_HIGH, effort));
        this.reasoningEnabled = this.reasoningEffort > REASONING_OFF;
    }

    public String getShortModel() {
        if (model == null) {
            return "";
        }
        String[] parts = model.split("/");
        String tail = parts[parts.length - 1];
        int colon = tail.indexOf(':');
        return colon != -1 ? tail.substring(0, colon) : tail;
    }

    public int getLegacyHash() {
        return (url + model + key).hashCode();
    }

    public boolean isSelected() {
        String selectedId = AiConfig.getSelectedServiceId();
        if (!TextUtils.isEmpty(selectedId)) {
            return Objects.equals(selectedId, getId());
        }
        Service selected = AiConfig.getSelectedService();
        return selected != null && Objects.equals(selected.getId(), getId());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        Service service = (Service) other;
        return Objects.equals(url, service.url)
                && Objects.equals(model, service.model)
                && Objects.equals(key, service.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, model, key);
    }
}
