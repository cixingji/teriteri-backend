# Canal search-index synchronization

## Data flow

`MySQL ROW binlog -> Canal Server -> backend Canal Client -> Kafka -> MySQL idempotent event inbox -> Elasticsearch`

Canal batches are acknowledged only after every derived event is durably accepted by Kafka. Event IDs combine the binlog file, offset, table, event type and row key. The Kafka consumer inserts them into `search_sync_event`, whose unique key makes redelivery idempotent. Canal Server retains its acknowledged cursor; `canal_sync_checkpoint` mirrors the last file and position for operations visibility.

The inbox processor waits one second and coalesces changes by video, user or category. It retries failures five times with exponential backoff, then marks the event `DEAD` and publishes it to `video-index-sync-dlt`. Administrators can replay one or all dead events.

Only `video.status = 1` documents exist in the search index. Changes to `video`, `video_stats`, user nicknames and categories rebuild the affected document from current MySQL state, so out-of-order low-level row events converge on the latest state.

## Windows setup

1. Enable MySQL binlog with `binlog-format=ROW`, create a Canal account with `SELECT`, `REPLICATION SLAVE` and `REPLICATION CLIENT`, then restart MySQL.
2. Apply `database/canal_search_sync.sql`.
3. Run `scripts/setup-canal.ps1` with the MySQL address, database and Canal credentials.
4. Run `scripts/create-sync-topics.ps1 -KafkaHome <path>` against a running Kafka installation.
5. Copy the values from `sync.properties.example` into the runtime configuration. Keep `search.index.direct-write-enabled=true` during initial verification; set it to `false` after the Canal pipeline is healthy.

The management page exposes the checkpoint, inbox counts, dead-letter replay, rebuild progress and daily 02:40 consistency repair. The consistency job compares published MySQL rows with Elasticsearch documents and repairs missing, stale and extra records.
