# Harvester of Sorrow — REST API Reference

> Base URL: `http://localhost:8080`  
> All endpoints are prefixed with `/api`  
> No authentication is currently required.

---

## Table of Contents

- [Search](#search)
- [Downloads](#downloads)
- [Progress Streaming (SSE)](#progress-streaming-sse)
- [Library & Monitoring](#library--monitoring)
- [Actuator / Health](#actuator--health)
- [Schemas](#schemas)
- [Enums](#enums)
- [Environment Variables](#environment-variables)

---

## Search

### `GET /api/search`

Search across all registered sources (VixSrc and/or RaiPlay) in parallel.

**Query Parameters**

| Name     | Type   | Required | Default | Description                                        |
|----------|--------|----------|---------|----------------------------------------------------|
| `query`  | string | yes      | —       | Free-text search term                              |
| `type`   | string | no       | `BOTH`  | Content type filter: `MOVIES`, `TV`, `BOTH`        |
| `source` | string | no       | —       | Limit to a single source: `VIXSRC`, `RAIPLAY`      |

**Response** `200 OK` — `ContentMetadata[]`

```json
[
  {
    "source": "VIXSRC",
    "sourceMetadata": { "tmdbId": 550 },
    "tmdbId": 550,
    "title": "Fight Club",
    "originalTitle": "Fight Club",
    "year": 1999,
    "overview": "...",
    "voteAverage": 8.4,
    "season": null,
    "episode": null,
    "episodeName": null,
    "numberOfSeasons": null,
    "totalEpisodes": null,
    "episodesPerSeason": null
  }
]
```

---

### `GET /api/search/movies`

Search movies via VixSrc / TMDB.

**Query Parameters**

| Name    | Type   | Required | Description          |
|---------|--------|----------|----------------------|
| `query` | string | yes      | Movie title to search |

**Response** `200 OK` — `ContentMetadata[]`

---

### `GET /api/search/tv`

Search TV shows via VixSrc / TMDB.

**Query Parameters**

| Name    | Type   | Required | Description            |
|---------|--------|----------|------------------------|
| `query` | string | yes      | TV show title to search |

**Response** `200 OK` — `ContentMetadata[]`

---

## Downloads

### `GET /api/downloads`

Return all download tasks.

**Response** `200 OK` — `DownloadTask[]`

```json
[
  {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "source": "VIXSRC",
    "contentType": "MOVIE",
    "tmdbId": 550,
    "title": "Fight Club",
    "season": null,
    "episode": null,
    "episodeName": null,
    "languages": ["en", "it"],
    "quality": "1080",
    "status": "DOWNLOADING",
    "progress": 42.5,
    "bitrate": "5000k",
    "downloadedBytes": 524288000,
    "totalBytes": 1258291200,
    "downloadSpeed": "5.2 MB/s",
    "etaSeconds": 138,
    "errorMessage": null,
    "createdAt": "2024-01-15T10:30:00",
    "startedAt": "2024-01-15T10:30:05",
    "completedAt": null,
    "subTasks": []
  }
]
```

---

### `GET /api/downloads/{id}`

Get a single download task by ID.

**Path Parameters**

| Name | Type   | Required | Description          |
|------|--------|----------|----------------------|
| `id` | string | yes      | Download task UUID   |

**Response**

| Status | Description              |
|--------|--------------------------|
| `200`  | `DownloadTask` object    |
| `404`  | Task not found           |

---

### `POST /api/download/movie`

Queue a VixSrc movie download.

**Query Parameters**

| Name        | Type     | Required | Description                              |
|-------------|----------|----------|------------------------------------------|
| `tmdbId`    | integer  | yes      | TMDB ID of the movie                     |
| `languages` | string[] | no       | Audio languages, e.g. `en,it,fr`        |
| `quality`   | string   | no       | Video quality: `best`, `1080`, `720`, … |

**Response** `200 OK` — `DownloadTask`

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "source": "VIXSRC",
  "contentType": "MOVIE",
  "tmdbId": 550,
  "title": "Fight Club",
  "status": "QUEUED",
  "progress": 0.0,
  "createdAt": "2024-01-15T10:30:00",
  "subTasks": []
}
```

---

### `POST /api/download/tv`

Queue a VixSrc TV episode download.

**Query Parameters**

| Name        | Type     | Required | Description                               |
|-------------|----------|----------|-------------------------------------------|
| `tmdbId`    | integer  | yes      | TMDB ID of the TV show                    |
| `season`    | integer  | yes      | Season number                             |
| `episode`   | integer  | yes      | Episode number                            |
| `languages` | string[] | no       | Audio languages                           |
| `quality`   | string   | no       | Video quality: `best`, `1080`, `720`, … |

**Response** `200 OK` — `DownloadTask`

---

### `POST /api/download/raiplay/movie`

Queue a RaiPlay movie download.

**Query Parameters**

| Name     | Type    | Required | Description                                                                  |
|----------|---------|----------|------------------------------------------------------------------------------|
| `pathId` | string  | yes      | RaiPlay content path, e.g. `/video/2018/12/COSMONAUTA-f5cbe4fd-....json`     |
| `title`  | string  | yes      | Display title                                                                |
| `year`   | integer | no       | Release year                                                                 |

**Response** `200 OK` — `DownloadTask`

---

### `POST /api/download/raiplay/tv`

Queue a RaiPlay TV episode download.

**Query Parameters**

| Name          | Type    | Required | Description             |
|---------------|---------|----------|-------------------------|
| `pathId`      | string  | yes      | RaiPlay content path    |
| `title`       | string  | yes      | Show title              |
| `season`      | integer | yes      | Season number           |
| `episode`     | integer | yes      | Episode number          |
| `episodeName` | string  | no       | Episode name            |

**Response** `200 OK` — `DownloadTask`

---

### `DELETE /api/downloads/{id}`

Cancel a download task.

**Path Parameters**

| Name | Type   | Required | Description        |
|------|--------|----------|--------------------|
| `id` | string | yes      | Download task UUID |

**Response**

| Status | Description    |
|--------|----------------|
| `200`  | Cancelled OK   |
| `404`  | Task not found |

---

### `POST /api/downloads/{id}/retry`

Re-queue a failed or cancelled download task.

**Path Parameters**

| Name | Type   | Required | Description        |
|------|--------|----------|--------------------|
| `id` | string | yes      | Download task UUID |

**Response**

| Status | Description    |
|--------|----------------|
| `200`  | Queued again   |
| `404`  | Task not found |

---

## Progress Streaming (SSE)

### `GET /api/progress/stream`

Subscribe to real-time download progress updates via **Server-Sent Events**.

Keep the connection open; the server will push an event each time task progress changes.

**Response** `200 OK` — `text/event-stream`

Each event data payload:

```json
{
  "taskId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "subTaskId": null,
  "status": "DOWNLOADING",
  "progress": 42.5,
  "bitrate": "5000k",
  "downloadedBytes": 524288000,
  "totalBytes": 1258291200,
  "downloadSpeed": "5.2 MB/s",
  "etaSeconds": 138,
  "message": null,
  "errorMessage": null,
  "timestamp": "2024-01-15T10:31:23"
}
```

**React usage example**

```ts
const es = new EventSource('/api/progress/stream');
es.onmessage = (e) => {
  const update = JSON.parse(e.data);
  // update your state here
};
```

---

## Library & Monitoring

The library represents TV show directories found on disk. Each entry can optionally be linked to a *monitored show* — when monitoring is active, the app periodically checks for new episodes and auto-queues them.

### `GET /api/library`

Scan the TV shows directory and return all entries (monitored and unmonitored) with episode counts.

**Response** `200 OK` — `LibraryEntry[]`

```json
[
  {
    "directoryName": "Breaking.Bad.2008",
    "seasonCount": 5,
    "episodeCount": 62,
    "monitored": true,
    "monitoredShow": { /* MonitoredShow object, or null */ }
  }
]
```

---

### `GET /api/library/monitored`

Return all monitored shows.

**Response** `200 OK` — `MonitoredShow[]`

```json
[
  {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "title": "Breaking Bad",
    "year": 2008,
    "tmdbId": 1396,
    "source": "VIXSRC",
    "sourceMetadata": { "tmdbId": 1396 },
    "directoryName": "Breaking.Bad.2008",
    "enabled": true,
    "lastCheckedAt": "2024-01-15T10:00:00",
    "lastNewEpisodeAt": null,
    "createdAt": "2024-01-10T08:30:00"
  }
]
```

---

### `GET /api/library/monitored/{id}`

Get a single monitored show.

**Path Parameters**

| Name | Type   | Required | Description            |
|------|--------|----------|------------------------|
| `id` | string | yes      | Monitored show UUID    |

**Response**

| Status | Description            |
|--------|------------------------|
| `200`  | `MonitoredShow` object |
| `404`  | Not found              |

---

### `POST /api/library/monitored`

Add a new monitored show. The `directoryName` must match an existing folder under `DOWNLOAD_TV_SHOWS_PATH`.

**Request Body** `application/json`

```json
{
  "title": "Breaking Bad",
  "year": 2008,
  "tmdbId": 1396,
  "source": "VIXSRC",
  "sourceMetadata": { "tmdbId": 1396 },
  "directoryName": "Breaking.Bad.2008"
}
```

| Field           | Type          | Required | Description                              |
|-----------------|---------------|----------|------------------------------------------|
| `title`         | string        | yes      | Show title                               |
| `year`          | integer       | no       | Release year                             |
| `tmdbId`        | integer       | no       | TMDB identifier                          |
| `source`        | `MediaSource` | yes      | `VIXSRC` or `RAIPLAY`                   |
| `sourceMetadata`| object        | no       | Source-specific metadata                 |
| `directoryName` | string        | yes      | Folder name under the TV shows directory |

**Response** `200 OK` — `MonitoredShow`

---

### `PUT /api/library/monitored/{id}`

Update the source / title configuration of an existing monitored show.

**Path Parameters**

| Name | Type   | Required | Description         |
|------|--------|----------|---------------------|
| `id` | string | yes      | Monitored show UUID |

**Request Body** `application/json`

```json
{
  "title": "Breaking Bad",
  "year": 2008,
  "tmdbId": 1396,
  "source": "RAIPLAY",
  "sourceMetadata": { "pathId": "/programmi/breaking-bad.json" }
}
```

**Response**

| Status | Description    |
|--------|----------------|
| `200`  | Updated        |
| `404`  | Not found      |

---

### `DELETE /api/library/monitored/{id}`

Remove a monitored show. Already-downloaded files are kept on disk.

**Path Parameters**

| Name | Type   | Required | Description         |
|------|--------|----------|---------------------|
| `id` | string | yes      | Monitored show UUID |

**Response**

| Status | Description    |
|--------|----------------|
| `204`  | Removed        |
| `404`  | Not found      |

---

### `POST /api/library/monitored/{id}/enable`

Resume automatic monitoring for a paused show.

**Response**

| Status | Description    |
|--------|----------------|
| `200`  | Enabled        |
| `404`  | Not found      |

---

### `POST /api/library/monitored/{id}/disable`

Pause automatic monitoring without removing the show.

**Response**

| Status | Description    |
|--------|----------------|
| `200`  | Disabled       |
| `404`  | Not found      |

---

### `POST /api/library/monitored/{id}/check`

Trigger an immediate episode check for a monitored show. New episodes found are enqueued for download automatically.

**Response** `200 OK`

```json
{ "newEpisodesEnqueued": 3 }
```

| Status | Description    |
|--------|----------------|
| `200`  | Check complete |
| `404`  | Not found      |

---

## Actuator / Health

Standard Spring Boot Actuator endpoints (read-only):

| Endpoint              | Description              |
|-----------------------|--------------------------|
| `GET /actuator/health` | Application health status |
| `GET /actuator/info`   | Build / version info      |
| `GET /actuator/metrics`| JVM & app metrics         |

---

## Schemas

### `LibraryEntry`

| Field           | Type              | Notes                                        |
|-----------------|-------------------|----------------------------------------------|
| `directoryName` | string            | Folder name under the TV shows directory     |
| `seasonCount`   | integer           | Number of season sub-folders found on disk   |
| `episodeCount`  | integer           | Total `.mkv` files across all seasons        |
| `monitored`     | boolean           | Whether this entry has a linked MonitoredShow|
| `monitoredShow` | `MonitoredShow` \| null | Present when `monitored` is `true`    |

---

### `MonitoredShow`

| Field              | Type          | Notes                                        |
|--------------------|---------------|----------------------------------------------|
| `id`               | string (UUID) |                                              |
| `title`            | string        |                                              |
| `year`             | integer \| null |                                            |
| `tmdbId`           | integer \| null |                                            |
| `source`           | `MediaSource` |                                              |
| `sourceMetadata`   | object \| null | `{ tmdbId }` (VixSrc) or `{ pathId }` (RaiPlay) |
| `directoryName`    | string        | Folder name under `DOWNLOAD_TV_SHOWS_PATH`   |
| `enabled`          | boolean       | Whether auto-monitoring is active            |
| `lastCheckedAt`    | datetime \| null | ISO-8601; last time the check ran         |
| `lastNewEpisodeAt` | datetime \| null | ISO-8601; last time a new episode was found |
| `createdAt`        | datetime      | ISO-8601                                     |

---

### `ContentMetadata`

| Field              | Type                     | Notes                                       |
|--------------------|--------------------------|---------------------------------------------|
| `source`           | `MediaSource`            | `VIXSRC` or `RAIPLAY`                       |
| `sourceMetadata`   | object                   | `{ tmdbId }` for VixSrc, `{ pathId }` for RaiPlay |
| `tmdbId`           | integer                  | TMDB identifier                             |
| `title`            | string                   |                                             |
| `originalTitle`    | string                   |                                             |
| `year`             | integer                  |                                             |
| `overview`         | string                   |                                             |
| `voteAverage`      | number                   |                                             |
| `season`           | integer \| null          | TV only                                     |
| `episode`          | integer \| null          | TV only                                     |
| `episodeName`      | string \| null           | TV only                                     |
| `numberOfSeasons`  | integer \| null          | TV only                                     |
| `totalEpisodes`    | integer \| null          | TV only                                     |
| `episodesPerSeason`| `{ [season]: count }` \| null | TV only                               |

---

### `DownloadTask`

| Field             | Type              | Notes                                   |
|-------------------|-------------------|-----------------------------------------|
| `id`              | string (UUID)     |                                         |
| `source`          | `MediaSource`     |                                         |
| `contentType`     | `ContentType`     | `MOVIE` or `TV`                         |
| `tmdbId`          | integer \| null   |                                         |
| `title`           | string            |                                         |
| `season`          | integer \| null   |                                         |
| `episode`         | integer \| null   |                                         |
| `episodeName`     | string \| null    |                                         |
| `languages`       | string[]          |                                         |
| `quality`         | string            |                                         |
| `status`          | `DownloadStatus`  |                                         |
| `progress`        | number            | `0`–`100`                               |
| `bitrate`         | string \| null    | e.g. `"5000k"`                          |
| `downloadedBytes` | long \| null      |                                         |
| `totalBytes`      | long \| null      |                                         |
| `downloadSpeed`   | string \| null    | e.g. `"5.2 MB/s"`                       |
| `etaSeconds`      | long \| null      |                                         |
| `errorMessage`    | string \| null    |                                         |
| `createdAt`       | datetime          | ISO-8601                                |
| `startedAt`       | datetime \| null  |                                         |
| `completedAt`     | datetime \| null  |                                         |
| `subTasks`        | `DownloadSubTask[]` |                                       |

---

### `DownloadSubTask`

| Field             | Type             | Notes                              |
|-------------------|------------------|------------------------------------|
| `id`              | string (UUID)    |                                    |
| `parentTaskId`    | string           |                                    |
| `type`            | string           | `VIDEO`, `AUDIO`, `SUBTITLE`       |
| `language`        | string \| null   | null for VIDEO tracks              |
| `title`           | string           |                                    |
| `codec`           | string           |                                    |
| `resolution`      | string           | e.g. `"1920x1080"`                 |
| `bitrate`         | long             |                                    |
| `status`          | `DownloadStatus` |                                    |
| `progress`        | number           | `0`–`100`                          |
| `downloadSpeed`   | string \| null   |                                    |
| `downloadedBytes` | long \| null     |                                    |
| `totalBytes`      | long \| null     |                                    |
| `etaSeconds`      | long \| null     |                                    |
| `errorMessage`    | string \| null   |                                    |
| `startedAt`       | datetime \| null |                                    |
| `completedAt`     | datetime \| null |                                    |

---

## Enums

### `DownloadStatus`

| Value        | Description                            |
|--------------|----------------------------------------|
| `QUEUED`     | Waiting in queue                       |
| `EXTRACTING` | Resolving playlist / stream URL        |
| `DOWNLOADING`| Actively downloading segments          |
| `MERGING`    | Merging video, audio, subtitle tracks  |
| `COPYING`    | Moving file to final destination       |
| `COMPLETED`  | Finished successfully                  |
| `FAILED`     | Download failed (see `errorMessage`)   |
| `CANCELLED`  | Cancelled by user                      |
| `NOT_FOUND`  | Content not available on the source    |

### `MediaSource`

| Value     | Description      |
|-----------|------------------|
| `VIXSRC`  | VixSrc.to        |
| `RAIPLAY` | RaiPlay.it (IT)  |

### `ContentType`

| Value   | Description |
|---------|-------------|
| `MOVIE` | Film        |
| `TV`    | TV episode  |

### `ContentTypeFilter` (search `type` param)

| Value    | Description            |
|----------|------------------------|
| `MOVIES` | Movies only            |
| `TV`     | TV shows only          |
| `BOTH`   | Movies and TV (default)|

---

## Environment Variables

| Variable                | Required | Default  | Description                              |
|-------------------------|----------|----------|------------------------------------------|
| `TMDB_API_KEY`          | yes      | —        | TMDB v3 API key for metadata lookups     |
| `RAIPLAY_USERNAME`      | no       | —        | RaiPlay account username                 |
| `RAIPLAY_PASSWORD`      | no       | —        | RaiPlay account password                 |
| `DOWNLOAD_MOVIES_PATH`  | no       | —        | Output directory for movies              |
| `DOWNLOAD_TV_SHOWS_PATH`| no       | —        | Output directory for TV shows            |
| `DOWNLOAD_TEMP_PATH`    | no       | —        | Temporary working directory              |
| `PARALLEL_DOWNLOADS`    | no       | `10`     | Max concurrent download tasks            |
| `SEGMENT_CONCURRENCY`   | no       | `5`      | Max parallel HLS segment downloads       |
| `DEFAULT_QUALITY`       | no       | `best`   | Fallback quality when not specified      |
| `SERVER_PORT`           | no       | `8080`   | HTTP server port                         |
