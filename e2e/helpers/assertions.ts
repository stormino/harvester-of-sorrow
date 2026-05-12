import { existsSync, statSync, readdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { expect } from '@playwright/test';
import { probe } from './ffprobe.js';
import { moviesPath, tvShowsPath } from './paths.js';

export interface VideoExpectations {
  minDurationSeconds?: number;
  minSizeBytes?: number;
  audioLanguages?: string[];
  expectSubtitles?: boolean;
  maxVideoWidth?: number;
}

/**
 * Assert that a downloaded file is a valid video using ffprobe.
 * All checks are soft-fail so the full report is available on failure.
 */
export async function expectValidVideoFile(
  filePath: string,
  expectations: VideoExpectations = {},
): Promise<void> {
  const {
    minDurationSeconds = 10,
    minSizeBytes = 1_000_000,
    audioLanguages,
    expectSubtitles = false,
    maxVideoWidth,
  } = expectations;

  expect(existsSync(filePath), `File should exist: ${filePath}`).toBe(true);

  const stat = statSync(filePath);
  expect(
    stat.size,
    `File size ${stat.size} should be >= ${minSizeBytes} bytes`,
  ).toBeGreaterThanOrEqual(minSizeBytes);

  const result = await probe(filePath);

  // Duration
  const duration = parseFloat(result.format.duration);
  expect(
    duration,
    `Duration ${duration}s should be >= ${minDurationSeconds}s`,
  ).toBeGreaterThanOrEqual(minDurationSeconds);

  // Video stream
  const videoStreams = result.streams.filter(s => s.codec_type === 'video');
  expect(videoStreams.length, 'Should have exactly one video stream').toBe(1);
  expect(videoStreams[0].width, 'Video width should be > 0').toBeGreaterThan(0);
  expect(videoStreams[0].height, 'Video height should be > 0').toBeGreaterThan(0);
  if (maxVideoWidth !== undefined) {
    expect(
      videoStreams[0].width,
      `Video width ${videoStreams[0].width} should be <= ${maxVideoWidth} (worst quality)`,
    ).toBeLessThanOrEqual(maxVideoWidth);
  }

  // Audio stream
  const audioStreams = result.streams.filter(s => s.codec_type === 'audio');
  expect(audioStreams.length, 'Should have at least one audio stream').toBeGreaterThanOrEqual(1);

  // Language checks (BCP-47 tolerant — compare lowercase prefix)
  if (audioLanguages && audioLanguages.length > 0) {
    for (const lang of audioLanguages) {
      const found = audioStreams.some(s => {
        const streamLang = s.tags?.language?.toLowerCase() ?? '';
        return streamLang === lang.toLowerCase() || streamLang.startsWith(lang.toLowerCase());
      });
      expect(found, `Should have an audio stream for language "${lang}"`).toBe(true);
    }
  }

  // Subtitle streams
  if (expectSubtitles) {
    const subStreams = result.streams.filter(s => s.codec_type === 'subtitle');
    expect(subStreams.length, 'Should have at least one subtitle stream').toBeGreaterThanOrEqual(1);
  }
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
