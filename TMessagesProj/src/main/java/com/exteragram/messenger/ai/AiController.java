package com.exteragram.messenger.ai;

import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import app.exteraless.ai.AiConfig;
import app.exteraless.ai.data.Role;
import app.exteraless.ai.data.Service;

public final class AiController {

    private static final AiController instance = new AiController();

    private AiController() {
    }

    public static AiController getInstance() {
        return instance;
    }

    public static boolean canUseAI() {
        return app.exteraless.ai.AiController.canUseAI();
    }

    public List<Role> getRoles() {
        return app.exteraless.ai.AiController.getAllRoles();
    }

    public List<Role> getSuggestedRoles() {
        return app.exteraless.ai.AiController.getSuggestedRoles();
    }

    public boolean addRole(Role role) {
        return app.exteraless.ai.AiController.addRole(role);
    }

    public void removeRole(Role role) {
        app.exteraless.ai.AiController.removeRole(role);
    }

    public Role getSelectedRole() {
        return app.exteraless.ai.AiController.getSelectedRole();
    }

    public void setSelectedRole(Role role) {
        AiConfig.setSelectedAiRole(role);
    }

    public List<Service> getAll() {
        return new ArrayList<>(AiConfig.getServices());
    }

    public void addService(Service service) {
        app.exteraless.ai.AiController.saveService(service);
    }

    public void removeService(Service service) {
        app.exteraless.ai.AiController.removeService(service);
    }

    public Service getSelected() {
        return app.exteraless.ai.AiController.getSelected();
    }

    public boolean isServicesEmpty() {
        return AiConfig.getServices().isEmpty();
    }

    public boolean isCustomRole(Role role) {
        return role != null && !role.isSuggestion();
    }

    public boolean isSuggestedRole(Role role) {
        return role != null && role.isSuggestion();
    }

    public void loadRoles() {
        AiConfig.getRoles();
    }

    public void loadServices() {
        AiConfig.getServices();
    }

    public void saveRoles() {
        AiConfig.saveRoles(AiConfig.getRoles());
    }

    public void saveServices() {
        AiConfig.saveServices(AiConfig.getServices());
    }

    public boolean updateRole(Role from, Role to) {
        if (from == null || to == null) {
            return false;
        }
        app.exteraless.ai.AiController.removeRole(from);
        return app.exteraless.ai.AiController.addRole(to);
    }

    public void updateService(Service from, Service to) {
        if (from != null) {
            app.exteraless.ai.AiController.removeService(from);
        }
        if (to != null) {
            app.exteraless.ai.AiController.saveService(to);
        }
    }

    public static void clearHistory(BaseFragment fragment, Theme.ResourcesProvider provider,
                                    boolean confirm) {
        clearHistory(fragment, provider, confirm, null);
    }

    public static void clearHistory(BaseFragment fragment, Theme.ResourcesProvider provider,
                                    boolean confirm, Runnable after) {
        if (fragment == null) {
            return;
        }
        if (!confirm || fragment.getParentActivity() == null) {
            applyClear(fragment, after);
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), provider);
        builder.setTitle(LocaleController.getString(R.string.ClearHistory));
        builder.setMessage(AndroidUtilities.replaceTags(
                LocaleController.getString(R.string.OEAiClearHistoryInfo)));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.ClearButton),
                (dialog, which) -> applyClear(fragment, after));
        AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold, provider));
        }
    }

    private static void applyClear(BaseFragment fragment, Runnable after) {
        AiConfig.clearConversationHistory();
        if (after != null) {
            after.run();
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_delete,
                LocaleController.getString(R.string.OEAiHistoryCleared)).show();
    }

    public static void showErrorBulletin(BaseFragment fragment, int code) {
        if (fragment != null) {
            BulletinFactory.of(fragment).createErrorBulletin(errorText(code)).show();
        }
    }

    public static void showErrorBulletin(ViewGroup container, Theme.ResourcesProvider provider,
                                         int code) {
        if (container instanceof FrameLayout) {
            BulletinFactory.of((FrameLayout) container, provider)
                    .createErrorBulletin(errorText(code)).show();
        }
    }

    private static String errorText(int code) {
        final String failed = LocaleController.getString(R.string.OEAiFailed);
        return code == 0 ? failed : failed + " \u00b7 " + code;
    }

    public static boolean canSendImage(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".heif");
    }

    public static boolean canSendImage(MessageObject messageObject) {
        if (messageObject == null) {
            return false;
        }
        File file = FileLoader.getInstance(messageObject.currentAccount)
                .getPathToMessage(messageObject.messageOwner);
        return file != null && canSendImage(file.getAbsolutePath());
    }
}
