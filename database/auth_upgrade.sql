-- Authentication upgrade for an existing database.
-- Run once after backing up the user table.

ALTER TABLE `user`
  ADD COLUMN `github_id` varchar(64) DEFAULT NULL COMMENT 'GitHub user id' AFTER `delete_date`,
  ADD COLUMN `github_login` varchar(255) DEFAULT NULL COMMENT 'GitHub login name' AFTER `github_id`,
  ADD COLUMN `password_initialized` tinyint(1) NOT NULL DEFAULT '1'
    COMMENT 'Whether a local password is configured' AFTER `github_login`,
  ADD UNIQUE KEY `uk_user_github_id` (`github_id`);
