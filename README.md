<div align=center>
<img src="https://tinypic.host/images/2024/12/06/logo.png" width="180" height="180" />
</div>

<div align=center>
<img src="https://tinypic.host/images/2024/12/06/teriteri-pink.png" height="46" />
</div>

<div align=center>
<img src="https://img.shields.io/badge/SpringBoot-2.7.15-6DB33F" />
<img src="https://img.shields.io/badge/Java-8%2B-ED8B00" />
<img src="https://img.shields.io/badge/MyBatis--Plus-3.5.x-4479A1" />
<img src="https://img.shields.io/badge/Elasticsearch-7.17.16-005571" />
<img src="https://img.shields.io/badge/Kafka-3.x-231F20" />
<img src="https://img.shields.io/badge/Redis-7.x-DC382D" />
</div>

# 芙影视界平台后端

芙影视界是一个面向视频分享与互动交流场景的前后端分离平台，提供视频投稿、审核、播放、弹幕、评论、私信、搜索和播放统计等能力。本仓库是平台后端服务，配套客户端和管理端分别位于：

- [客户端](https://github.com/cixingji/teriteri-client)
- [管理端](https://github.com/cixingji/teriteri-admin)

## 项目特点

- Spring Security + JWT 无状态认证，区分普通用户和管理员身份。
- MyBatis-Plus + MySQL 持久化核心业务数据，Redis 支撑缓存、集合和限流状态。
- Elasticsearch 实现视频多字段全文搜索，支持中文分词、权重排序和高亮。
- Netty WebSocket 支持私信、弹幕等实时通信场景。
- 视频上传支持断点续传、分片校验、秒传检查和单用户同时一个上传任务。
- FFmpeg 异步完成媒体探测、转码和 HLS 切片，媒体文件默认使用本地目录保存。
- Kafka 承接播放统计和业务日志，配合限流、重试和死信队列降低高频写入压力。
- Canal + Kafka + Elasticsearch 提供 MySQL 变更到搜索索引的异步同步链路。
- 提供 Windows + Docker Desktop 的隔离压测环境和 k6 压测入口。

## 技术栈

| 类型 | 技术 |
| --- | --- |
| 服务端 | Spring Boot、Spring Security、JWT、MyBatis-Plus |
| 数据存储 | MySQL、Redis、Elasticsearch |
| 消息与实时通信 | Kafka、Netty WebSocket |
| 媒体处理 | FFmpeg、HLS、本地文件存储 |
| 构建环境 | Maven、JDK 8+ |

## 主要功能

- 用户注册、登录、JWT 会话和 GitHub 登录扩展
- 普通用户与管理员登录
- 视频投稿、审核、删除和状态管理
- MP4、MKV 视频分片上传与异步处理
- 视频播放、点赞、收藏、投币、评论、弹幕
- 视频多字段搜索、分区筛选、排序和高亮
- 私信、最近聊天列表和 WebSocket 实时消息
- 播放统计、异步聚合、幂等处理和失败重试
- MySQL、Kafka、Elasticsearch 数据同步

## 文档导航

- [认证与登录](AUTHENTICATION.md)
- [实时通信](REALTIME_IM.md)
- [搜索设计](SEARCH.md)
- [本地视频处理链路](docs/local-video-pipeline.md)
- [Kafka 流量治理](docs/kafka-traffic.md)
- [Canal 搜索同步](docs/canal-search-sync.md)
- [视频总结设计说明](VIDEO_SUMMARY.md)（当前默认关闭）
- [隔离压测环境](performance/README.md)

## 本地运行

### 依赖

- JDK 8 或更高版本
- Maven 3.8+
- MySQL 8.x
- Redis 6+
- Elasticsearch 7.17.16，并安装与版本匹配的 IK 分词插件
- FFmpeg 与 FFprobe
- Kafka（启用流量治理时需要）

### 数据库

1. 创建数据库后执行 `database/teriteri.sql`。
2. 启用播放统计和业务日志时，再执行 `database/traffic_kafka.sql`。
3. 需要视频总结表时执行 `database/video_summary_task.sql`；视频总结默认关闭。
4. 不要把真实密码、JWT 密钥、OSS 密钥或第三方 OAuth 密钥提交到 GitHub。

### 配置

复制并填写 `src/main/resources/application.yml` 中的本地配置，重点配置：

- MySQL、Redis、Elasticsearch 连接
- JWT 密钥
- 媒体根目录、FFmpeg 和 FFprobe 路径
- Kafka 地址与开关
- GitHub OAuth（如需启用）

### 启动

```bash
mvn spring-boot:run
```

默认 HTTP 端口以配置文件为准，Netty 实时通信端口也以对应配置为准。

## Windows 压测环境

Docker Desktop 启动后执行：

```powershell
.\performance\scripts\setup-k6.ps1
.\performance\scripts\start-environment.ps1
```

该环境使用独立端口和 Docker 数据卷，不连接本机默认 MySQL、Redis 或 Elasticsearch。详细说明见 [performance/README.md](performance/README.md)。

## 目录结构

```text
src/main/java       后端业务代码
src/main/resources  配置与资源
database/            数据库脚本
docs/                功能设计与运维文档
performance/         隔离压测环境与脚本
```

## 项目声明

本项目由 `cixingji` 维护，主要用于学习、工程实践和技术交流。使用第三方图片、字体、视频或其他资源时，请遵守相应的许可和版权要求。
