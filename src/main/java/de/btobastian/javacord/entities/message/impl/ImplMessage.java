/*
 * Copyright (C) 2016 Bastian Oppermann
 * 
 * This file is part of Javacord.
 * 
 * Javacord is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser general Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 * 
 * Javacord is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, see <http://www.gnu.org/licenses/>.
 */
package de.btobastian.javacord.entities.message.impl;

import com.google.common.base.Joiner;
import com.google.common.util.concurrent.FutureCallback;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import de.btobastian.javacord.ImplDiscordAPI;
import de.btobastian.javacord.entities.Channel;
import de.btobastian.javacord.entities.Server;
import de.btobastian.javacord.entities.User;
import de.btobastian.javacord.entities.impl.ImplServer;
import de.btobastian.javacord.entities.impl.ImplUser;
import de.btobastian.javacord.entities.message.Message;
import de.btobastian.javacord.entities.message.MessageAttachment;
import de.btobastian.javacord.entities.message.MessageReceiver;
import de.btobastian.javacord.entities.message.embed.Embed;
import de.btobastian.javacord.entities.message.embed.EmbedBuilder;
import de.btobastian.javacord.entities.message.embed.impl.ImplEmbed;
import de.btobastian.javacord.listener.message.MessageDeleteListener;
import de.btobastian.javacord.listener.message.MessageEditListener;
import de.btobastian.javacord.utils.LoggerUtil;
import de.btobastian.javacord.utils.ratelimits.RateLimitType;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * The implementation of the user interface.
 */
public class ImplMessage implements Message {

    /**
     * The logger of this class.
     */
    private static final Logger logger = LoggerUtil.getLogger(ImplMessage.class);

    private static final ThreadLocal<SimpleDateFormat> TIMEZONE_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        }
    };
    private static final ThreadLocal<SimpleDateFormat> FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
        }
    };
    private static final ThreadLocal<SimpleDateFormat> FORMAT_ALTERNATIVE = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        }
    };
    private static final ThreadLocal<SimpleDateFormat> FORMAT_ALTERNATIVE_TWO = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm");
        }
    };

    private final ImplDiscordAPI api;

    private final String id;
    private String content = null;
    private final boolean tts;
    private final User author;
    private final List<User> mentions = new ArrayList<>();
    private final MessageReceiver receiver;
    private final String channelId;
    private final List<MessageAttachment> attachments = new ArrayList<>();
    private Calendar creationDate = Calendar.getInstance();
    private final Collection<Embed> embeds = new ArrayList<>();

    /**
     * Creates a new instance of this class.
     *
     * @param data A JSONObject containing all necessary data.
     * @param api The api of this server.
     */
    public ImplMessage(JSONObject data, ImplDiscordAPI api, MessageReceiver receiver) {
        this.api = api;

        id = data.getString("id");
        if (data.has("content")) {
            content = data.getString("content");
        }
        tts = data.getBoolean("tts");

        if (data.has("timestamp")) {
            String time = data.getString("timestamp");
            Calendar calendar = Calendar.getInstance();
            try {
                //remove the nano seconds, rejoining on +. If the formatting changes then the string will remain the same
                String nanoSecondsRemoved = Joiner.on("+").join(time.split("\\d{3}\\+"));
                calendar.setTime(TIMEZONE_FORMAT.get().parse(nanoSecondsRemoved));
            } catch (ParseException timeZoneIgnored) {
                try {
                    calendar.setTime(FORMAT.get().parse(time.substring(0, time.length() - 9)));
                } catch (ParseException ignored) {
                    try {
                        calendar.setTime(FORMAT_ALTERNATIVE.get().parse(time.substring(0, time.length() - 9)));
                    } catch (ParseException ignored2) {
                        try {
                            calendar.setTime(FORMAT_ALTERNATIVE_TWO.get().parse(time.substring(0, time.length() - 9)));
                        } catch (ParseException e) {
                            logger.warn("Could not parse timestamp {}. Please contact the developer!", time, e);
                        }
                    }
                }
            }
            creationDate = calendar;
        }
        author = api.getOrCreateUser(data.getJSONObject("author"));

        try {
            JSONArray attachments = data.getJSONArray("attachments");
            for (int i = 0; i < attachments.length(); i++) {
                JSONObject attachment = attachments.getJSONObject(i);
                String url = attachment.getString("url");
                String proxyUrl = attachment.getString("proxy_url");
                int size = attachment.getInt("size");
                String id = attachment.getString("id");
                String name = attachment.getString("filename");
                this.attachments.add(new ImplMessageAttachment(url, proxyUrl, size, id, name));
            }
        } catch (JSONException ignored) { }

        JSONArray mentions = data.getJSONArray("mentions");
        for (int i = 0; i < mentions.length(); i++) {
            String userId = mentions.getJSONObject(i).getString("id");
            User user;
            try {
                user = api.getUserById(userId).get();
            } catch (InterruptedException | ExecutionException e) {
                continue;
            }
            this.mentions.add(user);
        }

        JSONArray embeds = data.getJSONArray("embeds");
        for (int i = 0; i < embeds.length(); i++) {
            Embed embed = new ImplEmbed(embeds.getJSONObject(i));
            this.embeds.add(embed);
        }

        channelId = data.getString("channel_id");
        if (receiver == null) {
            this.receiver = findReceiver(channelId);
        } else {
            this.receiver = receiver;
        }

        if (getChannelReceiver() != null) {
            ((ImplServer) getChannelReceiver().getServer()).addMember(author);
        }

        api.addMessage(this);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getContent() {
        return content;
    }

    @Override
    public Channel getChannelReceiver() {
        if (receiver instanceof Channel) {
            return (Channel) receiver;
        }
        return null;
    }

    @Override
    public User getUserReceiver() {
        if (receiver instanceof User) {
            return (User) receiver;
        }
        return null;
    }

    @Override
    public MessageReceiver getReceiver() {
        return receiver;
    }

    @Override
    public User getAuthor() {
        return author;
    }

    @Override
    public boolean isPrivateMessage() {
        return getUserReceiver() != null;
    }

    @Override
    public List<User> getMentions() {
        return new ArrayList<>(mentions);
    }

    @Override
    public boolean isTts() {
        return tts;
    }

    @Override
    public Future<Void> delete() {
        final Message message = this;
        return api.getThreadPool().getExecutorService().submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                logger.debug("Trying to delete message (id: {}, author: {}, content: \"{}\")",
                        getId(), getAuthor(), getContent());
                if (isPrivateMessage()) {
                    api.checkRateLimit(null, RateLimitType.PRIVATE_MESSAGE_DELETE, null);
                } else {
                    api.checkRateLimit(null, RateLimitType.SERVER_MESSAGE_DELETE, getChannelReceiver().getServer());
                }
                HttpResponse<JsonNode> response = Unirest.delete
                        ("https://discordapp.com/api/channels/" + channelId + "/messages/" + getId())
                        .header("authorization", api.getToken())
                        .asJson();
                api.checkResponse(response);
                if (isPrivateMessage()) {
                    api.checkRateLimit(response, RateLimitType.PRIVATE_MESSAGE_DELETE, null);
                } else {
                    api.checkRateLimit(
                            response, RateLimitType.SERVER_MESSAGE_DELETE, getChannelReceiver().getServer());
                }
                api.removeMessage(message);
                logger.debug("Deleted message (id: {}, author: {}, content: \"{}\")",
                        getId(), getAuthor(), getContent());
                // call listener
                api.getThreadPool().getSingleThreadExecutorService("listeners").submit(new Runnable() {
                    @Override
                    public void run() {
                        List<MessageDeleteListener> listeners = api.getListeners(MessageDeleteListener.class);
                        synchronized (listeners) {
                            for (MessageDeleteListener listener : listeners) {
                                try {
                                    listener.onMessageDelete(api, message);
                                } catch (Throwable t) {
                                    logger.warn("Uncaught exception in MessageDeleteListener!", t);
                                }
                            }
                        }
                    }
                });
                return null;
            }
        });
    }

    @Override
    public Collection<MessageAttachment> getAttachments() {
        return Collections.unmodifiableCollection(attachments);
    }

    @Override
    public Future<Message> reply(String content) {
        return reply(content, null, false, null);
    }

    @Override
    public Future<Message> reply(String content, boolean tts) {
        return reply(content, null, tts, null);
    }

    @Override
    public Future<Message> reply(String content, EmbedBuilder embed) {
        return reply(content, embed, false, null);
    }

    @Override
    public Future<Message> reply(String content, EmbedBuilder embed, boolean tts) {
        return reply(content, embed, tts, null);
    }

    @Override
    public Future<Message> reply(String content, FutureCallback<Message> callback) {
        return reply(content, null, false, callback);
    }

    @Override
    public Future<Message> reply(String content, boolean tts, FutureCallback<Message> callback) {
        return reply(content, null, tts, callback);
    }

    @Override
    public Future<Message> reply(String content, EmbedBuilder embed, FutureCallback<Message> callback) {
        return reply(content, embed, false, callback);
    }

    @Override
    public Future<Message> reply(String content, EmbedBuilder embed, boolean tts, FutureCallback<Message> callback) {
        return receiver.sendMessage(content, embed, tts, callback);
    }

    @Override
    public Future<Message> replyFile(final File file) {
        return replyFile(file, null, null);
    }

    @Override
    public Future<Message> replyFile(final File file, FutureCallback<Message> callback) {
        return replyFile(file, null, callback);
    }

    @Override
    public Future<Message> replyFile(InputStream inputStream, String filename) {
        return replyFile(inputStream, filename, null, null);
    }

    @Override
    public Future<Message> replyFile(InputStream inputStream, String filename, FutureCallback<Message> callback) {
        return replyFile(inputStream, filename, null, callback);
    }

    @Override
    public Future<Message> replyFile(File file, String comment) {
        return replyFile(file, comment, null);
    }

    @Override
    public Future<Message> replyFile(final File file, final String comment, FutureCallback<Message> callback) {
        return receiver.sendFile(file, comment, callback);
    }

    @Override
    public Future<Message> replyFile(InputStream inputStream, String filename, String comment) {
        return replyFile(inputStream, filename, comment, null);
    }

    @Override
    public Future<Message> replyFile(final InputStream inputStream, final String filename, final String comment,
                                     FutureCallback<Message> callback) {
        return receiver.sendFile(inputStream, filename, comment, callback);
    }

    @Override
    public Calendar getCreationDate() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(creationDate.getTime());
        return calendar;
    }

    @Override
    public int compareTo(Message other) {
        return this.creationDate.compareTo(other.getCreationDate());
    }

    @Override
    public Future<Void> edit(final String content) {
        return api.getThreadPool().getExecutorService().submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                if (isPrivateMessage()) {
                    api.checkRateLimit(null, RateLimitType.PRIVATE_MESSAGE, null);
                } else {
                    api.checkRateLimit(null, RateLimitType.SERVER_MESSAGE, getChannelReceiver().getServer());
                }
                HttpResponse<JsonNode> response = Unirest
                        .patch("https://discordapp.com/api/channels/" + channelId + "/messages/" + getId())
                        .header("authorization", api.getToken())
                        .header("content-type", "application/json")
                        .body(new JSONObject().put("content", content).toString())
                        .asJson();
                api.checkResponse(response);
                if (isPrivateMessage()) {
                    api.checkRateLimit(response, RateLimitType.PRIVATE_MESSAGE, null);
                } else {
                    api.checkRateLimit(response, RateLimitType.SERVER_MESSAGE, getChannelReceiver().getServer());
                }
                final String oldContent = getContent();
                setContent(content);
                if (!oldContent.equals(content)) {
                    api.getThreadPool().getSingleThreadExecutorService("listeners").submit(new Runnable() {
                        @Override
                        public void run() {
                            List<MessageEditListener> listeners = api.getListeners(MessageEditListener.class);
                            synchronized (listeners) {
                                for (MessageEditListener listener : listeners) {
                                    try {
                                        listener.onMessageEdit(api, ImplMessage.this, oldContent);
                                    } catch (Throwable t) {
                                        logger.warn("Uncaught exception in MessageEditListener!", t);
                                    }
                                }
                            }
                        }
                    });
                }
                return null;
            }
        });
    }

    @Override
    public Collection<Embed> getEmbeds() {
        return Collections.unmodifiableCollection(embeds);
    }

    @Override
    public Future<Void> addUnicodeReaction(final String unicodeEmoji) {
        return api.getThreadPool().getExecutorService().submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                logger.debug("Trying to add reaction to message with id {} (emoji: {})", getId(), unicodeEmoji);
                if (isPrivateMessage()) {
                    api.checkRateLimit(null, RateLimitType.UNKNOWN, null);
                } else {
                    api.checkRateLimit(null, RateLimitType.UNKNOWN, getChannelReceiver().getServer());
                }
                HttpResponse<JsonNode> response = Unirest
                        .put("https://discordapp.com/api/channels/" + channelId + "/messages/" + getId() + "/reactions/" + unicodeEmoji + "/@me")
                        .header("authorization", api.getToken())
                        .header("content-type", "application/json")
                        .body("{}")
                        .asJson();
                api.checkResponse(response);
                if (isPrivateMessage()) {
                    api.checkRateLimit(response, RateLimitType.UNKNOWN, null);
                } else {
                    api.checkRateLimit(response, RateLimitType.UNKNOWN, getChannelReceiver().getServer());
                }
                logger.debug("Added reaction to message with id {} (emoji: {})", getId(), unicodeEmoji);
                return null;
            }
        });
    }

    /**
     * Updates the content of the message.
     *
     * @param content The new content of the message.
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * Tries to find the message receiver based on its channel id.
     *
     * @param channelId The channel id of the receiver.
     * @return The receiver with the given id.
     */
    private MessageReceiver findReceiver(String channelId) {
        for (Server server : api.getServers()) {
            if (server.getChannelById(channelId) != null) {
                return server.getChannelById(channelId);
            }
        }
        for (User user : api.getUsers()) {
            if (channelId.equals(((ImplUser) user).getUserChannelId())) {
                return user;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return getAuthor().getName() + ": " + getContent() + " (id: " + getId() + ")";
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

}
