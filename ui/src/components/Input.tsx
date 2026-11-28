import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';
import { CloseIcon } from './Icons';
import s from './Input.module.css';

interface SearchFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size' | 'prefix'> {
  prefix?: ReactNode;
  size?: 'md' | 'lg';
  wrapClassName?: string;
  /** Shows a clear (×) button while the field has text; called when it is pressed. */
  onClear?: () => void;
  clearLabel?: string;
}

/** Input with a leading icon inside a bordered box (search bars, filters). */
export const SearchField = forwardRef<HTMLInputElement, SearchFieldProps>(function SearchField(
  { prefix, size = 'lg', wrapClassName, onClear, clearLabel = 'Clear', ...rest }, ref) {
  const hasText = rest.value != null && String(rest.value) !== '';
  return (
    <div className={`${s.wrap} ${size === 'md' ? s.md : ''} ${wrapClassName ?? ''}`}>
      {prefix && <span className={s.prefix}>{prefix}</span>}
      <input ref={ref} className={s.input} {...rest} />
      {onClear && hasText && (
        <button type="button" className={s.clear} onClick={onClear} aria-label={clearLabel} title={clearLabel}
          data-testid="search-clear">
          <CloseIcon size={size === 'md' ? 15 : 17} />
        </button>
      )}
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
