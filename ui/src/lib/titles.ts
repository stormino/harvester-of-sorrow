import type { ContentType, SearchResult, Source, XdccRelease, XdccTitle } from '../api/types';

/** One work (film or show) with every place it can be downloaded from. */
export interface TitleGroup {
  key: string;
  title: string;
  year?: number;
  contentType: ContentType;
  tmdbId?: number;
  posterUrl?: string;
  overview?: string;
  rating?: number;
  externalUrl?: string;
  /** Streaming sources, VixSrc first. */
  streams: SearchResult[];
  /** XDCC releases merged from every XDCC title matched to this work, best first. */
  xdcc: XdccRelease[];
  /** Highest XDCC relevance (3 best), -1 without XDCC. */
  xdccRelevance: number;
  /** Every name the sources gave this work (title, original title, TMDB title). */
  names: string[];
  /** How well the names match the query: 4 exact, 3 starts with it, 2 contains it, 1 has all words, 0 none. */
  relevance: number;
}

const SOURCE_RANK: Record<Source, number> = { VIXSRC: 0, RAIPLAY: 1, LINK: 2 };

export const normalize = (t: string) => t.toLowerCase()
  .normalize('NFD').replace(/[̀-ͯ]/g, '')
  .replace(/&/g, ' and ').replace(/[^a-z0-9]+/g, ' ').trim();

const yearsMatch = (a?: number, b?: number) => a == null || b == null || Math.abs(a - b) <= 1;

/**
 * Merges streaming results and XDCC titles into works. Matching is by TMDB id when both sides have
 * one, else by normalised title (and year, when both have it) within the same content type.
 */
export function mergeTitles(streams: SearchResult[], xdcc: XdccTitle[], query = ''): TitleGroup[] {
  const groups: TitleGroup[] = [];

  const find = (type: ContentType, tmdbId: number | undefined, title: string, year: number | undefined,
    alt?: string | (string | undefined)[], source?: Source) => {
    const names = [title, ...(Array.isArray(alt) ? alt : [alt])].filter((n): n is string => !!n).map(normalize);
    return groups.find((g) => g.contentType === type && !g.streams.some((x) => x.source === source) && (
      tmdbId != null && g.tmdbId != null
        ? g.tmdbId === tmdbId
        : names.includes(normalize(g.title)) && yearsMatch(g.year, year)));
  };

  for (const r of streams) {
    const g = find(r.contentType, r.tmdbId, r.title, r.year, [r.originalTitle, r.localTitle], r.source);
    if (g) {
      g.streams.push(r);
      g.tmdbId ??= r.tmdbId;
      g.posterUrl ??= r.posterUrl;
      g.overview ??= r.overview;
      g.rating ??= r.rating;
      g.year ??= r.year;
      g.names.push(r.title, r.originalTitle ?? '', r.localTitle ?? '');
      if (r.source === 'VIXSRC' || !g.externalUrl) g.externalUrl = r.externalUrl ?? g.externalUrl;
    } else {
      groups.push({
        key: r.tmdbId != null ? `${r.contentType}:${r.tmdbId}` : `${r.contentType}:${normalize(r.title)}:${r.year ?? ''}`,
        title: r.title, year: r.year, contentType: r.contentType, tmdbId: r.tmdbId, posterUrl: r.posterUrl,
        overview: r.overview, rating: r.rating, externalUrl: r.externalUrl, streams: [r], xdcc: [], xdccRelevance: -1,
        names: [r.title, r.originalTitle ?? '', r.localTitle ?? ''], relevance: 0,
      });
    }
  }

  for (const t of xdcc) {
    // XDCC titles group packs by name only: a remake's releases ("Dune 1984" vs "Dune 2021") can end up
    // together. Releases naming another year go to the work of that year.
    const byYear = new Map<number | undefined, XdccRelease[]>();
    for (const r of t.releases) {
      const y = releaseYear(r.label);
      const k = y != null && !yearsMatch(t.year, y) ? y : t.year;
      byYear.set(k, [...(byYear.get(k) ?? []), r]);
    }
    for (const [year, releases] of byYear) {
      const own = year === t.year;
      const tmdbId = own ? t.tmdbId : undefined;
      const g = find(t.contentType, tmdbId, t.title, year, t.tmdbTitle);
      if (g) {
        g.xdcc.push(...releases);
        g.xdccRelevance = Math.max(g.xdccRelevance, t.relevance);
        g.names.push(t.title, t.tmdbTitle ?? '');
        if (own) {
          g.posterUrl ??= t.posterUrl;
          g.tmdbId ??= t.tmdbId;
        }
      } else {
        groups.push({
          key: tmdbId != null ? `${t.contentType}:${tmdbId}` : `${t.contentType}:${normalize(t.title)}:${year ?? ''}:x`,
          title: (own && t.tmdbTitle) || t.title, year, contentType: t.contentType, tmdbId,
          posterUrl: own ? t.posterUrl : undefined, streams: [], xdcc: [...releases], xdccRelevance: t.relevance,
          names: [t.title, t.tmdbTitle ?? ''], relevance: 0,
          externalUrl: tmdbId != null
            ? `https://www.themoviedb.org/${t.contentType === 'MOVIE' ? 'movie' : 'tv'}/${tmdbId}` : undefined,
        });
      }
    }
  }

  for (const g of groups) {
    g.streams.sort((a, b) => SOURCE_RANK[a.source] - SOURCE_RANK[b.source]);
    // The same pack can come from the search by each of a title's names
    const packKey = (r: XdccRelease) => r.urls?.[0] ?? r.label;
    g.xdcc = g.xdcc.filter((r, i) => g.xdcc.findIndex((x) => packKey(x) === packKey(r)) === i);
    g.xdcc.sort(compareReleases);
  }

  for (const g of groups) g.relevance = Math.max(0, ...g.names.filter(Boolean).map((n) => textRelevance(n, query)));

  // Titles matching the query words and known to TMDB (or a streaming source) first; inside each band VixSrc, then RaiPlay, then XDCC-only;
  // then the closest name match; then the order the sources gave.
  const tier = (g: TitleGroup) => (g.streams.length ? SOURCE_RANK[g.streams[0].source] : 5);
  return groups.map((g, i) => ({ g, i }))
    .sort((a, b) => band(a.g) - band(b.g)
      || tier(a.g) - tier(b.g)
      || b.g.relevance - a.g.relevance
      || b.g.xdccRelevance - a.g.xdccRelevance
      || a.i - b.i)
    .map((x) => x.g);
}

/**
 * Other names to search XDCC by: release names use the language of the release, so "Curioso come George"
 * finds the Italian files and "Curious George" the English ones. Takes the Italian and English names of
 * the first VixSrc results, skipping names whose search the query (or a name already taken) covers.
 */
export function xdccAltNames(streams: SearchResult[], query: string, max = 2): string[] {
  const taken = [normalize(query)];
  const out: string[] = [];
  const covered = (n: string) => taken.some((t) => (` ${n} `).includes(` ${t} `));
  for (const r of streams.filter((x) => x.source === 'VIXSRC').slice(0, 4)) {
    for (const name of [r.localTitle, r.title]) {
      if (!name || out.length >= max) continue;
      const n = normalize(name);
      if (n.length < 3 || covered(n)) continue;
      taken.push(n);
      out.push(name);
    }
  }
  return out;
}

/** 0: matches the query and is a known work; 1: everything else (shown after a divider). */
export function band(g: TitleGroup): number {
  return g.relevance > 0 && (g.streams.length > 0 || g.tmdbId != null) ? 0 : 1;
}

/** 4 exact, 3 starts with the query, 2 contains it, 1 contains all its words, 0 otherwise. */
export function textRelevance(name: string, query: string): number {
  const n = normalize(name);
  const q = normalize(query);
  if (!q) return 1;
  if (n === q) return 4;
  if (n.startsWith(q + ' ') || n.startsWith(q)) return 3;
  if (` ${n} `.includes(` ${q} `)) return 2;
  const words = new Set(n.split(' '));
  return q.split(' ').every((w) => words.has(w)) ? 1 : 0;
}

const releaseYear = (label: string): number | undefined => {
  const m = label.match(/(?:^|[^0-9])((?:19|20)\d{2})(?:[^0-9p]|$)/);
  return m ? Number(m[1]) : undefined;
};

/** Complete before incomplete, normal before cam/telesync, then higher resolution, then more bots. */
export function compareReleases(a: XdccRelease, b: XdccRelease): number {
  return Number(b.complete) - Number(a.complete)
    || Number(a.quality.lowQuality) - Number(b.quality.lowQuality)
    || (b.quality.resolution ?? 0) - (a.quality.resolution ?? 0)
    || b.bots - a.bots;
}

/** Short description of the best XDCC release, e.g. "1080p · ITA". */
export function bestRelease(releases: XdccRelease[]): string {
  const r = releases.find((x) => x.complete && !x.quality.lowQuality) ?? releases[0];
  if (!r) return '';
  return [r.quality.resolution ? `${r.quality.resolution}p` : r.quality.source, r.quality.languages.join('/')]
    .filter(Boolean).join(' · ');
}

/** Season number from an episode tag like "S01E02" or "S02". */
export function seasonOf(episode?: string): number | undefined {
  const m = episode?.match(/S(\d{1,2})/i);
  return m ? Number(m[1]) : undefined;
}
