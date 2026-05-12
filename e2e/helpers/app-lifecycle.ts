import { spawn } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execSync } from 'node:child_process';

const __dirname = dirname(fileURLToPath(import.meta.url));
export const repoRoot = resolve(__dirname, '..', '..');
export const e2eDir = resolve(repoRoot, 'target', 'e2e');
export const pidFile = resolve(e2eDir, 'app.pid');

const startScript = resolve(__dirname, '..', 'scripts', 'start-app.sh');
const preflight = resolve(__dirname, '..', 'scripts', 'preflight.sh');

// Standard health endpoint — reliable, includes DB and disk checks
const HEALTH_URL = 'http://localhost:8089/actuator/health';
// Vaadin's first-run frontend compile regularly takes 2–3 min on a cold machine
const HEALTH_TIMEOUT_MS = 3 * 60_000;
const HEALTH_POLL_MS = 2_000;
// Spring Boot DevTools restarts the app context on first Vaadin page load.
// We require 12 s of continuous UP before declaring the app ready.
const STABILITY_WINDOW_MS = 12_000;

async function waitForHealth(): Promise<void> {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  let stableFrom: number | null = null;

  while (Date.now() < deadline) {
    try {
      const res = await fetch(HEALTH_URL);
      if (res.ok) {
        const body = (await res.json()) as { status?: string };
        if (body.status === 'UP') {
          if (stableFrom === null) stableFrom = Date.now();
          if (Date.now() - stableFrom >= STABILITY_WINDOW_MS) return;
          await new Promise(r => setTimeout(r, HEALTH_POLL_MS));
          continue;
        }
      }
    } catch {
      // not up yet
    }
    stableFrom = null;
    await new Promise(r => setTimeout(r, HEALTH_POLL_MS));
  }

  throw new Error(
    `App did not become healthy within ${HEALTH_TIMEOUT_MS / 1000}s.\n` +
    `Check target/e2e/app.stdout.log for errors.\n` +
    `Tip: run with KEEP_E2E_ARTIFACTS=1 so the log is preserved after teardown.`,
  );
}

export async function setup(): Promise<void> {
  execSync(preflight, { stdio: 'inherit' });

  if (existsSync(e2eDir)) {
    rmSync(e2eDir, { recursive: true, force: true });
  }
  mkdirSync(e2eDir, { recursive: true });

  const child = spawn(startScript, [], {
    detached: true,
    stdio: 'ignore',
    shell: false,
  });
  child.unref();

  if (child.pid === undefined) {
    throw new Error('Failed to spawn start-app.sh — no PID assigned');
  }

  // Write PGID (= PID when detached) so teardown can kill the whole process tree
  writeFileSync(pidFile, String(child.pid));
  console.log(`App started (PID ${child.pid}), waiting for health check…`);

  await waitForHealth();
  console.log('App is ready at http://localhost:8089');
}

export async function teardown(): Promise<void> {
  const stopScript = resolve(__dirname, '..', 'scripts', 'stop-app.sh');
  try {
    execSync(stopScript, { stdio: 'inherit' });
  } catch (e) {
    console.warn('stop-app.sh exited non-zero:', e);
  }

  if (process.env.KEEP_E2E_ARTIFACTS === '0') {
    if (existsSync(e2eDir)) {
      rmSync(e2eDir, { recursive: true, force: true });
    }
  } else {
    console.log('Leaving target/e2e/ intact (set KEEP_E2E_ARTIFACTS=0 to clean up).');
  }
}
