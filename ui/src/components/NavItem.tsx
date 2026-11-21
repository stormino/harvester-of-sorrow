import type { ReactNode } from 'react';
import { NavLink } from 'react-router';
import s from './NavItem.module.css';

interface Props {
  to: string;
  icon: ReactNode;
  label: string;
  badge?: number;
  /** What the badge counts, for screen readers: "active" → "3 active". */
  badgeLabel?: string;
  onNavigate?: () => void;
  testId?: string;
}

export function NavItem({ to, icon, label, badge, badgeLabel = 'active', onNavigate, testId }: Props) {
  return (
    <NavLink
      to={to}
      end={to === '/' || to.startsWith('/?')}
      onClick={onNavigate}
      data-testid={testId}
      className={({ isActive }) => `${s.item} ${isActive ? s.active : ''}`}
    >
      <span className={s.icon}>{icon}</span>
      <span className={s.label}>{label}</span>
      {badge != null && badge > 0 && <span className={s.badge} aria-label={`${badge} ${badgeLabel}`}>{badge}</span>}
    </NavLink>
  );
}

export function NavSection({ label }: { label: string }) {
  return <div className={`caps ${s.section}`}>{label}</div>;
}
