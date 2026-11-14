import { useSyncExternalStore } from 'react';

export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (cb) => {
      const mq = window.matchMedia(query);
      mq.addEventListener('change', cb);
      return () => mq.removeEventListener('change', cb);
    },
    () => window.matchMedia(query).matches,
    () => false,
  );
}

/** Below 768px the sidebar becomes a drawer + bottom tab bar and tables become cards. */
export const useIsMobile = () => useMediaQuery('(max-width: 767px)');
