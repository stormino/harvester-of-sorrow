import { type Page, expect } from '@playwright/test';
import type { Source } from './search.js';

export type DownloadStatus =
  | 'QUEUED'
  | 'EXTRACTING'
  | 'DOWNLOADING'
  | 'MERGING'
  | 'COPYING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'NOT_FOUND';

/** Navigate to the downloads page. */
export async function gotoQueue(page: Page): Promise<void> {
  await page.goto('/downloads');
  await page.locator('#queue-grid').waitFor({ state: 'visible' });
}

/**
 * After enqueuing a task, poll the queue until a row appears that matches
 * the given source and return its task ID.
 *
 * Uses the `data-task-id` attribute on the status badge as the stable
 * per-row identifier.
 */
export async function findLatestTaskId(
  page: Page,
  opts: { source: Source; timeoutMs?: number },
): Promise<string> {
  const timeout = opts.timeoutMs ?? 30_000;
  const deadline = Date.now() + timeout;

  while (Date.now() < deadline) {
    // Collect all status badge task IDs currently visible
    const badges = page.locator(`[data-testid="queue-row-status"][data-task-id]`);
    const count = await badges.count();

    for (let i = 0; i < count; i++) {
      const badge = badges.nth(i);
      const taskId = await badge.getAttribute('data-task-id');
      if (taskId) return taskId;
    }

    await page.waitForTimeout(500);
  }

  throw new Error(`No task found in queue within ${timeout}ms`);
}

/**
 * Polls the queue view until the row for `taskId` reaches one of the
 * `targetStatuses`. Returns the status it settled on.
 */
export async function waitForStatus(
  page: Page,
  taskId: string,
  targetStatuses: DownloadStatus[],
  opts: { timeoutMs?: number } = {},
): Promise<DownloadStatus> {
  const timeout = opts.timeoutMs ?? 20 * 60 * 1000; // default 20 min
  const deadline = Date.now() + timeout;

  while (Date.now() < deadline) {
    const badge = page.locator(
      `[data-testid="queue-row-status"][data-task-id="${taskId}"]`,
    );

    const count = await badge.count();
    if (count > 0) {
      const rawStatus = await badge.getAttribute('data-status');
      const status = rawStatus as DownloadStatus | null;
      if (status && (targetStatuses as string[]).includes(status)) {
        return status;
      }

      // Bail out immediately on terminal failure
      if (status === 'FAILED' || status === 'NOT_FOUND') {
        throw new Error(
          `Task ${taskId} reached terminal status ${status} while waiting for ${targetStatuses.join('|')}`,
        );
      }
    }

    await page.waitForTimeout(1_000);
  }

  throw new Error(
    `Task ${taskId} did not reach ${targetStatuses.join('|')} within ${timeout / 1000}s`,
  );
}

/** Click the cancel button for a specific task. */
export async function cancelTask(page: Page, taskId: string): Promise<void> {
  const cancelBtn = page.locator(
    `[data-testid="queue-row-cancel"][data-task-id="${taskId}"]`,
  );
  await cancelBtn.waitFor({ state: 'visible', timeout: 10_000 });
  await cancelBtn.click();
}
