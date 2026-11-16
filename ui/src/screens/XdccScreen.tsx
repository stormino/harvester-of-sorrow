import { useQuery } from '@tanstack/react-query';
import { useMemo, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { LinkResolution, XdccPack } from '../api/types';
import { Button } from '../components/Button';
import { DownloadIcon, SearchIcon } from '../components/Icons';
import { SearchField, TextField } from '../components/Input';
import { useToast } from '../components/Toast';
import { LinkDialog } from './LinkDialog';
import base from './Screen.module.css';
import s from './XdccScreen.module.css';

/** Search XDCC packs (xdcc.eu, PW Search Engine) and queue the chosen ones through the link dialog. */
export function XdccScreen() {
  const { toast } = useToast();
  const status = useQuery({ queryKey: ['xdcc-status'], queryFn: api.xdccStatus, staleTime: 60_000 });
  const [input, setInput] = useState('');
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
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
  const packs = useMemo(() => search.data?.packs ?? [], [search.data]);

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    const q = input.trim();
    if (q.length < 3) return;
    setSelected(new Set());
    if (q === query) void search.refetch();
    else setQuery(q);
  };

  const toggle = (url: string) =>
    setSelected((cur) => {
      const next = new Set(cur);
      if (next.has(url)) next.delete(url);
      else next.add(url);
      return next;
    });

  const openDialog = () => {
    const urls = packs.filter((p) => selected.has(p.url)).map((p) => p.url);
    if (!urls.length) return;
    setOpening(true);
    api.resolveLinks(urls, urls.length > 1 ? query : undefined).then(
      (r) => setResolved(r),
      (e: Error) => toast(`Cannot prepare the download: ${e.message}`, 'error'),
    ).finally(() => setOpening(false));
  };

  const unavailable = status.data && !status.data.available;

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
          onChange={(e) => setInput(e.target.value)} placeholder="File name, e.g. dune 2024 1080p"
          aria-label="Search XDCC packs" autoComplete="off" enterKeyHint="search" disabled={!!unavailable} />
        <Button type="submit" size="xl" icon={<SearchIcon size={17} />}
          disabled={!!unavailable || input.trim().length < 3 || search.isFetching}>
          {search.isFetching ? 'Searching...' : 'Search'}
        </Button>
      </form>

      {search.isError && <div className={`${base.empty} ${base.error}`} role="alert">Search failed: {(search.error as Error).message}</div>}
      {search.data && (
        <>
          <div className={s.resultHead}>
            <span className={s.dim} data-testid="xdcc-sources">
              {packs.length} pack{packs.length === 1 ? '' : 's'}
              {search.data.sources.map((x) => ` · ${x.name}: ${x.message ?? (x.available ? 'ok' : 'unavailable')}`).join('')}
            </span>
            <Button id="xdcc-download" size="md" icon={<DownloadIcon size={15} />} onClick={openDialog}
              disabled={selected.size === 0} loading={opening}>
              Download {selected.size > 0 ? `${selected.size} selected` : 'selected'}
            </Button>
          </div>
          {packs.length === 0
            ? <div className={base.empty}>No packs found for <strong>{query}</strong></div>
            : <PackTable packs={packs} selected={selected} toggle={toggle} />}
        </>
      )}

      <ManualRequest onOpen={setManualUrl} />

      {resolved && <LinkDialog resolved={resolved} onClose={() => { setResolved(null); setSelected(new Set()); }} />}
      {manualUrl && <LinkDialog initialUrl={manualUrl} onClose={() => setManualUrl(null)} />}
    </div>
  );
}

function PackTable({ packs, selected, toggle }: { packs: XdccPack[]; selected: Set<string>; toggle: (u: string) => void }) {
  return (
    <div className={s.table} role="table" aria-label="XDCC packs">
      {packs.map((p) => (
        <label key={p.url} className={`${s.row} ${selected.has(p.url) ? s.on : ''}`} data-testid="xdcc-pack">
          <input type="checkbox" checked={selected.has(p.url)} onChange={() => toggle(p.url)} aria-label={`Select ${p.fileName}`} />
          <span className={s.name} title={p.fileName}>{p.fileName}</span>
          <span className={s.size}>{p.size}</span>
          <span className={s.where} title={`${p.network} ${p.channel} — ${p.bot} #${p.pack} (via ${p.source})`}>
            {p.bot} <span className={s.dim}>#{p.pack} · {p.channel} · {p.network}</span>
          </span>
        </label>
      ))}
    </div>
  );
}

/** A pack known by heart: server, channel, bot and pack number. */
function ManualRequest({ onOpen }: { onOpen: (url: string) => void }) {
  const [open, setOpen] = useState(false);
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
    <details className={s.manual} open={open} onToggle={(e) => setOpen((e.target as HTMLDetailsElement).open)}>
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
