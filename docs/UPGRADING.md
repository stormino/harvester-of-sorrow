# Upgrading from 3.x (VixsrcDownloader) to 5.0

5.0 replaces the Vaadin UI with a React UI served by the same container. Downloads, the queue
history and monitored shows carry over.

## What changes

| | 3.x | 5.0 |
|---|---|---|
| Image | `ghcr.io/stormino/vixsrc-downloader-java` | `ghcr.io/stormino/harvester-of-sorrow` |
| Container (compose) | `vixsrc-downloader` | `harvester-of-sorrow` |
| Data volume (compose) | `vixsrc-data` → `/app/data` | `hos-data` → `/app/data` |
| Database file | `/app/data/vixsrc.db` | `/app/data/hos.db` (uses `vixsrc.db` if `hos.db` does not exist) |
| UI | Vaadin | React (same URLs: `/`, `/downloads`, `/library`, `/settings`) |
| REST API | `/api/...` | `/api/v1/...` (old endpoints removed; see Swagger UI) |
| Live updates | Vaadin push / `/api/progress/stream` | SSE `/api/v1/events` |

Environment variables and the `/downloads` layout are unchanged.

## Steps

1. Back up the data volume:
   ```bash
   docker run --rm -v vixsrc-data:/data -v "$PWD":/backup alpine tar czf /backup/vixsrc-data.tgz -C /data .
   ```
   (Compose prefixes volume names with the project, e.g. `vixsrcdownloader_vixsrc-data`; check `docker volume ls`.)
2. Stop 3.x: `docker compose down` (in the old project).
3. Keep the old database. Either:
   - **Reuse the old volume:** in the new `docker-compose.yml`, mount it at `/app/data` and declare it
     `external: true` with its real name; or
   - **Copy it into the new volume:**
     ```bash
     docker volume create hos-data
     docker run --rm -v vixsrc-data:/from -v hos-data:/to alpine cp -a /from/. /to/
     ```
   On first start 5.0 finds `vixsrc.db`, logs a warning and uses it in place. The schema is the same
   as 3.0.1 (no new migrations). Rename it to `hos.db` (or set `SQLITE_DB_PATH`) to silence the warning.
4. Start 5.0: `docker compose up -d`.
5. Reload any browser tab still open on the old UI (it keeps polling Vaadin endpoints and gets 405).

## Notes

- Tasks that were running when 3.x stopped are restored as FAILED; use Retry.
- Behind nginx, disable buffering for `/api/v1/events` (see README).
- Rollback: stop 5.0 and start 3.x on the same volume. The database schema is unchanged, so 3.x
  can use it (if you renamed the file, rename it back to `vixsrc.db`). The backup is your safety net.
