import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { api } from '../api/client';
import { keys, useLibrary, useSettings } from '../api/queries';
import type { LibraryEntry, MonitoredShow } from '../api/types';
import { sourceLabel } from '../components/Badge';
import { Button } from '../components/Button';
import { ConfirmDialog } from '../components/Dialog';
import { BellIcon, EditIcon, FolderIcon, RetryIcon, SearchIcon, TrashIcon } from '../components/Icons';
import { SearchField } from '../components/Input';
import { MonitorBadge, type MonitorState } from '../components/StatusBadge';
import { useToast } from '../components/Toast';
import { formatDateTime } from '../lib/format';
import { useIsMobile } from '../lib/useMediaQuery';
import { MonitorDialog } from './MonitorDialog';
import base from './Screen.module.css';
import s from './LibraryScreen.module.css';

const monitorState = (m?: MonitoredShow): MonitorState => (!m ? 'none' : m.enabled ? 'monitoring' : 'paused');

interface RowActions {
  configure: (e: LibraryEntry) => void;
  togglePause: (m: MonitoredShow) => void;
  check: (m: MonitoredShow, dir: string) => void;
  remove: (e: LibraryEntry) => void;
  checking: Set<string>;
  pausing: Set<string>;
}

export function LibraryScreen() {
  const library = useLibrary();
  const { data: settings } = useSettings();
  const qc = useQueryClient();
  const { toast } = useToast();
  const isMobile = useIsMobile();
  const [filter, setFilter] = useState('');
  const [configuring, setConfiguring] = useState<LibraryEntry | null>(null);
  const [removing, setRemoving] = useState<LibraryEntry | null>(null);
  const [checking, setChecking] = useState<Set<string>>(new Set());
  const [pausing, setPausing] = useState<Set<string>>(new Set());

  const refresh = () => qc.invalidateQueries({ queryKey: keys.library });

  const rows = useMemo(() => {
    const f = filter.trim().toLowerCase();
    return (library.data ?? []).filter((e) => !f || e.directoryName.toLowerCase().includes(f)
      || e.monitored?.title.toLowerCase().includes(f));
  }, [library.data, filter]);

  const mark = (set: typeof setChecking, id: string, on: boolean) => set((x) => {
    const n = new Set(x);
    if (on) n.add(id); else n.delete(id);
    return n;
  });

  const remove = useMutation({
    mutationFn: (id: string) => api.removeMonitored(id),
    onSuccess: () => {
      toast(`Stopped monitoring "${removing?.directoryName}"`, 'contrast');
      setRemoving(null);
      void refresh();
    },
    onError: (e) => toast((e as Error).message, 'error'),
  });

  const actions: RowActions = {
    configure: setConfiguring,
    togglePause: async (m) => {
      mark(setPausing, m.id, true);
      try {
        await api.setMonitoring(m.id, !m.enabled);
        toast(m.enabled ? `Paused monitoring "${m.directoryName}"` : `Resumed monitoring "${m.directoryName}"`, 'contrast');
        await refresh();
      } catch (e) {
        toast((e as Error).message, 'error');
      } finally {
        mark(setPausing, m.id, false);
      }
    },
    check: async (m, dir) => {
      mark(setChecking, m.id, true);
      try {
        const r = await api.checkNow(m.id);
        const n = r.newEpisodesEnqueued;
        toast(n > 0 ? `${n} new episode${n > 1 ? 's' : ''} enqueued for "${dir}"` : 'No new episodes found',
          n > 0 ? 'success' : 'contrast');
        await refresh();
      } catch (e) {
        toast((e as Error).message, 'error');
      } finally {
        mark(setChecking, m.id, false);
      }
    },
    remove: setRemoving,
    checking,
    pausing,
  };

  const empty = (
    <div className={base.empty}>
      {library.isLoading ? 'Loading...'
        : library.isError ? <span className={base.error}>Could not load the library: {(library.error as Error).message}</span>
          : filter ? `No shows match "${filter}"`
            : <>No show folders found{settings ? <> in <span className="mono">{settings.tvShowsPath}</span></> : null}.</>}
    </div>
  );

  return (
    <div className={base.screen}>
      <div className={s.head}>
        <h2 id="library-title" className={base.title}>Library</h2>
        <SearchField id="library-filter-input" size="md" wrapClassName={s.filter} prefix={<SearchIcon size={16} />}
          value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter by title..."
          aria-label="Filter by title" type="search" />
        <Button id="library-refresh-button" variant="secondary" size="lg"
          icon={<RetryIcon size={15} className={library.isFetching ? s.spin : undefined} />} onClick={() => void refresh()}>
          Refresh
        </Button>
      </div>
      <p className={s.sub}>Downloaded TV shows detected on disk. Monitor them to queue new episodes automatically.</p>

      {isMobile ? (
        <div id="library-grid" className={s.cards}>
          {rows.length === 0 ? empty : rows.map((e) => <LibraryCard key={e.directoryName} e={e} actions={actions} />)}
        </div>
      ) : (
        <div id="library-grid" className={s.table} role="table" aria-label="TV library">
          <div className={s.scroll}>
            <div className={s.inner}>
              <div className={`${s.cols} ${s.headRow}`} role="row">
                {['Show Directory', 'Actions', 'Seasons', 'Episodes', 'Status', 'Source', 'Last Checked'].map((h) => (
                  <div key={h} className={s.th} role="columnheader">{h}</div>
                ))}
              </div>
              {rows.length === 0 ? empty : rows.map((e) => <LibraryRow key={e.directoryName} e={e} actions={actions} />)}
            </div>
          </div>
        </div>
      )}

      {configuring && (
        <MonitorDialog entry={configuring} onClose={() => setConfiguring(null)}
          onSaved={() => { setConfiguring(null); void refresh(); }} />
      )}
      <ConfirmDialog
        open={!!removing}
        title="Stop monitoring?"
        message={`Stop monitoring "${removing?.directoryName}"? Already-downloaded episodes are kept on disk.`}
        confirmLabel="Remove"
        danger
        busy={remove.isPending}
        onConfirm={() => removing?.monitored && remove.mutate(removing.monitored.id)}
        onClose={() => setRemoving(null)}
      />
    </div>
  );
}

function RowButtons({ e, actions }: { e: LibraryEntry; actions: RowActions }) {
  const m = e.monitored;
  if (!m) {
    return (
      <Button size="sm" icon={<BellIcon size={14} />} onClick={() => actions.configure(e)}
        data-testid="library-monitor-button">Monitor</Button>
    );
  }
  const checking = actions.checking.has(m.id);
  return (
    <>
      <Button variant="outline" size="md" iconOnly icon={<EditIcon size={14} />} title="Edit" aria-label="Edit monitoring"
        onClick={() => actions.configure(e)} />
      <Button variant={m.enabled ? 'outline' : 'success'} size="sm" loading={actions.pausing.has(m.id)}
        onClick={() => actions.togglePause(m)}>{m.enabled ? 'Pause' : 'Resume'}</Button>
      <Button variant="link" size="sm" disabled={checking}
        icon={<SearchIcon size={13} className={checking ? s.spin : undefined} />}
        onClick={() => actions.check(m, e.directoryName)} title="Check now">
        {checking ? 'Checking' : 'Check now'}
      </Button>
      <Button variant="ghost" size="md" iconOnly icon={<TrashIcon size={14} />} title="Remove" aria-label="Stop monitoring"
        onClick={() => actions.remove(e)} />
    </>
  );
}

function LibraryRow({ e, actions }: { e: LibraryEntry; actions: RowActions }) {
  const m = e.monitored;
  return (
    <div className={`${s.cols} ${s.row}`} role="row" data-testid="library-row" data-directory={e.directoryName}>
      <div className={s.dirCell} role="cell">
        <span className={s.dirIcon}><FolderIcon size={15} /></span>
        <span className={s.dir} title={e.directoryName}>{e.directoryName}</span>
        {!e.directoryExists && <span className={s.missing}>folder missing</span>}
      </div>
      <div className={s.actions} role="cell"><RowButtons e={e} actions={actions} /></div>
      <div className={s.num} role="cell">{e.seasonCount}</div>
      <div className={s.num} role="cell">{e.episodeCount}</div>
      <div className={s.td} role="cell"><MonitorBadge state={monitorState(m)} /></div>
      <div className={s.source} role="cell">{m ? sourceLabel(m.source) : '-'}</div>
      <div className={s.checked} role="cell">{formatDateTime(m?.lastCheckedAt)}</div>
    </div>
  );
}

function LibraryCard({ e, actions }: { e: LibraryEntry; actions: RowActions }) {
  const m = e.monitored;
  return (
    <div className={s.card} data-testid="library-row" data-directory={e.directoryName}>
      <div className={s.cardHead}>
        <span className={s.dirIcon}><FolderIcon size={15} /></span>
        <span className={s.dir}>{e.directoryName}</span>
      </div>
      <div className={s.cardMeta}>
        <MonitorBadge state={monitorState(m)} />
        <span className="mono">{e.seasonCount}S · {e.episodeCount}E</span>
        {m && <span>{sourceLabel(m.source)}</span>}
        {m && <span className="mono">{formatDateTime(m.lastCheckedAt)}</span>}
        {!e.directoryExists && <span className={s.missing}>folder missing</span>}
      </div>
      <div className={s.cardActions}><RowButtons e={e} actions={actions} /></div>
    </div>
  );
}
