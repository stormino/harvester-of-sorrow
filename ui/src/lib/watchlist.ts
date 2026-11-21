import type { SearchResult, WatchlistItem, WatchlistRequest, XdccTitle } from '../api/types';
import { mergeTitles, normalize, type TitleGroup } from './titles';

/** What the server stores for a title found by search. */
export function requestFromGroup(g: TitleGroup): WatchlistRequest {
  return {
    titleKey: g.key,
    contentType: g.contentType,
    tmdbId: g.tmdbId,
    title: g.title,
    year: g.year,
    overview: g.overview,
    rating: g.rating,
    posterUrl: g.posterUrl,
    externalUrl: g.externalUrl,
    streams: g.streams,
  };
}

/** Saving a removed item again (Undo). */
export function requestFromItem(i: WatchlistItem): WatchlistRequest {
  return {
    titleKey: i.titleKey, contentType: i.contentType, tmdbId: i.tmdbId, title: i.title, year: i.year,
    overview: i.overview, rating: i.rating, posterUrl: i.posterUrl, externalUrl: i.externalUrl, streams: i.streams,
  };
}

/**
 * The title panel's view of a saved title: where it can be downloaded now. {@code streams} and
 * {@code xdcc} are a fresh search by title (matched the same way as on the search page); until the
 * streaming search is back, the saved streaming results stand in.
 */
export function groupFromItem(i: WatchlistItem, xdcc: XdccTitle[] = [], streams?: SearchResult[]): TitleGroup {
  const groups = mergeTitles(streams ?? i.streams, xdcc, i.title);
  const match = groups.find((g) => g.key === i.titleKey)
    ?? groups.find((g) => g.contentType === i.contentType && i.tmdbId != null && g.tmdbId === i.tmdbId)
    ?? groups.find((g) => g.contentType === i.contentType && normalize(g.title) === normalize(i.title)
      && (g.year == null || i.year == null || Math.abs(g.year - i.year) <= 1));
  const base: TitleGroup = {
    key: i.titleKey, title: i.title, year: i.year, contentType: i.contentType, tmdbId: i.tmdbId,
    posterUrl: i.posterUrl, overview: i.overview, rating: i.rating, externalUrl: i.externalUrl,
    streams: i.streams, xdcc: [], xdccRelevance: -1, names: [i.title], relevance: 0,
  };
  if (!match) return { ...base, streams: streams ? [] : i.streams };
  // Saved details first; the fresh match fills the gaps and says where to download from now.
  return {
    ...base,
    tmdbId: base.tmdbId ?? match.tmdbId,
    year: base.year ?? match.year,
    overview: base.overview ?? match.overview,
    rating: base.rating ?? match.rating,
    posterUrl: base.posterUrl ?? match.posterUrl,
    externalUrl: base.externalUrl ?? match.externalUrl,
    localTitle: match.localTitle,
    streams: match.streams,
    xdcc: match.xdcc,
  };
}
