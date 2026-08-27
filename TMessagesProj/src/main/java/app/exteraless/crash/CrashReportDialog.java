package app.exteraless.crash;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

public final class CrashReportDialog {

    private static boolean shown;

    private CrashReportDialog() {
    }

    public static void showIfPending(Activity activity) {
        if (shown || activity == null || activity.isFinishing()) {
            return;
        }
        final String report = CrashLog.take();
        if (TextUtils.isEmpty(report)) {
            return;
        }
        shown = true;
        AndroidUtilities.runOnUIThread(() -> show(activity, report), 400);
    }

    private static void show(Activity activity, String report) {
        if (activity.isFinishing()) {
            return;
        }
        final Context context = activity;

        final TextView textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        textView.setTypeface(android.graphics.Typeface.MONOSPACE);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        textView.setText(report);
        textView.setTextIsSelectable(true);
        textView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), AndroidUtilities.dp(4));

        final ScrollView scrollView = new ScrollView(context);
        scrollView.addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final FrameLayout container = new FrameLayout(context);
        container.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                (int) (AndroidUtilities.displaySize.y * 0.4f / AndroidUtilities.density), Gravity.TOP));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.OECrashTitle));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Copy), (dialog, which) -> {
            copy(context, report);
            dialog.dismiss();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Close), (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private static void copy(Context context, String report) {
        try {
            final ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("exteraless crash", report));
            }
        } catch (Throwable ignore) {
            return;
        }
        if (LaunchActivity.getLastFragment() != null) {
            BulletinFactory.of(LaunchActivity.getLastFragment())
                    .createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        }
    }
}
