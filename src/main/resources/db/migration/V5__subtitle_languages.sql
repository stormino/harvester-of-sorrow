-- Subtitle languages chosen separately from audio. NULL = same as `languages`
-- (tasks queued before 7.2.0), '' = no subtitles.
ALTER TABLE download_task ADD COLUMN subtitle_languages TEXT;
