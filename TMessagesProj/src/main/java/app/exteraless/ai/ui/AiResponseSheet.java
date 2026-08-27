package app.exteraless.ai.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.ArrayList;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.exteraless.ai.AiConfig;
import app.exteraless.appearance.M3CircularProgress;
import app.exteraless.ai.AiController;
import app.exteraless.ai.data.Message;
import app.exteraless.ai.data.Role;
import app.exteraless.ai.network.Client;
import app.exteraless.ai.network.GenerationCallback;

public class AiResponseSheet extends BottomSheet {

    private static final String CURSOR = "▏";
    private static final long CURSOR_PERIOD = 480;
    private static final long STATUS_PERIOD = 400;
    private static final int STATUS_TAIL = 140;
    private static final Pattern DETAILS = Pattern.compile(
            "(?is)<details[^>]*>(.*?)</details>");
    private static final Pattern SUMMARY = Pattern.compile(
            "(?is)^\\s*<summary[^>]*>(.*?)</summary>");
    private static final Pattern PARTIAL_TAG = Pattern.compile("<[a-zA-Z/][^>]*$");

    private final String requestId = UUID.randomUUID().toString();
    private final Client client;
    private final String prompt;
    private final boolean allowInsert;
    private final Utilities.Callback<String> onInsert;
    private final Utilities.Callback<String> onComplete;

    private final AnimatedTextView statusView;
    private final LinearLayout reasoningBlock;
    private final TextView reasoningTitle;
    private final ImageView reasoningChevron;
    private final TextView reasoningText;
    private final TextView reasoningPreview;
    private final ScrollView reasoningScroll;
    private final TextView answerView;
    private final LinearLayout answerContainer;
    private final RadialProgressView loadingView;
    private final TextView primaryButton;
    private final ImageView copyButton;
    private final ScrollView scrollView;

    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private boolean reasoningExpanded;
    private long reasoningStarted;
    private long reasoningSpent;
    private long statusUpdated;
    private final Runnable cursorTick = this::blinkCursor;
    private boolean cursorVisible = true;
    private boolean finished;
    private boolean failed;
    private final boolean useHistory;

    public static void show(Context context, Theme.ResourcesProvider resourcesProvider,
                            String prompt, boolean allowInsert,
                            Utilities.Callback<String> onInsert) {
        show(context, resourcesProvider, prompt, allowInsert, onInsert, null, true);
    }

    public static void show(Context context, Theme.ResourcesProvider resourcesProvider,
                            String prompt, boolean allowInsert,
                            Utilities.Callback<String> onInsert, Role role, boolean useHistory) {
        show(context, resourcesProvider, prompt, allowInsert, onInsert, role, useHistory,
                null, null);
    }

    public static void show(Context context, Theme.ResourcesProvider resourcesProvider,
                            String prompt, boolean allowInsert,
                            Utilities.Callback<String> onInsert, Role role, boolean useHistory,
                            String cachedAnswer, Utilities.Callback<String> onComplete) {
        show(context, resourcesProvider, prompt, allowInsert, onInsert, role, useHistory,
                cachedAnswer, onComplete, null);
    }

    public static void show(Context context, Theme.ResourcesProvider resourcesProvider,
                            String prompt, boolean allowInsert,
                            Utilities.Callback<String> onInsert, Role role, boolean useHistory,
                            String cachedAnswer, Utilities.Callback<String> onComplete,
                            Client clientOverride) {
        if (context == null || TextUtils.isEmpty(prompt)) {
            return;
        }
        if (TextUtils.isEmpty(cachedAnswer) && !AiController.canUseAI()) {
            BulletinFactory.global().createErrorBulletin(
                    getString(R.string.OEAiNotConfigured)).show();
            return;
        }
        new AiResponseSheet(context, resourcesProvider, prompt, allowInsert, onInsert,
                role, useHistory, cachedAnswer, onComplete, clientOverride).show();
    }

    private AiResponseSheet(Context context, Theme.ResourcesProvider resourcesProvider,
                            String prompt, boolean allowInsert,
                            Utilities.Callback<String> onInsert, Role role, boolean useHistory,
                            String cachedAnswer, Utilities.Callback<String> onComplete,
                            Client clientOverride) {
        super(context, false, resourcesProvider);
        this.prompt = prompt;
        this.allowInsert = allowInsert;
        this.onInsert = onInsert;
        this.onComplete = onComplete;
        this.useHistory = useHistory;
        this.client = clientOverride != null ? clientOverride
                : new Client.Builder().roleOverride(role).build();

        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(18), dp(10), dp(18), dp(14));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImageResource(R.drawable.ai_chat);
        icon.setColorFilter(new PorterDuffColorFilter(
                getThemedColor(Theme.key_featuredStickers_buttonText), PorterDuff.Mode.SRC_IN));
        icon.setBackground(Theme.createRoundRectDrawable(dp(20), accent));
        icon.setPadding(dp(9), dp(9), dp(9), dp(9));
        header.addView(icon, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL,
                0, 0, 12, 0));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(AiController.getSelectedRole().getName());
        titles.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        statusView = new AnimatedTextView(context, true, true, true);
        statusView.setAnimationProperties(.4f, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        statusView.setTextSize(dp(13));
        statusView.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        statusView.setGravity(Gravity.LEFT);
        statusView.getDrawable().setOverrideFullWidth(AndroidUtilities.displaySize.x);
        statusView.setText(AiController.getSelected().getShortModel(), false);
        titles.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 17,
                0, 1, 0, 0));

        header.addView(titles, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));
        container.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        reasoningBlock = new LinearLayout(context);
        reasoningBlock.setOrientation(LinearLayout.VERTICAL);
        reasoningBlock.setVisibility(View.GONE);
        reasoningBlock.setBackground(Theme.createRoundRectDrawable(dp(14),
                getThemedColor(Theme.key_graySection)));
        reasoningBlock.setPadding(dp(13), dp(9), dp(11), dp(9));

        LinearLayout reasoningHeader = new LinearLayout(context);
        reasoningHeader.setOrientation(LinearLayout.HORIZONTAL);
        reasoningHeader.setGravity(Gravity.CENTER_VERTICAL);
        reasoningHeader.setOnClickListener(v -> toggleReasoning());

        reasoningTitle = new TextView(context);
        reasoningTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        reasoningTitle.setTypeface(AndroidUtilities.bold());
        reasoningTitle.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        reasoningTitle.setText(getString(R.string.OEAiThinking));
        reasoningHeader.addView(reasoningTitle, LayoutHelper.createLinear(0,
                LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        reasoningChevron = new ImageView(context);
        reasoningChevron.setScaleType(ImageView.ScaleType.CENTER);
        reasoningChevron.setImageResource(R.drawable.arrow_more);
        reasoningChevron.setColorFilter(new PorterDuffColorFilter(
                getThemedColor(Theme.key_dialogTextGray2), PorterDuff.Mode.SRC_IN));
        reasoningHeader.addView(reasoningChevron, LayoutHelper.createLinear(22, 22,
                Gravity.CENTER_VERTICAL));

        reasoningBlock.addView(reasoningHeader, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 24));

        reasoningPreview = new TextView(context);
        reasoningPreview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        reasoningPreview.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        reasoningPreview.setSingleLine(true);
        reasoningPreview.setEllipsize(TextUtils.TruncateAt.START);
        reasoningPreview.setVisibility(View.GONE);
        reasoningBlock.addView(reasoningPreview, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 1));

        reasoningText = new TextView(context);
        reasoningText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        reasoningText.setLineSpacing(dp(1), 1f);
        reasoningText.setTextColor(getThemedColor(Theme.key_dialogTextGray2));

        reasoningScroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                        (int) (AndroidUtilities.displaySize.y * 0.28f), MeasureSpec.AT_MOST));
            }
        };
        reasoningScroll.setVerticalScrollBarEnabled(false);
        reasoningScroll.setVisibility(View.GONE);
        reasoningScroll.addView(reasoningText, new FrameLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        reasoningBlock.addView(reasoningScroll, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 2));

        container.addView(reasoningBlock, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        answerView = new TextView(context);
        answerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        answerView.setLineSpacing(dp(2), 1f);
        answerView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));

        answerContainer = new LinearLayout(context);
        answerContainer.setOrientation(LinearLayout.VERTICAL);
        answerContainer.addView(answerView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                        (int) (AndroidUtilities.displaySize.y * 0.62f), MeasureSpec.AT_MOST));
            }
        };
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setMinimumHeight(dp(140));
        scrollView.addView(answerContainer, new FrameLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        loadingView = new RadialProgressView(context, resourcesProvider);
        loadingView.setSize(dp(28));
        loadingView.setStrokeWidth(2.5f);
        loadingView.setProgressColor(accent);
        loadingView.setStyle(M3CircularProgress.STYLE_LOADING_INDICATOR);

        FrameLayout card = new FrameLayout(context);
        card.setBackground(Theme.createRoundRectDrawable(dp(18),
                getThemedColor(Theme.key_graySection)));
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        card.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));
        card.addView(loadingView, LayoutHelper.createFrame(40, 40, Gravity.CENTER));
        container.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 12, 0, 12));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        copyButton = new ImageView(context);
        copyButton.setScaleType(ImageView.ScaleType.CENTER);
        copyButton.setImageResource(R.drawable.msg_copy);
        copyButton.setColorFilter(new PorterDuffColorFilter(
                getThemedColor(Theme.key_dialogTextBlack), PorterDuff.Mode.SRC_IN));
        copyButton.setBackground(Theme.AdaptiveRipple.filledRect(
                getThemedColor(Theme.key_graySection), 10));
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(answer.toString());
            BulletinFactory.global().createCopyBulletin(getString(R.string.TextCopied)).show();
        });
        buttons.addView(copyButton, LayoutHelper.createLinear(48, 46, 0, 0, 8, 0));

        primaryButton = new TextView(context);
        primaryButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        primaryButton.setTypeface(AndroidUtilities.bold());
        primaryButton.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        primaryButton.setBackground(Theme.AdaptiveRipple.filledRect(accent, 10));
        primaryButton.setGravity(Gravity.CENTER);
        primaryButton.setText(getString(R.string.OEAiStop));
        primaryButton.setOnClickListener(v -> onPrimaryClick());
        buttons.addView(primaryButton, LayoutHelper.createLinear(0, 46, 1f, Gravity.NO_GRAVITY));

        container.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        setCustomView(container);
        if (TextUtils.isEmpty(cachedAnswer)) {
            start();
            AndroidUtilities.runOnUIThread(cursorTick, CURSOR_PERIOD);
        } else {
            answer.append(cachedAnswer);
            finish();
        }
    }

    @Override
    protected boolean canDismissWithSwipe() {
        if (!finished) {
            return false;
        }
        if (scrollView != null && scrollView.getScrollY() > 0) {
            return false;
        }
        return reasoningScroll == null || reasoningScroll.getVisibility() != View.VISIBLE
                || reasoningScroll.getScrollY() == 0;
    }

    private void blinkCursor() {
        if (finished) {
            return;
        }
        cursorVisible = !cursorVisible;
        if (answer.length() > 0) {
            answerView.setText(display() + (cursorVisible ? CURSOR : ""));
        }
        AndroidUtilities.runOnUIThread(cursorTick, CURSOR_PERIOD);
    }

    private void onPrimaryClick() {
        if (!finished) {
            client.cancel(requestId);
            finish();
            return;
        }
        if (allowInsert && onInsert != null && !failed && answer.length() > 0) {
            onInsert.run(answer.toString());
        }
        dismiss();
    }

    private void start() {
        ArrayList<Message> messages = useHistory && AiConfig.getSaveHistory()
                ? AiConfig.getConversationHistory() : new ArrayList<>();
        messages.add(new Message("user", prompt));
        client.generate(requestId, messages, new GenerationCallback() {
            @Override
            public void onChunk(String chunk) {
                collapseReasoning();
                answer.append(chunk);
                cursorVisible = true;
                loadingView.setVisibility(View.GONE);
                answerView.setText(display() + CURSOR);
            }

            @Override
            public void onReasoning(String chunk) {
                appendReasoning(chunk);
                showReasoningTail();
            }

            @Override
            public void onResponse(String response) {
                if (answer.length() == 0 && !TextUtils.isEmpty(response)) {
                    answer.append(response);
                }
                if (useHistory && AiConfig.getSaveHistory()) {
                    ArrayList<Message> history = AiConfig.getConversationHistory();
                    history.add(new Message("user", prompt));
                    history.add(new Message("assistant", answer.toString()));
                    AiConfig.saveConversationHistory(history);
                }
                statusView.setText(AiController.getSelected().getShortModel(), true);
                finish();
                if (onComplete != null && !failed && answer.length() > 0) {
                    onComplete.run(answer.toString());
                }
            }

            @Override
            public void onError(int code, String message) {
                failed = true;
                loadingView.setVisibility(View.GONE);
                answerView.setTextColor(getThemedColor(Theme.key_text_RedRegular));
                answerView.setText(TextUtils.isEmpty(message)
                        ? getString(R.string.OEAiFailed) : message);
                statusView.setText(code == 0 ? getString(R.string.OEAiFailed)
                        : getString(R.string.OEAiFailed) + " · " + code, true);
                finish();
            }

        });
    }

    private CharSequence display() {
        String text = answer.toString();
        text = DETAILS.matcher(text).replaceAll("$1");
        text = text.replaceAll("(?is)</?details[^>]*>", "")
                .replaceAll("(?is)</?summary[^>]*>", "");
        text = PARTIAL_TAG.matcher(text).replaceAll("");
        return text;
    }

    private void showReasoningTail() {
        long now = System.currentTimeMillis();
        if (now - statusUpdated < STATUS_PERIOD || reasoningSpent > 0 || reasoningExpanded) {
            return;
        }
        statusUpdated = now;
        String text = reasoning.toString().replace('\n', ' ').trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > STATUS_TAIL) {
            text = text.substring(text.length() - STATUS_TAIL);
        }
        reasoningPreview.setVisibility(View.VISIBLE);
        reasoningPreview.setText(text);
    }

    private void appendReasoning(String chunk) {
        if (TextUtils.isEmpty(chunk)) {
            return;
        }
        if (reasoning.length() == 0) {
            reasoningStarted = System.currentTimeMillis();
            reasoningBlock.setVisibility(View.VISIBLE);
        }
        reasoning.append(chunk);
        reasoningText.setText(reasoning.toString());
        if (reasoningExpanded) {
            reasoningScroll.post(() -> {
                if (reasoningScroll.getChildCount() > 0) {
                    int offset = reasoningScroll.getChildAt(0).getHeight()
                            - reasoningScroll.getHeight();
                    reasoningScroll.scrollTo(0, Math.max(0, offset));
                }
            });
        }
    }

    private void collapseReasoning() {
        if (reasoning.length() == 0 || reasoningSpent > 0) {
            return;
        }
        reasoningSpent = Math.max(1, (System.currentTimeMillis() - reasoningStarted) / 1000);
        reasoningTitle.setText(LocaleController.formatString(R.string.OEAiThoughtTime,
                (int) reasoningSpent));
        reasoningPreview.setVisibility(View.GONE);
        if (reasoningExpanded) {
            toggleReasoning();
        }
    }

    private void toggleReasoning() {
        reasoningExpanded = !reasoningExpanded;
        reasoningScroll.setVisibility(reasoningExpanded ? View.VISIBLE : View.GONE);
        reasoningPreview.setVisibility(!reasoningExpanded && reasoningSpent == 0
                && reasoning.length() > 0 ? View.VISIBLE : View.GONE);
        reasoningChevron.animate().rotation(reasoningExpanded ? 180 : 0)
                .setDuration(180).start();
        if (reasoningExpanded) {
            reasoningScroll.post(() -> {
                if (reasoningScroll.getChildCount() > 0) {
                    int offset = reasoningScroll.getChildAt(0).getHeight()
                            - reasoningScroll.getHeight();
                    reasoningScroll.scrollTo(0, Math.max(0, offset));
                }
            });
        }
    }

    private void finish() {
        finished = true;
        collapseReasoning();
        loadingView.setVisibility(View.GONE);
        AndroidUtilities.cancelRunOnUIThread(cursorTick);
        if (!failed && answer.length() > 0) {
            int position = scrollView.getScrollY();
            buildAnswer(answer.toString());
            scrollView.post(() -> scrollView.scrollTo(0, position));
        }
        copyButton.setVisibility(!failed && answer.length() > 0 ? View.VISIBLE : View.GONE);
        primaryButton.setText(allowInsert && onInsert != null && !failed && answer.length() > 0
                ? getString(R.string.OEAiInsert) : getString(R.string.Close));
    }

    private void buildAnswer(String text) {
        Matcher matcher = DETAILS.matcher(text);
        if (!matcher.find()) {
            fillTextBlock(answerView, format(text));
            return;
        }
        answerContainer.removeAllViews();
        int from = 0;
        matcher.reset();
        while (matcher.find()) {
            addTextBlock(text.substring(from, matcher.start()));
            addDetailsBlock(matcher.group(1));
            from = matcher.end();
        }
        addTextBlock(text.substring(from));
    }

    private void addTextBlock(String text) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(text.trim())) {
            return;
        }
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        view.setLineSpacing(dp(2), 1f);
        view.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        fillTextBlock(view, format(text.trim()));
        answerContainer.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, answerContainer.getChildCount() > 0 ? 10 : 0, 0, 0));
    }

    private void fillTextBlock(TextView view, CharSequence text) {
        view.setText(text);
        view.setTextIsSelectable(true);
    }

    private void addDetailsBlock(String body) {
        String title = getString(R.string.OEAiDetails);
        String content = body == null ? "" : body;
        Matcher summary = SUMMARY.matcher(content);
        if (summary.find()) {
            String found = summary.group(1);
            if (!TextUtils.isEmpty(found) && !TextUtils.isEmpty(found.trim())) {
                title = found.trim();
            }
            content = content.substring(summary.end());
        }
        content = content.trim();
        if (TextUtils.isEmpty(content)) {
            return;
        }

        LinearLayout block = new LinearLayout(getContext());
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(Theme.createRoundRectDrawable(dp(12),
                getThemedColor(Theme.key_dialogBackground)));
        block.setPadding(dp(12), dp(8), dp(10), dp(8));

        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(getContext());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        titleView.setText(title);
        head.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f,
                Gravity.CENTER_VERTICAL));

        ImageView chevron = new ImageView(getContext());
        chevron.setScaleType(ImageView.ScaleType.CENTER);
        chevron.setImageResource(R.drawable.arrow_more);
        chevron.setColorFilter(new PorterDuffColorFilter(
                getThemedColor(Theme.key_featuredStickers_addButton), PorterDuff.Mode.SRC_IN));
        head.addView(chevron, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL));
        block.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 26));

        TextView bodyView = new TextView(getContext());
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        bodyView.setLineSpacing(dp(2), 1f);
        bodyView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        bodyView.setVisibility(View.GONE);
        fillTextBlock(bodyView, format(content));
        block.addView(bodyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 6, 0, 2));

        head.setOnClickListener(v -> {
            boolean shown = bodyView.getVisibility() == View.VISIBLE;
            bodyView.setVisibility(shown ? View.GONE : View.VISIBLE);
            chevron.animate().rotation(shown ? 0 : 180).setDuration(180).start();
        });

        answerContainer.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, answerContainer.getChildCount() > 0 ? 10 : 0, 0, 0));
    }

    private static CharSequence format(String text) {
        try {
            String prepared = text
                    .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*(.+)$", "**$1**")
                    .replaceAll("(?m)^\\s{0,3}[*-]\\s+", "• ");
            SpannableStringBuilder builder = new SpannableStringBuilder(prepared);
            applyMarker(builder, "```", false, true);
            applyMarker(builder, "`", false, true);
            applyMarker(builder, "**", true, false);
            return builder;
        } catch (Throwable e) {
            return text;
        }
    }

    private static void applyMarker(SpannableStringBuilder builder, String marker,
                                    boolean bold, boolean mono) {
        int from = 0;
        while (true) {
            String current = builder.toString();
            int start = current.indexOf(marker, from);
            if (start < 0) {
                return;
            }
            int end = current.indexOf(marker, start + marker.length());
            if (end < 0) {
                return;
            }
            builder.delete(end, end + marker.length());
            builder.delete(start, start + marker.length());
            int spanEnd = end - marker.length();
            if (spanEnd > start) {
                if (bold) {
                    builder.setSpan(new StyleSpan(Typeface.BOLD), start, spanEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (mono) {
                    builder.setSpan(new TypefaceSpan("monospace"), start, spanEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            from = spanEnd;
        }
    }

    @Override
    public void dismiss() {
        AndroidUtilities.cancelRunOnUIThread(cursorTick);
        client.cancel(requestId);
        super.dismiss();
    }
}
