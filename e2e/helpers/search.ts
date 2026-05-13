import { type Page, type Locator } from '@playwright/test';

export type Source = 'vixsrc' | 'raiplay';
export type ContentType = 'MOVIE' | 'TV';

/**
 * Fills the search box, submits, and returns the first result card matching
 * the given source and content type. Waits for at least one matching card.
 */
export async function searchAndPickFirst(
  page: Page,
  query: string,
  source: Source,
  type: ContentType,
): Promise<Locator> {
  await page.goto('/');
  // vaadin-text-field is a Web Component — the real <input> is inside its shadow root
  await page.locator('#search-input').locator('input').fill(query);
  await page.locator('#search-button').click();

  const card = page
    .locator(`[data-testid="result-card"][data-source="${source}"][data-type="${type}"]`)
    .first();

  await card.waitFor({ state: 'visible', timeout: 30_000 });
  return card;
}

/**
 * Clicks the download button on a result card to open the download dialog.
 */
export async function openDownloadDialog(card: Locator): Promise<void> {
  const btn = card.locator('[data-testid="result-card-download"]');
  await btn.waitFor({ state: 'visible' });
  await btn.click();

  // Wait for dialog footer button to appear as a proxy for the dialog being open
  await card.page().locator('#dialog-confirm-download').waitFor({ state: 'visible', timeout: 5_000 });
}

/**
 * In an open download dialog: optionally sets quality, then clicks "Add to Queue".
 *
 * Quality selection is skipped when the value matches the app default ('worst')
 * because vaadin-select requires a click-based interaction; the default is
 * pre-selected by the app so no interaction is needed in the common case.
 * Language is controlled by DEFAULT_LANGUAGE in .env.e2e and pre-selected by the app.
 */
export async function enqueueMovie(page: Page): Promise<void> {
  await page.locator('#dialog-confirm-download').click();
}

/**
 * In an open download dialog: fills season/episode, optionally sets quality,
 * then clicks "Add to Queue".
 */
export async function enqueueEpisode(
  page: Page,
  opts: { season: number; episode: number },
): Promise<void> {
  // vaadin-integer-field is also a Web Component — pierce to the inner <input>
  await page.locator('#dialog-season-field').locator('input').fill(String(opts.season));
  await page.locator('#dialog-episode-field').locator('input').fill(String(opts.episode));
  await page.locator('#dialog-confirm-download').click();
}



