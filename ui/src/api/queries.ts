import { useQuery } from '@tanstack/react-query';
import { api } from './client';
import type { Disk } from './types';

export const keys = {
  system: ['system'] as const,
  settings: ['settings'] as const,
  sources: ['sources'] as const,
  library: ['library'] as const,
};

export const useSystem = () =>
  useQuery({ queryKey: keys.system, queryFn: api.system, refetchInterval: 30_000, staleTime: 10_000 });

export const useSettings = () => useQuery({ queryKey: keys.settings, queryFn: api.settings, staleTime: Infinity });

export const useSources = () => useQuery({ queryKey: keys.sources, queryFn: api.sources, staleTime: Infinity });

export const useLibrary = () => useQuery({ queryKey: keys.library, queryFn: api.library });

/**
 * Free space of the media library: the movies/TV volume with the least free space.
 * The temp path is left out; it is often a separate local disk (Settings lists it).
 */
export function tightestDisk(disks: Disk[] | undefined): Disk | undefined {
  return disks
    ?.filter((d) => d.freeBytes != null && d.label !== 'temp')
    .reduce<Disk | undefined>((min, d) => (!min || d.freeBytes! < min.freeBytes! ? d : min), undefined);
}
