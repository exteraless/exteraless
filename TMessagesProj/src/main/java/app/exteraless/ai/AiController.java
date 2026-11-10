package app.exteraless.ai;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import app.exteraless.ai.data.Role;
import app.exteraless.ai.data.Service;
import app.exteraless.ai.data.Suggestions;

public final class AiController {

    private AiController() {
    }

    public static boolean canUseAI() {
        Service service = AiConfig.getSelectedService();
        if (service != null && service.isChatGpt()) {
            return app.exteraless.ai.chatgpt.ChatGptAuth.getInstance().canUsePlan();
        }
        return service != null && !TextUtils.isEmpty(service.getKey())
                && !TextUtils.isEmpty(service.getUrl()) && !TextUtils.isEmpty(service.getModel());
    }

    public static Service getSelected() {
        return AiConfig.getSelectedService();
    }

    public static void saveService(Service service) {
        if (service == null) {
            return;
        }
        ArrayList<Service> services = AiConfig.getServices();
        boolean replaced = false;
        for (int a = 0; a < services.size(); a++) {
            if (Objects.equals(services.get(a).getId(), service.getId())) {
                services.set(a, service);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            services.add(service);
        }
        AiConfig.saveServices(services);
        AiConfig.setSelectedServices(service);
    }

    public static void removeService(Service service) {
        if (service == null) {
            return;
        }
        ArrayList<Service> services = AiConfig.getServices();
        services.removeIf(item -> Objects.equals(item.getId(), service.getId()));
        AiConfig.saveServices(services);
        if (services.isEmpty()) {
            AiConfig.clearSelectedService();
        } else {
            AiConfig.setSelectedServices(services.get(0));
        }
    }

    public static List<Role> getSuggestedRoles() {
        ArrayList<Role> roles = new ArrayList<>();
        for (Suggestions suggestion : Suggestions.values()) {
            roles.add(suggestion.getRole());
        }
        return roles;
    }

    public static List<Role> getAllRoles() {
        ArrayList<Role> roles = new ArrayList<>(getSuggestedRoles());
        roles.addAll(AiConfig.getRoles());
        return roles;
    }

    public static Role getSelectedRole() {
        String selected = AiConfig.getSelectedRole();
        for (Role role : getAllRoles()) {
            if (TextUtils.equals(role.getName(), selected)) {
                return role;
            }
        }
        return Suggestions.ASSISTANT.getRole();
    }

    public static boolean addRole(Role role) {
        if (role == null || TextUtils.isEmpty(role.getName())) {
            return false;
        }
        ArrayList<Role> roles = AiConfig.getRoles();
        for (Role existing : roles) {
            if (TextUtils.equals(existing.getName(), role.getName())) {
                return false;
            }
        }
        roles.add(role);
        AiConfig.saveRoles(roles);
        return true;
    }

    public static void removeRole(Role role) {
        if (role == null) {
            return;
        }
        ArrayList<Role> roles = AiConfig.getRoles();
        roles.removeIf(item -> TextUtils.equals(item.getName(), role.getName()));
        AiConfig.saveRoles(roles);
        if (TextUtils.equals(AiConfig.getSelectedRole(), role.getName())) {
            AiConfig.setSelectedRole(Suggestions.ASSISTANT.getRole().getName());
        }
    }
}
