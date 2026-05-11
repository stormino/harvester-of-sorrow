import { spawn } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync, existsSync, copyFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execSync } from 'node:child_process';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, '..', '..');
const e2eDir = resolve(repoRoot, 'target', 'e2e');
const pidFile = resolve(e2eDir, 'app.pid');
const startScript = resolve(__dirname, '..', 'scripts', 'start-app.sh');
const preflight = resolve(__dirname, '..', 'scripts', 'preflight.sh');

const APP_URL = 'http://localhost:8089/actuator/health';
const HEALTH_TIMEOUT_MS = 90_000;
const HEALTH_POLL_MS = 2_000;

async function waitForHealth(): Promise<void> {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(APP_URL);
      if (res.ok) {
        const body = await res.json() as { status?: string };
        if (body.status === 'UP') return;
      }
    } catch {
      // not up yet
    }
    await new Promise(r => setTimeout(r, HEALTH_POLL_MS));
  }
  throw new Error(`App did not become healthy within ${HEALTH_TIMEOUT_MS / 1000}s. Check target/e2e/app.stdout.log`);
}

export async function globalSetup(): Promise<void> {
  // 1. Preflight
  execSync(preflight, { stdio: 'inherit' });

  // 2. Wipe previous run
  if (existsSync(e2eDir)) {
    rmSync(e2eDir, { recursive: true, force: true });
  }
  mkdirSync(e2eDir, { recursive: true });

  // 3. Spawn app
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

  // 4. Wait for ready
  await waitForHealth();
  console.log('App is UP at http://localhost:8089');
}

export async function globalTeardown(): Promise<void> {
  const stopScript = resolve(__dirname, '..', 'scripts', 'stop-app.sh');
  try {
    execSync(stopScript, { stdio: 'inherit' });
  } catch (e) {
    console.warn('stop-app.sh exited non-zero:', e);
  }

  // Preserve artifacts when KEEP_E2E_ARTIFACTS=1, otherwise wipe
  if (process.env.KEEP_E2E_ARTIFACTS !== '1') {
    if (existsSync(e2eDir)) {
      rmSync(e2eDir, { recursive: true, force: true });
    }
  } else {
    console.log('KEEP_E2E_ARTIFACTS=1 — leaving target/e2e/ intact for debugging.');
  }
}
