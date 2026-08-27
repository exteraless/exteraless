package app.exteraless.chats;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BackgroundGradientDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.MotionBackgroundDrawable;

@SuppressLint("ViewConstructor")
public class WideChannelPostsPreviewCell extends FrameLayout {

    private static final int HORIZONTAL_PADDING_DP = 12;
    private static final int VERTICAL_PADDING_DP = 10;

    private final ChatMessageCell regularCell;
    private final ChatMessageCell wideCell;
    private final Drawable shadowDrawable;
    private final Theme.ResourcesProvider resourcesProvider;

    private BackgroundGradientDrawable.Disposable backgroundGradientDisposable;
    private ValueAnimator animator;
    private float progress;
    private int previewContentWidth;

    public WideChannelPostsPreviewCell(Context context, BaseFragment fragment) {
        super(context);
        resourcesProvider = fragment.getResourceProvider();
        setWillNotDraw(false);
        setClipChildren(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setContentDescription(getString(R.string.OEChatsWideChannelPosts));
        setFocusable(false);
        setClickable(false);

        shadowDrawable = Theme.getThemedDrawable(context, R.drawable.greydivider_bottom,
                Theme.getColor(Theme.key_windowBackgroundGrayShadow, resourcesProvider));

        regularCell = createCell(context, createMessage(false));
        wideCell = createCell(context, createMessage(true));
        addView(regularCell);
        addView(wideCell);

        progress = ChatsConfig.wideChannelPosts.Bool() ? 1f : 0f;
    }

    private ChatMessageCell createCell(Context context, MessageObject messageObject) {
        ChatMessageCell cell = new ChatMessageCell(context, UserConfig.selectedAccount, false, null, resourcesProvider) {
            @Override
            public int getParentWidth() {
                int width = WideChannelPostsPreviewCell.this.previewContentWidth;
                return width > 0 ? width : super.getParentWidth();
            }
        };
        cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
            @Override
            public boolean canPerformActions() {
                return false;
            }
        });
        cell.isChat = false;
        cell.hasDiscussion = true;
        cell.linkedChatId = 2;
        cell.setFullyDraw(true);
        cell.setMessageObject(messageObject, null, false, false, false);
        return cell;
    }

    private MessageObject createMessage(boolean wide) {
        int account = UserConfig.selectedAccount;
        int date = (int) (System.currentTimeMillis() / 1000) - 3600;

        TLRPC.TL_message message = new TLRPC.TL_message();
        message.date = date;
        message.dialog_id = -1;
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID
                | TLRPC.MESSAGE_FLAG_HAS_VIEWS
                | TLRPC.MESSAGE_FLAG_REPLY;
        message.id = wide ? 2 : 1;
        message.message = getString(R.string.OEChatsWideChannelPostsPreviewText);
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.reply_to = new TLRPC.TL_messageReplyHeader();
        message.reply_to.flags |= 16;
        message.reply_to.reply_to_msg_id = 10;
        message.views = 1240;
        message.forwards = 18;
        message.replies = new TLRPC.TL_messageReplies();
        message.replies.comments = true;
        message.replies.channel_id = 2;
        message.replies.replies = 3;

        message.from_id = new TLRPC.TL_peerChannel();
        message.from_id.channel_id = 1;
        message.peer_id = new TLRPC.TL_peerChannel();
        message.peer_id.channel_id = 1;
        message.out = false;
        message.post = true;

        PreviewMessageObject messageObject = new PreviewMessageObject(account, message, wide);
        messageObject.customReplyName = getString(R.string.OEChatsChannelPosts);
        messageObject.replyMessageObject = createReplyMessage(account, date);
        messageObject.viewsReloaded = true;
        messageObject.resetLayout();
        return messageObject;
    }

    private MessageObject createReplyMessage(int account, int date) {
        TLRPC.TL_message reply = new TLRPC.TL_message();
        reply.date = date - 60;
        reply.dialog_id = -1;
        reply.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        reply.id = 10;
        reply.message = getString(R.string.OEChatsWideChannelPostsPreviewReply);
        reply.media = new TLRPC.TL_messageMediaEmpty();
        reply.from_id = new TLRPC.TL_peerChannel();
        reply.from_id.channel_id = 1;
        reply.peer_id = new TLRPC.TL_peerChannel();
        reply.peer_id.channel_id = 1;
        reply.post = true;
        return new MessageObject(account, reply, true, false);
    }

    public void setWide(boolean wide, boolean animated) {
        float target = wide ? 1f : 0f;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        boolean canAnimate = animated && isAttachedToWindow() && getWidth() > 0
                && SharedConfig.animationsEnabled()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled());
        if (!canAnimate || Math.abs(progress - target) < 0.001f) {
            progress = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(280);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(valueAnimator -> {
            progress = (float) valueAnimator.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int contentWidth = Math.max(dp(1), width - dp(HORIZONTAL_PADDING_DP * 2));
        if (previewContentWidth != contentWidth) {
            previewContentWidth = contentWidth;
            regularCell.forceResetMessageObject();
            wideCell.forceResetMessageObject();
        }
        int childWidthSpec = MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        regularCell.measure(childWidthSpec, childHeightSpec);
        wideCell.measure(childWidthSpec, childHeightSpec);

        int height = dp(VERTICAL_PADDING_DP * 2)
                + Math.max(regularCell.getMeasuredHeight(), wideCell.getMeasuredHeight());
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        layoutCell(regularCell, width);
        layoutCell(wideCell, width);
    }

    private void layoutCell(ChatMessageCell cell, int width) {
        int left = LocaleController.isRTL
                ? width - dp(HORIZONTAL_PADDING_DP) - cell.getMeasuredWidth()
                : dp(HORIZONTAL_PADDING_DP);
        int top = dp(VERTICAL_PADDING_DP);
        cell.layout(left, top, left + cell.getMeasuredWidth(), top + cell.getMeasuredHeight());
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        Drawable drawable = Theme.getCachedWallpaperNonBlocking();
        if (drawable == null) {
            canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
        } else {
            drawable.setAlpha(255);
            if (drawable instanceof ColorDrawable || drawable instanceof GradientDrawable || drawable instanceof MotionBackgroundDrawable) {
                drawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
                if (drawable instanceof BackgroundGradientDrawable backgroundGradientDrawable) {
                    backgroundGradientDisposable = backgroundGradientDrawable.drawExactBoundsSize(canvas, this);
                } else {
                    drawable.draw(canvas);
                }
            } else if (drawable instanceof BitmapDrawable bitmapDrawable) {
                if (bitmapDrawable.getTileModeX() == Shader.TileMode.REPEAT) {
                    canvas.save();
                    float scale = 2f / AndroidUtilities.density;
                    canvas.scale(scale, scale);
                    drawable.setBounds(0, 0,
                            (int) Math.ceil(getMeasuredWidth() / scale),
                            (int) Math.ceil(getMeasuredHeight() / scale));
                } else {
                    float scale = Math.max(
                            getMeasuredWidth() / (float) drawable.getIntrinsicWidth(),
                            getMeasuredHeight() / (float) drawable.getIntrinsicHeight());
                    int width = (int) Math.ceil(drawable.getIntrinsicWidth() * scale);
                    int height = (int) Math.ceil(drawable.getIntrinsicHeight() * scale);
                    int x = (getMeasuredWidth() - width) / 2;
                    int y = (getMeasuredHeight() - height) / 2;
                    canvas.save();
                    canvas.clipRect(0, 0, getMeasuredWidth(), getMeasuredHeight());
                    drawable.setBounds(x, y, x + width, y + height);
                }
                drawable.draw(canvas);
                canvas.restore();
            }
        }
        shadowDrawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
        shadowDrawable.draw(canvas);
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        long drawingTime = getDrawingTime();
        if (progress < 1f) {
            drawChild(canvas, regularCell, drawingTime);
        }
        if (progress <= 0f) {
            return;
        }
        if (progress >= 1f) {
            drawChild(canvas, wideCell, drawingTime);
            return;
        }
        int revealWidth = Math.round(getWidth() * progress);
        canvas.save();
        if (LocaleController.isRTL) {
            canvas.clipRect(0, 0, revealWidth, getHeight());
        } else {
            canvas.clipRect(getWidth() - revealWidth, 0, getWidth(), getHeight());
        }
        drawChild(canvas, wideCell, drawingTime);
        canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (backgroundGradientDisposable != null) {
            backgroundGradientDisposable.dispose();
            backgroundGradientDisposable = null;
        }
        super.onDetachedFromWindow();
    }

    private static final class PreviewMessageObject extends MessageObject {

        private final boolean wide;

        PreviewMessageObject(int account, TLRPC.Message message, boolean wide) {
            super(account, message, true, false);
            this.wide = wide;
        }

        @Override
        public boolean isWideChannelPost() {
            return wide;
        }
    }
}
