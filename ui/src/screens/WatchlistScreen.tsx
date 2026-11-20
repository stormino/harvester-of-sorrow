import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '../api/client';
import type { WatchlistItem } from '../api/types';
import { BookmarkIcon, DownloadIcon, StarIcon } from '../components/Icons';
import { useWatchlist } from '../lib/useWatchlist';
import { groupFromItem } from '../lib/watchlist';
import { SaveButton, TrailerLink } from './TitleActions';
import { TitlePanel } from './TitlePanel';
import base from './Screen.module.css';
import s from './WatchlistScreen.module.css';

type TypeFilter = 'ALL' | 'MOVIE' | 'TV';

/** Titles saved for later: decide with the plot and the trailer, then download from the title panel. */
export function WatchlistScreen() {
  const [params, setParams] = useSearchParams();
  const type: TypeFilter = params.get('type') === 'MOVIE' || params.get('type') === 'TV' ? params.get('type') as TypeFilter : 'ALL';
  const openId = params.get('open');
  const watchlist = useWatchlist();
  const items = watchlist.items;

  const movies = items.filter((i) => i.contentType === 'MOVIE').length;
  const shows = items.length - movies;
  const shown = type === 'ALL' ? items : items.filter((i) => i.contentType === type);
  const open = openId ? items.find((i) => i.id === openId) : undefined;

  const update = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      if (v == null) next.delete(k);
      else next.set(k, v);
    }
    setParams(next);
  };

  return (
    <div className={base.screen}>
      <div className={s.head}>
        <h2 className={base.title}>Watchlist</h2>
        {items.length > 0 && (
          <span className={s.count}>{items.length} title{items.length > 1 ? 's' : ''} saved for later</span>
        )}
        <span className={s.spacer} />
        {movies > 0 && shows > 0 && (
          <div className={s.filter} role="radiogroup" aria-label="Show">
            {([['ALL', 'All', items.length], ['MOVIE', 'Films', movies], ['TV', 'Shows', shows]] as const).map(([v, label, n]) => (
              <button key={v} type="button" role="radio" aria-checked={type === v}
                className={`${s.filterOpt} ${type === v ? s.filterOn : ''}`}
                onClick={() => update({ type: v === 'ALL' ? null : v })}>
                {label} <span className={s.filterCount}>{n}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      {watchlist.isLoading && <div className={base.empty}>Loading...</div>}
      {watchlist.error && <div className={base.empty}><span className={base.error}>Could not load the watchlist: {watchlist.error.message}</span></div>}
      {!watchlist.isLoading && !watchlist.error && items.length === 0 && (
        <div className={`${base.empty} ${s.empty}`}>
          <BookmarkIcon size={28} />
          <p>Nothing saved yet.</p>
          <p className={s.emptyDim}>Use the bookmark on a search result to save a title for later.</p>
        </div>
      )}

      <div className={s.grid} data-testid="watchlist">
        {shown.map((i) => (
          <Card key={i.id} item={i} onOpen={() => update({ open: i.id })} onRemove={() => void watchlist.remove(i)} />
        ))}
      </div>

      {open && <WatchlistPanel key={open.id} item={open} onClose={() => update({ open: null })} />}
    </div>
  );
}

function Card({ item, onOpen, onRemove }: { item: WatchlistItem; onOpen: () => void; onRemove: () => void }) {
  const isTV = item.contentType === 'TV';
  return (
    // The whole card opens the title; the buttons inside do their own thing (they stop the click).
    <article className={`${s.card} ${isTV ? s.tv : ''}`} onClick={onOpen} data-testid="watchlist-card" data-title={item.title}>
      <div className={s.poster}>
        {item.posterUrl
          ? <img src={item.posterUrl} alt="" loading="lazy" referrerPolicy="no-referrer"
              onError={(e) => { e.currentTarget.style.display = 'none'; }} />
          : <span className={s.posterLabel}>{isTV ? 'tv' : 'film'}</span>}
      </div>
      <div className={s.info}>
        <div className={s.titleRow}>
          <h3 className={s.title}>
            <button type="button" className={s.titleBtn} onClick={(e) => { e.stopPropagation(); onOpen(); }}>{item.title}</button>
          </h3>
          <SaveButton saved title={item.title} onToggle={onRemove} className={s.remove} />
        </div>
        <div className={s.meta}>
          <span className={s.kind}>{isTV ? 'TV' : 'Film'}</span>
          {item.year && <span>{item.year}</span>}
          {item.rating != null && item.rating > 0 && <span className={s.rating}><StarIcon size={12} />{item.rating.toFixed(1)}</span>}
          <span>saved {savedAgo(item.createdAt)}</span>
        </div>
        {item.overview && <p className={s.overview}>{item.overview}</p>}
        <div className={s.fill} />
        <div className={s.actions}>
          <TrailerLink contentType={item.contentType} tmdbId={item.tmdbId} />
          <button type="button" className={s.download} onClick={(e) => { e.stopPropagation(); onOpen(); }}>
            <DownloadIcon size={14} />Download…
          </button>
        </div>
      </div>
    </article>
  );
}

/** The title panel for a saved title, with where it can be downloaded now (a fresh search by title). */
function WatchlistPanel({ item, onClose }: { item: WatchlistItem; onClose: () => void }) {
  const watchlist = useWatchlist();
  const streams = useQuery({
    queryKey: ['search', item.title, 'BOTH'],
    queryFn: () => api.search(item.title, 'BOTH'),
    staleTime: 5 * 60_000,
    retry: false,
  });
  const xdccStatus = useQuery({ queryKey: ['xdcc-status'], queryFn: api.xdccStatus, staleTime: 60_000 });
  const xdccEnabled = item.title.trim().length >= 3 && !!xdccStatus.data?.available;
  const xdcc = useQuery({
    queryKey: ['xdcc-search', item.title],
    queryFn: () => api.xdccSearch(item.title),
    enabled: xdccEnabled,
    staleTime: 5 * 60_000,
    retry: false,
  });
  const group = useMemo(() => groupFromItem(item, xdcc.data?.titles, streams.data),
    [item, xdcc.data, streams.data]);
  const loading = xdccStatus.isLoading || (xdccEnabled && xdcc.isLoading);

  // Found more than was saved (e.g. saved before the VixSrc results had arrived): keep it.
  const refreshed = useRef(false);
  useEffect(() => {
    if (refreshed.current || !streams.data) return;
    const more = group.streams.length > item.streams.length
      || (!item.overview && !!group.overview) || (item.rating == null && group.rating != null);
    if (more) {
      refreshed.current = true;
      void watchlist.refresh(group);
    }
  }, [streams.data, group, item, watchlist]);

  // Remount when sources arrive, so the panel opens on VixSrc rather than staying on XDCC
  return <TitlePanel key={group.streams.map((x) => x.source).join()} group={group} onClose={onClose} xdccLoading={loading} />;
}

/** "today", "yesterday", "3 days ago", "2 weeks ago", "5 months ago". */
export function savedAgo(iso: string, now = new Date()): string {
  const days = Math.floor((now.getTime() - new Date(iso).getTime()) / 86_400_000);
  if (days <= 0) return 'today';
  if (days === 1) return 'yesterday';
  if (days < 14) return `${days} days ago`;
  if (days < 60) return `${Math.floor(days / 7)} weeks ago`;
  if (days < 730) return `${Math.floor(days / 30)} months ago`;
  return `${Math.floor(days / 365)} years ago`;
}
