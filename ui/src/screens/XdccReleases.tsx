import { useMemo, useState } from 'react';
import { api } from '../api/client';
import type { LinkResolution, XdccRelease } from '../api/types';
import { Button } from '../components/Button';
import { sourceLabel } from '../components/Badge';
import { Chip, ChipList } from '../components/Chip';
import { DownloadIcon } from '../components/Icons';
import { useToast } from '../components/Toast';
import { seasonOf, type TitleGroup } from '../lib/titles';
import { LinkDialog } from './LinkDialog';
import f from './Form.module.css';
import p from './TitlePanel.module.css';
import s from './XdccReleases.module.css';

type Resolution = 'any' | '2160' | '1080' | '720';
interface Filters { resolution: Resolution; language: string; hideLow: boolean }

const key = (r: XdccRelease) => r.urls[0];

function passes(r: XdccRelease, x: Filters): boolean {
  if (x.hideLow && r.quality.lowQuality) return false;
  if (x.resolution !== 'any' && (r.quality.resolution ?? 0) < Number(x.resolution)) return false;
  if (x.language && !r.quality.languages.includes(x.language) && !r.quality.languages.includes('MULTI')) return false;
  return true;
}

/** XDCC releases of one title: filter, pick, then confirm naming in the link dialog. */
export function XdccReleases({ group, onQueued }: { group: TitleGroup; onQueued: () => void }) {
  const { toast } = useToast();
  const [filters, setFilters] = useState<Filters>({ resolution: 'any', language: '', hideLow: true });
  const [season, setSeason] = useState<number | null>(null);
  const [selected, setSelected] = useState<Map<string, XdccRelease>>(new Map());
  const [opening, setOpening] = useState(false);
  const [resolved, setResolved] = useState<LinkResolution | null>(null);

  const seasons = useMemo(() => [...new Set(group.xdcc.map((r) => seasonOf(r.episode)).filter((n): n is number => n != null))]
    .sort((a, b) => a - b), [group.xdcc]);
  const visible = group.xdcc.filter((r) => passes(r, filters) && (season == null || seasonOf(r.episode) === season));
  const hidden = group.xdcc.length - visible.length;
  const partial = partialShow(group);

  const toggle = (r: XdccRelease) => setSelected((cur) => {
    const next = new Map(cur);
    if (next.has(key(r))) next.delete(key(r));
    else next.set(key(r), r);
    return next;
  });

  const download = () => {
    const urls = [...selected.values()].flatMap((r) => r.urls);
    if (!urls.length) return;
    const name = `${group.title}${group.year ? ` ${group.year}` : ''}`;
    setOpening(true);
    api.resolveLinks(urls, urls.length > 1 ? name : undefined).then(
      setResolved,
      (e: Error) => toast(`Cannot prepare the download: ${e.message}`, 'error'),
    ).finally(() => setOpening(false));
  };

  return (
    <>
      <div className={f.form}>
        {partial && <p className={s.note} data-testid="xdcc-partial">{partial}</p>}
        <div className={s.filters} role="group" aria-label="Filters">
          <label>Resolution
            <select value={filters.resolution} onChange={(e) => setFilters({ ...filters, resolution: e.target.value as Resolution })}>
              <option value="any">Any</option><option value="2160">2160p</option>
              <option value="1080">1080p+</option><option value="720">720p+</option>
            </select>
          </label>
          <label>Audio
            <select value={filters.language} onChange={(e) => setFilters({ ...filters, language: e.target.value })}>
              <option value="">Any</option><option value="ITA">Italian</option><option value="ENG">English</option>
            </select>
          </label>
          <label className={s.check}>
            <input type="checkbox" checked={filters.hideLow} onChange={(e) => setFilters({ ...filters, hideLow: e.target.checked })} />
            Hide cam / telesync
          </label>
        </div>

        {seasons.length > 1 && (
          <ChipList label="Season">
            <Chip size="sm" sans selected={season == null} onClick={() => setSeason(null)}>All</Chip>
            {seasons.map((n) => (
              <Chip key={n} size="sm" selected={season === n} onClick={() => setSeason(season === n ? null : n)}>S{n}</Chip>
            ))}
          </ChipList>
        )}

        <div className={s.list}>
          {visible.length === 0
            ? <div className={f.placeholder}>No release matches these filters{hidden > 0 && ` (${hidden} hidden)`}.</div>
            : visible.map((r) => <ReleaseRow key={key(r)} r={r} checked={selected.has(key(r))} onToggle={() => toggle(r)} />)}
          {visible.length > 0 && hidden > 0 && <div className={f.dim}>{hidden} more hidden by filters</div>}
        </div>
      </div>

      <div className={p.spacer} />
      <div className={p.actions}>
        <span className={p.actionNote}>
          {selected.size ? `${selected.size} release${selected.size > 1 ? 's' : ''} selected` : 'Pick one or more releases'}
        </span>
        <Button id="xdcc-download" size="lg" icon={<DownloadIcon size={16} />} onClick={download}
          disabled={selected.size === 0} loading={opening}>
          Continue
        </Button>
      </div>

      {resolved && <LinkDialog resolved={resolved} onClose={(queued) => {
        setResolved(null);
        if (queued) onQueued();
      }} />}
    </>
  );
}

/** A show with only loose episodes on XDCC (no season pack): say so, and where the whole show is. */
export function partialShow(group: TitleGroup): string | null {
  if (group.contentType !== 'TV' || group.xdcc.length === 0 || group.xdcc.some((r) => !r.episode)) return null;
  const eps = [...new Set(group.xdcc.map((r) => r.episode!))].sort();
  const list = eps.length <= 6 ? eps.join(', ') : `${eps.slice(0, 5).join(', ')} and ${eps.length - 5} more`;
  const where = group.streams.length ? ` The whole show is on ${sourceLabel(group.streams[0].source)}.` : '';
  return `XDCC has only ${eps.length === 1 ? 'one episode' : `${eps.length} single episodes`} of this show (${list}).${where}`;
}

function ReleaseRow({ r, checked, onToggle }: { r: XdccRelease; checked: boolean; onToggle: () => void }) {
  const q = r.quality;
  const chips: string[] = [];
  if (r.episode) chips.push(r.episode);
  if (q.resolution) chips.push(`${q.resolution}p`);
  if (q.source) chips.push(q.source);
  if (q.codec) chips.push(q.codec);
  if (q.hdr) chips.push('HDR');
  if (q.audio) chips.push(q.audio);
  return (
    <label className={`${s.release} ${checked ? s.on : ''} ${r.complete ? '' : s.disabled}`} data-testid="xdcc-release"
      title={r.label}>
      <input type="checkbox" checked={checked} onChange={onToggle} disabled={!r.complete} aria-label={`Select ${r.label}`} />
      <span className={s.body}>
        <span className={s.chips}>
          {chips.map((c) => <span key={c} className={s.chip}>{c}</span>)}
          {q.languages.map((l) => <span key={l} className={`${s.chip} ${s.lang}`}>{l}</span>)}
          {q.subtitles.map((l) => <span key={`sub-${l}`} className={s.chip}>sub {l}</span>)}
          {q.lowQuality && <span className={`${s.chip} ${s.warn}`}>low quality</span>}
          {!r.complete && <span className={`${s.chip} ${s.warn}`}>parts {r.missingParts.join(', ')} missing</span>}
        </span>
        <span className={s.fileName}>{r.label}</span>
      </span>
      <span className={s.meta}>
        {r.size ?? '?'}<br />{r.bots} bot{r.bots === 1 ? '' : 's'}
      </span>
    </label>
  );
}
