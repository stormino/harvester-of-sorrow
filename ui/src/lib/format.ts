const DASH = '—';

/** 1.24 GB, 512 MB, 87 KB (decimal units, like the backend logs). */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes <= 0) return DASH;
  if (bytes >= 1e12) return (bytes / 1e12).toFixed(2) + ' TB';
  if (bytes >= 1e9) return (bytes / 1e9).toFixed(2) + ' GB';
  if (bytes >= 1e6) return (bytes / 1e6).toFixed(0) + ' MB';
  if (bytes >= 1e3) return (bytes / 1e3).toFixed(0) + ' KB';
  return Math.round(bytes) + ' B';
}

/** 5.2 MB/s */
export function formatSpeed(bps: number | null | undefined): string {
  if (bps == null || !Number.isFinite(bps) || bps <= 0) return DASH;
  if (bps >= 1e9) return (bps / 1e9).toFixed(2) + ' GB/s';
  if (bps >= 1e6) return (bps / 1e6).toFixed(1) + ' MB/s';
  if (bps >= 1e3) return (bps / 1e3).toFixed(0) + ' KB/s';
  return Math.round(bps) + ' B/s';
}

/** 2h 5m, 12m 30s, 45s */
export function formatEta(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds <= 0) return DASH;
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

/** 41.8% */
export function formatPercent(p: number | null | undefined): string {
  const v = Math.max(0, Math.min(100, p ?? 0));
  return v.toFixed(1) + '%';
}

const pad = (n: number) => String(n).padStart(2, '0');

/** HH:mm:ss in the browser's time zone; date prefix when not today. */
export function formatTime(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return DASH;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return DASH;
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate();
  return sameDay ? time : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${time}`;
}

/** yyyy-MM-dd HH:mm in the browser's time zone, or "-". */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 300000 ms -> "5 min", 3600000 -> "60 min" */
export function formatInterval(ms: number): string {
  if (ms % 3_600_000 === 0) return `${ms / 3_600_000} h`;
  return `${Math.round(ms / 60_000)} min`;
}

export function pad2(n: number): string {
  return pad(n);
}
