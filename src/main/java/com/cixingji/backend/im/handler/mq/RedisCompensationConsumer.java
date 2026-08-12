//package com.cixingji.backend.im.handler.mq;
//
//import com.cixingji.backend.pojo.dto.SyncFailDTO;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.data.redis.core.RedisTemplate;
//import org.springframework.kafka.annotation.KafkaListener;
//import org.springframework.stereotype.Service;
//
//@Service
//@Slf4j
//public class RedisCompensationConsumer {
//
//    // 注入 RedisTemplate (泛型需与 Canal Handler 中保持一致)
//
//    private final RedisTemplate<Object,Object> redisTemplate;
//
//    @Autowired
//    public RedisCompensationConsumer(RedisTemplate<Object, Object> redisTemplate) {
//        this.redisTemplate = redisTemplate;
//    }
//
//    // 【可选】引入 Spring Retry 或自行实现重试机制
//
//    // 监听步骤 3 中定义的 topic
//    @KafkaListener(topics = "canal_redis_compensation", groupId = "redis-compensation-group")
//    public void listen(SyncFailDTO message) {
//        String key = message.getKey();
//        String opType = message.getOperationType();
//
//        log.warn("[补偿开始] 尝试重新同步 key: {}，操作: {}", key, opType);
//
//        try {
//            // 核心逻辑：再次尝试 Redis 操作
//            if ("DELETE".equals(opType)) {
//                redisTemplate.delete(key);
//            } else { // INSERT 或 UPDATE
//                // 这里的 data 需要进行类型转换，Spring Kafka 默认是 Map，需要反序列化回 User
//                // 简化：如果之前发送的就是 User 对象（依赖正确的 JsonSerializer/Deserializer 配置）
//                redisTemplate.opsForValue().set(key, message.getData());
//            }
//
//            log.info("[补偿成功] key: {}", key);
//
//        } catch (Exception e) {
//            // 【关键】：如果补偿失败，则需要延迟或重复消费
//            // 1. Kafka 默认重试：如果抛出异常，Kafka 可能会根据配置自动重试几次。
//            // 2. 延迟重试：更高级的方案是，将此消息发送到一个**延迟队列**或**真正的死信队列（DLQ）**，等待更长时间再重试，防止对宕机的Redis造成持续压力。
//            log.error("[补偿失败] key: {}，将等待 Kafka 重试或进入下一步 DLQ 流程。", key, e);
//
//            // 抛出异常，让 Kafka 消费者机制介入重试 (或配置手动 Ack/Nack)
//            throw new RuntimeException("Redis compensation failed, waiting for Kafka retry.", e);
//        }
//    }
//}