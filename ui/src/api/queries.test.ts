import { describe, expect, it } from 'vitest';
import { tightestDisk } from './queries';

describe('tightestDisk', () => {
  it('ignores the temp volume and picks the fullest media volume', () => {
    const d = tightestDisk([
      { label: 'movies', path: '/m', freeBytes: 848e9, totalBytes: 2.7e12 },
      { label: 'tvshows', path: '/t', freeBytes: 848e9, totalBytes: 2.7e12 },
      { label: 'temp', path: '/tmp', freeBytes: 74e9, totalBytes: 152e9 },
    ]);
    expect(d?.freeBytes).toBe(848e9);
    expect(d?.label).toBe('movies');
  });
});
