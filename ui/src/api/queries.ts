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

/** The download volume with the least free space (paths may share one volume). */
export function tightestDisk(disks: Disk[] | undefined): Disk | undefined {
  return disks
    ?.filter((d) => d.freeBytes != null)
    .reduce<Disk | undefined>((min, d) => (!min || d.freeBytes! < min.freeBytes! ? d : min), undefined);
}
