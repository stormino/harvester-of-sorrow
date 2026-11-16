import { useMutation } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { api } from '../api/client';
import { tightestDisk, useSystem } from '../api/queries';
import type { SubTask, Task } from '../api/types';
import { KindChip, SourcePill } from '../components/Badge';
import { Button } from '../components/Button';
import { Dialog } from '../components/Dialog';
import { AlertIcon, BoltIcon, ChevronIcon, CloseIcon, DiskIcon, InfoIcon, RetryIcon, TrashIcon } from '../components/Icons';
import { ProgressBar } from '../components/ProgressBar';
import { TaskStatusBadge } from '../components/StatusBadge';
import { useToast } from '../components/Toast';
import { formatBytes, formatEta, formatPercent, formatSpeed, formatTime } from '../lib/format';
import { countTasks, isIndeterminate, overallSpeed, sortTasks, statusLine } from '../lib/tasks';
import { useIsMobile } from '../lib/useMediaQuery';
import { useTaskStream } from '../live/TaskStream';
import base from './Screen.module.css';
import s from './DownloadsScreen.module.css';

const barColor = (t: Pick<Task, 'status'>) =>
  t.status === 'COMPLETED' ? 'var(--success)'
    : t.status === 'FAILED' || t.status === 'CANCELLED' ? 'var(--error)' : 'var(--brand)';

const KIND: Record<SubTask['type'], string> = { VIDEO: 'video', AUDIO: 'audio', SUBTITLE: 'sub' };

interface Actions {
  cancel: (t: Task) => void;
  retry: (t: Task) => void;
  info: (t: Task) => void;
  busy: Set<string>;
}

export function DownloadsScreen() {
  const { tasks, ready, connection, upsert } = useTaskStream();
  const { data: system } = useSystem();
  const { toast } = useToast();
  const isMobile = useIsMobile();
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const [infoTask, setInfoTask] = useState<Task | null>(null);

  const sorted = useMemo(() => sortTasks(tasks), [tasks]);
  const counts = useMemo(() => countTasks(tasks), [tasks]);
  const speed = overallSpeed(tasks);
  const disk = tightestDisk(system?.disks);

  const withBusy = async (id: string, fn: () => Promise<Task>, ok: string) => {
    setBusy((b) => new Set(b).add(id));
    try {
      upsert([await fn()]);
      toast(ok, 'contrast');
    } catch (e) {
      toast((e as Error).message, 'error');
    } finally {
      setBusy((b) => {
        const n = new Set(b);
        n.delete(id);
        return n;
      });
    }
  };

  const actions: Actions = {
    cancel: (t) => withBusy(t.id, () => api.cancel(t.id), 'Download cancelled'),
    retry: (t) => withBusy(t.id, () => api.retry(t.id), 'Retrying download...'),
    info: setInfoTask,
    busy,
  };

  const clear = useMutation({
    mutationFn: api.clearFinished,
    onSuccess: (r) => toast(r.removed > 0
      ? `Cleared ${r.removed} finished download${r.removed > 1 ? 's' : ''}` : 'Nothing to clear', 'contrast'),
    onError: (e) => toast((e as Error).message, 'error'),
  });

  const toggle = (id: string) => setExpanded((x) => {
    const n = new Set(x);
    if (n.has(id)) n.delete(id); else n.add(id);
    return n;
  });

  const empty = (
    <div className={base.empty}>
      {!ready ? (connection === 'disconnected' ? 'Cannot reach the server, retrying...' : 'Loading...') : 'No downloads'}
    </div>
  );

  return (
    <div className={base.screen}>
      <div className={`${base.head} ${s.head}`}>
        <div>
          <h2 className={base.title}>Downloads</h2>
          <div className={s.stats}>
            <span className={s.statusLine} data-testid="queue-status-line">{statusLine(counts)}</span>
            {speed > 0 && (
              <span className={s.speed}>
                <BoltIcon size={15} /><span className={s.speedLabel}>Overall speed</span>
                <span className={s.mono}>{formatSpeed(speed)}</span>
              </span>
            )}
            {disk?.freeBytes != null && (
              <span className={s.disk} title={disk.path}>
                <DiskIcon size={14} />Free disk space <span className={s.monoStrong}>{formatBytes(disk.freeBytes)}</span>
              </span>
            )}
          </div>
        </div>
        <Button id="clear-completed-button" variant="danger" size="md" icon={<TrashIcon size={15} />}
          loading={clear.isPending} onClick={() => clear.mutate()}>
          Clear Completed
        </Button>
      </div>

      {isMobile ? (
        <div id="queue-grid" className={s.cards}>
          {sorted.length === 0 ? empty : sorted.map((t) => (
            <TaskCard key={t.id} t={t} actions={actions} expanded={expanded.has(t.id)} onToggle={() => toggle(t.id)} />
          ))}
        </div>
      ) : (
        <div id="queue-grid" className={s.table} role="table" aria-label="Download queue">
          <div className={s.scroll}>
            <div className={s.inner}>
              <div className={`${s.cols} ${s.headRow}`} role="row">
                {['Title', 'Status', 'Progress', 'Downloaded', 'Speed', 'ETA', 'Created'].map((h) => (
                  <div key={h} className={s.th} role="columnheader">{h}</div>
                ))}
                <div className={`${s.th} ${s.right}`} role="columnheader">Actions</div>
              </div>
              {sorted.length === 0 ? empty : sorted.map((t) => (
                <TaskRow key={t.id} t={t} actions={actions} expanded={expanded.has(t.id)} onToggle={() => toggle(t.id)} />
              ))}
            </div>
          </div>
        </div>
      )}

      <Dialog open={!!infoTask} title="Error details" onClose={() => setInfoTask(null)} width={460}>
        {infoTask && (
          <div className={s.errorBox}>
            <div className={s.errorIcon}><AlertIcon size={19} /></div>
            <div>
              <div className={s.errorHead}>{infoTask.displayName}</div>
              <p className={s.errorMsg}>{infoTask.errorMessage || 'No additional details.'}</p>
              {infoTask.outputPath && <div className={s.path}>{infoTask.outputPath}</div>}
            </div>
          </div>
        )}
      </Dialog>
    </div>
  );
}

function ActionButtons({ t, actions, card }: { t: Task; actions: Actions; card?: boolean }) {
  const busy = actions.busy.has(t.id);
  const size = card ? 'md' : 'sm';
  return (
    <>
      {t.errorMessage && (t.status === 'FAILED' || t.status === 'NOT_FOUND') && (
        <Button variant={card ? 'outline' : 'ghost'} size={size} iconOnly icon={<InfoIcon size={15} />}
          title="Error details" aria-label="Error details" onClick={() => actions.info(t)} />
      )}
      {t.canRetry && (
        <Button variant="success" size={size} iconOnly icon={<RetryIcon size={15} />} title="Retry" aria-label="Retry"
          loading={busy} onClick={() => actions.retry(t)} data-testid="queue-row-retry" data-task-id={t.id} />
      )}
      {t.canCancel && (
        <Button variant="danger" size={size} iconOnly icon={<CloseIcon size={15} />} title="Cancel" aria-label="Cancel"
          loading={busy} onClick={() => actions.cancel(t)} data-testid="queue-row-cancel" data-task-id={t.id} />
      )}
    </>
  );
}

function TaskRow({ t, actions, expanded, onToggle }: { t: Task; actions: Actions; expanded: boolean; onToggle: () => void }) {
  const indet = isIndeterminate(t);
  const hasTracks = t.subTasks.length > 0;
  return (
    <div className={s.group} role="rowgroup" data-testid="queue-row" data-task-id={t.id}>
      <div className={`${s.cols} ${s.row}`} role="row">
        <div className={s.titleCell} role="cell">
          <button type="button" className={`${s.chevron} ${expanded ? s.open : ''}`} onClick={onToggle}
            disabled={!hasTracks} aria-expanded={expanded} aria-label={expanded ? 'Hide tracks' : 'Show tracks'}>
            <ChevronIcon size={14} />
          </button>
          <SourcePill source={t.source} />
          <div className={s.nameCol}>
            <span className={s.name} title={t.outputPath ?? t.displayName}>{t.displayName}</span>
            {t.statusMessage && <span className={s.waiting} data-testid="queue-row-message">{t.statusMessage}</span>}
          </div>
        </div>
        <div className={s.td} role="cell">
          <TaskStatusBadge status={t.status} taskId={t.id} testId="queue-row-status" />
        </div>
        <div className={s.td} role="cell">
          <ProgressBar value={t.progress} indeterminate={indet} color={barColor(t)} label={`${t.displayName} progress`} />
          <div className={`${s.pct} ${indet ? s.pctDim : ''}`}>{indet ? 'working...' : formatPercent(t.progress)}</div>
        </div>
        <div className={`${s.td} ${s.cellMono}`} role="cell">{formatBytes(t.downloadedBytes)}</div>
        <div className={`${s.td} ${s.cellMono} ${t.speedBps ? s.speedOn : s.speedOff}`} role="cell">{formatSpeed(t.speedBps)}</div>
        <div className={`${s.td} ${s.cellMono}`} role="cell">{formatEta(t.etaSeconds)}</div>
        <div className={`${s.td} ${s.cellDim}`} role="cell" title={t.createdAt}>{formatTime(t.createdAt)}</div>
        <div className={s.actions} role="cell"><ActionButtons t={t} actions={actions} /></div>
      </div>
      {expanded && hasTracks && (
        <div className={s.tracks}>
          {t.subTasks.map((st) => (
            <div key={st.id} className={s.track}>
              <div className={s.trackName}>
                <KindChip>{KIND[st.type]}</KindChip>
                <span className={s.trackLabel}>{st.displayName}</span>
              </div>
              <TaskStatusBadge status={st.status} />
              <span className={s.cellDim}>{formatBytes(st.totalBytes ?? st.downloadedBytes)}</span>
              <div className={s.trackBar}>
                <ProgressBar value={st.progress} height={4} color={barColor(st)} indeterminate={isIndeterminate(st)} />
                <span className={s.trackPct}>{Math.round(st.progress)}%</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TaskCard({ t, actions, expanded, onToggle }: { t: Task; actions: Actions; expanded: boolean; onToggle: () => void }) {
  const indet = isIndeterminate(t);
  const hasTracks = t.subTasks.length > 0;
  return (
    <div className={s.card} data-testid="queue-row" data-task-id={t.id}>
      <div className={s.cardTop}>
        <div className={s.cardMain}>
          <div className={s.cardTitle}>
            <SourcePill source={t.source} />
            <span className={s.cardName} title={t.displayName}>{t.displayName}</span>
          </div>
          <div className={s.cardStatus}>
            <TaskStatusBadge status={t.status} taskId={t.id} testId="queue-row-status" />
            <span className={s.cellDim}>{formatTime(t.createdAt)}</span>
          </div>
          {t.statusMessage && <div className={s.waiting} data-testid="queue-row-message">{t.statusMessage}</div>}
        </div>
        <div className={s.cardActions}><ActionButtons t={t} actions={actions} card /></div>
      </div>
      <div className={s.cardProgress}>
        <ProgressBar value={t.progress} indeterminate={indet} color={barColor(t)} height={8} />
        <span className={s.cardPct}>{indet ? '…' : formatPercent(t.progress)}</span>
      </div>
      <div className={s.cardStats}>
        <span>{formatBytes(t.downloadedBytes)} / {formatBytes(t.totalBytes)}</span>
        <span className={t.speedBps ? s.speedOn : ''}>{formatSpeed(t.speedBps)}</span>
        <span>ETA {formatEta(t.etaSeconds)}</span>
        {hasTracks && (
          <button type="button" className={`${s.chevron} ${expanded ? s.open : ''}`} onClick={onToggle}
            aria-expanded={expanded} aria-label={expanded ? 'Hide tracks' : 'Show tracks'}>
            <ChevronIcon size={14} />
          </button>
        )}
      </div>
      {expanded && hasTracks && (
        <div className={s.cardTracks}>
          {t.subTasks.map((st) => (
            <div key={st.id} className={s.cardTrack}>
              <div className={s.trackName}><KindChip>{KIND[st.type]}</KindChip><span className={s.trackLabel}>{st.displayName}</span></div>
              <ProgressBar value={st.progress} height={4} color={barColor(st)} />
              <span className={s.trackPct}>{Math.round(st.progress)}%</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
