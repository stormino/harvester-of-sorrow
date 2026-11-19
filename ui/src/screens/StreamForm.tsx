import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api/client';
import { useSources } from '../api/queries';
import type { MediaInfo, SearchResult } from '../api/types';
import { Button } from '../components/Button';
import { DownloadIcon } from '../components/Icons';
import { Chip, ChipList } from '../components/Chip';
import { useToast } from '../components/Toast';
import { pad2 } from '../lib/format';
import { useTaskStream } from '../live/TaskStream';
import s from './Form.module.css';
import p from './TitlePanel.module.css';

interface Props {
  item: SearchResult;
  /** Called right after the request is sent (the panel closes). */
  onQueued: () => void;
}

/** Language, quality, season and episode choice for a streaming source (VixSrc, RaiPlay). */
export function StreamForm({ item, onQueued }: Props) {
  const isTV = item.contentType === 'TV';
  const { data: sources } = useSources();
  const { toast, dismiss } = useToast();
  const { upsert } = useTaskStream();

  // What the title really has (for a show: its first episode), read from the source's playlist.
  const mediaInfo = useQuery({
    queryKey: ['media-info', item.source, item.sourceRef],
    queryFn: () => api.mediaInfo(item.source, item.contentType, item.sourceRef),
    staleTime: 10 * 60_000,
    retry: false,
  });
  const info = mediaInfo.data?.known ? mediaInfo.data : undefined;
  const infoSettled = !mediaInfo.isLoading;

  // Without media info: the source's full language list (one choice for audio and subtitles).
  const sourceInfo = sources?.sources.find((x) => x.id === item.source);
  const languages = info ? audioLanguages(info) : sourceInfo?.languages ?? [];
  const subtitleChoices = info ? subtitleLanguages(info) : [];
  const qualities = info?.qualities ?? sources?.qualities ?? ['best', '1080', '720', 'worst'];

  const [langs, setLangs] = useState<string[] | null>(null);
  const [subs, setSubs] = useState<string[]>([]);
  const [allSubs, setAllSubs] = useState(false);
  const [quality, setQuality] = useState<string | null>(null);
  const [seasons, setSeasons] = useState<number[]>([]);
  const [episodes, setEpisodes] = useState<number[]>([]);

  // Preselect once both the server defaults and the media info are in: the default language
  // when the title has it, otherwise the language it does have (e.g. `en` only).
  const preselected = useRef(false);
  useEffect(() => {
    if (preselected.current || !sources || !infoSettled) return;
    preselected.current = true;
    const def = sources.defaultLanguage;
    const audio = languages.length === 0 || languages.includes(def) ? [def] : [languages[0]];
    setLangs(audio);
    if (info) {
      const sub = [def, audio[0]].find((l) => subtitleChoices.includes(l));
      setSubs(sub ? [sub] : []);
    }
    setQuality(qualities.includes(sources.defaultQuality) ? sources.defaultQuality : qualities[0]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sources, infoSettled]);

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
      // Only when the title's subtitles are known; otherwise the server uses the audio languages.
      subtitleLanguages: info ? subs : undefined,
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
    onQueued();
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

  const actionNote = () => {
    const what = !isTV ? 'Film' : seasons.length === 0 ? 'All seasons'
      : episodes.length > 0 ? `${episodes.length} episode${episodes.length > 1 ? 's' : ''}`
        : `Season${seasons.length > 1 ? 's' : ''} ${seasons.join(', ')}`;
    const subsNote = info ? ` · subs ${subs.join('/') || 'none'}` : '';
    return `${what} · ${(langs ?? []).join('/') || '—'}${subsNote} · ${quality ?? ''}`;
  };

  const canConfirm = langs !== null && quality !== null && (languages.length === 0 || langs.length > 0);

  let episodePlaceholder: string;
  if (seasons.length === 0) episodePlaceholder = 'Select a single season first';
  else if (seasons.length > 1) episodePlaceholder = 'Select exactly one season to choose episodes';
  else if (seasonEpisodes.isLoading) episodePlaceholder = 'Loading episodes...';
  else if (seasonEpisodes.isError) episodePlaceholder = 'Could not load episodes — the whole season will be queued';
  else if ((seasonEpisodes.data ?? []).length === 0) episodePlaceholder = `No episodes of season ${oneSeason} found on this source`;
  else episodePlaceholder = 'All episodes';
  const episodesEnabled = oneSeason !== undefined && (seasonEpisodes.data ?? []).length > 0;

  return (
    <>
      <div className={s.form}>
        <div>
          <div className={s.label}>
            {info ? 'Audio' : 'Languages'}
            {info && <span className={s.dim}>— {audioNote(info, isTV)}</span>}
            {mediaInfo.isLoading && <Loading />}
          </div>
          {mediaInfo.isLoading ? (
            <div className={s.placeholder}>Checking which languages this title has...</div>
          ) : info && languages.length === 0 ? (
            <div className={s.placeholder}>{info.audio.map((a) => a.name).join(', ') || 'Audio'} (language unknown)</div>
          ) : (
            <ChipList id="dialog-language-selector" label={info ? 'Audio' : 'Languages'}>
              {(languages.length ? languages : langs ?? []).map((l) => (
                <Chip key={l} selected={!!langs?.includes(l)} data-value={l} title={trackName(info?.audio, l)}
                  onClick={() => setLangs((xs) => toggle(xs ?? [], l))}>{l}</Chip>
              ))}
            </ChipList>
          )}
        </div>

        {info && (
          <div>
            <div className={s.label}>
              Subtitles <span className={s.dim}>— {subtitleChoices.length === 0 ? 'none on this title' : 'off by default in the player; forced ones included'}</span>
            </div>
            {subtitleChoices.length > 0 && (() => {
              // The default language and the audio languages first; the rest on request.
              const main = subtitleChoices.filter((l) => l === sources?.defaultLanguage || languages.includes(l) || subs.includes(l));
              const shown = allSubs || main.length === 0 ? subtitleChoices : main;
              const hidden = subtitleChoices.length - shown.length;
              return (
                <ChipList id="dialog-subtitle-selector" label="Subtitles">
                  {shown.map((l) => (
                    <Chip key={l} size="sm" selected={subs.includes(l)} data-value={l} title={trackName(info.subtitles, l)}
                      onClick={() => setSubs((xs) => toggle(xs, l))}>{l}</Chip>
                  ))}
                  {hidden > 0 && (
                    <Chip size="sm" sans selected={false} onClick={() => setAllSubs(true)}>+{hidden} more</Chip>
                  )}
                </ChipList>
              );
            })()}
          </div>
        )}

        <div>
          <div className={s.label}>Quality</div>
          {mediaInfo.isLoading ? (
            <div className={s.placeholder}>Checking available qualities...</div>
          ) : (
            <ChipList id="dialog-quality-selector" label="Quality" grow>
              {qualities.map((q) => (
                <Chip key={q} selected={quality === q} grow sans data-value={q} onClick={() => setQuality(q)}>
                  {/^\d+$/.test(q) ? `${q}p` : q}
                </Chip>
              ))}
            </ChipList>
          )}
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
      <div className={p.spacer} />
      <div className={p.actions}>
        <span className={p.actionNote}>{actionNote()}</span>
        <Button id="dialog-confirm-download" size="lg" icon={<DownloadIcon size={16} />} onClick={confirm} disabled={!canConfirm}>
          Add to queue
        </Button>
      </div>
    </>
  );
}

/** Audio languages with a known code, in the source's order. */
function audioLanguages(info: MediaInfo): string[] {
  return [...new Set(info.audio.map((a) => a.language).filter((l): l is string => !!l))];
}

/** Subtitle languages (full and forced tracks of a language are one choice), sorted by name. */
function subtitleLanguages(info: MediaInfo): string[] {
  const codes = [...new Set(info.subtitles.map((t) => t.language).filter((l): l is string => !!l))];
  return codes.sort((a, b) => trackName(info.subtitles, a).localeCompare(trackName(info.subtitles, b)));
}

/** "Italian" for "it" (the non-forced track's name when there is one). */
function trackName(tracks: MediaInfo['audio'] | undefined, code: string): string {
  const ts = (tracks ?? []).filter((t) => t.language === code);
  return (ts.find((t) => !t.forced) ?? ts[0])?.name ?? code;
}

function audioNote(info: MediaInfo, isTV: boolean): string {
  const names = info.audio.map((a) => a.name);
  const what = names.length === 1 ? `only ${names[0]}` : names.join(' and ');
  return isTV ? `${what} (first episode)` : what;
}

function Loading() {
  return <span className={s.loading}><span className={s.spinner} /> loading...</span>;
}
