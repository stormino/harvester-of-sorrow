import { useMutation } from '@tanstack/react-query';
import { useState, type KeyboardEvent } from 'react';
import { api } from '../api/client';
import { useSources } from '../api/queries';
import type { LibraryEntry, SearchResult, Source } from '../api/types';
import { SourcePill, sourceLabel } from '../components/Badge';
import { Button } from '../components/Button';
import { Dialog } from '../components/Dialog';
import { CheckIcon, SearchIcon } from '../components/Icons';
import { TextField } from '../components/Input';
import { Segmented } from '../components/Segmented';
import { useToast } from '../components/Toast';
import { guessTitle } from '../lib/format';
import s from './LibraryScreen.module.css';

interface Props {
  entry: LibraryEntry;
  onClose: () => void;
  onSaved: () => void;
}

export function MonitorDialog({ entry, onClose, onSaved }: Props) {
  const existing = entry.monitored;
  const { data: sources } = useSources();
  const { toast } = useToast();
  const enabled = sources?.sources.map((x) => x.id) ?? ['VIXSRC'];
  const [source, setSource] = useState<Source>(existing?.source ?? 'VIXSRC');
  const [query, setQuery] = useState(existing?.title ?? guessTitle(entry.directoryName));
  const [matches, setMatches] = useState<SearchResult[] | null>(null);
  const [chosen, setChosen] = useState<SearchResult | null>(null);

  const search = useMutation({
    mutationFn: () => api.search(query.trim(), 'TV', source),
    onSuccess: (r) => {
      setMatches(r);
      setChosen(r.length === 1 ? r[0] : null);
    },
    onError: (e) => toast((e as Error).message, 'error'),
  });

  const save = useMutation({
    mutationFn: (m: SearchResult) => {
      const body = {
        title: m.title, year: m.year, tmdbId: m.tmdbId, source: m.source, sourceRef: m.sourceRef,
        directoryName: entry.directoryName,
      };
      return existing ? api.updateMonitored(existing.id, body) : api.addMonitored(body);
    },
    onSuccess: () => {
      toast(`✓ Monitoring saved for "${entry.directoryName}"`, 'success');
      onSaved();
    },
    onError: (e) => toast((e as Error).message, 'error'),
  });

  const doSearch = () => {
    if (!query.trim()) {
      toast('Please enter a title to search', 'error');
      return;
    }
    search.mutate();
  };

  const onSave = () => {
    if (!chosen) {
      toast('Please search and select a show first', 'error');
      return;
    }
    save.mutate(chosen);
  };

  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Enter') doSearch();
  };

  return (
    <Dialog open width={480} onClose={onClose} testId="monitor-dialog"
      title={`${existing ? 'Edit monitoring' : 'Configure monitoring'} — ${entry.directoryName}`}
      footer={
        <>
          <Button variant="outline" size="lg" onClick={onClose}>Cancel</Button>
          <Button id="monitor-save-button" size="lg" icon={<CheckIcon size={15} />} loading={save.isPending}
            onClick={onSave}>Save</Button>
        </>
      }>
      <div className={s.form}>
        <div>
          <span className={s.label}>Source</span>
          <Segmented id="monitor-source-select" ariaLabel="Source" full value={source}
            options={enabled.map((x) => ({ value: x, label: sourceLabel(x) }))}
            onChange={(v) => { setSource(v); setMatches(null); setChosen(null); }} />
        </div>
        <div>
          <label className={s.label} htmlFor="monitor-search-field">Search</label>
          <div className={s.searchRow}>
            <TextField id="monitor-search-field" value={query} onChange={(e) => setQuery(e.target.value)}
              onKeyDown={onKey} placeholder="Show title..." autoComplete="off" />
            <Button id="monitor-search-button" size="lg" icon={<SearchIcon size={15} />} loading={search.isPending}
              onClick={doSearch}>Search</Button>
          </div>
          {existing && !matches && (
            <p className={s.current} style={{ marginTop: 8 }}>
              Currently matched: <strong>{existing.title}</strong>{existing.year ? ` (${existing.year})` : ''} on {sourceLabel(existing.source)}
            </p>
          )}
        </div>
        {matches && (
          <div>
            <span className={s.label}>Matched show</span>
            {matches.length === 0 ? (
              <p className={s.noMatches}>No TV shows found on {sourceLabel(source)} for "{query}".</p>
            ) : (
              <div id="monitor-result-select" className={s.matches} role="listbox" aria-label="Matched show">
                {matches.map((m) => {
                  const on = chosen?.sourceRef === m.sourceRef;
                  return (
                    <button key={m.sourceRef} type="button" role="option" aria-selected={on}
                      className={`${s.match} ${on ? s.matchOn : ''}`} onClick={() => setChosen(m)}>
                      <span className={s.matchTitle}>{m.title}</span>
                      {m.year && <span className={s.matchYear}>{m.year}</span>}
                      <SourcePill source={m.source} upper={false} />
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </div>
    </Dialog>
  );
}
