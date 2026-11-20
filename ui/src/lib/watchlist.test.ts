import { describe, expect, it } from 'vitest';
import type { SearchResult, WatchlistItem, XdccTitle } from '../api/types';
import { savedAgo } from '../screens/WatchlistScreen';
import { mergeTitles } from './titles';
import { groupFromItem, requestFromGroup } from './watchlist';

const vixsrc: SearchResult = {
  source: 'VIXSRC', contentType: 'MOVIE', title: 'Perfect Days', year: 2023, tmdbId: 976893,
  overview: 'Hirayama is content with his life.', rating: 7.8, posterUrl: 'p.jpg', seasons: [],
  sourceRef: '{"type":"VIXSRC","tmdbId":976893}', externalUrl: 'https://www.themoviedb.org/movie/976893',
};

const xdccTitle = (title: string, year: number, tmdbId?: number): XdccTitle => ({
  title, year, tmdbId, contentType: 'MOVIE', relevance: 3,
  releases: [{ label: `${title}.${year}.iTA.1080p.mkv`, packs: [] }],
} as unknown as XdccTitle);

describe('watchlist', () => {
  it('saves what the search page shows and reopens it with the same key', () => {
    const [group] = mergeTitles([vixsrc], [], 'perfect days');
    const req = requestFromGroup(group);
    expect(req).toMatchObject({ titleKey: 'MOVIE:976893', title: 'Perfect Days', tmdbId: 976893, streams: [vixsrc] });

    const item = { ...req, id: '1', createdAt: '2026-11-20T10:00:00' } as WatchlistItem;
    const reopened = groupFromItem(item);
    expect(reopened.key).toBe(group.key);
    expect(reopened.streams).toEqual([vixsrc]);
    expect(reopened.xdcc).toEqual([]);
  });

  it('adds the XDCC releases found now, and only those of the same title', () => {
    const item = { ...requestFromGroup(mergeTitles([vixsrc], [], '')[0]), id: '1', createdAt: '' } as WatchlistItem;
    const g = groupFromItem(item, [xdccTitle('Perfect Days', 2023, 976893), xdccTitle('Perfect Days', 1996)]);
    expect(g.xdcc).toHaveLength(1);
    expect(g.xdcc[0].label).toContain('2023');
    expect(g.title).toBe('Perfect Days');
  });

  it('shows where a title saved without streams can be downloaded now', () => {
    // Saved from search before the VixSrc results had arrived: XDCC only, no plot
    const item = { titleKey: 'MOVIE:976893', contentType: 'MOVIE', tmdbId: 976893, title: 'Perfect Days', year: 2023,
      streams: [], id: '1', createdAt: '' } as unknown as WatchlistItem;
    expect(groupFromItem(item).streams).toEqual([]);
    const now = groupFromItem(item, [], [vixsrc]);
    expect(now.streams).toEqual([vixsrc]);
    expect(now.overview).toBe(vixsrc.overview);
    expect(requestFromGroup(now)).toMatchObject({ titleKey: 'MOVIE:976893', streams: [vixsrc] });
  });

  it('says how long ago a title was saved', () => {
    const now = new Date('2026-11-20T12:00:00');
    expect(savedAgo('2026-11-20T08:00:00', now)).toBe('today');
    expect(savedAgo('2026-11-19T08:00:00', now)).toBe('yesterday');
    expect(savedAgo('2026-11-15T12:00:00', now)).toBe('5 days ago');
    expect(savedAgo('2026-10-30T12:00:00', now)).toBe('3 weeks ago');
    expect(savedAgo('2026-06-20T12:00:00', now)).toBe('5 months ago');
  });
});
