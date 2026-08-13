# Video full-text search

The video search index is `video_search_v2`. It is separate from the legacy `video` index so the
new mappings can be introduced without mutating existing Elasticsearch field definitions.

## Indexed fields and relevance

The comprehensive sort uses these field boosts:

- title: `6`
- tags: `4`
- uploader nickname: `3`
- subcategory name: `2.5`
- main category name: `2`
- description: `1`

All six text fields use `ik_max_word` while indexing and `ik_smart` while searching. Elasticsearch
returns HTML-encoded highlight fragments. The API additionally supports newest, most-played, and
most-liked sorting plus main/subcategory filters.

## Windows IK setup

Stop Elasticsearch, open PowerShell, and run:

```powershell
.\scripts\setup-search.ps1 -ElasticsearchHome "C:\tools\elasticsearch-7.17.16"
```

The script validates the directory, avoids reinstalling an existing plugin, and installs the IK
version matching Elasticsearch 7.17.16. Restart Elasticsearch afterward. The official IK project
requires the plugin version to match the Elasticsearch version exactly.

## Synchronization and recovery

Video creates, review-state changes, deletions, statistics changes, and uploader nickname changes
enqueue index operations. The scheduler coalesces repeated updates for three seconds and writes each
document with its video ID, making retries idempotent. Failed writes remain queued for another pass.
At application startup, all non-deleted MySQL videos are queued again to repair missed writes.

An administrator can explicitly recreate and repopulate the index:

```text
POST /admin/search/index/rebuild
```

## Search API

```text
GET /search/videos?keyword=Java&page=1&size=30&sort=relevance&mcId=tech&scId=computer_tech
```

Valid sort values are `relevance`, `latest`, `play`, and `likes`. The legacy
`/search/video/only-pass` endpoint and response shape remain available.
