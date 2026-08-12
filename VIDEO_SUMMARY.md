# AI video summary

This module implements the following asynchronous pipeline:

```text
video URL -> FFmpeg MP3 extraction -> XFYun long-form ASR
          -> timestamped transcript -> Spark hierarchical summary -> JSON result
```

## Setup

1. Run [`database/video_summary_task.sql`](database/video_summary_task.sql) against the project MySQL database.
2. Keep the bundled 64-bit FFmpeg from `jave-all-deps`, or configure a different absolute executable path.
3. Configure XFYun recording transcription credentials and the Spark HTTP API password.
4. Enable the feature only after the database and credentials are ready.

The minimum environment variables are:

```text
AI_VIDEO_SUMMARY_ENABLED=true
AI_VIDEO_SUMMARY_XFYUN_APP_ID=your-app-id
AI_VIDEO_SUMMARY_XFYUN_SECRET_KEY=your-asr-secret-key
AI_VIDEO_SUMMARY_SPARK_API_PASSWORD=your-spark-api-password
```

See [`video-summary.properties.example`](video-summary.properties.example) for every option. Do not commit real credentials.

`AI_VIDEO_SUMMARY_FFMPEG_PATH` is optional. When it is empty, the extractor uses the FFmpeg binary bundled by the existing `jave-all-deps` dependency.

The backend passes `video.video_url` directly to FFmpeg. That URL must therefore be reachable from the backend process. If OSS objects become private, generate a short-lived signed read URL before extraction.

## Callback and polling

Polling is always available and does not hold a worker thread while XFYun processes the audio. To also enable callbacks, configure both:

```text
AI_VIDEO_SUMMARY_CALLBACK_BASE_URL=https://api.example.com
AI_VIDEO_SUMMARY_CALLBACK_TOKEN=a-long-random-secret
```

The submitted callback becomes:

```text
https://api.example.com/video/summary/callback/xfyun?token=...
```

The callback endpoint is public because it is called by XFYun, but it rejects requests unless the configured token matches.

## API

All user-facing endpoints use the existing JWT authentication mechanism.

```text
POST /video/summary?vid={videoId}
GET  /video/summary/task?taskId={taskId}
GET  /video/summary/latest?vid={videoId}
```

The task states are:

```text
PENDING -> EXTRACTING -> TRANSCRIBING -> SUMMARIZING -> SUCCESS
                                                        -> FAILED
```

Submitting the same video and summary version is idempotent. Failed tasks are reset and retried when the user submits again. Increment `ai.video-summary.version` after materially changing the prompt or summary algorithm.

## Verification

Run the focused unit tests without external credentials:

```powershell
.\mvnw.cmd '-Dtest=XfyunSignatureTest,XfyunTranscriptParserTest,TranscriptChunkerTest,SparkVideoSummarizerTest' test
```

The tests verify the official XFYun signature example, timestamped transcript parsing, lossless text chunking, and hierarchical summary orchestration. A real end-to-end request additionally requires valid XFYun credentials, FFmpeg, MySQL, and an accessible video URL.
