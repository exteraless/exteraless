package app.exteraless.chats;

import android.text.TextUtils;

import com.radolyn.ayugram.messages.AyuMessagesController;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;

import xyz.nextalone.nagram.NaConfig;

public final class DeletedReplyQuote {

    private static final class Author {

        final String name;
        final TLRPC.User user;
        final TLRPC.Chat chat;
        final int messageId;

        Author(String name, TLRPC.User user, TLRPC.Chat chat, int messageId) {
            this.name = name;
            this.user = user;
            this.chat = chat;
            this.messageId = messageId;
        }
    }

    private DeletedReplyQuote() {
    }

    public static void rewrite(int currentAccount, SendMessagesHelper.SendMessageParams params) {
        if (params == null || !NaConfig.INSTANCE.getReplyToDeletedAsQuote().Bool()) {
            return;
        }
        ChatActivity.ReplyQuote replyQuote = params.replyQuote;
        MessageObject source = params.replyToMsg != null ? params.replyToMsg
                : (replyQuote == null ? null : replyQuote.message);
        if (source == null || source.messageOwner == null) {
            return;
        }
        long selfId = UserConfig.getInstance(currentAccount).clientUserId;
        if (!AyuMessagesController.getInstance()
                .isAyuDeletedMessageId(selfId, source.getDialogId(), source.getId())) {
            return;
        }
        Author author = resolveAuthor(currentAccount, source);
        if (author == null || TextUtils.isEmpty(author.name)) {
            return;
        }
        CharSequence quoted = replyQuote != null ? replyQuote.getText() : source.messageText;
        String body = quoted == null ? "" : quoted.toString();
        String header = author.name;
        String prefix = header + "\n" + body;

        if (params.entities == null) {
            params.entities = new ArrayList<>();
        }
        for (TLRPC.MessageEntity entity : params.entities) {
            entity.offset += prefix.length();
        }
        params.entities.add(blockquote(prefix.length()));
        TLRPC.MessageEntity authorEntity = authorEntity(author, header.length());
        if (authorEntity != null) {
            params.entities.add(authorEntity);
        }
        params.message = prefix + (params.message == null ? "" : params.message);
        params.replyToMsg = params.replyToTopMsg;
        params.replyQuote = null;
    }

    private static TLRPC.TL_messageEntityBlockquote blockquote(int length) {
        TLRPC.TL_messageEntityBlockquote quote = new TLRPC.TL_messageEntityBlockquote();
        quote.offset = 0;
        quote.length = length;
        quote.collapsed = true;
        return quote;
    }

    private static TLRPC.MessageEntity authorEntity(Author author, int length) {
        if (author.user != null) {
            TLRPC.TL_inputMessageEntityMentionName mention = new TLRPC.TL_inputMessageEntityMentionName();
            TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
            inputUser.user_id = author.user.id;
            inputUser.access_hash = author.user.access_hash;
            mention.user_id = inputUser;
            mention.offset = 0;
            mention.length = length;
            return mention;
        }
        String url = chatUrl(author.chat, author.messageId);
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        TLRPC.TL_messageEntityTextUrl entity = new TLRPC.TL_messageEntityTextUrl();
        entity.offset = 0;
        entity.length = length;
        entity.url = url;
        return entity;
    }

    private static String chatUrl(TLRPC.Chat chat, int messageId) {
        if (chat == null) {
            return null;
        }
        String username = ChatObject.getPublicUsername(chat);
        if (!TextUtils.isEmpty(username)) {
            return "tg://resolve?domain=" + username;
        }
        if (!ChatObject.isChannel(chat) || chat.id == 0 || messageId <= 0) {
            return null;
        }
        return "tg://privatepost?channel=" + chat.id + "&post=" + messageId;
    }

    private static Author resolveAuthor(int currentAccount, MessageObject messageObject) {
        TLRPC.Message message = messageObject.messageOwner;
        MessagesController controller = MessagesController.getInstance(currentAccount);
        Author author = peerAuthor(controller, message.from_id, message.id);
        if (author != null) {
            return author;
        }
        if (message.post && message.peer_id instanceof TLRPC.TL_peerChannel) {
            author = chatAuthor(controller.getChat(message.peer_id.channel_id), message.id);
            if (author != null) {
                return author;
            }
        }
        long senderId = messageObject.getSenderId();
        if (senderId > 0) {
            author = userAuthor(controller.getUser(senderId), message.id);
        } else if (senderId < 0) {
            author = chatAuthor(controller.getChat(-senderId), message.id);
        }
        if (author != null) {
            return author;
        }
        if (message.fwd_from != null) {
            author = peerAuthor(controller, message.fwd_from.from_id, message.id);
            if (author != null) {
                return author;
            }
            author = peerAuthor(controller, message.fwd_from.saved_from_peer, message.id);
            if (author != null) {
                return author;
            }
            if (!TextUtils.isEmpty(message.fwd_from.from_name)) {
                return new Author(message.fwd_from.from_name, null, null, message.id);
            }
        }
        return userAuthor(controller.getUser(UserConfig.getInstance(currentAccount).clientUserId),
                message.id);
    }

    private static Author peerAuthor(MessagesController controller, TLRPC.Peer peer, int messageId) {
        if (peer instanceof TLRPC.TL_peerUser) {
            return userAuthor(controller.getUser(peer.user_id), messageId);
        }
        if (peer instanceof TLRPC.TL_peerChannel) {
            return chatAuthor(controller.getChat(peer.channel_id), messageId);
        }
        if (peer instanceof TLRPC.TL_peerChat) {
            return chatAuthor(controller.getChat(peer.chat_id), messageId);
        }
        return null;
    }

    private static Author userAuthor(TLRPC.User user, int messageId) {
        if (user == null) {
            return null;
        }
        String name = UserObject.getUserName(user);
        return TextUtils.isEmpty(name) ? null : new Author(name, user, null, messageId);
    }

    private static Author chatAuthor(TLRPC.Chat chat, int messageId) {
        if (chat == null || TextUtils.isEmpty(chat.title)) {
            return null;
        }
        return new Author(chat.title, null, chat, messageId);
    }
}
