package app.exteraless.components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.utils.ViewOutlineProviderImpl;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProfileMusicView;
import org.telegram.ui.Components.ScaleStateListAnimator;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.nowplaying.LastFmNowPlaying;
import app.exteraless.nowplaying.ProfileMusicMark;
import app.exteraless.nowplaying.ZeroWidthCodec;

public class ProfileMusicCard extends FrameLayout {

    private static final long MUSIC_EMOJI_ID = 5271627010681108586L;

    private static final float[] PATTERN = {
        -5.5f, 20f, 20f, 0.35f,
        -5.5f, -20f, 20f, 0.35f,
        -36f, -42f, 22f, 0.375f,
        -36f, 0f, 25f, 0.425f,
        -36f, 42f, 22f, 0.375f,
        -70f, 22f, 23f, 0.35f,
        -70f, -22f, 23f, 0.35f,
        -99f, 46f, 21f, 0.275f,
        -99f, 0f, 22f, 0.325f,
        -99f, -46f, 21f, 0.275f,
        -128f, -23f, 20f, 0.225f,
        -128f, 23f, 20f, 0.225f
    };

    private final Theme.ResourcesProvider resourcesProvider;
    private final FrameLayout cardLayout;
    private final BackupImageView imageView;
    private final TextView nameView;
    private final TextView artistView;
    private final TextView statusView;
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable emoji;
    private final GradientDrawable background;

    private boolean hasCover;
    private boolean lastfmCover;
    private TLRPC.Document savedDocument;
    private int backgroundColor;
    private int accentColor;
    private long currentEmojiId = -1;
    private long currentDocumentId = -1;
    private long imageToken;
    private long paletteToken = -1;
    private CharSequence savedTitle;
    private CharSequence savedAuthor;
    private String lastfmNick;
    private String lastfmUrl;
    private Runnable onCardClick;

    public ProfileMusicCard(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setPadding(dp(12), 0, dp(12), 0);

        background = new GradientDrawable() {
            @Override
            protected void onBoundsChange(Rect bounds) {
                super.onBoundsChange(bounds);
                setGradientRadius(bounds.width() * 2f);
            }
        };
        background.setDither(true);
        background.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        background.setGradientCenter(1f, 0.5f);
        background.setCornerRadius(dpf2(AppearanceConfig.sectionRadius()));

        cardLayout = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                emoji.setColor(accentColor);
                drawPattern(canvas, emoji, getWidth(), getHeight(), hasCover ? 1f : 0.4f);
                super.dispatchDraw(canvas);
            }
        };
        cardLayout.setBackground(background);
        cardLayout.setClipToOutline(true);
        cardLayout.setOutlineProvider(ViewOutlineProviderImpl.fromDrawable(background));
        cardLayout.setClickable(true);
        cardLayout.setOnClickListener(v -> {
            if (lastfmUrl != null) {
                Browser.openUrl(getContext(), lastfmUrl);
            } else if (onCardClick != null) {
                onCardClick.run();
            }
        });
        ScaleStateListAnimator.apply(cardLayout, 0.035f, 1.5f);
        addView(cardLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        emoji = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(cardLayout, false, dp(20), AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_STATIC);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        cardLayout.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 12, 12, 12, 12));

        imageView = new BackupImageView(context);
        imageView.setClipToOutline(true);
        imageView.setOutlineProvider(ViewOutlineProviderImpl.boundsWithPaddingRoundRect(0, coverCornerRadius()));
        imageView.getImageReceiver().setDelegate(new ImageReceiver.ImageReceiverDelegate() {
            @Override
            public void didSetImage(ImageReceiver imageReceiver, boolean set, boolean thumb, boolean memCache) {
                if (set && !thumb) {
                    extractColors(imageReceiver.getBitmap());
                }
            }
        });
        row.addView(imageView, LayoutHelper.createLinear(68, 68, 0, 0, 12, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        NotificationCenter.listenEmojiLoading(nameView);
        texts.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        artistView = new TextView(context);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        artistView.setAlpha(0.6f);
        NotificationCenter.listenEmojiLoading(artistView);
        texts.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setAlpha(1f);
        statusView.setVisibility(GONE);
        texts.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        applyThemeColors();
    }

    public void setOnCardClickListener(Runnable listener) {
        onCardClick = listener;
    }

    public void set(TLRPC.Document document, long emojiDocumentId, long ownerId) {
        if (document == null) {
            return;
        }
        CharSequence rawAuthor = ProfileMusicView.getAuthor(document);
        String nick = ProfileMusicMark.nickFrom(FileLoader.getDocumentFileName(document), ownerId);
        savedTitle = ZeroWidthCodec.strip(ProfileMusicView.getTitle(document));
        savedAuthor = ZeroWidthCodec.strip(rawAuthor);
        if (TextUtils.isEmpty(savedTitle)) {
            savedTitle = LocaleController.getString(R.string.AudioUnknownTitle);
        }
        if (TextUtils.isEmpty(savedAuthor)) {
            savedAuthor = LocaleController.getString(R.string.AudioUnknownArtist);
        }
        showSaved();

        long emojiId = emojiDocumentId != 0 ? emojiDocumentId : MUSIC_EMOJI_ID;
        if (emojiId != currentEmojiId) {
            currentEmojiId = emojiId;
            emoji.set(emojiId, true);
        }

        savedDocument = document;
        if (document.id != currentDocumentId || lastfmCover) {
            currentDocumentId = document.id;
            applySavedCover();
        }

        lastfmNick = nick;
        if (nick != null) {
            LastFmNowPlaying.Track cached = LastFmNowPlaying.cached(nick);
            LastFmNowPlaying.debug("card: nick=" + nick + ", doc " + document.id + ", cached=" + (cached != null ? (cached.live ? "live" : "not live") : "no"));
            if (cached != null) {
                onLastFm(nick, cached);
            } else {
                LastFmNowPlaying.request(nick, this::onLastFm);
            }
        }
    }

    private void applySavedCover() {
        lastfmCover = false;
        imageToken++;
        hasCover = false;
        backgroundColor = 0;
        accentColor = 0;
        TLRPC.PhotoSize thumb = savedDocument != null
                ? FileLoader.getClosestPhotoSizeWithSize(savedDocument.thumbs, 1000) : null;
        ImageLocation location = thumb != null ? ImageLocation.getForDocument(thumb, savedDocument) : null;
        String artworkUrl = location == null && savedDocument != null
                ? MessageObject.getArtworkUrl(savedDocument, false) : null;
        if (location != null) {
            imageView.setImage(location, null, (Drawable) null, 0, savedDocument);
        } else if (!TextUtils.isEmpty(artworkUrl)) {
            imageView.setImage(ImageLocation.getForPath(artworkUrl), "300_300", (Drawable) null, 0, null);
        } else {
            imageView.setImageResource(R.drawable.nocover_big, getThemedColor(Theme.key_player_button));
        }
        applyThemeColors();
    }

    private void showSaved() {
        nameView.setText(Emoji.replaceEmoji(savedTitle, nameView.getPaint().getFontMetricsInt(), false));
        artistView.setText(Emoji.replaceEmoji(savedAuthor, artistView.getPaint().getFontMetricsInt(), false));
        statusView.setVisibility(GONE);
        lastfmUrl = null;
    }

    private void onLastFm(String nick, LastFmNowPlaying.Track track) {
        if (!TextUtils.equals(nick, lastfmNick) || track == null || !track.live) {
            LastFmNowPlaying.debug("card: skip " + nick + " (current " + lastfmNick + ", track "
                    + (track == null ? "none" : track.live ? "live" : "not live") + ")");
            return;
        }
        LastFmNowPlaying.debug("card: showing " + track.artist + " — " + track.name);
        nameView.setText(Emoji.replaceEmoji(track.name, nameView.getPaint().getFontMetricsInt(), false));
        artistView.setText(Emoji.replaceEmoji(track.artist, artistView.getPaint().getFontMetricsInt(), false));
        statusView.setText(LocaleController.getString(R.string.OENowPlayingScrobbling));
        statusView.setVisibility(VISIBLE);
        lastfmUrl = track.trackUrl != null ? "https://www.last.fm" + track.trackUrl : "https://www.last.fm/user/" + nick;
        lastfmCover = true;
        imageToken++;
        if (!TextUtils.isEmpty(track.coverUrl)) {
            imageView.setImage(ImageLocation.getForPath(track.coverUrl), "300_300", (Drawable) null, 0, null);
        } else {
            hasCover = false;
            backgroundColor = 0;
            accentColor = 0;
            imageView.setImageResource(R.drawable.nocover_big, getThemedColor(Theme.key_player_button));
            applyThemeColors();
        }
    }

    private void extractColors(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || paletteToken == imageToken) {
            return;
        }
        final long token = imageToken;
        paletteToken = token;
        final Bitmap copy;
        try {
            copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable ignore) {
            return;
        }
        if (copy == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            int base;
            try {
                Palette palette = Palette.from(copy).generate();
                Palette.Swatch swatch = palette.getDarkVibrantSwatch();
                if (swatch == null) swatch = palette.getMutedSwatch();
                if (swatch == null) swatch = palette.getDarkMutedSwatch();
                if (swatch == null) swatch = palette.getDominantSwatch();
                base = swatch != null ? swatch.getRgb() : AndroidUtilities.getDominantColor(copy);
            } catch (Throwable ignore) {
                return;
            }
            double contrast = ColorUtils.calculateContrast(Color.WHITE, base);
            if (contrast > 15) {
                base = adjustHsl(base, 2f);
            } else if (contrast < 10) {
                base = adjustHsl(base, 0.5f);
            }
            if (ColorUtils.calculateContrast(Color.WHITE, base) < 3) {
                base = ColorUtils.blendARGB(base, Color.BLACK, 0.3f);
            }
            float[] hsl = new float[3];
            ColorUtils.colorToHSL(base, hsl);
            final float luminance;
            if (hsl[2] <= 0.25f) {
                luminance = 2f;
            } else if (hsl[2] <= 0.5f) {
                luminance = 1.5f;
            } else if (hsl[2] <= 0.75f) {
                luminance = 1f;
            } else {
                luminance = 0.5f;
            }
            final int resolved = base;
            final int accent = adjustHsl(base, luminance);
            AndroidUtilities.runOnUIThread(() -> {
                if (token != imageToken) {
                    return;
                }
                hasCover = true;
                backgroundColor = resolved;
                accentColor = accent;
                applyColors();
            });
        });
    }

    private void applyThemeColors() {
        backgroundColor = hasCover ? backgroundColor : getThemedColor(Theme.key_windowBackgroundWhite);
        accentColor = hasCover ? accentColor : getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        applyColors();
    }

    private void applyColors() {
        background.mutate();
        background.setColors(new int[]{backgroundColor, hasCover ? adjustHsl(backgroundColor, 1.5f) : backgroundColor});
        int textColor = hasCover ? Color.WHITE : getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        nameView.setTextColor(textColor);
        artistView.setTextColor(textColor);
        statusView.setTextColor(textColor);
        cardLayout.invalidate();
    }

    private float coverCornerRadius() {
        return dpf2(Math.max(AppearanceConfig.sectionRadius() - 12, 8));
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private static int adjustHsl(int color, float luminance) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[2] = Math.min(hsl[2] * luminance, 1f);
        return ColorUtils.HSLToColor(hsl);
    }

    private static void drawPattern(Canvas canvas, Drawable pattern, float w, float h, float alpha) {
        if (alpha <= 0) {
            return;
        }
        for (int i = 0; i < PATTERN.length; i += 4) {
            final float x = dpf2(PATTERN[i]) + w;
            final float y = dpf2(PATTERN[i + 1]) + h / 2f;
            final float size = dpf2(PATTERN[i + 2]) / 2f;
            pattern.setBounds((int) (x - size), (int) (y - size), (int) (x + size), (int) (y + size));
            pattern.setAlpha((int) (255 * alpha * PATTERN[i + 3]));
            pattern.draw(canvas);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        emoji.attach();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        emoji.detach();
    }
}
