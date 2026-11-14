# REST API

The API is documented by the running server:

- **Swagger UI:** `http://<host>:8080/swagger-ui.html`
- **OpenAPI spec:** `http://<host>:8080/v3/api-docs` (also committed as `ui/openapi.json`)

Conventions:

- All endpoints are under `/api/v1`. No authentication.
- Request and response bodies are JSON DTOs; fields that are null are omitted.
- Timestamps are ISO-8601 instants (UTC); sizes are bytes; speeds are bytes per second.
- Errors are RFC 7807 `application/problem+json` (`status`, `title`, `detail`).
- `sourceRef` values returned by search and episode listings are opaque: pass them back unchanged.
- Live updates: `GET /api/v1/events` (Server-Sent Events). The first event is `snapshot` (all tasks);
  then `task.created`, `task.updated` and `task.removed`, each carrying a full task. A `:heartbeat`
  comment is sent every 15 s; updates are throttled to about 4 per second per task.
