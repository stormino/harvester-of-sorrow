import { existsSync, statSync, readdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { expect } from '@playwright/test';
import { probe } from './ffprobe.js';
import { moviesPath, tvShowsPath } from './paths.js';

/**
 * Assert that a downloaded file is a valid, playable video using ffprobe:
 * file exists, non-empty, has a video stream and an audio stream, positive duration.
 */
export async function expectValidVideoFile(filePath: string): Promise<void> {
  expect(existsSync(filePath), `File should exist: ${filePath}`).toBe(true);
  expect(statSync(filePath).size, `File should not be empty: ${filePath}`).toBeGreaterThan(0);

  const result = await probe(filePath);

  const duration = parseFloat(result.format.duration);
  expect(duration, `Duration should be > 0`).toBeGreaterThan(0);

  const videoStreams = result.streams.filter(s => s.codec_type === 'video');
  expect(videoStreams.length, 'Should have at least one video stream').toBeGreaterThanOrEqual(1);

  const audioStreams = result.streams.filter(s => s.codec_type === 'audio');
  expect(audioStreams.length, 'Should have at least one audio stream').toBeGreaterThanOrEqual(1);
}


/**
 * Poll the filesystem after COMPLETED status for up to 5 s, then return the
 * matched path. Logs directory contents on failure to help tighten the glob.
 */
export async function findDownloadedFile(
  kind: 'movies' | 'tvshows',
  titleHint: string,
  subPath?: string,
): Promise<string> {
  const baseDir = kind === 'movies' ? moviesPath : tvShowsPath;
  const deadline = Date.now() + 5_000;

  while (Date.now() < deadline) {
    const found = walkFind(baseDir, titleHint, subPath);
    if (found) return found;
    await new Promise(r => setTimeout(r, 500));
  }

  // Failure: dump directory listing to aid debugging
  let listing = '(directory does not exist)';
  if (existsSync(baseDir)) {
    listing = JSON.stringify(listRecursive(baseDir), null, 2);
  }
  throw new Error(
    `Could not find a downloaded file matching "${titleHint}" under ${baseDir}.\n` +
    `Directory contents:\n${listing}`,
  );
}

function walkFind(dir: string, hint: string, subPath?: string): string | null {
  if (!existsSync(dir)) return null;
  // Normalize to alphanumeric-only so "Fight Club" matches "Fight.Club.1999.mp4"
  const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
  const hintNorm = norm(hint);

  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      const found = walkFind(full, hint, subPath);
      if (found) return found;
    } else if (entry.isFile() && entry.name.endsWith('.mp4')) {
      const pathLower = full.toLowerCase();
      const hintMatch = norm(entry.name).includes(hintNorm);
      const subMatch = subPath ? pathLower.includes(subPath.toLowerCase()) : true;
      if (hintMatch && subMatch) return full;
    }
  }
  return null;
}

function listRecursive(dir: string, depth = 0): string[] {
  if (depth > 4) return [];
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    out.push('  '.repeat(depth) + entry.name + (entry.isDirectory() ? '/' : ''));
    if (entry.isDirectory()) {
      out.push(...listRecursive(join(dir, entry.name), depth + 1));
    }
  }
  return out;
}
