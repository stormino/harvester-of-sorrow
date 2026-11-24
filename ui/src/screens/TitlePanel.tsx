import { useState } from 'react';
import { sourceLabel } from '../components/Badge';
import { Dialog } from '../components/Dialog';
import { StarIcon } from '../components/Icons';
import { bestRelease, notYetLabel, type TitleGroup } from '../lib/titles';
import { useWatchlist } from '../lib/useWatchlist';
import { SaveButton, TrailerLink } from './TitleActions';
import { StreamForm } from './StreamForm';
import { XdccReleases } from './XdccReleases';
import s from './TitlePanel.module.css';

/** Tab ids: a streaming source (VIXSRC, RAIPLAY) or XDCC. */
type Tab = string;

/** Everything about one title: details, and one tab per place it can be downloaded from. */
export function TitlePanel({ group, onClose, xdccLoading }: {
  group: TitleGroup; onClose: () => void;
  /** XDCC releases are still being looked up (title opened from the watchlist). */
  xdccLoading?: boolean;
}) {
  const watchlist = useWatchlist();
  const tabs: { id: Tab; label: string; note: string }[] = [
    ...group.streams.map((r) => ({
      id: r.source as string,
      label: sourceLabel(r.source),
      note: r.contentType === 'TV' && r.seasons.length
        ? `${r.seasons.length} season${r.seasons.length > 1 ? 's' : ''}` : 'direct download',
    })),
    ...(group.xdcc.length ? [{ id: 'XDCC', label: 'XDCC', note: [`${group.xdcc.length} release${group.xdcc.length > 1 ? 's' : ''}`, bestRelease(group.xdcc)].filter(Boolean).join(' · ') }]
      : xdccLoading ? [{ id: 'XDCC', label: 'XDCC', note: 'searching…' }] : []),
  ];
  const [tab, setTab] = useState<Tab>(tabs[0]?.id ?? 'XDCC');
  const stream = group.streams.find((r) => r.source === tab);
  const isTV = group.contentType === 'TV';
  const saved = watchlist.saved(group.key);

  return (
    <Dialog open title={group.title} onClose={onClose} width={620} variant="drawer"
      testId="title-panel"
      header={
        <div className={`${s.head} ${isTV ? s.tv : ''}`}>
          <div className={s.poster}>
            {group.posterUrl
              ? <img src={group.posterUrl} alt="" referrerPolicy="no-referrer"
                  onError={(e) => { e.currentTarget.style.display = 'none'; }} />
              : <span>{isTV ? 'tv' : 'film'}</span>}
          </div>
          <div className={s.headText}>
            <h3 className={s.title}>{group.title}</h3>
            {group.localTitle && <div className={s.localTitle}>{group.localTitle}</div>}
            <div className={s.meta}>
              <span className={s.kind}>{isTV ? 'TV' : 'Film'}</span>
              {group.year && <span>{group.year}</span>}
              {group.rating != null && group.rating > 0 && (
                <span className={s.rating}><StarIcon size={12} />{group.rating.toFixed(1)}</span>
              )}
              {group.externalUrl && (
                <a href={group.externalUrl} target="_blank" rel="noopener noreferrer" className={s.ext}>
                  {group.externalUrl.includes('raiplay') ? 'RaiPlay' : 'TMDB'} ↗
                </a>
              )}
            </div>
            {group.overview && <p className={s.overview}>{group.overview}</p>}
            <div className={s.headActions}>
              <TrailerLink contentType={group.contentType} tmdbId={group.tmdbId} />
              <SaveButton labelled saved={!!saved} title={group.title}
                onToggle={() => void watchlist.toggle(group)} />
            </div>
          </div>
        </div>
      }>
      {tabs.length > 1 && (
        <div className={s.tabs} role="tablist" aria-label="Download from">
          {tabs.map((t) => (
            <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} data-value={t.id}
              className={`${s.tab} ${tab === t.id ? s.tabOn : ''} ${s[`src${t.id}`] ?? ''}`} onClick={() => setTab(t.id)}>
              <span className={s.tabLabel}>{t.label}</span>
              <span className={s.tabNote}>{t.note}</span>
            </button>
          ))}
        </div>
      )}
      {tabs.length === 1 && <div className={s.single}>Download from <strong>{tabs[0].label}</strong></div>}

      {tabs.length === 0 && (
        <div className={s.notYet} data-testid="not-yet-panel">
          <strong>{notYetLabel(group.releaseDate)}</strong>
          {saved
            ? <p>On your watchlist: HoS looks for it on VixSrc, RaiPlay and XDCC every day and marks it
                <em> Available for download</em> as soon as one has it.</p>
            : <p>No source has it yet. Save it to your watchlist (above): HoS looks for it every day and marks it
                <em> Available for download</em> as soon as it can be downloaded.</p>}
        </div>
      )}
      {tabs.length === 0 ? null : stream
        ? <StreamForm key={stream.source} item={stream} onQueued={onClose} />
        : group.xdcc.length === 0 && xdccLoading
          ? <div className={s.single}>Searching XDCC for {group.title}…</div>
          : <XdccReleases group={group} onQueued={onClose} />}
    </Dialog>
  );
}
