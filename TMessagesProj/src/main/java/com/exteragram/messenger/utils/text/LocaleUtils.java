package com.exteragram.messenger.utils.text;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.text.style.URLSpan;
import android.view.View;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.FilterCreateActivity;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Components.URLSpanReplacement;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.helpers.TypefaceHelper;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Шим {@code com.exteragram.messenger.utils.text.LocaleUtils}.
 *
 * dex-модули плагинов скомпилированы против него напрямую (MandreTweaks зовёт
 * {@code formatWithUsernames} и {@code fullyFormatText} в конструкторе строки
 * каталога), поэтому подстановка имён из class_aliases.py тут не работает —
 * класс должен существовать под настоящим именем.
 */
public abstract class LocaleUtils {

    private static final Pattern MARKDOWN_LINK_PATTERN =
            Pattern.compile("\\[([^]]+?)]\\(([^)\\s]+)\\)");

    public static String ensureUrlHasHttps(String url) {
        if (url == null) {
            return null;
        }
        if (url.startsWith("http://") || url.startsWith("https://") || url.contains("://")) {
            return url;
        }
        return AndroidUtilities.WEB_URL != null && AndroidUtilities.WEB_URL.matcher(url).matches()
                ? "https://".concat(url) : url;
    }

    public static CharSequence fromHtml(String html) {
        return new SpannableString(Html.fromHtml(html, 0));
    }

    public static CharSequence formatWithURLs(CharSequence text) {
        if (TextUtils.isEmpty(text) || AndroidUtilities.WEB_URL == null) {
            return text;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        Matcher matcher = AndroidUtilities.WEB_URL.matcher(text);
        while (matcher.find()) {
            try {
                builder.setSpan(new URLSpanNoUnderline(ensureUrlHasHttps(matcher.group(0))),
                        matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return builder;
    }

    public static CharSequence formatWithUsernames(CharSequence text) {
        return formatWithUsernames(text, LaunchActivity.getSafeLastFragment());
    }

    public static CharSequence formatWithUsernames(CharSequence text, BaseFragment fragment) {
        return formatWithUsernames(text, fragment, null);
    }

    public static CharSequence formatWithUsernames(CharSequence text, final BaseFragment fragment,
                                                   final Runnable onClick) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '@') {
                start = i;
                continue;
            }
            if (start == -1) {
                continue;
            }
            int end = i + 1;
            if (end != text.length() && (Character.isLetterOrDigit(text.charAt(end))
                    || text.charAt(end) == '_')) {
                continue;
            }
            if (end - start > 1) {
                URLSpan[] existing = builder.getSpans(start, end, URLSpan.class);
                if (existing == null || existing.length == 0) {
                    final String username = text.subSequence(start, end).toString();
                    try {
                        builder.setSpan(new URLSpanNoUnderline(username) {
                            @Override
                            public void onClick(View view) {
                                if (onClick != null) {
                                    onClick.run();
                                }
                                if (fragment == null || fragment.getMessagesController() == null) {
                                    return;
                                }
                                fragment.getMessagesController()
                                        .openByUserName(username.substring(1), fragment, 1);
                            }
                        }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
            start = -1;
        }
        return builder;
    }

    public static void parseMarkdownLinks(CharSequence[] holder) {
        parseMarkdownLinks(holder, null);
    }

    public static void parseMarkdownLinks(CharSequence[] holder, final Runnable onClick) {
        if (holder == null || holder.length == 0 || holder[0] == null) {
            return;
        }
        CharSequence text = holder[0];
        Spannable spannable = text instanceof Spannable
                ? (Spannable) text
                : Spannable.Factory.getInstance().newSpannable(text.toString());
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(spannable);
        ArrayList<String> sources = new ArrayList<>();
        ArrayList<CharSequence> replacements = new ArrayList<>();
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end(1);
            if (start < 0 || end < 0 || start > end || end > spannable.length()) {
                continue;
            }
            SpannableStringBuilder label =
                    new SpannableStringBuilder(spannable.subSequence(start, end));
            label.setSpan(new URLSpanReplacement(ensureUrlHasHttps(matcher.group(2))) {
                @Override
                public void onClick(View view) {
                    if (onClick != null) {
                        onClick.run();
                    }
                    super.onClick(view);
                }
            }, 0, label.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sources.add(matcher.group(0));
            replacements.add(label);
        }
        if (sources.isEmpty()) {
            return;
        }
        holder[0] = TextUtils.replace(holder[0], sources.toArray(new String[0]),
                replacements.toArray(new CharSequence[0]));
    }

    public static CharSequence fullyFormatText(CharSequence text) {
        return fullyFormatText(text, null, null);
    }

    public static CharSequence fullyFormatText(CharSequence text, BaseFragment fragment,
                                               Runnable onClick) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        CharSequence[] holder = new CharSequence[]{formatWithURLs(text)};
        parseMarkdownLinks(holder, onClick);
        CharSequence formatted = holder[0];
        return AndroidUtilities.replaceTags(fragment == null || onClick == null
                ? formatWithUsernames(formatted)
                : formatWithUsernames(formatted, fragment, onClick));
    }

    public static String getAppName() {
        try {
            return ApplicationLoader.applicationContext.getString(R.string.OpenExtera);
        } catch (Exception e) {
            return "exteraless";
        }
    }

    public static String getActionBarTitle() {
        return getActionBarTitle(UserConfig.selectedAccount);
    }

    public static String getActionBarTitle(int account) {
        try {
            CharSequence title = TypefaceHelper.getTitleText(account);
            if (!TextUtils.isEmpty(title)) {
                return title.toString();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return getAppName();
    }

    public static CharSequence formatWithHtmlURLs(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(new SpannableString(text));
        URLSpan[] spans = builder.getSpans(0, builder.length(), URLSpan.class);
        for (URLSpan span : spans) {
            int start = builder.getSpanStart(span);
            int end = builder.getSpanEnd(span);
            String url = span.getURL();
            builder.removeSpan(span);
            builder.setSpan(new URLSpanNoUnderline(url), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }

    public static Spannable createCopySpan(BaseFragment fragment) {
        SpannableString span = new SpannableString(" ");
        if (fragment == null || fragment.getParentActivity() == null) {
            return span;
        }
        Drawable drawable = ContextCompat.getDrawable(fragment.getParentActivity(), R.drawable.msg_copy);
        if (drawable == null) {
            return span;
        }
        drawable = drawable.mutate();
        drawable.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_undo_cancelColor, fragment.getResourceProvider()),
                PorterDuff.Mode.SRC_IN));
        drawable.setBounds(0, 0, AndroidUtilities.dp(22), AndroidUtilities.dp(22));
        span.setSpan(new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    public static CharSequence applyNewSpan(CharSequence text) {
        SpannableStringBuilder builder =
                new SpannableStringBuilder(text == null ? "" : text);
        builder.append("  d");
        FilterCreateActivity.NewSpan newSpan = new FilterCreateActivity.NewSpan(10f);
        newSpan.setText("NEW");
        newSpan.setTypeface(AndroidUtilities.bold());
        newSpan.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        builder.setSpan(newSpan, builder.length() - 1, builder.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    public static boolean canUseLocalPremiumEmojis() {
        return false;
    }

    public static boolean canUseLocalPremiumEmojis(int account) {
        return false;
    }

    public static String normalizeResourceLanguage(String language) {
        if (TextUtils.isEmpty(language)) {
            return null;
        }
        String lower = language.toLowerCase(Locale.US);
        if ("he".equals(lower)) {
            return "iw";
        }
        return "no".equals(lower) ? "nb" : lower;
    }

    public static String normalizeResourceRegion(String language, String region) {
        if (TextUtils.isEmpty(region)) {
            return null;
        }
        String upper = region.toUpperCase(Locale.US);
        if (!"zh".equals(normalizeResourceLanguage(language))) {
            return upper;
        }
        if ("HANS".equals(upper) || "CN".equals(upper) || "SG".equals(upper)) {
            return "CN";
        }
        return "TW";
    }
}
