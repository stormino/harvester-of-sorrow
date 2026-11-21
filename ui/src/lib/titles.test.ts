import { describe, expect, it } from 'vitest';
import type { SearchResult, XdccRelease, XdccTitle } from '../api/types';
import { mergeTitles, seasonOf, xdccAltNames } from './titles';

const res = (p: Partial<SearchResult>): SearchResult => ({
  source: 'VIXSRC', contentType: 'MOVIE', title: 'X', seasons: [], sourceRef: p.title ?? 'x', ...p,
} as SearchResult);
const rel = (resolution: number, extra: Partial<XdccRelease> = {}): XdccRelease => ({
  label: `r${resolution}`, quality: { resolution, languages: ['ITA'], subtitles: [], lowQuality: false },
  format: 'mkv', bots: 1, networks: [], urls: [`xdcc://${resolution}`], complete: true, missingParts: [], ...extra,
} as XdccRelease);
const xt = (p: Partial<XdccTitle>): XdccTitle => ({ title: 'X', contentType: 'MOVIE', relevance: 2, releases: [], ...p } as XdccTitle);

describe('mergeTitles', () => {
  it('merges by TMDB id and puts VixSrc titles first', () => {
    const groups = mergeTitles(
      [res({ source: 'RAIPLAY', title: 'Only Rai' }), res({ title: 'Dune', tmdbId: 1, year: 2021 })],
      [xt({ title: 'dune', tmdbId: 1, releases: [rel(720), rel(1080)] }), xt({ title: 'Other', relevance: 0, releases: [rel(720)] })],
    );
    expect(groups.map((g) => g.title)).toEqual(['Dune', 'Only Rai', 'Other']);
    expect(groups[0].xdcc.map((r) => r.quality.resolution)).toEqual([1080, 720]);
  });

  it('matches RaiPlay without TMDB id by name and year, keeping VixSrc first', () => {
    const [g] = mergeTitles(
      [res({ source: 'RAIPLAY', title: 'Il Commissario Montalbano', contentType: 'TV' }),
        res({ title: 'Il commissario Montalbano', tmdbId: 9, contentType: 'TV', year: 1999 })],
      [],
    );
    expect(g.streams.map((r) => r.source)).toEqual(['VIXSRC', 'RAIPLAY']);
  });

  it('sends XDCC releases of a remake to the work of their year, and ranks by query match', () => {
    const groups = mergeTitles(
      [res({ title: 'Dune', tmdbId: 1, year: 2021 }), res({ title: 'Anatomy of a Fall', tmdbId: 3 }),
        res({ title: 'Dune', tmdbId: 2, year: 1984 })],
      [xt({ title: 'Dune', tmdbId: 1, year: 2021, releases: [
        rel(1080, { label: 'Dune.2021.1080p.mkv', urls: ['a'] }), rel(1080, { label: 'Dune.1984.1080p.mkv', urls: ['b'] })] })],
      'dune',
    );
    expect(groups.map((g) => `${g.title} ${g.year ?? ''}`.trim())).toEqual(['Dune 2021', 'Dune 1984', 'Anatomy of a Fall']);
    expect(groups[0].xdcc.map((r) => r.urls[0])).toEqual(['a']);
    expect(groups[1].xdcc.map((r) => r.urls[0])).toEqual(['b']);
  });

  it('keeps a film and a show with the same name apart', () => {
    const groups = mergeTitles([res({ title: 'Fargo', tmdbId: 1 }), res({ title: 'Fargo', tmdbId: 2, contentType: 'TV' })], []);
    expect(groups).toHaveLength(2);
  });
});

it('reads the season of an episode tag', () => {
  expect(seasonOf('S02E05')).toBe(2);
  expect(seasonOf(undefined)).toBeUndefined();
});

describe('xdccAltNames', () => {
  const vix = (title: string, localTitle?: string): SearchResult => ({
    source: 'VIXSRC', contentType: 'MOVIE', title, localTitle, seasons: [], sourceRef: 'x',
  } as SearchResult);

  it('searches XDCC by the English name of an Italian query, and the other way round', () => {
    const results = [vix('Curious George', 'Curioso come George'), vix('Curious George 2: Follow That Monkey!', 'Curioso come George 2')];
    expect(xdccAltNames(results, 'Curioso come George')).toEqual(['Curious George']);
    expect(xdccAltNames(results, 'curious george')).toEqual(['Curioso come George']);
  });

  it('skips names the query already covers and caps the extra searches', () => {
    expect(xdccAltNames([vix('The Godfather', 'Il padrino'), vix('The Godfather Part II', 'Il padrino - Parte II'),
      vix('Gotti'), vix('Black Caesar')], 'Il padrino')).toEqual(['The Godfather', 'Gotti']);
    expect(xdccAltNames([{ ...vix('Dune'), source: 'RAIPLAY' }], 'duna')).toEqual([]);
  });
});

describe('mergeTitles with local titles', () => {
  it('dedupes a pack found by both names', () => {
    const r = { label: 'x.mkv', urls: ['xdcc://a'], quality: { languages: [], subtitles: [], hdr: false, lowQuality: false }, bots: 1, networks: [], format: 'mkv', complete: true, missingParts: [], parts: [] } as unknown as XdccRelease;
    const t = { title: 'Curious George', year: 2006, contentType: 'MOVIE', relevance: 3, tmdbId: 9975, releases: [r] } as XdccTitle;
    const s = { ...{ source: 'VIXSRC', contentType: 'MOVIE', title: 'Curious George', localTitle: 'Curioso come George', year: 2006, tmdbId: 9975, seasons: [], sourceRef: 'x' } } as SearchResult;
    const groups = mergeTitles([s], [t, { ...t }], 'Curioso come George');
    expect(groups[0].xdcc).toHaveLength(1);
    expect(groups[0].relevance).toBeGreaterThan(0);
  });
});
