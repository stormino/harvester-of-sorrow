import { isRouteErrorResponse, useRouteError } from 'react-router';
import { Button } from '../components/Button';
import { AlertIcon } from '../components/Icons';
import s from './RouteError.module.css';

/**
 * Shown instead of a screen that crashed. The usual cause is a tab still running an older HoS
 * after an update (its code no longer matches the server), which a reload fixes.
 */
export function RouteError() {
  const error = useRouteError();
  const message = isRouteErrorResponse(error)
    ? `${error.status} ${error.statusText}`
    : error instanceof Error ? error.message : String(error);
  return (
    <div className={s.wrap} role="alert" data-testid="route-error">
      <div className={s.card}>
        <div className={s.icon}><AlertIcon size={22} /></div>
        <h2 className={s.title}>Something went wrong</h2>
        <p className={s.text}>
          This page hit an error. If HoS was just updated, this tab is probably still running the old
          version: reloading fixes it.
        </p>
        <div className={s.actions}>
          <Button size="lg" onClick={() => window.location.reload()}>Reload</Button>
          <Button variant="outline" size="lg" onClick={() => { window.location.href = '/'; }}>Go to Search</Button>
        </div>
        <details className={s.details}>
          <summary>Technical details</summary>
          <pre>{message}</pre>
        </details>
      </div>
    </div>
  );
}
