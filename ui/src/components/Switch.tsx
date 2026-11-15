import type { ReactNode } from 'react';
import s from './Switch.module.css';

interface Props {
  id?: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  children: ReactNode;
}

/** On/off toggle with an inline label. Renders a button with role="switch". */
export function Switch({ id, checked, onChange, children }: Props) {
  return (
    <button id={id} type="button" role="switch" aria-checked={checked} className={s.switch}
      onClick={() => onChange(!checked)}>
      <span className={`${s.track} ${checked ? s.on : ''}`} aria-hidden="true"><span className={s.thumb} /></span>
      <span className={s.label}>{children}</span>
    </button>
  );
}
