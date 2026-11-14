import s from './ProgressBar.module.css';

interface Props {
  value?: number;
  indeterminate?: boolean;
  color?: string;
  height?: number;
  className?: string;
  label?: string;
}

export function ProgressBar({ value = 0, indeterminate, color, height = 6, className, label }: Props) {
  const v = Math.max(0, Math.min(100, value));
  return (
    <div
      className={`${s.track} ${className ?? ''}`}
      style={{ ['--h' as string]: `${height}px`, ['--bar' as string]: color }}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={indeterminate ? undefined : Math.round(v)}
    >
      {indeterminate ? <div className={s.indet} /> : <div className={s.fill} style={{ width: `${v}%` }} />}
    </div>
  );
}
