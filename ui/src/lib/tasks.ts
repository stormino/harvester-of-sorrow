import type { Task, TaskStatus } from '../api/types';
import { pad2 } from './format';

export const ACTIVE: readonly TaskStatus[] = ['DOWNLOADING', 'UNPACKING', 'MERGING', 'COPYING', 'EXTRACTING'];
export const FAILED_LIKE: readonly TaskStatus[] = ['FAILED', 'CANCELLED', 'NOT_FOUND'];

export const isActive = (s: TaskStatus) => ACTIVE.includes(s);

/** Active first, then queued, then the rest; alphabetical within each group. */
export function sortTasks(tasks: Task[]): Task[] {
  const rank = (s: TaskStatus) => (isActive(s) ? 0 : s === 'QUEUED' ? 1 : 2);
  return tasks.slice().sort((a, b) =>
    rank(a.status) - rank(b.status) || a.displayName.localeCompare(b.displayName));
}

export interface TaskCounts { active: number; queued: number; completed: number; failed: number }

export function countTasks(tasks: Task[]): TaskCounts {
  const c: TaskCounts = { active: 0, queued: 0, completed: 0, failed: 0 };
  for (const t of tasks) {
    if (isActive(t.status)) c.active++;
    else if (t.status === 'QUEUED') c.queued++;
    else if (t.status === 'COMPLETED') c.completed++;
    else if (FAILED_LIKE.includes(t.status)) c.failed++;
  }
  return c;
}

/** "3 active • 5 queued • 12 completed • 1 failed", only non-zero parts. */
export function statusLine(c: TaskCounts): string {
  const parts: string[] = [];
  if (c.active) parts.push(`${c.active} active`);
  if (c.queued) parts.push(`${c.queued} queued`);
  if (c.completed) parts.push(`${c.completed} completed`);
  if (c.failed) parts.push(`${c.failed} failed`);
  return parts.length ? parts.join('  •  ') : 'No downloads';
}

/** Sum of numeric per-task speeds of downloading tasks, in bytes per second. */
export function overallSpeed(tasks: Task[]): number {
  return tasks.reduce((sum, t) => sum + (t.status === 'DOWNLOADING' && t.speedBps ? t.speedBps : 0), 0);
}

/** Indeterminate bar while extracting and while downloading has not progressed yet. */
export function isIndeterminate(t: Pick<Task, 'status' | 'progress'>): boolean {
  return t.status === 'EXTRACTING' || (t.status === 'DOWNLOADING' && (t.progress ?? 0) <= 0);
}

export function episodeLabel(season: number, episode: number): string {
  return `S${pad2(season)}E${pad2(episode)}`;
}
