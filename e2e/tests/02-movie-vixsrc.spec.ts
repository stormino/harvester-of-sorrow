import { test } from '@playwright/test';
import content from '../fixtures/content.json' with { type: 'json' };
import { searchAndPickFirst, openDownloadDialog, enqueueMovie } from '../helpers/search.js';
import { gotoQueue, findLatestTaskId, waitForStatus } from '../helpers/queue.js';
import { findDownloadedFile, expectValidVideoFile } from '../helpers/assertions.js';

const fixture = content.vixsrcMovie;

test('vixsrc movie — full download happy path', async ({ page }) => {
  // 1. Search and enqueue
  const card = await searchAndPickFirst(page, fixture.query, 'vixsrc', 'MOVIE');
  await openDownloadDialog(card);
  await enqueueMovie(page);

  // 2. Navigate to queue and locate the task
  await gotoQueue(page);
  const taskId = await findLatestTaskId(page, { source: 'vixsrc' });

  // 3. Wait for DOWNLOADING to confirm ffmpeg started, then wait for COMPLETED
  await waitForStatus(page, taskId, ['DOWNLOADING'], { timeoutMs: 3 * 60 * 1000 });
  await waitForStatus(page, taskId, ['COMPLETED'], { timeoutMs: 10 * 60 * 1000 });

  // 4. Verify the file on disk
  const filePath = await findDownloadedFile('movies', fixture.titleHint);
  await expectValidVideoFile(filePath, {
    minDurationSeconds: 60,
    maxVideoWidth: 1280,
  });
});
