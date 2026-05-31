import { test, expect } from '@playwright/test';
import content from '../fixtures/content.json' with { type: 'json' };
import { searchAndPickFirst, openDownloadDialog, enqueueMultipleEpisodes } from '../helpers/search.js';
import { gotoQueue, findLatestTaskId, waitForStatus } from '../helpers/queue.js';
import { findDownloadedFile, expectValidVideoFile } from '../helpers/assertions.js';

const fixture = content.vixsrcTv;

test('vixsrc TV — multi-episode selection (S01E01 and S01E02) queues both episodes', async ({ page }) => {
  const card = await searchAndPickFirst(page, fixture.query, 'vixsrc', 'TV');
  await openDownloadDialog(card);

  // Select season 1, episodes 1 and 2
  await enqueueMultipleEpisodes(page, { seasons: [1], episodes: [1, 2] });

  await gotoQueue(page);

  // Wait for the first episode task to reach a download milestone
  const firstTaskId = await findLatestTaskId(page, { source: 'vixsrc' });
  await waitForStatus(page, firstTaskId, ['DOWNLOADING', 'MERGING', 'COPYING', 'COMPLETED'], { timeoutMs: 3 * 60 * 1000 });
  await waitForStatus(page, firstTaskId, ['COMPLETED'], { timeoutMs: 20 * 60 * 1000 });

  // Verify S01E01 file exists
  const s01e01Path = await findDownloadedFile('tvshows', fixture.titleHint, 'S01E01');
  await expectValidVideoFile(s01e01Path);
  expect(s01e01Path).toContain('Season 01');
});
