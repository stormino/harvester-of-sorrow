import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';
import { useLinkProviders, useSettings, useSystem } from '../api/queries';
import type { ToolCheck } from '../api/types';
import { Button } from '../components/Button';
import { Dialog } from '../components/Dialog';
import { AlertIcon, CheckIcon, WarningIcon, WrenchIcon } from '../components/Icons';
import { ReadonlyField } from '../components/Input';
import { useToast } from '../components/Toast';
import { formatBytes } from '../lib/format';
import base from './Screen.module.css';
import s from './SettingsScreen.module.css';

const RESTART_NOTE = 'Read-only. Change via environment variables (or application.yml) and restart.';

export function SettingsScreen() {
  const settings = useSettings();
  const system = useSystem();
  const { toast } = useToast();
  const [result, setResult] = useState<ToolCheck[] | null>(null);

  const check = useMutation({
    mutationFn: api.checkTools,
    onSuccess: setResult,
    onError: (e) => toast((e as Error).message, 'error'),
  });

  const cfg = settings.data;
  const sys = system.data;

  return (
    <div id="settings-view" className={`${base.screen} ${base.narrow}`}>
      <h2 className={`${base.title} ${s.title}`}>Settings</h2>

      {settings.isError && (
        <p className={`${base.empty} ${base.error}`} role="alert">Could not load settings: {(settings.error as Error).message}</p>
      )}

      {cfg && (
        <>
          <section className={s.panel} aria-labelledby="tmdb-h">
            <h3 id="tmdb-h" className={`${s.h3} ${s.h3tight}`}>TMDB Configuration</h3>
            <p className={s.desc}>
              Metadata is sourced from{' '}
              <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noreferrer">themoviedb.org</a>.
              {' '}An API key is required for VixSrc search.
            </p>
            <ReadonlyField label="API Key (TMDB_API_KEY)" testId="settings-tmdb-key"
              value={cfg.tmdbApiKeySet ? cfg.tmdbApiKeyMasked : 'Not set — VixSrc search is disabled'} />
            <div className={s.note}><WarningIcon size={15} />API key changes require an application restart.</div>
          </section>

          <section className={s.panel} aria-labelledby="dl-h">
            <h3 id="dl-h" className={s.h3}>Download Configuration</h3>
            <div className={s.grid}>
              <ReadonlyField label="Movies Path" value={cfg.moviesPath} />
              <ReadonlyField label="TV Shows Path" value={cfg.tvShowsPath} />
              <div className={s.span2}><ReadonlyField label="Temp Path" value={cfg.tempPath} /></div>
              <ReadonlyField label="Parallel Downloads" value={cfg.parallelDownloads} />
              <ReadonlyField label="Segment Concurrency" value={cfg.segmentConcurrency} />
              <ReadonlyField label="Default Quality" value={cfg.defaultQuality} />
              <ReadonlyField label="Default Language" value={cfg.defaultLanguage} />
              <ReadonlyField label="Library Monitoring"
                value={cfg.monitoringEnabled ? cfg.monitoringSchedule : 'disabled'} />
              <ReadonlyField label="Wait for Default Language"
                value={`${cfg.monitoringLanguageWaitDays} days, then download as it is`} />
              <ReadonlyField label="RaiPlay" value={cfg.raiPlayEnabled ? 'enabled' : 'disabled (no credentials)'} />
            </div>
            <div className={s.note}><WarningIcon size={15} />{RESTART_NOTE}</div>
          </section>

          <section className={s.panel} aria-labelledby="ex-h">
            <h3 id="ex-h" className={s.h3}>Extractor Configuration</h3>
            <div className={s.grid}>
              <ReadonlyField label="VixSrc Base URL" value={cfg.extractorBaseUrl} />
              <ReadonlyField label="Timeout (seconds)" value={cfg.extractorTimeoutSeconds} />
            </div>
          </section>
        </>
      )}

      <LinkProvidersPanel />

      <section className={s.panel} aria-labelledby="sys-h">
        <h3 id="sys-h" className={s.h3}>System Information</h3>
        {sys && (
          <div className={s.sys}>
            <Row k="Version" v={sys.appVersion} />
            <Row k="Java Version" v={sys.javaVersion} />
            <Row k="OS" v={sys.os} />
            <Row k="Available Processors" v={sys.cpuCount} />
            {sys.disks.map((d) => (
              <Row key={d.label} k={`Free space (${d.label})`}
                v={d.freeBytes != null ? `${formatBytes(d.freeBytes)} / ${formatBytes(d.totalBytes)}` : 'unknown'} />
            ))}
          </div>
        )}
        <Button id="check-tools-button" size="lg" icon={<WrenchIcon size={16} />} loading={check.isPending}
          onClick={() => check.mutate()}>Check Tools</Button>
      </section>

      <Dialog open={!!result} title="Tool check" width={480} onClose={() => setResult(null)}>
        {result?.map((t) => (
          <div key={t.tool} className={s.tool} data-testid="tool-check-result" data-available={t.available}>
            <div className={`${s.toolIcon} ${t.available ? s.ok : s.bad}`}>
              {t.available ? <CheckIcon size={19} /> : <AlertIcon size={19} />}
            </div>
            <div>
              <div className={`${s.toolHead} ${t.available ? s.toolOk : s.toolBad}`}>
                {t.tool} {t.available ? 'available' : 'not found'}
              </div>
              <p className={s.toolMsg}>{t.available ? t.version : (t.error ?? `Please install ${t.tool}.`)}</p>
            </div>
          </div>
        ))}
      </Dialog>
    </div>
  );
}

const PROVIDER_STATE: Record<string, string> = {
  READY: 'ready', IDLE: 'idle (warms up on the first link)', WARMING_UP: 'warming up', ERROR: 'error',
};

function LinkProvidersPanel() {
  const { data: providers } = useLinkProviders();
  if (!providers || providers.length === 0) return null;
  return (
    <section className={s.panel} aria-labelledby="links-h">
      <h3 id="links-h" className={s.h3}>Link Downloads</h3>
      <div className={s.sys}>
        {providers.map((p) => (
          <Row key={p.id} k={p.name}
            v={<span data-testid="link-provider" data-provider={p.id} data-state={p.state}>
              {PROVIDER_STATE[p.state] ?? p.state}{p.message ? ` — ${p.message}` : ''}
            </span>} />
        ))}
      </div>
    </section>
  );
}

function Row({ k, v }: { k: string; v: React.ReactNode }) {
  return <div className={s.sysRow}><span className={s.sysKey}>{k}</span><span className={s.sysVal}>{v}</span></div>;
}
