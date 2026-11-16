import { useQuery } from '@tanstack/react-query';
import { Fragment, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '../api/client';
import { Button } from '../components/Button';
import { AlertIcon, DownloadIcon, RetryIcon, SearchIcon, StarIcon } from '../components/Icons';
import { SearchField } from '../components/Input';
import { band, bestRelease, mergeTitles, type TitleGroup } from '../lib/titles';
import { LinkDialog, looksLikeLink } from './LinkDialog';
import { TitlePanel } from './TitlePanel';
import s from './SearchScreen.module.css';
import base from './Screen.module.css';

type TypeFilter = 'ALL' | 'MOVIE' | 'TV';
const parseType = (t: string | null): TypeFilter => (t === 'MOVIE' || t === 'TV' ? t : 'ALL');

const RECENT_KEY = 'hos-recent-searches';
function readRecent(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]');
    return Array.isArray(v) ? v.filter((x) => typeof x === 'string').slice(0, 8) : [];
  } catch {
    return [];
  }
}
function pushRecent(q: string): string[] {
  const next = [q, ...readRecent().filter((x) => x.toLowerCase() !== q.toLowerCase())].slice(0, 8);
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(next));
  } catch {
    // ignore
  }
  return next;
}

/** One search over every source (VixSrc, RaiPlay, XDCC); results grouped per title. */
export function SearchScreen() {
  // Search, type filter and the open title live in the URL, so back/refresh/links work.
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const type = parseType(params.get('type'));
  const openKey = params.get('open');
  const [input, setInput] = useState(q);
  const [recent, setRecent] = useState(readRecent);
  // undefined = closed; '' = open without a link; otherwise the link to inspect.
  const [link, setLink] = useState<string | undefined>(undefined);
  const inputRef = useRef<HTMLInputElement>(null);
  const isLink = looksLikeLink(input);

  useEffect(() => setInput(q), [q]);

  // "/" focuses the search box from anywhere on the page.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (e.key !== '/' || t.closest('input, textarea, select, [contenteditable]')) return;
      if (document.querySelector('[role="dialog"]')) return;
      e.preventDefault();
      inputRef.current?.focus();
      inputRef.current?.select();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  const streams = useQuery({
    queryKey: ['search', q, 'BOTH'],
    queryFn: () => api.search(q, 'BOTH'),
    enabled: q.trim().length > 0,
    staleTime: 5 * 60_000,
    retry: false,
  });
  const xdccStatus = useQuery({ queryKey: ['xdcc-status'], queryFn: api.xdccStatus, staleTime: 60_000 });
  const xdccEnabled = q.trim().length >= 3 && !!xdccStatus.data?.available;
  const xdcc = useQuery({
    queryKey: ['xdcc-search', q],
    queryFn: () => api.xdccSearch(q),
    enabled: xdccEnabled,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const groups = useMemo(() => mergeTitles(streams.data ?? [], xdcc.data?.titles ?? [], q), [streams.data, xdcc.data, q]);
  const movies = groups.filter((g) => g.contentType === 'MOVIE').length;
  const shows = groups.length - movies;
  const shown = type === 'ALL' ? groups : groups.filter((g) => g.contentType === type);
  const open = openKey ? groups.find((g) => g.key === openKey) : undefined;

  const update = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      if (v == null || v === '') next.delete(k);
      else next.set(k, v);
    }
    setParams(next);
  };

  const run = (raw: string) => {
    const query = raw.trim();
    if (!query) return;
    if (looksLikeLink(query)) {
      setLink(query);
      return;
    }
    setRecent(pushRecent(query));
    if (query === q) {
      void streams.refetch();
      if (xdccEnabled) void xdcc.refetch();
    } else {
      setParams({ q: query });
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    run(input);
  };

  const loading = streams.isFetching || xdcc.isFetching;
  const nothing = q && !loading && groups.length === 0 && !streams.isError;

  return (
    <div className={base.screen}>
      <form className={s.toolbar} onSubmit={submit}>
        <SearchField ref={inputRef} id="search-input" wrapClassName={s.query} prefix={<SearchIcon size={18} />}
          value={input} onChange={(e) => setInput(e.target.value)}
          placeholder="Search a film or a show, or paste a link…" aria-label="Search films and shows, or paste a link"
          autoComplete="off" enterKeyHint="search" autoFocus={!q} />
        <Button id="search-button" type="submit" size="xl" className={s.searchBtn}
          icon={isLink ? <DownloadIcon size={17} /> : <SearchIcon size={17} />} disabled={!input.trim()}>
          {isLink ? 'Open link' : 'Search'}
        </Button>
        <Button id="link-open" type="button" variant="ghost" size="xl" iconOnly icon={<DownloadIcon size={18} />}
          onClick={() => setLink('')} aria-label="Download from a link" title="Download from a link (Mega, direct file, XDCC)" />
      </form>

      {q && (
        <div className={s.statusRow}>
          <div className={s.status} data-testid="search-status">
            <SourceStatus name="VixSrc" loading={streams.isFetching} error={streams.error as Error | null}
              count={streams.data?.filter((r) => r.source === 'VIXSRC').length} onRetry={() => void streams.refetch()} />
            <SourceStatus name="RaiPlay" loading={streams.isFetching} error={streams.error as Error | null}
              count={streams.data?.filter((r) => r.source === 'RAIPLAY').length} onRetry={() => void streams.refetch()} />
            {xdccItems()}
          </div>
          {movies > 0 && shows > 0 && (
            <div className={s.filter} role="radiogroup" aria-label="Show">
              {([['ALL', 'All', groups.length], ['MOVIE', 'Films', movies], ['TV', 'Shows', shows]] as const).map(([v, label, n]) => (
                <button key={v} type="button" role="radio" aria-checked={type === v}
                  className={`${s.filterOpt} ${type === v ? s.filterOn : ''}`}
                  onClick={() => update({ type: v === 'ALL' ? null : v })}>
                  {label} <span className={s.filterCount}>{n}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      <div id="search-results" aria-busy={loading}>
        {!q && (
          <div className={s.hint}>
            <p>Search once: VixSrc, RaiPlay and XDCC are all looked up, and each title shows where you can get it.</p>
            <p className={s.hintDim}>Paste a Mega or direct link to download it · press <kbd>/</kbd> to search</p>
            {recent.length > 0 && (
              <div className={s.recent}>
                {recent.map((r) => (
                  <button key={r} type="button" className={s.recentChip} onClick={() => { setInput(r); run(r); }}>{r}</button>
                ))}
              </div>
            )}
          </div>
        )}
        {nothing && <div className={base.empty}>Nothing found for <span className={s.strong}>{q}</span></div>}

        {q && (shown.length > 0 || streams.isFetching) && (
          <div className={s.grid}>
            {shown.map((g, i) => (
              <Fragment key={g.key}>
                {band(g) === 1 && i > 0 && band(shown[i - 1]) === 0 && (
                  <div className={s.divider}><span>Other matches</span></div>
                )}
                <ResultCard g={g} xdccPending={xdcc.isFetching} onOpen={() => update({ open: g.key })} />
              </Fragment>
            ))}
            {streams.isFetching && groups.length === 0 && Array.from({ length: 6 }, (_, i) => <div key={i} className={s.skeleton} />)}
          </div>
        )}
      </div>

      {open && <TitlePanel key={open.key} group={open} onClose={() => update({ open: null })} />}
      {link !== undefined && <LinkDialog initialUrl={link || undefined} onClose={() => setLink(undefined)} />}
    </div>
  );

  function xdccItems() {
    if (xdccStatus.data && !xdccStatus.data.available) {
      return <SourceStatus name="XDCC" error={new Error(xdccStatus.data.sources.map((x) => x.message ?? 'unavailable').join('; '))} />;
    }
    if (q.trim().length < 3) return <SourceStatus name="XDCC" note="needs 3+ letters" />;
    if (xdcc.isError) return <SourceStatus name="XDCC" error={xdcc.error as Error} onRetry={() => void xdcc.refetch()} />;
    if (!xdcc.data || xdcc.isFetching) return <SourceStatus name="XDCC" loading />;
    return (
      <>
        {xdcc.data.sources.map((x) => (
          <SourceStatus key={x.name} name={x.name} error={x.available ? null : new Error(x.message ?? 'unavailable')}
            note={x.available ? x.message : undefined} />
        ))}
      </>
    );
  }
}

function SourceStatus({ name, loading, error, count, note, onRetry }: {
  name: string; loading?: boolean; error?: Error | null; count?: number; note?: string; onRetry?: () => void;
}) {
  if (loading) return <span className={s.src}><span className={s.spinner} />{name}</span>;
  if (error) {
    return (
      <span className={`${s.src} ${s.srcErr}`} title={error.message}>
        <AlertIcon size={13} />{name}
        {onRetry && <button type="button" className={s.retry} onClick={onRetry} aria-label={`Retry ${name}`}><RetryIcon size={12} /></button>}
      </span>
    );
  }
  return (
    <span className={s.src} title={note}>
      <span className={s.ok}>✓</span>{name}
      {count != null && <span className={s.srcCount}>{count}</span>}
      {count == null && note && <span className={s.srcNote}>{note}</span>}
    </span>
  );
}

function ResultCard({ g, xdccPending, onOpen }: { g: TitleGroup; xdccPending: boolean; onOpen: () => void }) {
  const isTV = g.contentType === 'TV';
  const tv = g.streams.find((r) => r.contentType === 'TV' && r.seasons.length > 0);
  return (
    <button type="button" className={`${s.card} ${isTV ? s.tv : ''}`} onClick={onOpen}
      data-testid="result-card" data-type={g.contentType} data-title={g.title}
      data-source={g.streams[0]?.source.toLowerCase() ?? 'xdcc'}>
      <div className={s.poster}>
        {g.posterUrl
          ? <img src={g.posterUrl} alt="" loading="lazy" referrerPolicy="no-referrer"
              onError={(e) => { e.currentTarget.style.display = 'none'; }} />
          : <span className={s.posterLabel}>{isTV ? 'tv' : 'film'}</span>}
      </div>
      <div className={s.info}>
        <div className={s.titleRow}>
          <h3 className={s.title}>{g.title}</h3>
        </div>
        <div className={s.meta}>
          <span className={s.kind}>{isTV ? 'TV' : 'Film'}</span>
          {g.year && <span>{g.year}</span>}
          {g.rating != null && g.rating > 0 && <span className={s.rating}><StarIcon size={12} />{g.rating.toFixed(1)}</span>}
          {tv && <span>{tv.seasons.length}S</span>}
        </div>
        {g.overview && <p className={s.overview}>{g.overview}</p>}
        <div className={s.fill} />
        <ul className={s.sources} aria-label="Available from">
          {g.streams.map((r) => (
            <li key={r.source} className={`${s.source} ${s[`src${r.source}`] ?? ''}`}>
              <span className={s.dot} />{r.source === 'VIXSRC' ? 'VixSrc' : 'RaiPlay'}
            </li>
          ))}
          {g.xdcc.length > 0 && (
            <li className={`${s.source} ${s.srcXDCC}`}>
              <span className={s.dot} />XDCC <span className={s.sourceNote}>{[g.xdcc.length, bestRelease(g.xdcc)].filter(Boolean).join(' · ')}</span>
            </li>
          )}
          {g.xdcc.length === 0 && xdccPending && <li className={`${s.source} ${s.sourcePending}`}><span className={s.spinner} />XDCC</li>}
        </ul>
      </div>
    </button>
  );
}
