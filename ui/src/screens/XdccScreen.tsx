import { useQuery } from '@tanstack/react-query';
import { useMemo, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { LinkResolution, XdccRelease, XdccTitle } from '../api/types';
import { Button } from '../components/Button';
import { ChevronIcon, DownloadIcon, SearchIcon } from '../components/Icons';
import { SearchField, TextField } from '../components/Input';
import { useToast } from '../components/Toast';
import { LinkDialog } from './LinkDialog';
import base from './Screen.module.css';
import s from './XdccScreen.module.css';

type Resolution = 'any' | '2160' | '1080' | '720';
interface Filters { resolution: Resolution; language: string; hideLow: boolean }

const key = (r: XdccRelease) => r.urls[0];

function passes(r: XdccRelease, f: Filters): boolean {
  if (f.hideLow && r.quality.lowQuality) return false;
  if (f.resolution !== 'any' && (r.quality.resolution ?? 0) < Number(f.resolution)) return false;
  if (f.language && !r.quality.languages.includes(f.language) && !r.quality.languages.includes('MULTI')) return false;
  return true;
}

/** Search XDCC packs (xdcc.eu, PW Search Engine) grouped by title, and queue releases through the link dialog. */
export function XdccScreen() {
  const { toast } = useToast();
  const status = useQuery({ queryKey: ['xdcc-status'], queryFn: api.xdccStatus, staleTime: 60_000 });
  const [input, setInput] = useState('');
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<Map<string, { release: XdccRelease; title: XdccTitle }>>(new Map());
  const [filters, setFilters] = useState<Filters>({ resolution: 'any', language: '', hideLow: true });
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [resolved, setResolved] = useState<LinkResolution | null>(null);
  const [opening, setOpening] = useState(false);
  const [manualUrl, setManualUrl] = useState<string | null>(null);

  const search = useQuery({
    queryKey: ['xdcc-search', query],
    queryFn: () => api.xdccSearch(query),
    enabled: query.length >= 3 && !!status.data?.available,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const titles = useMemo(() => (search.data?.titles ?? [])
    .map((t) => ({ ...t, releases: t.releases.filter((r) => passes(r, filters)) }))
    .filter((t) => t.releases.length > 0), [search.data, filters]);
  const hidden = (search.data?.titles ?? []).reduce((n, t) => n + t.releases.length, 0)
    - titles.reduce((n, t) => n + t.releases.length, 0);

  const isOpen = (t: XdccTitle, i: number) => open.has(t.title) !== (i === 0 || t.relevance >= 3);

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    const q = input.trim();
    if (q.length < 3) return;
    setSelected(new Map());
    setOpen(new Set());
    if (q === query) void search.refetch();
    else setQuery(q);
  };

  const toggle = (title: XdccTitle, release: XdccRelease) =>
    setSelected((cur) => {
      const next = new Map(cur);
      if (next.has(key(release))) next.delete(key(release));
      else next.set(key(release), { release, title });
      return next;
    });

  const openDialog = () => {
    const picks = [...selected.values()];
    if (!picks.length) return;
    const urls = picks.flatMap((p) => p.release.urls);
    const titlesPicked = new Set(picks.map((p) => p.title.title));
    const name = titlesPicked.size === 1
      ? `${picks[0].title.title}${picks[0].title.year ? ` ${picks[0].title.year}` : ''}` : query;
    setOpening(true);
    api.resolveLinks(urls, urls.length > 1 ? name : undefined).then(
      (r) => setResolved(r),
      (e: Error) => toast(`Cannot prepare the download: ${e.message}`, 'error'),
    ).finally(() => setOpening(false));
  };

  const unavailable = status.data && !status.data.available;
  const sourcesLine = search.data?.sources.map((x) => `${x.name}: ${x.message ?? (x.available ? 'ok' : 'unavailable')}`).join(' · ');

  return (
    <div className={base.screen}>
      <div className={base.head}>
        <div>
          <h2 className={base.title}>XDCC</h2>
          <div className={s.sub}>
            Packs offered by bots on IRC, found through {status.data?.sources.map((x) => x.name).join(' and ') ?? 'xdcc.eu'}.
          </div>
        </div>
      </div>

      {unavailable && (
        <div className={s.banner} role="status" data-testid="xdcc-unavailable">
          <strong>Search is not available:</strong>{' '}
          {status.data!.sources.map((x) => `${x.name}: ${x.message ?? 'unavailable'}`).join('; ')}.
          You can still request a pack you know, below.
        </div>
      )}

      <form className={s.toolbar} onSubmit={submit}>
        <SearchField id="xdcc-search" wrapClassName={s.query} prefix={<SearchIcon size={18} />} value={input}
          onChange={(e) => setInput(e.target.value)} placeholder="Title, e.g. una battaglia dopo l'altra"
          aria-label="Search XDCC packs" autoComplete="off" enterKeyHint="search" disabled={!!unavailable} />
        <Button type="submit" size="xl" icon={<SearchIcon size={17} />}
          disabled={!!unavailable || input.trim().length < 3 || search.isFetching}>
          {search.isFetching ? 'Searching...' : 'Search'}
        </Button>
      </form>

      {search.data && (
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
            Hide cam/telesync/mic-dubbed
          </label>
        </div>
      )}

      {search.isError && <div className={`${base.empty} ${base.error}`} role="alert">Search failed: {(search.error as Error).message}</div>}
      {search.data && (
        <>
          <div className={s.resultHead}>
            <span className={s.dim} data-testid="xdcc-sources">
              {titles.length} title{titles.length === 1 ? '' : 's'} from {search.data.packs} packs
              {hidden > 0 && ` (${hidden} hidden by filters)`} · {sourcesLine}
            </span>
            <Button id="xdcc-download" size="md" icon={<DownloadIcon size={15} />} onClick={openDialog}
              disabled={selected.size === 0} loading={opening}>
              Download {selected.size > 0 ? `${selected.size} selected` : 'selected'}
            </Button>
          </div>
          {titles.length === 0
            ? <div className={base.empty}>Nothing found for <strong>{query}</strong>{hidden > 0 && ' with these filters'}</div>
            : titles.map((t, i) => (
              <TitleCard key={t.title} t={t} open={isOpen(t, i)}
                onToggleOpen={() => setOpen((cur) => {
                  const next = new Set(cur);
                  if (next.has(t.title)) next.delete(t.title);
                  else next.add(t.title);
                  return next;
                })}
                selected={selected} toggle={toggle} />
            ))}
        </>
      )}

      <ManualRequest onOpen={setManualUrl} />

      {resolved && <LinkDialog resolved={resolved} onClose={() => { setResolved(null); setSelected(new Map()); }} />}
      {manualUrl && <LinkDialog initialUrl={manualUrl} onClose={() => setManualUrl(null)} />}
    </div>
  );
}

function TitleCard({ t, open, onToggleOpen, selected, toggle }: {
  t: XdccTitle; open: boolean; onToggleOpen: () => void;
  selected: Map<string, unknown>; toggle: (t: XdccTitle, r: XdccRelease) => void;
}) {
  const bots = t.releases.reduce((n, r) => n + r.bots, 0);
  return (
    <section className={`${s.group} ${t.relevance === 0 ? s.weak : ''}`} data-testid="xdcc-title" data-relevance={t.relevance}>
      <button type="button" className={s.groupHead} onClick={onToggleOpen} aria-expanded={open}>
        <span className={s.poster}>
          {t.posterUrl ? <img src={t.posterUrl} alt="" loading="lazy" referrerPolicy="no-referrer" />
            : <span>{t.contentType === 'TV' ? 'tv' : 'film'}</span>}
        </span>
        <span className={s.groupText}>
          <span className={s.groupTitle}>{t.title}{t.year ? ` (${t.year})` : ''}</span>
          <span className={s.dim}>
            {t.contentType === 'TV' ? 'TV' : 'Film'}
            {t.tmdbTitle && t.tmdbTitle.toLowerCase() !== t.title.toLowerCase() && ` · TMDB: ${t.tmdbTitle}`}
            {` · ${t.releases.length} release${t.releases.length === 1 ? '' : 's'} · ${bots} bot${bots === 1 ? '' : 's'}`}
          </span>
        </span>
        <span className={`${s.chevron} ${open ? s.chevronOpen : ''}`}><ChevronIcon size={16} /></span>
      </button>
      {open && (
        <div className={s.releases}>
          {t.releases.map((r) => <ReleaseRow key={key(r)} r={r} checked={selected.has(key(r))} onToggle={() => toggle(t, r)} />)}
        </div>
      )}
    </section>
  );
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
      <span className={s.chips}>
        {chips.map((c) => <span key={c} className={s.chip}>{c}</span>)}
        {q.languages.map((l) => <span key={l} className={`${s.chip} ${s.lang}`}>{l}</span>)}
        {q.subtitles.map((l) => <span key={`sub-${l}`} className={s.chip}>sub {l}</span>)}
        {q.lowQuality && <span className={`${s.chip} ${s.warn}`}>low quality</span>}
        {!r.complete && <span className={`${s.chip} ${s.warn}`}>parts {r.missingParts.join(', ')} missing</span>}
      </span>
      <span className={s.fileName}>{r.label}</span>
      <span className={s.meta}>
        {r.size ?? '?'} · {r.format} · {r.bots} bot{r.bots === 1 ? '' : 's'}
      </span>
    </label>
  );
}

/** A pack known by heart: server, channel, bot and pack number. */
function ManualRequest({ onOpen }: { onOpen: (url: string) => void }) {
  const [server, setServer] = useState('');
  const [channel, setChannel] = useState('#');
  const [bot, setBot] = useState('');
  const [pack, setPack] = useState('');
  const valid = server.trim() && channel.trim().length > 1 && bot.trim() && Number(pack) > 0;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!valid) return;
    const host = server.trim().includes(':') ? server.trim() : `${server.trim()}:6667`;
    const ch = channel.trim().startsWith('#') ? channel.trim() : `#${channel.trim()}`;
    onOpen(`xdcc://${host}/${encodeURIComponent(ch)}/${encodeURIComponent(bot.trim())}/${Number(pack)}`);
  };
  return (
    <details className={s.manual}>
      <summary>Request a pack manually</summary>
      <form className={s.manualForm} onSubmit={submit}>
        <TextField value={server} onChange={(e) => setServer(e.target.value)} placeholder="irc.server.it[:6667]" aria-label="IRC server" />
        <TextField value={channel} onChange={(e) => setChannel(e.target.value)} placeholder="#channel" aria-label="Channel" />
        <TextField value={bot} onChange={(e) => setBot(e.target.value)} placeholder="Bot nick" aria-label="Bot" />
        <TextField value={pack} onChange={(e) => setPack(e.target.value.replace(/\D/g, ''))} placeholder="Pack #" inputMode="numeric" aria-label="Pack number" />
        <Button type="submit" size="md" disabled={!valid}>Continue</Button>
      </form>
    </details>
  );
}
