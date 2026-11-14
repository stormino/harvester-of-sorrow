import s from './Segmented.module.css';

interface Props<T extends string> {
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
  id?: string;
  ariaLabel: string;
  /** Equal-width options spanning the row (dialogs). */
  full?: boolean;
}

export function Segmented<T extends string>({ value, options, onChange, id, ariaLabel, full }: Props<T>) {
  return (
    <div id={id} role="radiogroup" aria-label={ariaLabel} className={`${s.group} ${full ? s.full : ''}`}>
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={o.value === value}
          data-value={o.value}
          className={`${s.opt} ${o.value === value ? s.on : ''}`}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
