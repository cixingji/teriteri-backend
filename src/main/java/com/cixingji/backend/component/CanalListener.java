//package com.cixingji.backend.component;
//
//
//
//import com.alibaba.otter.canal.client.CanalConnector;
//import com.alibaba.otter.canal.client.CanalConnectors;
//import com.alibaba.otter.canal.protocol.CanalEntry;
//import com.alibaba.otter.canal.protocol.Message;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.PostConstruct;
//import java.net.InetSocketAddress;
//import java.util.List;
//
//@Component
//public class CanalListener {
//    /**
//     * 解析数据
//     *
//     * @param beforeColumns 修改、删除后的数据
//     * @param afterColumns  新增、修改、删除前的数据
//     * @param dbName        数据库名字
//     * @param tableName     表大的名字
//     * @param eventType     操作类型（INSERT,UPDATE,DELETE）
//     * @param timestamp     消耗时间
//     */
//    private static void dataDetails(List<CanalEntry.Column> beforeColumns, List<CanalEntry.Column> afterColumns, String dbName, String tableName, CanalEntry.EventType eventType, long timestamp) {
//
//        System.out.println("数据库：" + dbName);
//        System.out.println("表名：" + tableName);
//        System.out.println("操作类型:" + eventType);
//        if (CanalEntry.EventType.INSERT.equals(eventType)) {
//            System.out.println("这是一条新增的数据");
//        } else if (CanalEntry.EventType.DELETE.equals(eventType)) {
//            System.out.println("删除数据：" + afterColumns);
//        } else {
//            System.out.println("更新数据：更新前数据--" + afterColumns);
//            System.out.println("更新数据：更新后数据--" + beforeColumns);
//
//        }
//        System.out.println("操作时间：" + timestamp);
//    }
//
////    @PostConstruct
////    public void run() throws Exception {
////        CanalConnector conn = CanalConnectors.newSingleConnector(new InetSocketAddress("127.0.0.1", 6220), "redis", null, null);
////        while (true) {
////            conn.connect();
////            conn.subscribe(".*\\..*");
////            // 回滚到未进行ack的地方
////            conn.rollback();
////            // 获取数据 每次获取一百条改变数据
////            Message message = conn.getWithoutAck(100);
////            //获取这条消息的id
////            long id = message.getId();
////            int size = message.getEntries().size();
////            if (id != -1 && size > 0) {
////                // 数据解析
////                analysis(message.getEntries());
////            } else {
////                //暂停1秒防止重复链接数据库
////                Thread.sleep(1000);
////            }
////            // 确认消费完成这条消息
////            conn.ack(message.getId());
////            // 关闭连接
////            conn.disconnect();
////        }
////    }
//    @PostConstruct
//    public void init() {
//        // 在 PostConstruct 中启动一个新的线程来执行 run 逻辑
//        new Thread(() -> {
//            try {
//                this.runListener(); // 调用我们封装的监听逻辑
//            } catch (Exception e) {
//                System.err.println("Canal Listener 线程异常: " + e.getMessage());
//            }
//        }, "canal-listener-thread").start();
//    }
//    public void runListener() throws Exception {
//        // 【1. 实例化连接器，确保端口是 11111 或你实际配置的端口】
//        CanalConnector conn = CanalConnectors.newSingleConnector(new InetSocketAddress("127.0.0.1", 11111), "redis", null, null);
//        try {
//            // 【2. 连接、订阅、回滚：只执行一次】
//            conn.connect();
//            conn.subscribe(".*\\..*");
//            conn.rollback(); // 回滚到未进行ack的地方
//            // 【3. 进入主循环，只拉取和处理数据】
//            while (true) {
//                // 获取数据 每次获取一百条改变数据
//                Message message = conn.getWithoutAck(100);
//                //获取这条消息的id
//                long id = message.getId();
//                int size = message.getEntries().size();
//                if (id != -1 && size > 0) {
//                    // 数据解析
//                    analysis(message.getEntries());
//                    // 【确认消费完成这条消息】
//                    conn.ack(message.getId());
//                } else {
//                    //暂停1秒防止重复拉取
//                    Thread.sleep(1000);
//                }
//
//                // 【注意：这里不再有 conn.disconnect()!】
//            }
//        } catch (Exception e) {
//            // 捕获异常，打印错误
//            System.err.println("Canal Listener 运行异常: " + e.getMessage());
//            throw e;
//        } finally {
//            // 确保在程序退出时关闭连接
//            if (conn != null) {
//                conn.disconnect();
//            }
//        }
//    }
//    /**
//     * 数据解析
//     */
//    private void analysis(List<CanalEntry.Entry> entries) {
//        for (CanalEntry.Entry entry : entries) {
//            // 解析binlog
//            CanalEntry.RowChange rowChange = null;
//            try {
//                rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
//            } catch (Exception e) {
//                throw new RuntimeException("解析出现异常 data:" + entry.toString(), e);
//            }
//            if (rowChange != null) {
//                // 获取操作类型
//                CanalEntry.EventType eventType = rowChange.getEventType();
//                // 获取当前操作所属的数据库
//                String dbName = entry.getHeader().getSchemaName();
//                // 获取当前操作所属的表
//                String tableName = entry.getHeader().getTableName();
//                // 事务提交时间
//                long timestamp = entry.getHeader().getExecuteTime();
//                for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
//                    dataDetails(rowData.getBeforeColumnsList(), rowData.getAfterColumnsList(), dbName, tableName, eventType, timestamp);
//
//                }
//            }
//        }
//    }
//
//}
