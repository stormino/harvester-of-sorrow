import type { Source } from '../api/types';
import s from './Badge.module.css';

const SOURCE_LABEL: Record<Source, string> = { VIXSRC: 'VixSrc', RAIPLAY: 'RaiPlay' };

export function sourceLabel(src: Source): string {
  return SOURCE_LABEL[src] ?? src;
}

/** Colored source pill. `upper` renders VIXSRC (tables); otherwise VixSrc (cards). */
export function SourcePill({ source, large, upper = true }: { source: Source; large?: boolean; upper?: boolean }) {
  return (
    <span className={`${s.source} ${s[source] ?? ''} ${large ? s.sourceLg : ''}`}
      style={upper ? undefined : { textTransform: 'none' }}>
      {upper ? source : sourceLabel(source)}
    </span>
  );
}

export function CountChip({ children }: { children: React.ReactNode }) {
  return <span className={s.count}>{children}</span>;
}

export function KindChip({ children }: { children: React.ReactNode }) {
  return <span className={s.kind}>{children}</span>;
}
