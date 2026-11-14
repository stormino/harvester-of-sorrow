import { useEffect, useState, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { tightestDisk, useSystem } from '../api/queries';
import { Button } from '../components/Button';
import {
  CloseIcon, DiskIcon, DownloadIcon, LibraryIcon, MenuIcon, MoonIcon, ScytheLogo, SearchIcon, SettingsIcon, SunIcon,
} from '../components/Icons';
import { NavItem, NavSection } from '../components/NavItem';
import { ConnectionPill } from '../components/StatusBadge';
import { formatBytes } from '../lib/format';
import { isActive } from '../lib/tasks';
import { useIsMobile } from '../lib/useMediaQuery';
import { useTheme } from '../lib/useTheme';
import { useTaskStream } from '../live/TaskStream';
import s from './Shell.module.css';

const NAV: { to: string; label: string; icon: (size: number) => ReactNode; testId: string }[] = [
  { to: '/', label: 'Search', icon: (n) => <SearchIcon size={n} />, testId: 'nav-search' },
  { to: '/downloads', label: 'Downloads', icon: (n) => <DownloadIcon size={n} />, testId: 'nav-downloads' },
  { to: '/library', label: 'Library', icon: (n) => <LibraryIcon size={n} />, testId: 'nav-library' },
  { to: '/settings', label: 'Settings', icon: (n) => <SettingsIcon size={n} />, testId: 'nav-settings' },
];

const SIDEBAR_KEY = 'hos-sidebar';

function readSidebar(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_KEY) !== 'collapsed';
  } catch {
    return true;
  }
}

export function Shell() {
  const isMobile = useIsMobile();
  const { theme, toggle } = useTheme();
  const { tasks, connection } = useTaskStream();
  const { data: system } = useSystem();
  const [sidebarOpen, setSidebarOpen] = useState(readSidebar);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const location = useLocation();

  const activeCount = tasks.filter((t) => isActive(t.status)).length;

  useEffect(() => setDrawerOpen(false), [location.pathname, isMobile]);
  useEffect(() => {
    try {
      localStorage.setItem(SIDEBAR_KEY, sidebarOpen ? 'open' : 'collapsed');
    } catch {
      // ignore
    }
  }, [sidebarOpen]);

  const toggleNav = () => (isMobile ? setDrawerOpen((o) => !o) : setSidebarOpen((o) => !o));

  const navList = (onNavigate?: () => void) => NAV.map((n) => (
    <NavItem key={n.to} to={n.to} label={n.label} icon={n.icon(17)} testId={n.testId}
      badge={n.to === '/downloads' ? activeCount : undefined} onNavigate={onNavigate} />
  ));

  return (
    <div className={s.app}>
      <header className={s.navbar}>
        <Button variant="ghost" size="md" iconOnly icon={<MenuIcon size={19} />} onClick={toggleNav}
          aria-label="Toggle navigation" aria-expanded={isMobile ? drawerOpen : sidebarOpen} />
        <NavLink to="/" className={s.brand} aria-label="Harvester of Sorrow — Search">
          <span className={s.logo}><ScytheLogo /></span>
          <span className={s.wordmark}>Harvester of Sorrow</span>
          <span className={s.version} data-testid="app-version">{system ? `v${system.appVersion}` : ''}</span>
        </NavLink>
        <div className={s.spacer} />
        <ConnectionPill state={connection} />
        <button type="button" className={s.themeBtn} onClick={toggle}
          aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}>
          {theme === 'dark' ? <SunIcon size={16} /> : <MoonIcon size={16} />}
          <span className={s.themeLabel}>{theme === 'dark' ? 'Dark' : 'Light'}</span>
        </button>
      </header>

      <div className={s.body}>
        {!isMobile && sidebarOpen && (
          <nav className={s.sidebar} aria-label="Main">
            <NavSection label="Navigation" />
            {navList()}
            <div className={s.spacer} />
            <DiskWidget />
          </nav>
        )}

        {isMobile && drawerOpen && (
          <>
            <div className={s.scrim} onClick={() => setDrawerOpen(false)} />
            <nav className={s.drawer} aria-label="Main">
              <div className={s.drawerHead}>
                <span className="caps">Navigation</span>
                <Button variant="ghost" size="sm" iconOnly icon={<CloseIcon size={16} />}
                  onClick={() => setDrawerOpen(false)} aria-label="Close navigation" />
              </div>
              {navList(() => setDrawerOpen(false))}
              <div className={s.spacer} />
              <DiskWidget />
            </nav>
          </>
        )}

        <main className={s.main}>
          <Outlet />
        </main>
      </div>

      {isMobile && (
        <nav className={s.tabbar} aria-label="Tabs">
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.to === '/'} title={n.label} aria-label={n.label}
              className={({ isActive: on }) => `${s.tab} ${on ? s.tabActive : ''}`}>
              {n.icon(24)}
              {n.to === '/downloads' && activeCount > 0 && <span className={s.tabBadge}>{activeCount}</span>}
            </NavLink>
          ))}
        </nav>
      )}
    </div>
  );
}

function DiskWidget() {
  const { data } = useSystem();
  const disk = tightestDisk(data?.disks);
  if (!disk || disk.freeBytes == null) return null;
  const total = disk.totalBytes ?? 0;
  const used = total > 0 ? total - disk.freeBytes : 0;
  const pct = total > 0 ? (used / total) * 100 : 0;
  return (
    <div className={s.disk} title={disk.path}>
      <div className={s.diskHead}><DiskIcon size={14} />Disk free</div>
      <div className={s.diskFree}>{formatBytes(disk.freeBytes)}</div>
      {total > 0 && (
        <>
          <div className={s.diskBar}><div className={s.diskFill} style={{ width: `${pct}%` }} /></div>
          <div className={s.diskUsed}>{formatBytes(used)} / {formatBytes(total)} used</div>
        </>
      )}
    </div>
  );
}
