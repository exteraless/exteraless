package app.exteraless.plugins.ui.catalog;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.net.URI;
import java.util.Locale;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.LinkResolver;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;

final class CatalogTextCell extends FrameLayout implements Theme.Colorable {

    static final class Factory extends UItem.UItemFactory<CatalogTextCell> {
        static { setup(new Factory()); }

        @Override
        public CatalogTextCell createView(Context context, RecyclerListView listView,
                                           int currentAccount, int classGuid,
                                           Theme.ResourcesProvider resourcesProvider) {
            return new CatalogTextCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            ((CatalogTextCell) view).setMarkdown(item.text, item.subtext);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            return first.id == second.id;
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            return TextUtils.equals(first.text, second.text)
                    && TextUtils.equals(first.subtext, second.subtext);
        }

        static UItem asText(int id, CharSequence value, CharSequence baseUrl) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.text = value;
            item.subtext = baseUrl;
            return item;
        }
    }

    private final LinkSpanDrawable.LinksTextView textView;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Markwon markwon;
    private CharSequence rawMarkdown;
    private String baseUrl;

    private CatalogTextCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        markwon = Markwon.builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureConfiguration(
                            @NonNull MarkwonConfiguration.Builder builder) {
                        builder.linkResolver(new LinkResolver() {
                            @Override
                            public void resolve(@NonNull View view, @NonNull String link) {
                                String resolved = resolveSafeLink(link);
                                if (resolved != null) {
                                    Browser.openUrl(view.getContext(), resolved);
                                }
                            }
                        });
                    }
                })
                .build();
        setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14),
                AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        textView = new LinkSpanDrawable.LinksTextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setLinkTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        textView.setLineSpacing(AndroidUtilities.dp(3), 1f);
        textView.setTextIsSelectable(true);
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));
    }

    private void setMarkdown(CharSequence source, CharSequence sourceBaseUrl) {
        rawMarkdown = source;
        baseUrl = sourceBaseUrl == null ? null : sourceBaseUrl.toString();
        markwon.setMarkdown(textView, source == null ? "" : source.toString());
    }

    private String resolveSafeLink(String value) {
        if (TextUtils.isEmpty(value)) return null;
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute()) {
                if (TextUtils.isEmpty(baseUrl)) return null;
                URI base = new URI(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
                uri = base.resolve(uri);
            }
            String scheme = uri.getScheme();
            if (scheme == null || uri.getRawUserInfo() != null) return null;
            switch (scheme.toLowerCase(Locale.ROOT)) {
                case "https":
                case "http":
                    return TextUtils.isEmpty(uri.getHost()) ? null : uri.toString();
                case "tg":
                case "mailto":
                    return uri.toString();
                default:
                    return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    @Override
    public void updateColors() {
        textView.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setLinkTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        if (rawMarkdown != null) setMarkdown(rawMarkdown, baseUrl);
    }
}
