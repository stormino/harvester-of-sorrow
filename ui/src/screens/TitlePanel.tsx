import { useState } from 'react';
import { sourceLabel } from '../components/Badge';
import { Dialog } from '../components/Dialog';
import { StarIcon } from '../components/Icons';
import { bestRelease, type TitleGroup } from '../lib/titles';
import { StreamForm } from './StreamForm';
import { XdccReleases } from './XdccReleases';
import s from './TitlePanel.module.css';

export type PanelMode = 'drawer' | 'center';
const MODE_KEY = 'hos-panel';

export function readPanelMode(): PanelMode {
  try {
    return localStorage.getItem(MODE_KEY) === 'center' ? 'center' : 'drawer';
  } catch {
    return 'drawer';
  }
}

function savePanelMode(m: PanelMode) {
  try {
    localStorage.setItem(MODE_KEY, m);
  } catch {
    // ignore
  }
}

/** Tab ids: a streaming source (VIXSRC, RAIPLAY) or XDCC. */
type Tab = string;

/** Everything about one title: details, and one tab per place it can be downloaded from. */
export function TitlePanel({ group, onClose }: { group: TitleGroup; onClose: () => void }) {
  const [mode, setMode] = useState<PanelMode>(readPanelMode);
  const tabs: { id: Tab; label: string; note: string }[] = [
    ...group.streams.map((r) => ({
      id: r.source as string,
      label: sourceLabel(r.source),
      note: r.contentType === 'TV' && r.seasons.length
        ? `${r.seasons.length} season${r.seasons.length > 1 ? 's' : ''}` : 'direct download',
    })),
    ...(group.xdcc.length ? [{ id: 'XDCC', label: 'XDCC', note: [`${group.xdcc.length} release${group.xdcc.length > 1 ? 's' : ''}`, bestRelease(group.xdcc)].filter(Boolean).join(' · ') }] : []),
  ];
  const [tab, setTab] = useState<Tab>(tabs[0]?.id ?? 'XDCC');
  const stream = group.streams.find((r) => r.source === tab);
  const isTV = group.contentType === 'TV';

  const switchMode = () => {
    const next = mode === 'drawer' ? 'center' : 'drawer';
    setMode(next);
    savePanelMode(next);
  };

  return (
    <Dialog open title={group.title} onClose={onClose} width={mode === 'drawer' ? 620 : 680} variant={mode}
      testId="title-panel"
      actions={
        <button type="button" className={s.modeBtn} onClick={switchMode}
          title="Switch between side panel and centred dialog">
          {mode === 'drawer' ? '⇱ Dialog' : '⇥ Side panel'}
        </button>
      }
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

      {stream
        ? <StreamForm key={stream.source} item={stream} onQueued={onClose} />
        : <XdccReleases group={group} onQueued={onClose} />}
    </Dialog>
  );
}
