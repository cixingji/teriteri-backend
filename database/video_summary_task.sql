CREATE TABLE IF NOT EXISTS `video_summary_task` (
  `task_id` varchar(36) NOT NULL COMMENT 'Summary task ID',
  `vid` int(11) NOT NULL COMMENT 'Video ID',
  `summary_version` varchar(32) NOT NULL COMMENT 'Prompt and algorithm version',
  `status` varchar(24) NOT NULL COMMENT 'PENDING/EXTRACTING/TRANSCRIBING/SUMMARIZING/SUCCESS/FAILED',
  `asr_order_id` varchar(128) DEFAULT NULL COMMENT 'XFYun transcription order ID',
  `transcript` longtext DEFAULT NULL COMMENT 'Timestamped ASR transcript',
  `summary_json` longtext DEFAULT NULL COMMENT 'Structured video summary JSON',
  `error_message` varchar(1000) DEFAULT NULL COMMENT 'Last processing error',
  `retry_count` int(11) NOT NULL DEFAULT 0 COMMENT 'Retry count for current stage',
  `next_attempt_at` datetime DEFAULT NULL COMMENT 'Next scheduled attempt',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `version` int(11) NOT NULL DEFAULT 0 COMMENT 'Optimistic concurrency version',
  PRIMARY KEY (`task_id`),
  UNIQUE KEY `uk_video_summary_version` (`vid`, `summary_version`),
  KEY `idx_video_summary_dispatch` (`status`, `next_attempt_at`),
  KEY `idx_video_summary_asr_order` (`asr_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI video summary task';
