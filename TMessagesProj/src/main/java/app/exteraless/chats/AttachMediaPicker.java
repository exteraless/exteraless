package app.exteraless.chats;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Pair;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatAttachAlert;
import org.telegram.ui.Components.ChatAttachAlertPhotoLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class AttachMediaPicker {

    private AttachMediaPicker() {
    }

    public static boolean launchPicker(ChatAttachAlert alert) {
        if (!(alert.baseFragment instanceof ChatActivity)) {
            return false;
        }
        try {
            final Intent videoPickerIntent = new Intent(Intent.ACTION_GET_CONTENT);
            videoPickerIntent.setType("video/*");
            videoPickerIntent.putExtra(MediaStore.EXTRA_SIZE_LIMIT, FileLoader.DEFAULT_MAX_FILE_SIZE);
            videoPickerIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

            final Intent photoPickerIntent = new Intent(Intent.ACTION_PICK);
            photoPickerIntent.setType("image/*");
            photoPickerIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

            final Intent chooserIntent = Intent.createChooser(photoPickerIntent, null);
            final ArrayList<Intent> extraIntents = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33) {
                final Intent systemPickerIntent = new Intent(MediaStore.ACTION_PICK_IMAGES);
                final int limit = MediaStore.getPickImagesMaxLimit();
                final int max = alert.maxSelectedPhotos;
                if (max < 0 || max > 1) {
                    systemPickerIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max >= 2 && max <= limit ? max : limit);
                }
                extraIntents.add(systemPickerIntent);
            }
            extraIntents.add(videoPickerIntent);
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toArray(new Intent[0]));
            alert.baseFragment.startActivityForResult(chooserIntent, 1);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        alert.dismiss(true);
        return true;
    }

    public static boolean handlePickerResult(ChatActivity fragment, Intent data) {
        if (data == null) {
            return false;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        final ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                final Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        if (uris.isEmpty() && data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return false;
        }
        Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<MediaController.PhotoEntry> entries = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    final MediaController.PhotoEntry entry = buildEntry(uri);
                    if (entry != null) {
                        entries.add(entry);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            AndroidUtilities.runOnUIThread(() -> presentEntries(fragment, entries));
        });
        return true;
    }

    private static MediaController.PhotoEntry buildEntry(Uri uri) {
        String mime = ApplicationLoader.applicationContext.getContentResolver().getType(uri);
        if (mime == null) {
            mime = "";
        }
        final boolean isVideo = mime.startsWith("video/");
        final String ext;
        if (isVideo) {
            ext = "mp4";
        } else if ("image/png".equals(mime)) {
            ext = "png";
        } else if ("image/webp".equals(mime)) {
            ext = "webp";
        } else if ("image/gif".equals(mime)) {
            ext = "gif";
        } else {
            ext = "jpg";
        }
        final String path = MediaController.copyFileToCache(uri, ext, isVideo ? FileLoader.DEFAULT_MAX_FILE_SIZE : -1L);
        if (path == null) {
            return null;
        }
        return isVideo ? buildVideoEntry(path) : buildPhotoEntry(path);
    }

    private static MediaController.PhotoEntry buildPhotoEntry(String path) {
        final Pair<Integer, Integer> orientation = AndroidUtilities.getImageOrientation(path);
        int width = 0;
        int height = 0;
        try {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            width = options.outWidth;
            height = options.outHeight;
        } catch (Exception ignore) {
        }
        final MediaController.PhotoEntry entry = new MediaController.PhotoEntry(0, ChatAttachAlertPhotoLayout.lastImageId--, 0L, path, orientation.first, false, width, height, 0L).setOrientation(orientation);
        entry.canDeleteAfter = true;
        return entry;
    }

    private static MediaController.PhotoEntry buildVideoEntry(String path) {
        int duration = 0;
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(path);
            final String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value != null) {
                duration = (int) Math.ceil(Long.parseLong(value) / 1000.0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        final Bitmap bitmap = SendMessagesHelper.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND);
        if (bitmap == null) {
            return null;
        }
        final String fileName = Integer.MIN_VALUE + "_" + SharedConfig.getLastLocalId() + ".jpg";
        final File cacheFile = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), fileName);
        try (FileOutputStream stream = new FileOutputStream(cacheFile)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 55, stream);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        SharedConfig.saveConfig();
        final MediaController.PhotoEntry entry = new MediaController.PhotoEntry(0, ChatAttachAlertPhotoLayout.lastImageId--, 0L, path, 0, true, bitmap.getWidth(), bitmap.getHeight(), 0L);
        entry.duration = duration;
        entry.thumbPath = cacheFile.getAbsolutePath();
        entry.canDeleteAfter = true;
        return entry;
    }

    private static void presentEntries(ChatActivity fragment, List<MediaController.PhotoEntry> entries) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        if (entries.isEmpty()) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.UnsupportedAttachment)).show();
            return;
        }
        fragment.createChatAttachView();
        final ChatAttachAlert alert = fragment.chatAttachAlert;
        if (alert == null) {
            return;
        }
        final ChatAttachAlertPhotoLayout layout = alert.getPhotoLayout();
        List<MediaController.PhotoEntry> trimmed = entries;
        if (alert.maxSelectedPhotos >= 0 && alert.maxSelectedPhotos < entries.size()) {
            trimmed = entries.subList(0, alert.maxSelectedPhotos);
        }
        if (trimmed.isEmpty()) {
            return;
        }
        ChatAttachAlertPhotoLayout.mediaFromExternalCamera = true;
        ChatAttachAlertPhotoLayout.openIndex = ChatAttachAlertPhotoLayout.cameraPhotos.size();
        for (int i = 0; i < trimmed.size() - 1; i++) {
            final MediaController.PhotoEntry entry = trimmed.get(i);
            ChatAttachAlertPhotoLayout.cameraPhotos.add(entry);
            ChatAttachAlertPhotoLayout.selectedPhotos.put(entry.imageId, entry);
            ChatAttachAlertPhotoLayout.selectedPhotosOrder.add(entry.imageId);
        }
        layout.openPhotoViewer(trimmed.get(trimmed.size() - 1), false, true);
    }
}
