package com.cixingji.backend.component;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.cixingji.backend.config.DataSyncProperties;
import com.cixingji.backend.mapper.CanalSyncCheckpointMapper;
import com.cixingji.backend.pojo.dto.sync.CanalSyncEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CanalListenerTest {
    @SuppressWarnings("unchecked")
    private CanalListener listener() {
        return new CanalListener(new DataSyncProperties(), mock(KafkaTemplate.class),
                mock(CanalSyncCheckpointMapper.class), new ObjectMapper());
    }

    @Test
    void mapsVideoStatsToVideoAggregateWithStableBinlogIdentity() throws Exception {
        CanalEntry.Entry entry = entry("video_stats", CanalEntry.EventType.UPDATE,
                column("vid", "42"), "mysql-bin.000007", 1234L);

        List<CanalSyncEvent> first = listener().parse(Collections.singletonList(entry));
        List<CanalSyncEvent> second = listener().parse(Collections.singletonList(entry));

        assertEquals(1, first.size());
        assertEquals("VIDEO", first.get(0).getAggregateType());
        assertEquals("42", first.get(0).getAggregateKey());
        assertEquals(64, first.get(0).getEventId().length());
        assertEquals(first.get(0).getEventId(), second.get(0).getEventId());
    }

    @Test
    void mapsUserNicknameChangeToUserAggregate() throws Exception {
        CanalSyncEvent event = listener().parse(Collections.singletonList(entry("user", CanalEntry.EventType.UPDATE,
                column("uid", "9"), "mysql-bin.000008", 20L))).get(0);
        assertEquals("USER", event.getAggregateType());
        assertEquals("9", event.getAggregateKey());
    }

    @Test
    void mapsCategoryCompositeKey() throws Exception {
        CanalEntry.Column mc = column("mc_id", "anime");
        CanalEntry.Column sc = column("sc_id", "finish");
        CanalEntry.RowData row = CanalEntry.RowData.newBuilder().addAfterColumns(mc).addAfterColumns(sc).build();
        CanalSyncEvent event = listener().parse(Collections.singletonList(entry("category", CanalEntry.EventType.UPDATE,
                row, "mysql-bin.000009", 99L))).get(0);
        assertEquals("CATEGORY", event.getAggregateType());
        assertEquals("anime|finish", event.getAggregateKey());
    }

    private CanalEntry.Entry entry(String table, CanalEntry.EventType type, CanalEntry.Column column,
                                   String file, long offset) {
        return entry(table, type, CanalEntry.RowData.newBuilder().addAfterColumns(column).build(), file, offset);
    }

    private CanalEntry.Entry entry(String table, CanalEntry.EventType type, CanalEntry.RowData row,
                                   String file, long offset) {
        CanalEntry.RowChange change = CanalEntry.RowChange.newBuilder().setEventType(type).addRowDatas(row).build();
        CanalEntry.Header header = CanalEntry.Header.newBuilder().setSchemaName("teriteri").setTableName(table)
                .setLogfileName(file).setLogfileOffset(offset).setExecuteTime(1000L).build();
        return CanalEntry.Entry.newBuilder().setHeader(header).setEntryType(CanalEntry.EntryType.ROWDATA)
                .setStoreValue(change.toByteString()).build();
    }

    private CanalEntry.Column column(String name, String value) {
        return CanalEntry.Column.newBuilder().setName(name).setValue(value).setIsKey(true).setUpdated(true).build();
    }
}
