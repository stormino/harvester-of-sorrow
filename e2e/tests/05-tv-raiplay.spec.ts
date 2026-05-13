import { test } from '@playwright/test';
import content from '../fixtures/content.json' with { type: 'json' };
import { searchAndPickFirst, openDownloadDialog, enqueueEpisode } from '../helpers/search.js';
import { gotoQueue, findLatestTaskId, waitForStatus } from '../helpers/queue.js';
import { findDownloadedFile, expectValidVideoFile } from '../helpers/assertions.js';

const fixture = content.raiplayTv;

// RaiPlay requires credentials; skip gracefully if not provided
test.skip(
  !process.env.RAIPLAY_USERNAME,
  'RAIPLAY_USERNAME not set — RaiPlay scenarios skipped',
);

test('raiplay TV series — S01E01 downloads to correct path', async ({ page }) => {
  // 1. Search and enqueue S01E01
  const card = await searchAndPickFirst(page, fixture.query, 'raiplay', 'TV');
  await openDownloadDialog(card);
  await enqueueEpisode(page, {
    season: fixture.season,
    episode: fixture.episode,
  });

  // 2. Navigate to queue and locate the task
  await gotoQueue(page);
  const taskId = await findLatestTaskId(page, { source: 'raiplay' });

  // 3. Wait for completion
  await waitForStatus(page, taskId, ['DOWNLOADING'], { timeoutMs: 3 * 60 * 1000 });
  await waitForStatus(page, taskId, ['COMPLETED'], { timeoutMs: 20 * 60 * 1000 });

  // 4. Verify a valid video file was downloaded under the TV shows path
  const filePath = await findDownloadedFile('tvshows', fixture.titleHint);
  await expectValidVideoFile(filePath);
});
