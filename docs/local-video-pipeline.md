# Local resumable video pipeline

1. Apply `database/local_video_pipeline.sql` to an existing database. New databases can use `database/teriteri.sql` directly.
2. Run `powershell -ExecutionPolicy Bypass -File scripts/setup-ffmpeg.ps1`. The script downloads the pinned Windows essentials build and verifies its SHA-256 sidecar.
3. Configure `media.root`, `media.ffmpeg`, `media.ffprobe`, and optionally `media.encoder=nvidia`. Keep the media root outside the repository in production.
4. Start the backend and check the FFmpeg/FFprobe health-check log.

The upload API uses 5 MB chunks, per-chunk MD5, three client workers, resumable sessions and whole-file MD5 deduplication. Only one active upload is allowed per user. Uploaded MP4/MKV files are probed, limited to 2 GB and four hours, then queued persistently for a single global FFmpeg worker.

Output is H.264/AAC HLS with six-second segments and non-upscaled 360P/480P/720P/1080P variants. Source frame rate is preserved up to 60 FPS, HDR input is tone-mapped to SDR, and files without audio stay without audio. The first version uses the default audio track and ignores embedded subtitles.

Published playlists are public. Pending or rejected media requires an owner/admin JWT supplied through `access_token`; playlist responses propagate that token to variant playlists and segments. Shared assets are reference-counted and physically removed 24 hours after the last logical deletion by the daily cleanup job.
