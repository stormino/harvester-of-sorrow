import { describe, expect, it } from 'vitest';
import { formatBytes, formatDateTime, formatEta, formatPercent, formatSpeed, formatTime } from './format';
import { countTasks, isIndeterminate, overallSpeed, sortTasks, statusLine } from './tasks';
import type { Task } from '../api/types';

describe('formatters', () => {
  it('bytes', () => {
    expect(formatBytes(undefined)).toBe('—');
    expect(formatBytes(0)).toBe('—');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(87_000)).toBe('87 KB');
    expect(formatBytes(512_000_000)).toBe('512 MB');
    expect(formatBytes(1_240_000_000)).toBe('1.24 GB');
    expect(formatBytes(2_000_000_000_000)).toBe('2.00 TB');
  });

  it('speed', () => {
    expect(formatSpeed(null)).toBe('—');
    expect(formatSpeed(5_200_000)).toBe('5.2 MB/s');
    expect(formatSpeed(640_000)).toBe('640 KB/s');
  });

  it('eta', () => {
    expect(formatEta(0)).toBe('—');
    expect(formatEta(45)).toBe('45s');
    expect(formatEta(750)).toBe('12m 30s');
    expect(formatEta(7500)).toBe('2h 5m');
  });

  it('percent', () => {
    expect(formatPercent(41.83)).toBe('41.8%');
    expect(formatPercent(120)).toBe('100.0%');
    expect(formatPercent(undefined)).toBe('0.0%');
  });

  it('time uses the browser zone and adds the date on other days', () => {
    const now = new Date(2026, 5, 20, 12, 0, 0);
    expect(formatTime(new Date(2026, 5, 20, 9, 14, 2).toISOString(), now)).toBe('09:14:02');
    expect(formatTime(new Date(2026, 5, 19, 23, 1, 0).toISOString(), now)).toBe('06-19 23:01:00');
    expect(formatTime(undefined, now)).toBe('—');
    expect(formatDateTime(new Date(2026, 5, 20, 8, 30).toISOString())).toBe('2026-06-20 08:30');
    expect(formatDateTime(undefined)).toBe('-');
  });
});

const t = (id: string, status: Task['status'], over: Partial<Task> = {}): Task => ({
  id, source: 'VIXSRC', contentType: 'MOVIE', displayName: id, languages: [], status, progress: 0,
  createdAt: '2026-01-01T00:00:00Z', canCancel: true, canRetry: false, subTasks: [], ...over,
});

describe('task helpers', () => {
  it('sorts active, then queued, then the rest, alphabetically', () => {
    const sorted = sortTasks([t('zz', 'COMPLETED'), t('b', 'QUEUED'), t('y', 'DOWNLOADING'), t('a', 'FAILED'), t('x', 'EXTRACTING')]);
    expect(sorted.map((x) => x.id)).toEqual(['x', 'y', 'b', 'a', 'zz']);
  });

  it('counts and status line', () => {
    const c = countTasks([t('1', 'DOWNLOADING'), t('2', 'QUEUED'), t('3', 'QUEUED'), t('4', 'NOT_FOUND')]);
    expect(c).toEqual({ active: 1, queued: 2, completed: 0, failed: 1 });
    expect(statusLine(c)).toBe('1 active  •  2 queued  •  1 failed');
    expect(statusLine(countTasks([]))).toBe('No downloads');
  });

  it('overall speed adds numbers, not strings', () => {
    expect(overallSpeed([t('1', 'DOWNLOADING', { speedBps: 1e6 }), t('2', 'DOWNLOADING', { speedBps: 2.5e6 }),
      t('3', 'COMPLETED', { speedBps: 9e9 })])).toBe(3.5e6);
  });

  it('indeterminate while extracting or at 0% downloading', () => {
    expect(isIndeterminate({ status: 'EXTRACTING', progress: 0 })).toBe(true);
    expect(isIndeterminate({ status: 'DOWNLOADING', progress: 0 })).toBe(true);
    expect(isIndeterminate({ status: 'DOWNLOADING', progress: 3 })).toBe(false);
    expect(isIndeterminate({ status: 'QUEUED', progress: 0 })).toBe(false);
  });
});
