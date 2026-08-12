//package com.cixingji.backend.im.handler.mq;
//
//import com.cixingji.backend.pojo.dto.SyncFailDTO;
//import com.cixingji.backend.pojo.entity.User;
//import lombok.AllArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.data.redis.core.RedisTemplate;
//import org.springframework.kafka.core.KafkaTemplate;
//import org.springframework.stereotype.Component;
//import top.javatool.canal.client.annotation.CanalTable;
//import top.javatool.canal.client.handler.EntryHandler;
//
//@CanalTable("user")
//@Component
//@AllArgsConstructor
//@Slf4j
//public class NmsUserHandler  implements EntryHandler<User> {
//
//    // 引入 Kafka 模板
//    private final KafkaTemplate<String, Object> kafkaTemplate;
//    // 注入泛型正确的 RedisTemplate<Object, Object>
//    private final RedisTemplate<Object,Object> redisTemplate;
//
//    // 定义补偿 topic 名称
//    private static final String COMPENSATION_TOPIC = "canal_redis_compensation";
//
//    private void handleRedisOperation(String opType, String key, User user) {
//        try {
//            log.info("[尝试 Redis 同步] Op:{} Key:{}", opType, key);
//
//            // 核心逻辑：执行 Redis 操作
//            if ("DELETE".equals(opType)) {
//                redisTemplate.delete(key);
//            } else { // INSERT 或 UPDATE
//                redisTemplate.opsForValue().set(key, user);
//            }
//
//        } catch (Exception e) {
//            // 如果 Redis 操作失败（例如连接异常、宕机等），则进入补偿流程
//            log.error("[Redis 失败] 发送补偿消息到 Kafka. Key: {}", key, e);
//
//            // 1. 构造失败消息体
//            SyncFailDTO failMessage = new SyncFailDTO("user", opType, key, user);
//                     // 发送完整的 User 对象，以便重试时可以正确 SET
//
//            // 2. 发送到补偿队列 (使用 key 作为 Kafka key，确保同一 key 的消息有序)
//            kafkaTemplate.send(COMPENSATION_TOPIC, key, failMessage);
//        }
//    }
//
//    @Override
//    public void insert(User nmsUser) {
//        handleRedisOperation("INSERT", "user:"+nmsUser.getUsername(), nmsUser);
//    }
//
//    @Override
//    public void update(User before, User after) {
//        handleRedisOperation("UPDATE", "user:"+after.getUsername(), after);
//    }
//
//    @Override
//    public void delete(User nmsUser) {
//        handleRedisOperation("DELETE", "user:"+nmsUser.getUsername(), nmsUser);
//    }
//}
//
