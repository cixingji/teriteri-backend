-- Reliable one-to-one IM upgrade for an existing teriteri database.
-- Apply once before starting the upgraded backend.

ALTER TABLE `chat_detailed`
  ADD COLUMN `client_message_id` varchar(64) DEFAULT NULL COMMENT '客户端消息幂等ID' AFTER `time`,
  ADD COLUMN `delivered_at` datetime DEFAULT NULL COMMENT '送达时间' AFTER `client_message_id`,
  ADD COLUMN `read_at` datetime DEFAULT NULL COMMENT '已读时间' AFTER `delivered_at`;

-- Existing history is not an offline backlog. Mark it as delivered and read.
UPDATE `chat_detailed`
SET `delivered_at` = `time`, `read_at` = `time`
WHERE `delivered_at` IS NULL;

ALTER TABLE `chat_detailed`
  ADD UNIQUE KEY `uk_chat_sender_client_message` (`user_id`, `client_message_id`),
  ADD KEY `idx_chat_receiver_delivery` (`another_id`, `delivered_at`, `id`),
  ADD KEY `idx_chat_receiver_read` (`another_id`, `user_id`, `read_at`, `id`);
