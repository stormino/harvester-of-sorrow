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

const APP_URL = 'http://localhost:8089/actuator/health';
// Vaadin's first-run frontend compile regularly takes 2–3 min on a cold machine
const HEALTH_TIMEOUT_MS = 3 * 60_000;
const HEALTH_POLL_MS = 2_000;
// DevTools restarts the context once during startup (restartedMain thread) and
// can restart again when Vaadin writes generated files to target/ on first load.
// Require the app to stay UP continuously for this long before declaring ready.
const STABILITY_MS = 12_000;

async function waitForHealth(): Promise<void> {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  let stableFrom = 0;

  while (Date.now() < deadline) {
    let up = false;
    try {
      const res = await fetch(APP_URL);
      if (res.ok) {
        const body = (await res.json()) as { status?: string };
        up = body.status === 'UP';
      }
    } catch {
      // not up yet
    }

    if (up) {
      if (stableFrom === 0) {
        stableFrom = Date.now();
        console.log('App responded UP — waiting for stability…');
      } else if (Date.now() - stableFrom >= STABILITY_MS) {
        return;
      }
    } else {
      if (stableFrom > 0) {
        console.log('App went down during stability window — resetting…');
        stableFrom = 0;
      }
    }

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

  writeFileSync(pidFile, String(child.pid));
  console.log(`App started (PID ${child.pid}), waiting for health check…`);

  await waitForHealth();
  console.log('App is UP and stable at http://localhost:8089');
}

export async function teardown(): Promise<void> {
  const stopScript = resolve(__dirname, '..', 'scripts', 'stop-app.sh');
  try {
    execSync(stopScript, { stdio: 'inherit' });
  } catch (e) {
    console.warn('stop-app.sh exited non-zero:', e);
  }

  if (process.env.KEEP_E2E_ARTIFACTS !== '1') {
    if (existsSync(e2eDir)) {
      rmSync(e2eDir, { recursive: true, force: true });
    }
  } else {
    console.log('KEEP_E2E_ARTIFACTS=1 — leaving target/e2e/ intact for debugging.');
  }
}
