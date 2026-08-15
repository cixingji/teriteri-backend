package com.cixingji.backend.im.handler;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.im.IMServer;
import com.cixingji.backend.mapper.ChatDetailedMapper;
import com.cixingji.backend.pojo.dto.UserDTO;
import com.cixingji.backend.pojo.entity.ChatDetailed;
import com.cixingji.backend.pojo.entity.IMResponse;
import com.cixingji.backend.service.message.ChatService;
import com.cixingji.backend.service.user.UserService;
import com.cixingji.backend.utils.RedisUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class ChatHandler {

    private static final int MAX_OFFLINE_REPLAY = 100;

    private static ChatService chatService;
    private static ChatDetailedMapper chatDetailedMapper;
    private static UserService userService;
    private static RedisUtil redisUtil;
    private static Executor taskExecutor;

    @Autowired
    private void setDependencies(ChatService chatService,
                                 ChatDetailedMapper chatDetailedMapper,
                                 UserService userService,
                                 RedisUtil redisUtil,
                                 @Qualifier("taskExecutor") Executor taskExecutor) {
        ChatHandler.chatService = chatService;
        ChatHandler.chatDetailedMapper = chatDetailedMapper;
        ChatHandler.userService = userService;
        ChatHandler.redisUtil = redisUtil;
        ChatHandler.taskExecutor = taskExecutor;
    }

    /**
     * 发送消息
     * @param ctx
     * @param tx
     */
    public static void send(ChannelHandlerContext ctx, TextWebSocketFrame tx) {
        try {
            ChatDetailed chatDetailed = JSONObject.parseObject(tx.text(), ChatDetailed.class);
            Integer userId = (Integer) ctx.channel().attr(AttributeKey.valueOf("userId")).get();
            if (chatDetailed != null && chatDetailed.getClientMessageId() == null) {
                // 兼容尚未升级、仍使用原101指令结构的客户端。
                chatDetailed.setClientMessageId("legacy_" + UUID.randomUUID().toString().replace("-", ""));
            }
            if (!isValidMessage(chatDetailed, userId)) {
                ctx.channel().writeAndFlush(IMResponse.error("消息参数不合法"));
                return;
            }
            UserDTO recipient = userService.getUserById(chatDetailed.getAnotherId());
            if (recipient == null || !Integer.valueOf(0).equals(recipient.getState())) {
                ctx.channel().writeAndFlush(IMResponse.error("接收用户不存在或当前不可用"));
                return;
            }

            chatDetailed.setUserId(userId);
            chatDetailed.setUserDel(0);
            chatDetailed.setAnotherDel(0);
            chatDetailed.setWithdraw(0);
            chatDetailed.setTime(new Date());
            chatDetailed.setDeliveredAt(null);
            chatDetailed.setReadAt(null);

            ChatDetailed existing = findByClientMessageId(userId, chatDetailed.getClientMessageId());
            if (existing != null) {
                sendMessageEnvelope(existing, false);
                return;
            }

            try {
                chatDetailedMapper.insert(chatDetailed);
            } catch (DuplicateKeyException duplicate) {
                ChatDetailed duplicateMessage = findByClientMessageId(userId, chatDetailed.getClientMessageId());
                if (duplicateMessage != null) {
                    sendMessageEnvelope(duplicateMessage, false);
                    return;
                }
                throw duplicate;
            }
            // "chat_detailed_zset:对方:自己"
            redisUtil.zset("chat_detailed_zset:" + userId + ":" + chatDetailed.getAnotherId(), chatDetailed.getId());
            redisUtil.zset("chat_detailed_zset:" + chatDetailed.getAnotherId() + ":" + userId, chatDetailed.getId());
            chatService.updateChat(userId, chatDetailed.getAnotherId());
            sendMessageEnvelope(chatDetailed, false);

        } catch (Exception e) {
            log.error("发送聊天信息时出错了", e);
            ctx.channel().writeAndFlush(IMResponse.error("发送消息时出错了 Σ(ﾟдﾟ;)"));
        }
    }

    /** 接收方确认消息已经抵达任意一台设备。 */
    public static void acknowledgeDelivery(ChannelHandlerContext ctx, TextWebSocketFrame tx) {
        JSONObject command = JSONObject.parseObject(tx.text());
        Integer messageId = command.getInteger("id");
        Integer receiverId = currentUserId(ctx);
        if (messageId == null || receiverId == null) {
            return;
        }
        ChatDetailed detail = chatDetailedMapper.selectById(messageId);
        if (detail == null || !Objects.equals(detail.getAnotherId(), receiverId)) {
            ctx.channel().writeAndFlush(IMResponse.error("无权确认此消息"));
            return;
        }
        Date deliveredAt = detail.getDeliveredAt() == null ? new Date() : detail.getDeliveredAt();
        if (detail.getDeliveredAt() == null) {
            UpdateWrapper<ChatDetailed> update = new UpdateWrapper<>();
            update.eq("id", messageId).isNull("delivered_at").set("delivered_at", deliveredAt);
            int updated = chatDetailedMapper.update(null, update);
            if (updated == 0) {
                ChatDetailed latest = chatDetailedMapper.selectById(messageId);
                if (latest != null && latest.getDeliveredAt() != null) {
                    deliveredAt = latest.getDeliveredAt();
                }
            }
        }
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("type", "送达");
        receipt.put("id", messageId);
        receipt.put("clientMessageId", detail.getClientMessageId());
        receipt.put("deliveredAt", deliveredAt);
        broadcast(detail.getUserId(), "whisper", receipt);
    }

    /** 接收方读取与指定用户的消息，并把回执同步给发送方的全部设备。 */
    public static void acknowledgeRead(ChannelHandlerContext ctx, TextWebSocketFrame tx) {
        JSONObject command = JSONObject.parseObject(tx.text());
        Integer senderId = command.getInteger("anotherId");
        Integer upToMessageId = command.getInteger("upToMessageId");
        Integer receiverId = currentUserId(ctx);
        if (senderId == null || receiverId == null || Objects.equals(senderId, receiverId)) {
            return;
        }

        Date readAt = new Date();
        UpdateWrapper<ChatDetailed> update = new UpdateWrapper<>();
        update.eq("user_id", senderId)
                .eq("another_id", receiverId)
                .eq("withdraw", 0)
                .isNull("read_at")
                .setSql("delivered_at = COALESCE(delivered_at, NOW())")
                .set("read_at", readAt);
        if (upToMessageId != null) {
            update.le("id", upToMessageId);
        }
        int updated = chatDetailedMapper.update(null, update);
        if (updated == 0) {
            return;
        }

        Map<String, Object> receipt = new HashMap<>();
        receipt.put("type", "已读回执");
        receipt.put("readerId", receiverId);
        receipt.put("upToMessageId", upToMessageId);
        receipt.put("readAt", readAt);
        broadcast(senderId, "whisper", receipt);
    }

    /** 新连接鉴权后重放尚未被任何接收设备确认的消息。 */
    public static void pushOffline(ChannelHandlerContext ctx, Integer uid) {
        pushOffline(ctx, uid, 0L);
    }

    public static void pushOffline(ChannelHandlerContext ctx, Integer uid, Long afterId) {
        CompletableFuture.runAsync(() -> {
            try {
                QueryWrapper<ChatDetailed> query = new QueryWrapper<>();
                query.eq("another_id", uid)
                        .eq("withdraw", 0)
                        .isNull("delivered_at");
                if (afterId != null && afterId > 0) {
                    query.gt("id", afterId);
                }
                query.orderByAsc("id").last("LIMIT " + (MAX_OFFLINE_REPLAY + 1));
                List<ChatDetailed> pending = chatDetailedMapper.selectList(query);
                if (pending == null) {
                    return;
                }
                boolean hasMore = pending.size() > MAX_OFFLINE_REPLAY;
                List<ChatDetailed> batch = hasMore ? pending.subList(0, MAX_OFFLINE_REPLAY) : pending;
                long nextCursor = afterId == null ? 0L : afterId;
                for (ChatDetailed detail : batch) {
                    Map<String, Object> envelope = buildMessageEnvelope(detail, true, false);
                    ctx.channel().writeAndFlush(IMResponse.message("whisper", envelope));
                    nextCursor = detail.getId();
                }
                Map<String, Object> batchResult = new HashMap<>();
                batchResult.put("type", "离线批次");
                batchResult.put("hasMore", hasMore);
                batchResult.put("nextCursor", nextCursor);
                ctx.channel().writeAndFlush(IMResponse.message("connection", batchResult));
            } catch (Exception e) {
                log.error("重放用户{}的离线消息失败", uid, e);
            }
        }, taskExecutor);
    }

    private static boolean isValidMessage(ChatDetailed detail, Integer userId) {
        return detail != null
                && userId != null
                && detail.getAnotherId() != null
                && detail.getAnotherId() > 0
                && !Objects.equals(userId, detail.getAnotherId())
                && detail.getContent() != null
                && !detail.getContent().trim().isEmpty()
                && detail.getContent().length() <= 500
                && detail.getClientMessageId() != null
                && detail.getClientMessageId().matches("[A-Za-z0-9_-]{8,64}");
    }

    private static Integer currentUserId(ChannelHandlerContext ctx) {
        return (Integer) ctx.channel().attr(AttributeKey.valueOf("userId")).get();
    }

    private static ChatDetailed findByClientMessageId(Integer senderId, String clientMessageId) {
        if (clientMessageId == null) {
            return null;
        }
        QueryWrapper<ChatDetailed> query = new QueryWrapper<>();
        query.eq("user_id", senderId).eq("client_message_id", clientMessageId).last("LIMIT 1");
        return chatDetailedMapper.selectOne(query);
    }

    private static void sendMessageEnvelope(ChatDetailed detail, boolean offline) {
        Map<String, Object> envelope = buildMessageEnvelope(detail, offline);
        broadcast(detail.getUserId(), "whisper", envelope);
        broadcast(detail.getAnotherId(), "whisper", envelope);
    }

    private static Map<String, Object> buildMessageEnvelope(ChatDetailed detail, boolean offline) {
        return buildMessageEnvelope(detail, offline, true);
    }

    private static Map<String, Object> buildMessageEnvelope(ChatDetailed detail, boolean offline, boolean parallelLookup) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "接收");
        String activeWindowKey = "whisper:" + detail.getAnotherId() + ":" + detail.getUserId();
        try {
            Long activeWindows = redisUtil.scard(activeWindowKey);
            map.put("online", activeWindows != null && activeWindows > 0);
        } catch (Exception legacyValue) {
            map.put("online", redisUtil.isExist(activeWindowKey));
        }
        map.put("offline", offline);
        map.put("detail", detail);

        if (!parallelLookup) {
            map.put("chat", chatService.getChat(detail.getUserId(), detail.getAnotherId()));
            map.put("senderChat", chatService.getChat(detail.getAnotherId(), detail.getUserId()));
            map.put("user", userService.getUserById(detail.getUserId()));
            map.put("recipientUser", userService.getUserById(detail.getAnotherId()));
            return map;
        }

        CompletableFuture<Void> chatFuture = CompletableFuture.runAsync(() ->
                map.put("chat", chatService.getChat(detail.getUserId(), detail.getAnotherId())), taskExecutor);
        CompletableFuture<Void> senderChatFuture = CompletableFuture.runAsync(() ->
                map.put("senderChat", chatService.getChat(detail.getAnotherId(), detail.getUserId())), taskExecutor);
        CompletableFuture<Void> userFuture = CompletableFuture.runAsync(() ->
                map.put("user", userService.getUserById(detail.getUserId())), taskExecutor);
        CompletableFuture<Void> recipientFuture = CompletableFuture.runAsync(() ->
                map.put("recipientUser", userService.getUserById(detail.getAnotherId())), taskExecutor);
        CompletableFuture.allOf(chatFuture, senderChatFuture, userFuture, recipientFuture).join();
        return map;
    }

    private static void broadcast(Integer uid, String type, Object payload) {
        Set<Channel> channels = IMServer.userChannel.get(uid);
        if (channels == null) {
            return;
        }
        for (Channel channel : channels) {
            if (channel.isActive()) {
                channel.writeAndFlush(IMResponse.message(type, payload));
            }
        }
    }

    /**
     * 撤回消息
     * @param ctx
     * @param tx
     */
    public static void withdraw(ChannelHandlerContext ctx, TextWebSocketFrame tx) {
        try {
            JSONObject jsonObject = JSONObject.parseObject(tx.text());
            Integer id = jsonObject.getInteger("id");
            Integer user_id = (Integer) ctx.channel().attr(AttributeKey.valueOf("userId")).get();

            // 查询数据库
            ChatDetailed chatDetailed = chatDetailedMapper.selectById(id);
            if (chatDetailed == null) {
                ctx.channel().writeAndFlush(IMResponse.error("消息不存在"));
                return;
            }
            if (!Objects.equals(chatDetailed.getUserId(), user_id)) {
                ctx.channel().writeAndFlush(IMResponse.error("无权撤回此消息"));
                return;
            }
            long diff = System.currentTimeMillis() - chatDetailed.getTime().getTime();
            if (diff > 120000) {
                ctx.channel().writeAndFlush(IMResponse.error("发送时间超过两分钟不能撤回"));
                return;
            }
            // 更新 withdraw 字段
            UpdateWrapper<ChatDetailed> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", id).setSql("withdraw = 1");
            chatDetailedMapper.update(null, updateWrapper);

            // 转发到发送者和接收者的全部channel
            Map<String, Object> map = new HashMap<>();
            map.put("type", "撤回");
            map.put("sendId", chatDetailed.getUserId());
            map.put("acceptId", chatDetailed.getAnotherId());
            map.put("id", id);

            // 发给自己的全部channel
            Set<Channel> from = IMServer.userChannel.get(user_id);
            if (from != null) {
                for (Channel channel : from) {
                    channel.writeAndFlush(IMResponse.message("whisper", map));
                }
            }
            // 发给对方的全部channel
            Set<Channel> to = IMServer.userChannel.get(chatDetailed.getAnotherId());
            if (to != null) {
                for (Channel channel : to) {
                    channel.writeAndFlush(IMResponse.message("whisper", map));
                }
            }

        } catch (Exception e) {
            log.error("撤回聊天信息时出错了：" + e);
            ctx.channel().writeAndFlush(IMResponse.error("撤回消息时出错了 Σ(ﾟдﾟ;)"));
        }
    }
}
