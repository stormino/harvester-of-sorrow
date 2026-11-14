import { useQuery } from '@tanstack/react-query';
import { useEffect, useState, type KeyboardEvent, type MouseEvent } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '../api/client';
import type { SearchResult, SearchType } from '../api/types';
import { CountChip, SourcePill } from '../components/Badge';
import { Button } from '../components/Button';
import { DownloadIcon, SearchIcon, StarIcon } from '../components/Icons';
import { SearchField } from '../components/Input';
import { Segmented } from '../components/Segmented';
import { DownloadDialog } from './DownloadDialog';
import s from './SearchScreen.module.css';
import base from './Screen.module.css';

const TYPES: { value: SearchType; label: string }[] = [
  { value: 'MOVIES', label: 'Movies' },
  { value: 'TV', label: 'TV Shows' },
  { value: 'BOTH', label: 'Both' },
];

const parseType = (t: string | null): SearchType => (t === 'MOVIES' || t === 'TV' ? t : 'BOTH');

export function SearchScreen() {
  // The submitted search lives in the URL (?q=&type=) so it survives navigation and can be linked.
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const type = parseType(params.get('type'));
  const [input, setInput] = useState(q);
  const [inputType, setInputType] = useState<SearchType>(type);
  const [selected, setSelected] = useState<SearchResult | null>(null);

  useEffect(() => setInput(q), [q]);
  useEffect(() => setInputType(type), [type]);

  const search = useQuery({
    queryKey: ['search', q, type],
    queryFn: () => api.search(q, type),
    enabled: q.trim().length > 0,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const submit = (nextType = inputType) => {
    const query = input.trim();
    if (!query) return;
    if (query === q && nextType === type) {
      void search.refetch();
    } else {
      setParams({ q: query, type: nextType });
    }
  };

  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Enter') submit();
  };

  const busy = search.isFetching;

  return (
    <div className={base.screen}>
      <div className={base.head}>
        <h2 className={base.title}>Search</h2>
      </div>

      <div className={s.toolbar}>
        <SearchField id="search-input" wrapClassName={s.query} prefix={<SearchIcon size={18} />}
          value={input} onChange={(e) => setInput(e.target.value)} onKeyDown={onKey}
          placeholder="Search movies and TV shows..." aria-label="Search movies and TV shows" autoComplete="off"
          enterKeyHint="search" />
        <Segmented id="content-type-filter" ariaLabel="Type" value={inputType} options={TYPES}
          onChange={(t) => { setInputType(t); if (q && input.trim()) submit(t); }} />
        <Button id="search-button" size="xl" className={s.searchBtn} icon={<SearchIcon size={17} />}
          onClick={() => submit()} disabled={busy || !input.trim()}>
          {busy ? 'Searching...' : 'Search'}
        </Button>
      </div>

      <div id="search-results" aria-busy={busy}>
        {search.isError && (
          <div className={`${base.empty} ${base.error}`} role="alert">
            Search failed: {(search.error as Error).message}
          </div>
        )}
        {!search.isError && search.data && search.data.length === 0 && !busy && (
          <div className={base.empty}>No results found for: <span className={s.strong}>{q}</span></div>
        )}
        {!q && <div className={s.hint}>Search TMDB-backed VixSrc and RaiPlay titles by name.</div>}
        {search.data && search.data.length > 0 && (
          <div className={s.grid}>
            {search.data.map((r) => (
              <ResultCard key={`${r.source}:${r.sourceRef}`} r={r} onDownload={() => setSelected(r)} />
            ))}
          </div>
        )}
      </div>

      {selected && <DownloadDialog item={selected} onClose={() => setSelected(null)} />}
    </div>
  );
}

function ResultCard({ r, onDownload }: { r: SearchResult; onDownload: () => void }) {
  const isTV = r.contentType === 'TV';
  const episodes = r.totalEpisodes ?? r.seasons.reduce((n, x) => n + (x.episodeCount ?? 0), 0);
  const open = () => {
    if (r.externalUrl) window.open(r.externalUrl, '_blank', 'noopener,noreferrer');
  };
  const onDownloadClick = (e: MouseEvent) => {
    e.stopPropagation();
    onDownload();
  };
  return (
    <article
      className={`${s.card} ${isTV ? s.tv : ''} ${r.externalUrl ? '' : s.noLink}`}
      data-testid="result-card"
      data-source={r.source.toLowerCase()}
      data-type={r.contentType}
      data-title={r.title}
      onClick={open}
      tabIndex={r.externalUrl ? 0 : undefined}
      onKeyDown={(e) => { if (e.key === 'Enter' && e.target === e.currentTarget) open(); }}
      aria-label={r.externalUrl ? `${r.title} — open source page` : r.title}
    >
      <div className={s.top}>
        <div className={s.poster}>
          {r.posterUrl
            ? <img src={r.posterUrl} alt="" loading="lazy" referrerPolicy="no-referrer" />
            : <span className={s.posterLabel}>{isTV ? 'tv' : 'movie'}</span>}
        </div>
        <div className={s.info}>
          <h3 className={s.title}>{r.title}</h3>
          <div className={s.chips}>
            <SourcePill source={r.source} large upper={false} />
            {isTV && r.seasons.length > 0 && <CountChip>{r.seasons.length}S</CountChip>}
            {isTV && episodes > 0 && <CountChip>{episodes}E</CountChip>}
          </div>
          <div className={s.meta}>
            {r.year && <span className={s.year}>{r.year}</span>}
            {r.rating != null && (
              <span className={s.rating}><StarIcon size={13} />{r.rating.toFixed(1)}</span>
            )}
            {r.tmdbId != null && <span className={s.tmdb}>TMDB {r.tmdbId}</span>}
          </div>
        </div>
      </div>
      {r.overview && <p className={s.overview}>{r.overview}</p>}
      <div className={s.fill} />
      <button
        type="button"
        className={s.download}
        data-testid="result-card-download"
        id={r.tmdbId != null ? `result-card-download-${r.tmdbId}` : undefined}
        onClick={onDownloadClick}
        onKeyDown={(e) => e.stopPropagation()}
      >
        <DownloadIcon size={15} />Download
      </button>
    </article>
  );
}
