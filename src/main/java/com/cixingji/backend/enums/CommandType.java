package com.cixingji.backend.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CommandType {
    /**
     * 建立连接
     */
    CONNETION(100),

    /**
     * 聊天功能 发送
     */
    CHAT_SEND(101),

    /**
     * 聊天功能 撤回
     */
    CHAT_WITHDRAW(102),

    /**
     * 聊天功能 接收方确认已送达
     */
    CHAT_DELIVERY_ACK(103),

    /**
     * 聊天功能 接收方确认已读
     */
    CHAT_READ_ACK(104),

    /**
     * 应用层心跳。浏览器 WebSocket API 无法主动发送 Ping 帧。
     */
    HEARTBEAT(105),

    /**
     * 分批拉取离线消息
     */
    OFFLINE_PULL(106),

    ERROR(-1),
    ;

    private final Integer code;

    public static CommandType match(Integer code) {
        for (CommandType value: CommandType.values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }
        return ERROR;
    }
}
