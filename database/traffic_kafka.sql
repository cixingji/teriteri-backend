-- Kafka playback aggregation, business logs and dead-letter operations.
CREATE TABLE IF NOT EXISTS `traffic_play_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) NOT NULL,
  `vid` int NOT NULL,
  `actor_type` varchar(20) NOT NULL,
  `actor_key` varchar(64) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `attempts` int NOT NULL DEFAULT 0,
  `next_retry_at` datetime DEFAULT NULL,
  `error_message` varchar(2000) DEFAULT NULL,
  `occurred_at` datetime NOT NULL,
  `created_at` datetime NOT NULL,
  `processed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_traffic_play_event_id` (`event_id`),
  KEY `idx_traffic_play_pending` (`status`,`next_retry_at`,`id`),
  KEY `idx_traffic_play_video` (`vid`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `traffic_business_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) NOT NULL,
  `trace_id` varchar(64) NOT NULL,
  `action` varchar(40) NOT NULL,
  `method` varchar(10) NOT NULL,
  `path` varchar(255) NOT NULL,
  `response_status` int NOT NULL,
  `latency_ms` bigint NOT NULL,
  `actor_key` varchar(80) NOT NULL,
  `occurred_at` datetime NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_traffic_business_event_id` (`event_id`),
  KEY `idx_traffic_business_action` (`action`,`created_at`),
  KEY `idx_traffic_business_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `traffic_dead_letter` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_topic` varchar(255) NOT NULL,
  `source_partition` int NOT NULL,
  `source_offset` bigint NOT NULL,
  `message_key` varchar(255) DEFAULT NULL,
  `payload` longtext NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'OPEN',
  `error_message` varchar(2000) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `replayed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_traffic_dead_source` (`source_topic`,`source_partition`,`source_offset`),
  KEY `idx_traffic_dead_status` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
