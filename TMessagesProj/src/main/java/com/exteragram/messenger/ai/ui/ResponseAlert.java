package com.exteragram.messenger.ai.ui;

import android.content.Context;

import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import app.exteraless.ai.AiConfig;
import app.exteraless.ai.network.Client;
import app.exteraless.ai.ui.AiResponseSheet;

public final class ResponseAlert {

    private ResponseAlert() {
    }

    public static void showAlert(BaseFragment fragment, Client client, String prompt,
                                 boolean allowInsert, boolean noforwards, Object unusedMessage,
                                 Theme.ResourcesProvider resourcesProvider,
                                 Utilities.Callback2<String, String> onInsert) {
        BaseFragment target = fragment != null ? fragment : LaunchActivity.getSafeLastFragment();
        Context context = target != null ? target.getParentActivity() : null;
        if (context == null) {
            return;
        }
        Theme.ResourcesProvider provider = resourcesProvider != null
                ? resourcesProvider : target.getResourceProvider();
        boolean insert = allowInsert && !noforwards && !AiConfig.getShowResponseOnly()
                && onInsert != null;
        Utilities.Callback<String> insertCallback = insert
                ? response -> onInsert.run(prompt, response) : null;
        AiResponseSheet.show(context, provider, prompt, insert, insertCallback,
                null, AiConfig.getSaveHistory(), null, null, client);
    }

    public static void showAlert(BaseFragment fragment, Client client, String prompt,
                                 boolean allowInsert,
                                 Utilities.Callback2<String, String> onInsert) {
        showAlert(fragment, client, prompt, allowInsert, false, null, null, onInsert);
    }
}
