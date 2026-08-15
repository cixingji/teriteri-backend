package com.cixingji.backend.im;

import com.alibaba.fastjson2.JSON;
import com.cixingji.backend.enums.CommandType;
import com.cixingji.backend.pojo.entity.ChatDetailed;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReliableImProtocolTest {

    @AfterEach
    void clearRegistry() {
        IMServer.userChannel.clear();
    }

    @Test
    void commandCodesAreUniqueAndResolvable() {
        Set<Integer> codes = new HashSet<>();
        for (CommandType type : CommandType.values()) {
            assertTrue(codes.add(type.getCode()), "duplicate command code: " + type.getCode());
            assertEquals(type, CommandType.match(type.getCode()));
        }
        assertEquals(CommandType.ERROR, CommandType.match(999));
    }

    @Test
    void channelRegistryKeepsMultipleDevicesUntilLastDisconnects() {
        EmbeddedChannel phone = new EmbeddedChannel();
        EmbeddedChannel browser = new EmbeddedChannel();

        IMServer.register(7, phone);
        IMServer.register(7, browser);
        assertEquals(2, IMServer.userChannel.get(7).size());
        assertTrue(IMServer.isOnline(7));

        IMServer.unregister(7, phone);
        assertEquals(1, IMServer.userChannel.get(7).size());

        IMServer.unregister(7, browser);
        assertFalse(IMServer.userChannel.containsKey(7));
        phone.close();
        browser.close();
    }

    @Test
    void sendCommandCarriesClientIdForIdempotentRetry() {
        ChatDetailed detail = JSON.parseObject(
                "{\"code\":101,\"anotherId\":9,\"content\":\"hello\",\"clientMessageId\":\"msg_12345678\"}",
                ChatDetailed.class);

        assertEquals(9, detail.getAnotherId());
        assertEquals("hello", detail.getContent());
        assertEquals("msg_12345678", detail.getClientMessageId());
    }
}
