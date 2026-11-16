import type { TaskStatus } from '../api/types';
import type { ConnectionState } from '../live/TaskStream';
import s from './StatusBadge.module.css';

const TASK: Record<TaskStatus, { label: string; tone: string }> = {
  QUEUED: { label: 'Queued', tone: 'brand' },
  EXTRACTING: { label: 'Extracting', tone: 'brand' },
  DOWNLOADING: { label: 'Downloading', tone: 'down' },
  UNPACKING: { label: 'Unpacking', tone: 'brand' },
  MERGING: { label: 'Merging', tone: 'brand' },
  COPYING: { label: 'Copying', tone: 'brand' },
  COMPLETED: { label: 'Completed', tone: 'success' },
  FAILED: { label: 'Failed', tone: 'error' },
  CANCELLED: { label: 'Cancelled', tone: 'error' },
  NOT_FOUND: { label: 'Not found', tone: 'warning' },
};

const ANIMATED: TaskStatus[] = ['QUEUED', 'EXTRACTING', 'DOWNLOADING', 'UNPACKING', 'MERGING', 'COPYING'];

export function statusLabel(status: TaskStatus): string {
  return TASK[status]?.label ?? status;
}

/** Download task status; carries data-status for tests. */
export function TaskStatusBadge({ status, taskId, testId }: { status: TaskStatus; taskId?: string; testId?: string }) {
  const m = TASK[status] ?? { label: status, tone: 'muted' };
  return (
    <span className={`${s.badge} ${s[m.tone]}`} data-testid={testId} data-status={status} data-task-id={taskId}>
      <span className={`${s.dot} ${ANIMATED.includes(status) ? s.pulse : ''}`} />
      {m.label}
    </span>
  );
}

export type MonitorState = 'monitoring' | 'paused' | 'none';

const MONITOR: Record<MonitorState, { label: string; tone: string }> = {
  monitoring: { label: 'Monitoring', tone: 'success' },
  paused: { label: 'Paused', tone: 'muted' },
  none: { label: 'Not monitored', tone: 'plain' },
};

export function MonitorBadge({ state }: { state: MonitorState }) {
  const m = MONITOR[state];
  return <span className={`${s.badge} ${s[m.tone]}`} data-status={state}>{m.label}</span>;
}

const CONN: Record<ConnectionState, { label: string; color: string }> = {
  connected: { label: 'connected', color: 'var(--success)' },
  connecting: { label: 'connecting', color: 'var(--warning)' },
  disconnected: { label: 'reconnecting', color: 'var(--error)' },
};

export function ConnectionPill({ state }: { state: ConnectionState }) {
  const c = CONN[state];
  return (
    <span className={s.pill} style={{ ['--c' as string]: c.color }} data-testid="connection-status" data-state={state}
      role="status" aria-live="polite">
      <span className={`${s.pillDot} ${state !== 'connected' ? s.pulse : ''}`} />
      {c.label}
    </span>
  );
}

/** Marks a library show that TMDB reports as Ended / Canceled. */
export function CompletedBadge({ status }: { status?: string }) {
  return (
    <span className={`${s.badge} ${s.muted}`} data-testid="completed-badge"
      title={status ? `TMDB status: ${status}. Not checked for new episodes automatically.` : undefined}>
      Completed
    </span>
  );
}
