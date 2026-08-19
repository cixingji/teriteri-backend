-- Durable Canal -> Kafka -> Elasticsearch synchronization state.
CREATE TABLE IF NOT EXISTS `canal_sync_checkpoint` (
  `destination` varchar(100) NOT NULL, `batch_id` bigint DEFAULT NULL,
  `binlog_file` varchar(255) DEFAULT NULL, `binlog_position` bigint DEFAULT NULL,
  `updated_at` datetime NOT NULL, PRIMARY KEY (`destination`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `search_sync_event` (
  `id` bigint NOT NULL AUTO_INCREMENT, `event_id` varchar(64) NOT NULL,
  `source_table` varchar(64) NOT NULL, `event_type` varchar(20) NOT NULL,
  `aggregate_type` varchar(20) NOT NULL, `aggregate_key` varchar(255) NOT NULL,
  `binlog_file` varchar(255) DEFAULT NULL, `binlog_position` bigint DEFAULT NULL,
  `execute_time` bigint DEFAULT NULL, `payload` longtext NOT NULL,
  `status` varchar(20) NOT NULL, `attempts` int NOT NULL DEFAULT 0,
  `next_retry_at` datetime DEFAULT NULL, `error_message` varchar(2000) DEFAULT NULL,
  `created_at` datetime NOT NULL, `updated_at` datetime NOT NULL, `processed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_search_sync_event` (`event_id`),
  KEY `idx_search_sync_pending` (`status`,`next_retry_at`,`updated_at`),
  KEY `idx_search_sync_aggregate` (`aggregate_type`,`aggregate_key`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `search_index_job` (
  `id` bigint NOT NULL AUTO_INCREMENT, `job_type` varchar(30) NOT NULL, `status` varchar(20) NOT NULL,
  `total` int NOT NULL DEFAULT 0, `processed` int NOT NULL DEFAULT 0,
  `missing_count` int NOT NULL DEFAULT 0, `stale_count` int NOT NULL DEFAULT 0,
  `extra_count` int NOT NULL DEFAULT 0, `repaired_count` int NOT NULL DEFAULT 0,
  `error_message` varchar(2000) DEFAULT NULL, `created_at` datetime NOT NULL,
  `started_at` datetime DEFAULT NULL, `finished_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`), KEY `idx_search_job_status` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
