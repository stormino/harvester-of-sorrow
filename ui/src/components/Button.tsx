import type { ButtonHTMLAttributes, ReactNode } from 'react';
import s from './Button.module.css';

export type ButtonVariant = 'primary' | 'secondary' | 'outline' | 'ghost' | 'link' | 'danger' | 'dangerSolid' | 'success';
export type ButtonSize = 'sm' | 'md' | 'lg' | 'xl';

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  icon?: ReactNode;
  loading?: boolean;
  /** Square, icon-only button; pass a `title`/`aria-label`. */
  iconOnly?: boolean;
}

export function Button({
  variant = 'primary', size = 'md', icon, loading, iconOnly, className, children, disabled, type = 'button', ...rest
}: Props) {
  const cls = [s.btn, s[size], s[variant], iconOnly ? s.iconOnly : '', className ?? ''].join(' ');
  return (
    <button type={type} className={cls} disabled={disabled || loading} aria-busy={loading || undefined} {...rest}>
      {loading ? <span className={s.spinner} aria-hidden /> : icon}
      {children}
    </button>
  );
}
