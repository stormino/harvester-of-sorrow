import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo } from 'react';
import { api } from '../api/client';
import type { WatchlistItem, WatchlistRequest } from '../api/types';
import { useToast } from '../components/Toast';
import type { TitleGroup } from './titles';
import { requestFromGroup, requestFromItem } from './watchlist';

export const WATCHLIST_KEY = ['watchlist'] as const;

/** The user's watchlist and actions on it; every change shows a toast with Undo. */
export function useWatchlist() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const list = useQuery({ queryKey: WATCHLIST_KEY, queryFn: api.watchlist, staleTime: 60_000 });
  const byKey = useMemo(() => new Map((list.data ?? []).map((i) => [i.titleKey, i])), [list.data]);

  const setList = (f: (xs: WatchlistItem[]) => WatchlistItem[]) =>
    qc.setQueryData<WatchlistItem[]>(WATCHLIST_KEY, (xs) => f(xs ?? []));

  const add = async (req: WatchlistRequest, announce: boolean) => {
    try {
      const item = await api.addToWatchlist(req);
      setList((xs) => [item, ...xs.filter((x) => x.id !== item.id)]);
      if (announce) {
        toast(`${item.title} saved to your watchlist`, 'success',
          { action: { label: 'Undo', onClick: () => void remove(item, false) } });
      }
    } catch (e) {
      toast(`Could not save ${req.title}: ${(e as Error).message}`, 'error');
    }
  };

  const remove = async (item: WatchlistItem, announce: boolean) => {
    setList((xs) => xs.filter((x) => x.id !== item.id));
    try {
      await api.removeFromWatchlist(item.id);
      if (announce) {
        toast(`${item.title} removed from your watchlist`, 'contrast',
          { action: { label: 'Undo', onClick: () => void add(requestFromItem(item), false) } });
      }
    } catch (e) {
      void qc.invalidateQueries({ queryKey: WATCHLIST_KEY });
      toast(`Could not remove ${item.title}: ${(e as Error).message}`, 'error');
    }
  };

  return {
    items: list.data ?? [],
    isLoading: list.isLoading,
    error: list.error as Error | null,
    saved: (key: string) => byKey.get(key),
    /** Saves a search result, or removes it when it is already saved. */
    toggle: (g: TitleGroup) => {
      const existing = byKey.get(g.key);
      return existing ? remove(existing, true) : add(requestFromGroup(g), true);
    },
    remove: (item: WatchlistItem) => remove(item, true),
    /** Quietly stores what a fresh look at a saved title found (no toast, errors ignored). */
    refresh: async (g: TitleGroup) => {
      try {
        const item = await api.addToWatchlist(requestFromGroup(g));
        setList((xs) => xs.map((x) => (x.id === item.id ? item : x)));
      } catch {
        // the saved entry stays as it was
      }
    },
  };
}
