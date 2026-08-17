-- Local resumable upload and persistent FFmpeg/HLS pipeline.
-- Apply once to an existing teriteri database before starting the upgraded backend.

ALTER TABLE `video`
  ADD COLUMN `asset_id` bigint DEFAULT NULL COMMENT '本地媒体资产ID' AFTER `video_url`,
  ADD KEY `idx_video_asset` (`asset_id`);

CREATE TABLE `media_asset` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_hash` varchar(64) NOT NULL,
  `file_size` bigint NOT NULL,
  `extension` varchar(8) NOT NULL,
  `original_path` varchar(1000) DEFAULT NULL,
  `hls_path` varchar(1000) DEFAULT NULL,
  `status` varchar(24) NOT NULL,
  `ref_count` int NOT NULL DEFAULT 0,
  `width` int DEFAULT NULL,
  `height` int DEFAULT NULL,
  `duration` double DEFAULT NULL,
  `fps` double DEFAULT NULL,
  `has_audio` tinyint NOT NULL DEFAULT 0,
  `hdr` tinyint NOT NULL DEFAULT 0,
  `cover_candidates` varchar(3000) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `delete_after` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_media_source` (`source_hash`,`file_size`),
  KEY `idx_media_cleanup` (`ref_count`,`delete_after`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `video_upload_session` (
  `id` varchar(32) NOT NULL,
  `uid` int NOT NULL,
  `file_hash` varchar(64) NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `extension` varchar(8) NOT NULL,
  `total_size` bigint NOT NULL,
  `total_chunks` int NOT NULL,
  `uploaded_bytes` bigint NOT NULL DEFAULT 0,
  `status` varchar(24) NOT NULL,
  `asset_id` bigint DEFAULT NULL,
  `video_id` int DEFAULT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `expires_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_upload_user_status` (`uid`,`status`,`updated_at`),
  KEY `idx_upload_hash` (`uid`,`file_hash`,`total_size`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `video_upload_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(32) NOT NULL,
  `chunk_index` int NOT NULL,
  `chunk_hash` varchar(64) NOT NULL,
  `chunk_size` bigint NOT NULL,
  `storage_path` varchar(1000) NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_upload_chunk` (`session_id`,`chunk_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `video_transcode_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `asset_id` bigint NOT NULL,
  `status` varchar(24) NOT NULL,
  `progress` int NOT NULL DEFAULT 0,
  `attempts` int NOT NULL DEFAULT 0,
  `encoder` varchar(20) NOT NULL DEFAULT 'cpu',
  `error_message` varchar(2000) DEFAULT NULL,
  `next_attempt_at` datetime DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transcode_asset` (`asset_id`),
  KEY `idx_transcode_queue` (`status`,`next_attempt_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
