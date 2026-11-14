import type { ButtonHTMLAttributes, ReactNode } from 'react';
import s from './Chip.module.css';

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  selected: boolean;
  size?: 'sm' | 'md';
  grow?: boolean;
  sans?: boolean;
}

/** Toggle chip used in the download dialog (languages, quality, seasons, episodes). */
export function Chip({ selected, size = 'md', grow, sans, className, ...rest }: Props) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      className={[s.chip, size === 'sm' ? s.sm : '', selected ? s.on : '', grow ? s.grow : '', sans ? s.sans : '', className ?? ''].join(' ')}
      {...rest}
    />
  );
}

export function ChipList({ children, id, label, grow }: { children: ReactNode; id?: string; label: string; grow?: boolean }) {
  return (
    <div id={id} role="group" aria-label={label} className={s.list} style={grow ? { flexWrap: 'nowrap' } : undefined}>
      {children}
    </div>
  );
}
