import type { SVGProps } from 'react';

type P = SVGProps<SVGSVGElement> & { size?: number };

const base = (size = 16, extra?: SVGProps<SVGSVGElement>) => ({
  width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor',
  strokeWidth: 2, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const,
  'aria-hidden': true, ...extra,
});

export const SearchIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>
);
export const AntennaIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M4.9 16.1a10 10 0 0 1 0-8.2" /><path d="M19.1 7.9a10 10 0 0 1 0 8.2" /><path d="M7.8 13.4a5 5 0 0 1 0-2.8" /><path d="M16.2 10.6a5 5 0 0 1 0 2.8" /><circle cx="12" cy="12" r="1.5" /><path d="M12 13.5V21" /></svg>
);
export const DownloadIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M12 3v12" /><path d="m7 12 5 5 5-5" /><path d="M5 21h14" /></svg>
);
export const LibraryIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M7 3v18M17 3v18M3 8h4M3 12h18M3 16h4M17 8h4M17 16h4" /></svg>
);
export const SettingsIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M3 7h9" /><path d="M16 7h5" /><circle cx="14" cy="7" r="2.2" /><path d="M3 17h5" /><path d="M12 17h9" /><circle cx="10" cy="17" r="2.2" /></svg>
);
export const MenuIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M4 6h16M4 12h16M4 18h16" /></svg>
);
export const CloseIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M18 6 6 18M6 6l12 12" /></svg>
);
export const SunIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></svg>
);
export const MoonIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" /></svg>
);
export const DiskIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M5 12 7 5h10l2 7" /><rect x="3" y="12" width="18" height="7" rx="2" /><path d="M7 15.5h.01" /></svg>
);
export const BoltIcon = ({ size, ...p }: P) => (
  <svg {...base(size, { fill: 'currentColor', stroke: 'none' })} {...p}><path d="M13 2 4 14h7l-1 8 9-12h-7z" /></svg>
);
export const TrashIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M4 7h16" /><path d="M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" /><path d="M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13" /></svg>
);
export const ChevronIcon = ({ size, ...p }: P) => (
  <svg {...base(size, { strokeWidth: 2.4 })} {...p}><path d="m9 6 6 6-6 6" /></svg>
);
export const InfoIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><circle cx="12" cy="12" r="9" /><path d="M12 11v5M12 8h.01" /></svg>
);
export const AlertIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><circle cx="12" cy="12" r="9" /><path d="M12 8v5M12 16h.01" /></svg>
);
export const RetryIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M21 12a9 9 0 1 1-3-6.7" /><path d="M21 4v5h-5" /></svg>
);
export const StarIcon = ({ size, ...p }: P) => (
  <svg {...base(size, { fill: 'currentColor', stroke: 'none' })} {...p}><path d="m12 2 3 6.5 7 .7-5 4.8 1.4 7L12 17.8 5.6 21l1.4-7-5-4.8 7-.7z" /></svg>
);
/** Bookmark: outlined, or filled when {@code filled}. */
export const BookmarkIcon = ({ size, filled, ...p }: P & { filled?: boolean }) => (
  <svg {...base(size, filled ? { fill: 'currentColor' } : undefined)} {...p}><path d="M6 3h12v18l-6-4-6 4z" /></svg>
);
export const PlayIcon = ({ size, ...p }: P) => (
  <svg {...base(size, { fill: 'currentColor', stroke: 'none' })} {...p}><path d="M7 4v16l13-8z" /></svg>
);
export const FolderIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M3 7h6l2 2h10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /></svg>
);
export const BellIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" /><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" /></svg>
);
export const EditIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M12 20h9" /><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" /></svg>
);
export const WarningIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" /><path d="M12 9v4M12 17h.01" /></svg>
);
export const WrenchIcon = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M14.7 6.3a4 4 0 0 0-5.4 5.4l-6 6a1.4 1.4 0 1 0 2 2l6-6a4 4 0 0 0 5.4-5.4l-2.4 2.4-2-2z" /></svg>
);
export const CheckIcon = ({ size, ...p }: P) => (
  <svg {...base(size, { strokeWidth: 2.4 })} {...p}><path d="M20 6 9 17l-5-5" /></svg>
);

export function ScytheLogo({ size = 34 }: { size?: number }) {
  return (
    <svg width={size} height={(size * 40) / 34} viewBox="-5.0 -10.0 110.0 135.0" fill="currentColor" aria-hidden>
      <path d="m59.641 20.691-1.7383-0.69141c-0.85938 2.3516-1.6602 4.5-2.4297 6.4883 3.9609 1.4492 7.8398 3.1211 11.57 5.1094 8.6289 4.6289 16.859 11.301 20.578 20.629 1.0117 2.5781 1.6602 5.3086 1.8516 8.0781 3.1094-16.262-7.6289-30.77-29.84-39.609z" />
      <path d="m66.352 32.91c-3.5898-1.8281-7.3281-3.3594-11.141-4.6914l1.4297 2.7617c13.66 5.4297 27.809 13.398 32.16 30.84-0.19141-12.871-11.27-23.488-22.449-28.91z" />
      <path d="m56.379 14.859c0.019532-0.070313 0.050782-0.14844 0.070313-0.21094l0.21875-0.62109s-0.019531-0.10938-0.058594-0.14844c-0.73828-0.64062-1.1094-1.3203-1.3984-1.8594-0.17969-0.32812-0.32813-0.60938-0.53906-0.83984-0.011719-0.011719-0.039063-0.050782-0.10938-0.03125-0.070312 0.011718-0.089844 0.058593-0.089844 0.078124-0.28906 0.78906-0.64062 1.75-1.0312 2.8086l2.6797 0.71875c0.089844 0.019532 0.17969 0.070313 0.26172 0.10156z" />
      <path d="m10.879 88.512c0.83984 0.46875 2.1289 0.44922 3.1992-0.039063 0.53125-1.0898 1-2.25 1.4883-3.4688 2.0781-5.1602 4.9219-12.219 13.578-19.051 4.1602-3.2812 6.9414-9.7109 9.6289-15.941 1-2.3008 1.9414-4.4805 2.9414-6.4805 0.66016-1.3203 1.8984-2.1602 3-2.8984 0.78125-0.53125 1.5117-1.0312 1.8008-1.5312 0.96094-1.7109 2.0312-4.0117 3.1016-6.5117-0.28125-0.10938-0.55078-0.26172-0.78906-0.46094l-1.8906-1.5c-1.1797 2.7305-2.3086 5.1719-3.2617 6.8711-0.55859 0.98828-1.5586 1.6914-2.5312 2.3789-0.94141 0.66016-1.9102 1.3398-2.3398 2.1992-0.89063 1.7812-1.7305 3.7617-2.6094 5.8594-2.4414 5.8008-4.9688 11.801-9.0781 14.012-4.7891 2.5781-13.34 16.051-17.102 25.441 0.12109 0.5 0.42188 0.87891 0.87891 1.1289z" />
      <path d="m52.352 15.969c-0.44141 0-0.83984 0.26953-1 0.69922-1.4688 3.9219-2.8594 7.4609-4.1211 10.52-0.17969 0.44141-0.050781 0.94922 0.32812 1.25l2.5703 2.0391c0.26953 0.21875 0.62109 0.28906 0.94922 0.19922 0.32812-0.089843 0.60156-0.32031 0.73047-0.64062 1.3594-3.3516 2.8398-7.2188 4.5117-11.84 0.10156-0.28906 0.078126-0.58984-0.058593-0.85937-0.14062-0.26953-0.37891-0.46094-0.67969-0.53906l-2.9414-0.78906c-0.089844-0.03125-0.19141-0.039062-0.28125-0.039062z" />
    </svg>
  );
}
