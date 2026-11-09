package app.exteraless.plugins;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SendMessageChatArguments;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

public final class PluginMediaServices {
    private PluginMediaServices() {
    }

    private static File readableFile(String path) {
        File file = new File(path);
        if (!file.isFile() || !file.canRead()) {
            throw new IllegalArgumentException("Media file is not readable: " + path);
        }
        return file;
    }

    private static String mimeType(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null ? "application/octet-stream" : mime;
    }

    public static TLRPC.TL_document prepareDocument(String path) {
        return prepareDocument(path, null);
    }

    public static TLRPC.TL_document prepareDocument(String path, String mime) {
        File file = readableFile(path);
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.file_reference = new byte[0];
        document.date = (int) (System.currentTimeMillis() / 1000L);
        document.size = file.length();
        document.mime_type = mime == null || mime.isEmpty() ? mimeType(path) : mime;
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = file.getName();
        document.attributes.add(filename);
        return document;
    }



    public static TLRPC.TL_photo generatePhotoSizes(int account, String path, boolean highQuality) {
        readableFile(path);
        return SendMessagesHelper.getInstance(account).generatePhotoSizes(null, path, null, highQuality);
    }

    public static void addVideoAttributes(TLRPC.Document document, String path) {
        readableFile(path);
        TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
        video.supports_streaming = true;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            video.duration = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0;
            int w = (int) parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = (int) parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            long rotation = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            boolean swap = rotation == 90 || rotation == 270;
            video.w = swap ? h : w;
            video.h = swap ? w : h;
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignore) {
            }
        }
        replaceAttribute(document, video);
        Bitmap bitmap = SendMessagesHelper.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND);
        if (bitmap != null) {
            TLRPC.PhotoSize thumb = ImageLoader.scaleAndSaveImage(bitmap, 90, 90, 55, false);
            if (thumb != null) {
                document.thumbs.add(thumb);
                document.flags |= 1;
            }
            bitmap.recycle();
        }
    }

    public static void addAudioAttributes(TLRPC.Document document, String path) {
        readableFile(path);
        TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            audio.duration = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0;
            audio.title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            audio.performer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (audio.title != null) {
                audio.flags |= 1;
            }
            if (audio.performer != null) {
                audio.flags |= 2;
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignore) {
            }
        }
        replaceAttribute(document, audio);
    }

    private static void replaceAttribute(TLRPC.Document document, TLRPC.DocumentAttribute attribute) {
        for (int i = document.attributes.size() - 1; i >= 0; i--) {
            if (document.attributes.get(i).getClass() == attribute.getClass()) {
                document.attributes.remove(i);
            }
        }
        document.attributes.add(attribute);
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void editMedia(int account, MessageObject message, String path, String caption,
                                 ArrayList<TLRPC.MessageEntity> entities, boolean spoiler) {
        readableFile(path);
        if (message == null || message.messageOwner == null || message.currentAccount != account) {
            throw new IllegalArgumentException("The message must belong to the selected account");
        }
        String mime = mimeType(path);
        SendMessagesHelper.SendingMediaInfo media = new SendMessagesHelper.SendingMediaInfo();
        media.path = path;
        media.isVideo = mime.startsWith("video/");
        media.caption = caption == null ? message.messageOwner.message : caption;
        media.entities = caption == null ? message.messageOwner.entities : entities;
        media.hasMediaSpoilers = spoiler;
        message.editingMessage = media.caption;
        message.editingMessageEntities = media.entities;
        ArrayList<SendMessagesHelper.SendingMediaInfo> items = new ArrayList<>();
        items.add(media);
        boolean forceDocument = !mime.startsWith("image/") && !media.isVideo;
        SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account), items, message.getDialogId(),
                null, null, null, null, forceDocument, false, message, true,
                message.scheduled ? message.messageOwner.date : 0, 0, 0, false,
                null, SendMessageChatArguments.EMPTY, 0, false, 0, 0, null);
    }
}
