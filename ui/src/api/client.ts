import type {
  CheckResult, ClearResult, ContentType, DownloadRequest, Episode, LibraryEntry, LinkDownloadRequest,
  LinkProvider, LinkResolution, MonitoredShow, TmdbMatch,
  MonitoredShowRequest, SearchResult, SearchType, Settings, Source, SourcesInfo,
  SystemInfo, Task, ToolCheck,
} from './types';

const BASE = '/api/v1';

/** An RFC 7807 error returned by the API (or a network failure). */
export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(BASE + path, {
      method,
      headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, 'Cannot reach the server');
  }
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`;
    try {
      const problem = await res.json();
      detail = problem.detail || problem.title || detail;
    } catch {
      // not JSON
    }
    throw new ApiError(res.status, detail);
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return res.json() as Promise<T>;
}

const enc = encodeURIComponent;

export const api = {
  search: (query: string, type: SearchType, source?: Source) =>
    request<SearchResult[]>('GET', `/search?query=${enc(query)}&type=${type}${source ? `&source=${source}` : ''}`),
  sources: () => request<SourcesInfo>('GET', '/sources'),
  episodes: (source: Source, sourceRef: string, season?: number, title?: string) =>
    request<Episode[]>('POST', '/content/episodes', { source, sourceRef, season, title }),

  downloads: () => request<Task[]>('GET', '/downloads'),
  createDownload: (req: DownloadRequest) => request<Task[]>('POST', '/downloads', req),
  cancel: (id: string) => request<Task>('POST', `/downloads/${enc(id)}/cancel`),
  retry: (id: string) => request<Task>('POST', `/downloads/${enc(id)}/retry`),
  clearFinished: () => request<ClearResult>('DELETE', '/downloads?state=finished'),

  library: () => request<LibraryEntry[]>('GET', '/library'),
  addMonitored: (req: MonitoredShowRequest) => request<MonitoredShow>('POST', '/library/monitored', req),
  updateMonitored: (id: string, req: MonitoredShowRequest) =>
    request<MonitoredShow>('PUT', `/library/monitored/${enc(id)}`, req),
  removeMonitored: (id: string) => request<void>('DELETE', `/library/monitored/${enc(id)}`),
  setMonitoring: (id: string, enabled: boolean) =>
    request<MonitoredShow>('POST', `/library/monitored/${enc(id)}/${enabled ? 'enable' : 'disable'}`),
  checkNow: (id: string) => request<CheckResult>('POST', `/library/monitored/${enc(id)}/check`),

  linkProviders: () => request<LinkProvider[]>('GET', '/links/providers'),
  resolveLink: (url: string, provider?: string) =>
    request<LinkResolution>('POST', '/links/resolve', { url, provider: provider || undefined }),
  linkTmdb: (query: string, type: ContentType, year?: number) =>
    request<TmdbMatch[]>('GET', `/links/tmdb?query=${enc(query)}&type=${type}${year ? `&year=${year}` : ''}`),
  createLinkDownload: (req: LinkDownloadRequest) => request<Task[]>('POST', '/links/downloads', req),

  system: () => request<SystemInfo>('GET', '/system'),
  settings: () => request<Settings>('GET', '/settings'),
  checkTools: () => request<ToolCheck[]>('POST', '/system/tools/check'),
};

export const EVENTS_URL = `${BASE}/events`;
