import type { ReactNode } from 'react';
import { NavLink } from 'react-router';
import s from './NavItem.module.css';

interface Props {
  to: string;
  icon: ReactNode;
  label: string;
  badge?: number;
  onNavigate?: () => void;
  testId?: string;
}

export function NavItem({ to, icon, label, badge, onNavigate, testId }: Props) {
  return (
    <NavLink
      to={to}
      end={to === '/'}
      onClick={onNavigate}
      data-testid={testId}
      className={({ isActive }) => `${s.item} ${isActive ? s.active : ''}`}
    >
      <span className={s.icon}>{icon}</span>
      <span className={s.label}>{label}</span>
      {badge != null && badge > 0 && <span className={s.badge} aria-label={`${badge} active`}>{badge}</span>}
    </NavLink>
  );
}

export function NavSection({ label }: { label: string }) {
  return <div className={`caps ${s.section}`}>{label}</div>;
}
