package app.exteraless.chats;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;

public final class ShortcutIcons {

    private ShortcutIcons() {
    }

    public static IconCompat createAvatarIcon(Bitmap photo, TLRPC.User user, TLRPC.Chat chat) {
        final int serviceType;
        if (user == null) {
            serviceType = AvatarDrawable.AVATAR_TYPE_NORMAL;
        } else if (UserObject.isReplyUser(user)) {
            serviceType = AvatarDrawable.AVATAR_TYPE_REPLIES;
        } else if (UserObject.isUserSelf(user)) {
            serviceType = AvatarDrawable.AVATAR_TYPE_SAVED;
        } else {
            serviceType = AvatarDrawable.AVATAR_TYPE_NORMAL;
        }
        final Bitmap avatar;
        if (photo != null && serviceType == AvatarDrawable.AVATAR_TYPE_NORMAL) {
            avatar = photo;
        } else {
            final int placeholderSize = AndroidUtilities.dp(58);
            final AvatarDrawable drawable = user != null ? new AvatarDrawable(user) : new AvatarDrawable(chat);
            if (serviceType != AvatarDrawable.AVATAR_TYPE_NORMAL) {
                drawable.setAvatarType(serviceType);
            }
            drawable.setRoundRadius(1);
            drawable.setBounds(0, 0, placeholderSize, placeholderSize);
            avatar = Bitmap.createBitmap(placeholderSize, placeholderSize, Bitmap.Config.ARGB_8888);
            drawable.draw(new Canvas(avatar));
        }
        final int size = AndroidUtilities.dp(108);
        final float inset = size / 6f;
        final Matrix matrix = new Matrix();
        matrix.setRectToRect(new RectF(0, 0, avatar.getWidth(), avatar.getHeight()), new RectF(inset, inset, size - inset, size - inset), Matrix.ScaleToFit.FILL);
        final BitmapShader shader = new BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(matrix);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(shader);
        final Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(result).drawRect(0, 0, size, size, paint);
        return IconCompat.createWithAdaptiveBitmap(result);
    }
}
