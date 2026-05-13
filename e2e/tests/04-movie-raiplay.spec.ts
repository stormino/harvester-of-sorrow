import { test } from '@playwright/test';
import content from '../fixtures/content.json' with { type: 'json' };
import { searchAndPickFirst, openDownloadDialog, enqueueMovie } from '../helpers/search.js';
import { gotoQueue, findLatestTaskId, waitForStatus } from '../helpers/queue.js';
import { findDownloadedFile, expectValidVideoFile } from '../helpers/assertions.js';

const fixture = content.raiplayMovie;

// RaiPlay requires credentials; skip gracefully if not provided
test.skip(
  !process.env.RAIPLAY_USERNAME,
  'RAIPLAY_USERNAME not set — RaiPlay scenarios skipped',
);

test('raiplay movie — full download happy path', async ({ page }) => {
  // 1. Search and enqueue
  const card = await searchAndPickFirst(page, fixture.query, 'raiplay', 'MOVIE');
  await openDownloadDialog(card);
  await enqueueMovie(page);

  // 2. Navigate to queue and locate the task
  await gotoQueue(page);
  const taskId = await findLatestTaskId(page, { source: 'raiplay' });

  // 3. Wait for completion
  await waitForStatus(page, taskId, ['DOWNLOADING', 'MERGING', 'COPYING', 'COMPLETED'], { timeoutMs: 3 * 60 * 1000 });
  await waitForStatus(page, taskId, ['COMPLETED'], { timeoutMs: 20 * 60 * 1000 });

  // 4. Verify the file on disk
  const filePath = await findDownloadedFile('movies', fixture.titleHint);
  await expectValidVideoFile(filePath);
});
