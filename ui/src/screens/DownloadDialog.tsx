import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { api } from '../api/client';
import { useSources } from '../api/queries';
import type { SearchResult } from '../api/types';
import { Button } from '../components/Button';
import { Chip, ChipList } from '../components/Chip';
import { Dialog } from '../components/Dialog';
import { useToast } from '../components/Toast';
import { pad2 } from '../lib/format';
import { useTaskStream } from '../live/TaskStream';
import s from './DownloadDialog.module.css';

interface Props {
  item: SearchResult;
  onClose: () => void;
}

export function DownloadDialog({ item, onClose }: Props) {
  const isTV = item.contentType === 'TV';
  const { data: sources } = useSources();
  const { toast, dismiss } = useToast();
  const { upsert } = useTaskStream();

  const sourceInfo = sources?.sources.find((x) => x.id === item.source);
  const languages = sourceInfo?.languages ?? [];
  const qualities = sources?.qualities ?? ['best', '1080', '720', 'worst'];

  const defaults = () => {
    if (!sources) return { langs: null, quality: null };
    const def = sources.defaultLanguage;
    return {
      langs: languages.length === 0 || languages.includes(def) ? [def] : [languages[0]],
      quality: sources.defaultQuality,
    };
  };
  // Preselect the default language (or the source's own, e.g. `it` for RaiPlay) and quality.
  const [langs, setLangs] = useState<string[] | null>(() => defaults().langs);
  const [quality, setQuality] = useState<string | null>(() => defaults().quality);
  const [seasons, setSeasons] = useState<number[]>([]);
  const [episodes, setEpisodes] = useState<number[]>([]);

  // Sources not loaded yet at mount: apply the defaults once they arrive.
  useEffect(() => {
    if (!sources || langs !== null) return;
    const d = defaults();
    setLangs(d.langs);
    setQuality(d.quality);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sources]);

  // Seasons from the search result; when the source gave none, derive them from the episode list.
  const needSeasonLookup = isTV && item.seasons.length === 0;
  const allEpisodes = useQuery({
    queryKey: ['episodes', item.source, item.sourceRef, 'all'],
    queryFn: () => api.episodes(item.source, item.sourceRef, undefined, item.title),
    enabled: needSeasonLookup,
    staleTime: 10 * 60_000,
  });
  const seasonNumbers = useMemo(() => {
    if (!needSeasonLookup) return item.seasons.map((x) => x.number);
    return [...new Set((allEpisodes.data ?? []).map((e) => e.season))].sort((a, b) => a - b);
  }, [needSeasonLookup, item.seasons, allEpisodes.data]);

  const oneSeason = seasons.length === 1 ? seasons[0] : undefined;
  const seasonEpisodes = useQuery({
    queryKey: ['episodes', item.source, item.sourceRef, oneSeason],
    queryFn: () => api.episodes(item.source, item.sourceRef, oneSeason, item.title),
    enabled: isTV && oneSeason !== undefined,
    staleTime: 10 * 60_000,
  });

  const toggle = <T,>(list: T[], v: T) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);

  const toggleSeason = (n: number) => {
    setSeasons((xs) => toggle(xs, n).sort((a, b) => a - b));
    setEpisodes([]);
  };

  // Not a useMutation: the dialog closes immediately and mutate() callbacks die with it.
  const createDownload = () => api.createDownload({
      source: item.source,
      contentType: item.contentType,
      sourceRef: item.sourceRef,
      title: item.title,
      year: item.year,
      seasons: isTV ? seasons : [],
      episodes: isTV && oneSeason !== undefined ? episodes.slice().sort((a, b) => a - b) : [],
      languages: langs ?? [],
      quality: quality ?? undefined,
  });

  const addingMessage = () => {
    if (!isTV) return 'Adding to queue...';
    if (seasons.length === 0) return 'Adding all seasons to queue...';
    if (episodes.length > 0) return `Adding ${episodes.length} episode${episodes.length > 1 ? 's' : ''} to queue...`;
    return `Adding season${seasons.length > 1 ? 's' : ''} ${seasons.join(', ')} to queue...`;
  };

  const confirm = () => {
    const pending = toast(addingMessage(), 'contrast', { durationMs: 60_000 });
    onClose();
    createDownload().then(
      (tasks) => {
        dismiss(pending);
        upsert(tasks);
        toast(tasks.length > 1
          ? `✓ Added ${tasks.length} episodes to queue`
          : `✓ Added to queue: ${tasks[0]?.displayName ?? item.title}`, 'success', { durationMs: 5000 });
      },
      (e: Error) => {
        dismiss(pending);
        toast(`Failed to add download: ${e.message}`, 'error');
      },
    );
  };

  const canConfirm = langs !== null && (languages.length === 0 || langs.length > 0);

  let episodePlaceholder: string;
  if (seasons.length === 0) episodePlaceholder = 'Select a single season first';
  else if (seasons.length > 1) episodePlaceholder = 'Select exactly one season to choose episodes';
  else if (seasonEpisodes.isLoading) episodePlaceholder = 'Loading episodes...';
  else if (seasonEpisodes.isError) episodePlaceholder = 'Could not load episodes — the whole season will be queued';
  else if ((seasonEpisodes.data ?? []).length === 0) episodePlaceholder = `No episodes of season ${oneSeason} found on this source`;
  else episodePlaceholder = 'All episodes';
  const episodesEnabled = oneSeason !== undefined && (seasonEpisodes.data ?? []).length > 0;

  return (
    <Dialog open title={`Download: ${item.title}`} onClose={onClose} width={520} testId="download-dialog"
      footer={
        <>
          <Button variant="outline" size="lg" onClick={onClose}>Cancel</Button>
          <Button id="dialog-confirm-download" size="lg" onClick={confirm} disabled={!canConfirm}>Add to Queue</Button>
        </>
      }>
      <div className={s.form}>
        <div>
          <div className={s.label}>Languages</div>
          <ChipList id="dialog-language-selector" label="Languages">
            {(languages.length ? languages : langs ?? []).map((l) => (
              <Chip key={l} selected={!!langs?.includes(l)} data-value={l}
                onClick={() => setLangs((xs) => toggle(xs ?? [], l))}>{l}</Chip>
            ))}
          </ChipList>
        </div>

        <div>
          <div className={s.label}>Quality</div>
          <ChipList id="dialog-quality-selector" label="Quality" grow>
            {qualities.map((q) => (
              <Chip key={q} selected={quality === q} grow sans data-value={q} onClick={() => setQuality(q)}>{q}</Chip>
            ))}
          </ChipList>
        </div>

        {isTV && (
          <>
            <div>
              <div className={s.label}>
                Seasons <span className={s.dim}>— empty = all</span>
                {needSeasonLookup && allEpisodes.isLoading && <Loading />}
              </div>
              {seasonNumbers.length > 0 ? (
                <ChipList id="dialog-season-field" label="Seasons">
                  {seasonNumbers.map((n) => (
                    <Chip key={n} selected={seasons.includes(n)} data-value={n} onClick={() => toggleSeason(n)}>{n}</Chip>
                  ))}
                </ChipList>
              ) : (
                <div id="dialog-season-field" className={s.placeholder}>
                  {allEpisodes.isLoading ? 'Loading seasons...' : 'Season list unavailable — the whole show will be queued'}
                </div>
              )}
            </div>

            <div>
              <div className={`${s.label} ${episodesEnabled ? '' : s.labelOff}`}>
                Episodes {episodesEnabled && <span className={s.dim}>— empty = whole season</span>}
                {seasonEpisodes.isFetching && <Loading />}
              </div>
              {episodesEnabled ? (
                <div className={s.episodes}>
                  <ChipList id="dialog-episode-field" label="Episodes">
                    {seasonEpisodes.data!.map((ep) => (
                      <Chip key={ep.episode} size="sm" selected={episodes.includes(ep.episode)} data-value={ep.episode}
                        title={ep.name} onClick={() => setEpisodes((xs) => toggle(xs, ep.episode))}>
                        E{pad2(ep.episode)}
                      </Chip>
                    ))}
                  </ChipList>
                </div>
              ) : (
                <div id="dialog-episode-field" className={s.placeholder} aria-disabled="true">{episodePlaceholder}</div>
              )}
            </div>
          </>
        )}
      </div>
    </Dialog>
  );
}

function Loading() {
  return <span className={s.loading}><span className={s.spinner} /> loading...</span>;
}
