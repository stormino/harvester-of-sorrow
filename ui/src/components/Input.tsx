import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';
import s from './Input.module.css';

interface SearchFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size' | 'prefix'> {
  prefix?: ReactNode;
  size?: 'md' | 'lg';
  wrapClassName?: string;
}

/** Input with a leading icon inside a bordered box (search bars, filters). */
export const SearchField = forwardRef<HTMLInputElement, SearchFieldProps>(function SearchField(
  { prefix, size = 'lg', wrapClassName, ...rest }, ref) {
  return (
    <div className={`${s.wrap} ${size === 'md' ? s.md : ''} ${wrapClassName ?? ''}`}>
      {prefix && <span className={s.prefix}>{prefix}</span>}
      <input ref={ref} className={s.input} {...rest} />
    </div>
  );
});

/** Plain form text field. */
export const TextField = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function TextField(
  { className, ...rest }, ref) {
  return <input ref={ref} className={`${s.field} ${className ?? ''}`} {...rest} />;
});

/** Labelled read-only value (Settings). */
export function ReadonlyField({ label, value, testId }: { label: string; value: ReactNode; testId?: string }) {
  const id = useId();
  return (
    <div>
      <label className={s.label} id={id}>{label}</label>
      <div className={s.readonly} aria-labelledby={id} data-testid={testId}>{value}</div>
    </div>
  );
}
