# Kafka 流量削峰与业务日志

该链路把高频播放计数从请求线程移出，通过 Kafka 分区消费和 MySQL 入站表批量聚合；点赞、评论和播放入口使用 Guava 单机令牌桶，过载时返回 HTTP 429。业务日志同样异步写入 Kafka，只记录动作、路径、响应状态、耗时、匿名身份摘要和 Trace ID，不记录 Token 或请求正文。

## Windows 启用步骤

1. 在 MySQL 的 `teriteri` 库执行 `database/traffic_kafka.sql`。
2. 启动 Kafka，然后创建主题：

   ```powershell
   .\scripts\create-traffic-topics.ps1 -KafkaHome "C:\kafka"
   ```

3. 将 `config/application-traffic.example.yaml` 中的配置合并到本地 `application.yml`，确认 `spring.kafka.bootstrap-servers` 指向实际 Kafka 地址。
4. 将 `traffic.kafka.enabled` 改为 `true` 后重启后端。
5. 管理端进入“系统管理 → Kafka 流量治理”观察积压、失败事件、业务日志与 DLT。

Kafka 默认关闭。关闭时播放量沿用同步数据库更新，项目不依赖 Kafka 也能启动；异步业务日志暂停。启用 Kafka 前必须先执行迁移，否则消费者无法保存入站事件。

## 一致性与失败处理

- 用户和游客使用稳定身份，按 30 分钟窗口生成确定性事件 ID；Redis `SET NX` 快速去重，Redis 故障时使用进程内缓存兜底。
- Kafka 生产失败时，播放事件写入同一 MySQL 入站表；消费者重复投递由 `event_id` 唯一索引幂等处理。
- 聚合器默认每 2 秒读取最多 5000 条事件，按视频合并，在同一数据库事务中增加播放量并标记事件完成。
- 聚合失败使用指数退避，达到 5 次后标记 `DEAD`；管理员可单条或批量重试。
- Kafka 消费连续失败会发送到原主题的 `.DLT`，DLT 落库后可从管理端重放。

## 限流参数

默认全局配额为播放 1000 QPS、点赞 500 QPS、评论 200 QPS；单身份配额分别为 5、10、2 QPS。所有值均可在 `traffic.rate-limit` 下调整。限流只代表单个后端实例，部署多实例时总容量约等于实例数乘以单机配额。

若要验证“3000 次/秒突发、核心服务稳定在 1000 QPS”的目标，应在目标机器与真实数据库/Kafka 配置下运行压测，并以管理端拒绝计数、Kafka 消费延迟、数据库写入耗时和接口 P95/P99 为准；代码中的 1000 是限流配置，不是脱离环境的性能证明。
