import { useEffect, useMemo, useState, type KeyboardEvent } from 'react';
import { api } from '../api/client';
import { useLinkProviders } from '../api/queries';
import type { ContentType, LinkDownloadItem, LinkFile, LinkResolution, TmdbMatch } from '../api/types';
import { Button } from '../components/Button';
import { Chip, ChipList } from '../components/Chip';
import { Dialog } from '../components/Dialog';
import { SearchIcon } from '../components/Icons';
import { TextField } from '../components/Input';
import { Segmented } from '../components/Segmented';
import { useToast } from '../components/Toast';
import { formatBytes } from '../lib/format';
import { useTaskStream } from '../live/TaskStream';
import dd from './DownloadDialog.module.css';
import s from './LinkDialog.module.css';

interface Props {
  /** Link to inspect right away (e.g. pasted in the search box). */
  initialUrl?: string;
  /** Already resolved links (e.g. XDCC packs picked from a search): no link field. */
  resolved?: LinkResolution;
  onClose: () => void;
}

const TYPES: { value: ContentType; label: string }[] = [
  { value: 'MOVIE', label: 'Movie' },
  { value: 'TV', label: 'TV episodes' },
];

const MANUAL = -1;

/** Per-file choice for TV: queue it, and as which episode. */
interface EpisodeRow { selected: boolean; season: string; episode: string }

/** True for strings that look like a link rather than a search query. */
export function looksLikeLink(text: string): boolean {
  return /^https?:\/\/\S+$/i.test(text.trim());
}

export function LinkDialog({ initialUrl, resolved, onClose }: Props) {
  const { toast, dismiss } = useToast();
  const { upsert } = useTaskStream();
  const providers = useLinkProviders();

  const [url, setUrl] = useState(initialUrl ?? '');
  const [provider, setProvider] = useState('');
  const [resolving, setResolving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [res, setRes] = useState<LinkResolution | null>(null);

  const [type, setType] = useState<ContentType>('MOVIE');
  const [matches, setMatches] = useState<TmdbMatch[]>([]);
  const [matchId, setMatchId] = useState<number>(MANUAL);
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);
  const [manualTitle, setManualTitle] = useState('');
  const [manualYear, setManualYear] = useState('');
  const [movieFile, setMovieFile] = useState<string>('');
  const [rows, setRows] = useState<Record<string, EpisodeRow>>({});

  const resolve = (target = url, forced = provider) => {
    const link = target.trim();
    if (!link) return;
    setResolving(true);
    setError(null);
    api.resolveLink(link, forced).then(apply, (e: Error) => setError(e.message))
      .finally(() => setResolving(false));
  };

  function apply(r: LinkResolution) {
    setRes(r);
    setType(r.guess.contentType);
    setMatches(r.matches);
    setMatchId(r.matches[0]?.tmdbId ?? MANUAL);
    setQuery(r.guess.title);
    setManualTitle(r.guess.title);
    setManualYear(r.guess.year ? String(r.guess.year) : '');
    const media = r.files.filter((f) => f.video || f.archive);
    const largest = [...(media.length ? media : r.files)].sort((a, b) => (b.size ?? 0) - (a.size ?? 0))[0];
    setMovieFile(largest?.url ?? '');
    setRows(Object.fromEntries(r.files.map((f) => [f.url, {
      selected: f.video || f.archive || r.files.length === 1,
      season: f.guess.season != null ? String(f.guess.season) : (r.guess.season != null ? String(r.guess.season) : ''),
      episode: f.guess.episode != null ? String(f.guess.episode) : '',
    }])));
    void providers.refetch();
  }

  // Resolve a link handed over by the search box once, on open (or take the given resolution).
  useEffect(() => {
    if (resolved) apply(resolved);
    else if (initialUrl) resolve(initialUrl, '');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const searchTmdb = (nextType = type) => {
    if (!query.trim()) return;
    setSearching(true);
    api.linkTmdb(query.trim(), nextType, manualYear ? Number(manualYear) : undefined).then(
      (found) => {
        setMatches(found);
        setMatchId(found[0]?.tmdbId ?? MANUAL);
      },
      (e: Error) => toast(`TMDB search failed: ${e.message}`, 'error'),
    ).finally(() => setSearching(false));
  };

  const changeType = (t: ContentType) => {
    setType(t);
    // Candidates of the other kind are useless: look the title up again as the new kind.
    if (matches.some((m) => m.contentType !== t) || matches.length === 0) searchTmdb(t);
  };

  const visibleMatches = matches.filter((m) => m.contentType === type);
  const chosen = visibleMatches.find((m) => m.tmdbId === matchId);
  const title = chosen ? chosen.title : manualTitle.trim();
  const year = chosen ? chosen.year : (manualYear ? Number(manualYear) : undefined);

  const items: LinkDownloadItem[] = useMemo(() => {
    if (!res || !title) return [];
    const base = (f: LinkFile) => ({
      fileUrl: f.url, fileName: f.fileName, size: f.size, contentType: type,
      tmdbId: chosen?.tmdbId, title, year,
      parts: f.parts.length ? f.parts : undefined,
    });
    if (type === 'MOVIE') {
      const f = res.files.find((x) => x.url === movieFile);
      return f ? [base(f)] : [];
    }
    return res.files
      .filter((f) => rows[f.url]?.selected)
      .map((f) => ({ ...base(f), season: toInt(rows[f.url].season), episode: toInt(rows[f.url].episode) }));
  }, [res, type, movieFile, rows, title, year, chosen]);

  const isArchive = (i: LinkDownloadItem) => !!res?.files.find((f) => f.url === i.fileUrl)?.archive;
  // Archives may hold whole seasons: their episodes are read from the names inside, numbers are optional.
  const episodesValid = type === 'MOVIE'
    || items.every((i) => isArchive(i) ? (i.episode == null || i.season != null) : i.season != null && i.episode != null);
  const numbered = items.filter((i) => i.season != null && i.episode != null);
  const duplicate = type === 'TV' && new Set(numbered.map((i) => `${i.season}x${i.episode}`)).size < numbered.length;
  const anyArchive = items.some(isArchive);
  const canConfirm = !!res && items.length > 0 && !!title && episodesValid && !duplicate;

  const confirm = () => {
    if (!res) return;
    const pending = toast('Adding to queue...', 'contrast', { durationMs: 60_000 });
    onClose();
    api.createLinkDownload({ provider: res.provider, url: res.url, items }).then(
      (tasks) => {
        dismiss(pending);
        upsert(tasks);
        toast(tasks.length > 1
          ? `✓ Added ${tasks.length} files to queue`
          : `✓ Added to queue: ${tasks[0]?.displayName ?? title}`, 'success', { durationMs: 5000 });
      },
      (e: Error) => {
        dismiss(pending);
        toast(`Failed to add download: ${e.message}`, 'error');
      },
    );
  };

  const providerInfo = providers.data?.find((p) => p.id === res?.provider);
  const onUrlKey = (e: KeyboardEvent) => { if (e.key === 'Enter') resolve(); };
  const onQueryKey = (e: KeyboardEvent) => { if (e.key === 'Enter') searchTmdb(); };
  const setRow = (key: string, patch: Partial<EpisodeRow>) =>
    setRows((r) => ({ ...r, [key]: { ...r[key], ...patch } }));

  return (
    <Dialog open title={resolved ? `Download from ${resolved.providerName}` : 'Download from link'} onClose={onClose} width={640} testId="link-dialog"
      footer={
        <>
          <Button variant="outline" size="lg" onClick={onClose}>Cancel</Button>
          {res
            ? <Button id="link-confirm" size="lg" onClick={confirm} disabled={!canConfirm}>
                {items.length > 1 ? `Add ${items.length} to Queue` : 'Add to Queue'}
              </Button>
            : <Button id="link-inspect" size="lg" onClick={() => resolve()} loading={resolving}
                disabled={resolving || !url.trim()}>Inspect link</Button>}
        </>
      }>
      <div className={dd.form}>
        {!resolved && <div>
          <div className={dd.label}>Link</div>
          <div className={s.urlRow}>
            <TextField id="link-url" className={s.grow} value={url} placeholder="https://mega.nz/file/... or https://.../movie.mkv"
              onChange={(e) => { setUrl(e.target.value); setRes(null); setError(null); }} onKeyDown={onUrlKey}
              autoFocus={!initialUrl} spellCheck={false} autoComplete="off" aria-label="Link" />
            {res && <Button variant="outline" size="md" onClick={() => resolve()} loading={resolving}>Reload</Button>}
          </div>
          <div className={s.providers}>
            <span className={dd.dim}>Provider</span>
            <ChipList id="link-provider" label="Provider">
              <Chip size="sm" sans selected={provider === ''} onClick={() => setProvider('')}>Auto</Chip>
              {(providers.data ?? []).map((p) => (
                <Chip key={p.id} size="sm" sans selected={provider === p.id} data-value={p.id}
                  onClick={() => setProvider(p.id)}>{p.name}</Chip>
              ))}
            </ChipList>
          </div>
          {resolving && <div className={s.note}>Inspecting link...</div>}
          {error && <div className={dd.error} role="alert">{error}</div>}
        </div>}

        {res && (
          <>
            <div className={s.summary} data-testid="link-summary">
              <span className={s.provider}>{res.providerName}</span>
              <span className={s.linkName} title={res.name}>{res.name}</span>
              <span className={dd.dim}>
                {res.folder ? `${res.files.length} file${res.files.length === 1 ? '' : 's'} · ` : ''}
                {formatBytes(res.files.reduce((n, f) => n + (f.size ?? 0), 0))}
              </span>
            </div>
            {res.truncated > 0 && (
              <div className={s.note}>{res.truncated} more files in this folder were left out (too many files).</div>
            )}
            {providerInfo && providerInfo.state !== 'READY' && (
              <div className={s.warmup} data-testid="link-provider-status">
                {providerInfo.state === 'ERROR' ? '⚠ ' : ''}
                {providerInfo.name}: {providerInfo.message ?? providerInfo.state.toLowerCase()}.
                {' '}Downloads are queued now and start automatically once it is ready.
              </div>
            )}

            <div>
              <div className={dd.label}>What is it?</div>
              <Segmented id="link-type" ariaLabel="Content type" value={type} options={TYPES} onChange={changeType} />
            </div>

            <div>
              <div className={dd.label}>
                Title <span className={dd.dim}>— confirm the match{res.tmdbAvailable ? '' : ' (TMDB not configured)'}</span>
              </div>
              {res.tmdbAvailable && (
                <div className={s.urlRow}>
                  <TextField className={s.grow} value={query} onChange={(e) => setQuery(e.target.value)}
                    onKeyDown={onQueryKey} placeholder="Search TMDB" aria-label="Search TMDB" />
                  <Button variant="outline" size="md" icon={<SearchIcon size={15} />} onClick={() => searchTmdb()}
                    loading={searching}>Search</Button>
                </div>
              )}
              <div className={s.matches} role="radiogroup" aria-label="TMDB matches">
                {visibleMatches.map((m) => (
                  <button key={m.tmdbId} type="button" role="radio" aria-checked={matchId === m.tmdbId}
                    className={`${s.match} ${matchId === m.tmdbId ? s.on : ''}`} onClick={() => setMatchId(m.tmdbId)}
                    data-testid="link-match" data-tmdb-id={m.tmdbId}>
                    <span className={s.poster}>{m.posterUrl && <img src={m.posterUrl} alt="" loading="lazy" referrerPolicy="no-referrer" />}</span>
                    <span className={s.matchText}>
                      <span className={s.matchTitle}>{m.title}{m.year ? ` (${m.year})` : ''}</span>
                      {m.originalTitle && m.originalTitle !== m.title && <span className={dd.dim}>{m.originalTitle}</span>}
                      {m.overview && <span className={s.overview}>{m.overview}</span>}
                    </span>
                  </button>
                ))}
                {res.tmdbAvailable && visibleMatches.length === 0 && !searching && (
                  <div className={dd.placeholder}>No TMDB match — search again or enter the title below.</div>
                )}
                <button type="button" role="radio" aria-checked={matchId === MANUAL}
                  className={`${s.match} ${s.manual} ${matchId === MANUAL ? s.on : ''}`} onClick={() => setMatchId(MANUAL)}
                  data-testid="link-match-manual">
                  <span className={s.matchText}><span className={s.matchTitle}>Enter the title myself</span></span>
                </button>
              </div>
              {matchId === MANUAL && (
                <div className={s.manualFields}>
                  <TextField className={s.grow} value={manualTitle} onChange={(e) => setManualTitle(e.target.value)}
                    placeholder={type === 'TV' ? 'Show title' : 'Movie title'} aria-label="Title" />
                  <TextField className={s.year} value={manualYear} inputMode="numeric"
                    onChange={(e) => setManualYear(e.target.value.replace(/\D/g, '').slice(0, 4))}
                    placeholder="Year" aria-label="Year" />
                </div>
              )}
            </div>

            <div>
              <div className={dd.label}>
                {type === 'MOVIE' ? 'File' : 'Episodes'}
                {type === 'TV' && <span className={dd.dim}>— check the season and episode of each file</span>}
              </div>
              {type === 'MOVIE' ? (
                <div className={s.files} role="radiogroup" aria-label="File">
                  {res.files.map((f) => (
                    <label key={f.url} className={s.file}>
                      <input type="radio" name="link-movie-file" checked={movieFile === f.url}
                        onChange={() => setMovieFile(f.url)} disabled={res.files.length === 1} />
                      <FileName f={f} />
                    </label>
                  ))}
                </div>
              ) : (
                <div className={s.files}>
                  {res.files.map((f) => {
                    const r = rows[f.url];
                    if (!r) return null;
                    return (
                      <div key={f.url} className={s.file} data-testid="link-episode-row">
                        <input type="checkbox" checked={r.selected} aria-label={`Queue ${f.fileName}`}
                          onChange={(e) => setRow(f.url, { selected: e.target.checked })} />
                        <FileName f={f} />
                        <span className={s.se}>
                          S<TextField className={s.num} value={r.season} inputMode="numeric" aria-label="Season"
                            placeholder={f.archive ? 'auto' : ''}
                            disabled={!r.selected} onChange={(e) => setRow(f.url, { season: digits(e.target.value) })} />
                          E<TextField className={s.num} value={r.episode} inputMode="numeric" aria-label="Episode"
                            placeholder={f.archive ? 'auto' : ''}
                            disabled={!r.selected} onChange={(e) => setRow(f.url, { episode: digits(e.target.value) })} />
                        </span>
                      </div>
                    );
                  })}
                </div>
              )}
              {anyArchive && (
                <div className={s.note} data-testid="link-archive-note">
                  {type === 'TV'
                    ? 'Archives are unpacked after downloading; each episode inside is placed by the SxxEyy in its name (leave S/E empty for "auto"). Subtitles go next to their episode.'
                    : 'The archive is unpacked after downloading; its largest video goes into the library, with its subtitles.'}
                </div>
              )}
              {type === 'TV' && !episodesValid && (
                <div className={dd.error}>Every checked video needs a season and an episode (archives: a season if you set an episode).</div>
              )}
              {duplicate && <div className={dd.error}>Two checked files have the same season and episode.</div>}
            </div>
          </>
        )}
      </div>
    </Dialog>
  );
}

function FileName({ f }: { f: LinkFile }) {
  const path = f.folders.length ? `${f.folders.join(' / ')} / ` : '';
  return (
    <span className={s.fileName} title={path + f.fileName}>
      {path && <span className={dd.dim}>{path}</span>}
      {f.fileName}
      <span className={s.fileSize}>
        {formatBytes(f.size)}
        {f.archive ? ` · archive${f.parts.length > 1 ? `, ${f.parts.length} parts` : ''}` : f.video ? '' : ' · not a video'}
      </span>
    </span>
  );
}

function toInt(v: string): number | undefined {
  return v === '' ? undefined : Number(v);
}

function digits(v: string): string {
  return v.replace(/\D/g, '').slice(0, 3);
}
