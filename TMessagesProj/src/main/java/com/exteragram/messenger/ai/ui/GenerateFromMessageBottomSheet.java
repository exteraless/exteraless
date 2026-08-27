package com.exteragram.messenger.ai.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

public class GenerateFromMessageBottomSheet extends BottomSheet {

    public static final class GenerationData {

        private final String prompt;
        private final boolean useHistory;
        private final String imagePath;

        public GenerationData(String prompt, boolean useHistory, String imagePath) {
            this.prompt = prompt;
            this.useHistory = useHistory;
            this.imagePath = imagePath;
        }

        public String prompt() {
            return prompt;
        }

        public boolean useHistory() {
            return useHistory;
        }

        public String imagePath() {
            return imagePath;
        }
    }

    private final EditTextBoldCursor editText;

    public BaseFragment parentFragment;

    public GenerateFromMessageBottomSheet(BaseFragment fragment, Context context,
                                          Utilities.Callback<GenerationData> callback,
                                          boolean useHistory) {
        this(null, null, fragment, context, callback, useHistory);
    }

    public GenerateFromMessageBottomSheet(String value, String hint, BaseFragment fragment,
                                          Context context,
                                          Utilities.Callback<GenerationData> callback) {
        this(value, hint, fragment, context, callback, false);
    }

    public GenerateFromMessageBottomSheet(String value, String hint, BaseFragment fragment,
                                          Context context,
                                          Utilities.Callback<GenerationData> callback,
                                          boolean useHistory) {
        super(context, true, fragment == null ? null : fragment.getResourceProvider());

        parentFragment = fragment;
        Theme.ResourcesProvider provider = fragment == null ? null : fragment.getResourceProvider();

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(21), dp(12), dp(21), dp(16));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, provider));
        title.setText(getString(R.string.OEAiAsk));
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, provider));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, provider));
        editText.setBackgroundDrawable(null);
        editText.setSingleLine(true);
        editText.setHint(TextUtils.isEmpty(hint) ? getString(R.string.OEAiAsk) : hint);
        editText.setText(value == null ? "" : value);
        editText.setSelection(editText.getText().length());
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 14, 0, 14));

        TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, provider));
        button.setBackground(Theme.AdaptiveRipple.filledRect(
                Theme.getColor(Theme.key_featuredStickers_addButton, provider), 10));
        button.setText(getString(R.string.OEAiAsk));
        button.setOnClickListener(v -> {
            dismiss();
            if (callback != null) {
                callback.run(new GenerationData(editText.getText().toString(), useHistory, null));
            }
        });
        container.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46));

        setCustomView(container);
    }

    @Override
    public void show() {
        super.show();
        AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 120);
    }
}
