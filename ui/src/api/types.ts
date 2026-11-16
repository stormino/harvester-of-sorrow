import type { components } from './schema';

type S = components['schemas'];

export type Task = S['TaskDto'];
export type SubTask = S['SubTaskDto'];
export type TaskStatus = Task['status'];
export type Source = Task['source'];
export type ContentType = Task['contentType'];
export type SearchResult = S['SearchResultDto'];
export type Season = S['SeasonDto'];
export type Episode = S['EpisodeDto'];
export type SourcesInfo = S['SourcesDto'];
export type SourceInfo = S['SourceDto'];
export type DownloadRequest = S['DownloadRequest'];
export type LibraryEntry = S['LibraryEntryDto'];
export type MonitoredShow = S['MonitoredShowDto'];
export type MonitoredShowRequest = S['MonitoredShowRequest'];
export type CheckResult = S['CheckResultDto'];
export type SystemInfo = S['SystemDto'];
export type Disk = S['DiskDto'];
export type Settings = S['SettingsDto'];
export type ToolCheck = S['ToolCheckDto'];
export type ClearResult = S['ClearResultDto'];
export type LinkProvider = S['LinkProviderDto'];
export type LinkResolution = S['LinkResolutionDto'];
export type LinkFile = S['LinkFileDto'];
export type LinkGuess = S['LinkGuessDto'];
export type TmdbMatch = S['TmdbMatchDto'];
export type LinkDownloadRequest = S['LinkDownloadRequest'];
export type LinkDownloadItem = S['LinkDownloadItem'];
export type XdccStatus = S['XdccStatusDto'];
export type XdccSearch = S['XdccSearchDto'];
export type XdccPack = S['XdccPackDto'];

export type SearchType = 'MOVIES' | 'TV' | 'BOTH';
