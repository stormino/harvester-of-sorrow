import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, '..', '..');
const envFile = resolve(__dirname, '..', '.env.e2e');

function loadEnv(): Record<string, string> {
  if (!existsSync(envFile)) return {};
  const env: Record<string, string> = {};
  for (const line of readFileSync(envFile, 'utf8').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq < 0) continue;
    env[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim();
  }
  return env;
}

const env = loadEnv();

function get(key: string, fallback: string): string {
  return process.env[key] ?? env[key] ?? fallback;
}

export const moviesPath = resolve(repoRoot, get('DOWNLOAD_MOVIES_PATH', 'target/e2e/movies'));
export const tvShowsPath = resolve(repoRoot, get('DOWNLOAD_TV_SHOWS_PATH', 'target/e2e/tvshows'));
export const tempPath = resolve(repoRoot, get('DOWNLOAD_TEMP_PATH', 'target/e2e/temp'));
export const appLog = resolve(repoRoot, get('LOG_FILE', 'target/e2e/app.log'));
export const appStdoutLog = resolve(repoRoot, 'target/e2e/app.stdout.log');
export const pidFile = resolve(repoRoot, 'target/e2e/app.pid');
export const e2eRoot = resolve(repoRoot, 'target/e2e');
export { repoRoot };
