import { test, expect } from '@playwright/test';
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
  await waitForStatus(page, taskId, ['DOWNLOADING', 'MERGING', 'COPYING', 'COMPLETED'], { timeoutMs: 3 * 60 * 1000 });
  await waitForStatus(page, taskId, ['COMPLETED'], { timeoutMs: 20 * 60 * 1000 });

  // 4. Verify the file exists with season/episode/name in the path
  const filePath = await findDownloadedFile('tvshows', fixture.titleHint, 'S01E01');
  await expectValidVideoFile(filePath);
  expect(filePath).toContain('Season 01');
  // Episode name should be included in the filename (e.g. S01E01.Pista.Nera.mp4)
  expect(filePath).toMatch(/S01E01\..+\.mp4$/);
});
