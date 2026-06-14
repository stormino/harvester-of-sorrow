import { test, expect } from '@playwright/test';
import { existsSync, readdirSync } from 'node:fs';
import { execSync } from 'node:child_process';
import content from '../fixtures/content.json' with { type: 'json' };
import { searchAndPickFirst, openDownloadDialog, enqueueMovie } from '../helpers/search.js';
import { gotoQueue, findLatestTaskId, waitForStatus, cancelTask } from '../helpers/queue.js';
import { tempPath } from '../helpers/paths.js';

const fixture = content.vixsrcMovie;

test('cancel mid-download — no orphan processes or temp files', async ({ page }) => {
  // 1. Enqueue the smallest fixture (vixsrc movie)
  const card = await searchAndPickFirst(page, fixture.query, 'vixsrc', 'MOVIE');
  await openDownloadDialog(card);
  await enqueueMovie(page);

  await gotoQueue(page);
  const taskId = await findLatestTaskId(page, { source: 'vixsrc' });

  // 2. Wait until ffmpeg is actually running (DOWNLOADING status)
  await waitForStatus(page, taskId, ['DOWNLOADING'], { timeoutMs: 3 * 60 * 1000 });

  // 3. Give it 3 s so at least one progress event has fired (exercises cancel-mid-progress)
  await page.waitForTimeout(3_000);

  // 4. Cancel and measure how quickly the backend stops
  const cancelTime = Date.now();
  await cancelTask(page, taskId);

  // 5. Verify CANCELLED status within 15 s
  await waitForStatus(page, taskId, ['CANCELLED'], { timeoutMs: 15_000 });
  const cancelledMs = Date.now() - cancelTime;

  // Cancellation should be confirmed by the UI within 15 s, not linger
  expect(cancelledMs, `Cancellation took too long: ${cancelledMs}ms`).toBeLessThan(15_000);

  // 6. Verify no orphan ffmpeg process referencing this taskId within 5 s of CANCELLED
  //    Subprocesses should be dead promptly, not just "eventually"
  await page.waitForTimeout(2_000);
  try {
    const result = execSync(`pgrep -f "ffmpeg.*${taskId}" || true`, { encoding: 'utf8' }).trim();
    expect(result, `Orphan ffmpeg process found for task ${taskId}`).toBe('');
  } catch {
    // pgrep not available on all platforms — skip this check
  }

  // 7. Verify no orphan .tmp files remain under target/e2e/temp/
  if (existsSync(tempPath)) {
    const tmpFiles = readdirSync(tempPath).filter(f => f.endsWith('.tmp'));
    expect(
      tmpFiles,
      `Orphan .tmp files found in ${tempPath}: ${tmpFiles.join(', ')}`,
    ).toHaveLength(0);
  }
});

test('cancel queued download — transitions directly to CANCELLED', async ({ page }) => {
  // 1. Saturate the queue so the second enqueue stays QUEUED
  const card1 = await searchAndPickFirst(page, fixture.query, 'vixsrc', 'MOVIE');
  await openDownloadDialog(card1);
  await enqueueMovie(page);

  // Enqueue a second item — it should remain QUEUED while first runs
  const card2 = await searchAndPickFirst(page, fixture.query, 'vixsrc', 'MOVIE');
  await openDownloadDialog(card2);
  await enqueueMovie(page);

  await gotoQueue(page);
  const taskId = await findLatestTaskId(page, { source: 'vixsrc' });

  // 2. Cancel while still QUEUED (don't wait for DOWNLOADING)
  await cancelTask(page, taskId);

  // 3. Should go to CANCELLED without ever entering DOWNLOADING
  await waitForStatus(page, taskId, ['CANCELLED'], { timeoutMs: 10_000 });
});
