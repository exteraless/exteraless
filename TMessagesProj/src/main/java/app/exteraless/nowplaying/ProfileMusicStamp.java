package app.exteraless.nowplaying;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileUploadOperation;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;

public final class ProfileMusicStamp implements NotificationCenter.NotificationCenterDelegate {

    public interface Callback {
        void onFinished(boolean ok, int reason);

        default void onStage(int stage, int percent) {
        }
    }

    public static final int STAGE_DOWNLOAD = 0;
    public static final int STAGE_PREPARE = 1;
    public static final int STAGE_UPLOAD = 2;
    public static final int STAGE_SAVE = 3;

    public static final int REASON_OK = 0;
    public static final int REASON_NO_MUSIC = 1;
    public static final int REASON_DOWNLOAD = 2;
    public static final int REASON_UPLOAD = 3;
    public static final int REASON_SAVE = 4;

    private static final long TIMEOUT = 120_000L;
    private static final int ID3V1_LENGTH = 128;
    private static final String SEND_TAG_KEY = "exteraless_profile_music";

    private final int account;
    private final String nick;
    private final Callback callback;
    private final long selfId;
    private final String sendTag = Long.toHexString(Utilities.random.nextLong());

    private TLRPC.Document source;
    private String stampedName;
    private String awaitedFileName;
    private int sentMessageId;
    private String uploadPath;
    private long uploadStartedAt;
    private int loggedQuarter;
    private boolean finished;

    private final Runnable timeout = () -> {
        debug("timeout after " + TIMEOUT + " ms");
        finish(false, REASON_UPLOAD);
    };

    public static void apply(int account, String nick, Callback callback) {
        new ProfileMusicStamp(account, nick, callback).start();
    }


    private ProfileMusicStamp(int account, String nick, Callback callback) {
        this.account = account;
        this.nick = nick;
        this.callback = callback;
        this.selfId = UserConfig.getInstance(account).getClientUserId();
    }

    private static void debug(String message) {
        LastFmNowPlaying.debug("stamp: " + message);
    }

    private void start() {
        TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(selfId);
        debug("start nick=" + nick + ", userFull=" + (full != null) + ", music="
                + (full != null && full.saved_music != null ? full.saved_music.id : "none"));
        if (full == null || full.saved_music == null) {
            finish(false, REASON_NO_MUSIC);
            return;
        }
        source = full.saved_music;
        String fileName = FileLoader.getDocumentFileName(source);
        String current = ProfileMusicMark.nickFrom(fileName, selfId);
        boolean clean = TextUtils.equals(ZeroWidthCodec.stripToString(performerOf(source)), performerOf(source));
        debug("file=" + fileName + ", current nick=" + current + ", clean performer=" + clean);
        if (clean && (TextUtils.equals(current, nick)
                || (TextUtils.isEmpty(current) && TextUtils.isEmpty(nick)))) {
            finish(true, REASON_OK);
            return;
        }
        if (TextUtils.isEmpty(nick)) {
            stampedName = ProfileMusicMark.strip(fileName);
        } else {
            stampedName = ProfileMusicMark.stamp(fileName, nick, selfId);
            if (ProfileMusicMark.nickFrom(stampedName, selfId) == null) {
                debug("stamp did not round-trip");
                finish(false, REASON_SAVE);
                return;
            }
        }
        if (TextUtils.isEmpty(stampedName)) {
            stampedName = "audio.mp3";
        }

        NotificationCenter center = NotificationCenter.getInstance(account);
        center.addObserver(this, NotificationCenter.fileLoaded);
        center.addObserver(this, NotificationCenter.fileLoadFailed);
        center.addObserver(this, NotificationCenter.messageReceivedByServer);
        center.addObserver(this, NotificationCenter.fileUploadProgressChanged);
        center.addObserver(this, NotificationCenter.fileLoadProgressChanged);
        AndroidUtilities.runOnUIThread(timeout, TIMEOUT);

        File file = localFile();
        debug("stamped=" + stampedName + ", local file=" + (file != null ? file.length() + " bytes" : "none"));
        if (file != null) {
            upload(file);
        } else {
            awaitedFileName = FileLoader.getAttachFileName(source);
            debug("downloading " + awaitedFileName);
            stage(STAGE_DOWNLOAD, 0);
            FileLoader.getInstance(account).loadFile(source,
                    MessagesController.getInstance(account).getUser(selfId),
                    FileLoader.PRIORITY_HIGH, 0);
        }
    }

    private File localFile() {
        FileLoader loader = FileLoader.getInstance(account);
        File file = loader.getPathToAttach(source, null, false);
        if (file != null && file.exists() && file.length() > 0) {
            return file;
        }
        file = loader.getPathToAttach(source, null, true);
        if (file != null && file.exists() && file.length() > 0) {
            return file;
        }
        return null;
    }

    private void stage(int stage, int percent) {
        if (!finished && callback != null) {
            callback.onStage(stage, percent);
        }
    }

    private void upload(File file) {
        stage(STAGE_PREPARE, 0);
        Utilities.globalQueue.postRunnable(() -> {
            File copy = uniqueCopy(file);
            File sent = copy != null ? copy : file;
            TLRPC.TL_document out = buildDocument(sent, file);
            uploadPath = sent.getAbsolutePath();
            FileUploadOperation.setFastUpload(uploadPath, true);
            debug("upload " + sent.length() + " bytes" + (copy == null ? " (copy failed, original)" : "") + ", thumbs=" + out.thumbs.size());
            AndroidUtilities.runOnUIThread(() -> {
                if (finished) {
                    return;
                }
                uploadStartedAt = SystemClock.elapsedRealtime();
                stage(STAGE_UPLOAD, 0);
                debug("sending with file_name=" + fileNameOf(out) + ", upload path=" + sent.getName());
                HashMap<String, String> params = new HashMap<>();
                params.put(SEND_TAG_KEY, sendTag);
                SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
                        out, null, sent.getAbsolutePath(), selfId, null, null, null, null, null, params,
                        false, 0, 0, 0, null, null, false));
            });
        });
    }

    private TLRPC.TL_document buildDocument(File sent, File original) {
        TLRPC.TL_document out = new TLRPC.TL_document();
        out.id = 0;
        out.access_hash = 0;
        out.dc_id = 0;
        out.file_reference = new byte[0];
        out.date = ConnectionsManager.getInstance(account).getCurrentTime();
        out.mime_type = source.mime_type != null ? source.mime_type : "audio/mpeg";
        out.size = sent.length();

        TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
        audio.duration = durationOf(source);
        audio.title = ZeroWidthCodec.stripToString(titleOf(source));
        if (audio.title == null) {
            audio.title = "";
        }
        audio.performer = ZeroWidthCodec.stripToString(performerOf(source));
        if (audio.performer == null) {
            audio.performer = "";
        }
        audio.flags |= 1;
        audio.flags |= 2;
        out.attributes.add(audio);

        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = stampedName;
        out.attributes.add(name);

        attachCover(out, original);
        return out;
    }

    private File uniqueCopy(File file) {
        File dir = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "profile_music");
        File copy = new File(dir, System.currentTimeMillis() + "_" + stampedName);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            long length = file.length();
            long markerAt = hasId3v1(file) ? length - ID3V1_LENGTH : length;
            byte[] marker = ("OELFM" + System.nanoTime()).getBytes(StandardCharsets.US_ASCII);
            try (InputStream in = new FileInputStream(file); OutputStream output = new FileOutputStream(copy)) {
                copyBytes(in, output, markerAt);
                output.write(marker);
                copyBytes(in, output, length - markerAt);
            }
            if (copy.length() != length + marker.length) {
                copy.delete();
                return null;
            }
            return copy;
        } catch (Throwable e) {
            FileLog.e(e);
            copy.delete();
            return null;
        }
    }

    private static boolean hasId3v1(File file) {
        if (file.length() < ID3V1_LENGTH) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(file.length() - ID3V1_LENGTH);
            byte[] tag = new byte[3];
            raf.readFully(tag);
            return tag[0] == 'T' && tag[1] == 'A' && tag[2] == 'G';
        } catch (Throwable e) {
            return false;
        }
    }

    private static void copyBytes(InputStream in, OutputStream output, long count) throws java.io.IOException {
        byte[] buffer = new byte[64 * 1024];
        long left = count;
        while (left > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                throw new java.io.EOFException();
            }
            output.write(buffer, 0, read);
            left -= read;
        }
    }

    private static void attachCover(TLRPC.TL_document out, File file) {
        Bitmap cover = null;
        try {
            AudioInfo info = AudioInfo.getAudioInfo(file);
            if (info != null) {
                cover = info.getCover();
            }
            if (cover == null) {
                return;
            }
            TLRPC.PhotoSize thumb = ImageLoader.scaleAndSaveImage(cover, 132, 132, 55, false);
            if (thumb != null) {
                out.thumbs.add(thumb);
                out.flags |= 1;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cover != null) {
                cover.recycle();
            }
        }
    }

    private void fetchSent(int messageId) {
        TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
        req.id.add(messageId);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (finished) {
                return;
            }
            TLRPC.Document document = null;
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                for (int a = 0; a < res.messages.size(); a++) {
                    TLRPC.Message message = res.messages.get(a);
                    if (message.id == messageId && message.media != null) {
                        document = message.media.document;
                        break;
                    }
                }
            }
            if (document == null) {
                debug("sent message " + messageId + " not fetched, error " + (error != null ? error.text : null));
                FileLog.d("ProfileMusicStamp: message " + messageId + " not fetched, error " + (error != null ? error.text : null));
                deleteSent();
                finish(false, REASON_UPLOAD);
                return;
            }
            String received = fileNameOf(document);
            debug("server got " + received + " (doc " + document.id + ", " + document.size + " bytes)");
            FileLog.d("ProfileMusicStamp: message " + messageId
                    + " doc " + document.id + " size " + document.size
                    + ", source doc " + source.id + " size " + source.size
                    + ", sent " + stampedName + ", got " + received);
            if (!TextUtils.equals(ProfileMusicMark.nickFrom(received, selfId),
                    ProfileMusicMark.nickFrom(stampedName, selfId))) {
                deleteSent();
                finish(false, REASON_SAVE);
                return;
            }
            saveMusic(document);
        }));
    }

    private void deleteSent() {
        if (sentMessageId == 0) {
            return;
        }
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(sentMessageId);
        sentMessageId = 0;
        MessagesController.getInstance(account).deleteMessages(ids, null, null, selfId, 0, true, ChatActivity.MODE_DEFAULT);
    }

    private void saveMusic(TLRPC.Document document) {
        TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = false;
        req.id = inputDocument(document);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                debug("saveMusic failed " + error.text);
                FileLog.d("ProfileMusicStamp: saveMusic failed " + error.text);
                deleteSent();
                finish(false, REASON_SAVE);
                return;
            }
            debug("saveMusic ok, doc " + document.id);
            unsaveSource();
            TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(selfId);
            if (full != null) {
                full.saved_music = document;
                MessagesStorage.getInstance(account).updateUserInfo(full, false);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.profileMusicUpdated, selfId);
            }
            deleteSent();
            finish(true, REASON_OK);
        }));
    }

    private void unsaveSource() {
        TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = true;
        req.id = inputDocument(source);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            MessagesController.SavedMusicList list = MediaController.getInstance().currentSavedMusicList;
            if (list != null && list.dialogId == selfId) {
                MediaController.getInstance().currentSavedMusicList = null;
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.profileMusicUpdated, selfId);
        }));
    }

    private static TLRPC.TL_inputDocument inputDocument(TLRPC.Document document) {
        TLRPC.TL_inputDocument input = new TLRPC.TL_inputDocument();
        input.id = document.id;
        input.access_hash = document.access_hash;
        input.file_reference = document.file_reference != null ? document.file_reference : new byte[0];
        return input;
    }

    @Override
    public void didReceivedNotification(int id, int currentAccount, Object... args) {
        if (finished) {
            return;
        }
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
            if (awaitedFileName == null || !awaitedFileName.equals(args[0])) {
                return;
            }
            debug(id == NotificationCenter.fileLoaded ? "download done" : "download failed");
            if (id == NotificationCenter.fileLoadFailed) {
                finish(false, REASON_DOWNLOAD);
                return;
            }
            File file = localFile();
            if (file == null) {
                finish(false, REASON_DOWNLOAD);
                return;
            }
            awaitedFileName = null;
            upload(file);
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            if (awaitedFileName == null || !awaitedFileName.equals(args[0]) || !(args[1] instanceof Long) || !(args[2] instanceof Long)) {
                return;
            }
            long total = (Long) args[2];
            if (total > 0) {
                stage(STAGE_DOWNLOAD, (int) Math.min(100, (Long) args[1] * 100 / total));
            }
        } else if (id == NotificationCenter.fileUploadProgressChanged) {
            if (uploadPath == null || !uploadPath.equals(args[0]) || !(args[1] instanceof Long) || !(args[2] instanceof Long)) {
                return;
            }
            long uploaded = (Long) args[1];
            long total = (Long) args[2];
            if (total <= 0) {
                return;
            }
            stage(STAGE_UPLOAD, (int) Math.min(100, uploaded * 100 / total));
            int quarter = (int) (uploaded * 4 / total);
            if (quarter > loggedQuarter) {
                loggedQuarter = quarter;
                long elapsed = Math.max(1, SystemClock.elapsedRealtime() - uploadStartedAt);
                debug("uploaded " + quarter * 25 + "% in " + elapsed + " ms, " + uploaded / elapsed + " KB/s");
            }
        } else if (id == NotificationCenter.messageReceivedByServer) {
            if (sentMessageId != 0 || !(args[2] instanceof TLRPC.Message)) {
                return;
            }
            TLRPC.Message message = (TLRPC.Message) args[2];
            if (message.dialog_id != selfId) {
                return;
            }
            if (message.params == null || !sendTag.equals(message.params.get(SEND_TAG_KEY))) {
                debug("self message " + message.id + " sent, tag " + (message.params == null ? "missing" : message.params.get(SEND_TAG_KEY)) + ", ignored");
                return;
            }
            debug("sent as message " + message.id + ", local file_name="
                    + (message.media != null && message.media.document != null ? fileNameOf(message.media.document) : "none"));
            stage(STAGE_SAVE, 100);
            sentMessageId = message.id;
            fetchSent(message.id);
        }
    }

    private void finish(boolean ok, int reason) {
        if (finished) {
            return;
        }
        finished = true;
        debug("finish ok=" + ok + ", reason=" + reason);
        AndroidUtilities.cancelRunOnUIThread(timeout);
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.removeObserver(this, NotificationCenter.fileLoaded);
        center.removeObserver(this, NotificationCenter.fileLoadFailed);
        center.removeObserver(this, NotificationCenter.messageReceivedByServer);
        center.removeObserver(this, NotificationCenter.fileUploadProgressChanged);
        center.removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        FileUploadOperation.setFastUpload(uploadPath, false);
        if (callback != null) {
            callback.onFinished(ok, reason);
        }
    }

    private static String fileNameOf(TLRPC.Document document) {
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                return attribute.file_name;
            }
        }
        return null;
    }

    private static String performerOf(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                return attribute.performer;
            }
        }
        return null;
    }

    private static String titleOf(TLRPC.Document document) {
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                return attribute.title;
            }
        }
        return null;
    }

    private static int durationOf(TLRPC.Document document) {
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                return (int) attribute.duration;
            }
        }
        return 0;
    }
}
