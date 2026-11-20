import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { ContentType } from '../api/types';
import { BookmarkIcon, PlayIcon } from '../components/Icons';
import s from './TitleActions.module.css';

/** "Watch trailer" on YouTube (from TMDB); nothing when the title has none or no TMDB id. */
export function TrailerLink({ contentType, tmdbId, label = 'Watch trailer', className }: {
  contentType: ContentType; tmdbId?: number; label?: string; className?: string;
}) {
  const trailer = useQuery({
    queryKey: ['trailer', contentType, tmdbId],
    queryFn: () => api.trailer(contentType, tmdbId!),
    enabled: tmdbId != null,
    staleTime: Infinity,
    retry: false,
  });
  const url = trailer.data?.url;
  if (tmdbId == null || (!trailer.isLoading && !url)) return null;
  if (!url) {
    return <span className={`${s.trailer} ${s.pending} ${className ?? ''}`} aria-hidden="true"><PlayIcon size={12} />{label}</span>;
  }
  return (
    <a href={url} target="_blank" rel="noopener noreferrer" className={`${s.trailer} ${className ?? ''}`}
      onClick={(e) => e.stopPropagation()} data-testid="trailer-link">
      <PlayIcon size={12} />{label}
    </a>
  );
}

/** Bookmark toggle: outlined when not saved, filled when saved. */
export function SaveButton({ saved, title, onToggle, className, size = 17, labelled }: {
  saved: boolean; title: string; onToggle: () => void; className?: string; size?: number;
  /** Show "Watchlist" / "In watchlist" next to the icon. */
  labelled?: boolean;
}) {
  const label = saved ? `Remove ${title} from watchlist` : `Save ${title} to watchlist`;
  return (
    <button type="button" className={`${s.save} ${saved ? s.saved : ''} ${labelled ? s.labelled : ''} ${className ?? ''}`} aria-pressed={saved}
      aria-label={label} title={saved ? 'Remove from watchlist' : 'Save to watchlist'} data-testid="watchlist-toggle"
      onClick={(e) => { e.stopPropagation(); onToggle(); }}>
      <BookmarkIcon size={size} filled={saved} />
      {labelled && <span>{saved ? 'In watchlist' : 'Watchlist'}</span>}
    </button>
  );
}
