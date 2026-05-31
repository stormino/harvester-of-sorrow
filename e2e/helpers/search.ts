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
 * Types a value into a vaadin-multi-select-combo-box and confirms it with Enter
 * so the custom value is accepted as a chip/tag.
 */
async function selectComboValue(page: Page, fieldId: string, value: number): Promise<void> {
  const input = page.locator(`#${fieldId}`).locator('input');
  await input.fill(String(value));
  // Accept the custom value — Vaadin fires a custom-value-set event on Enter
  await input.press('Enter');
}

/**
 * In an open download dialog: selects a single season and single episode using
 * the multi-select combo boxes, then clicks "Add to Queue".
 *
 * Selecting a season triggers a Vaadin server round-trip that enables the episode
 * field, so we wait for it to become editable before filling it.
 */
export async function enqueueEpisode(
  page: Page,
  opts: { season: number; episode: number },
): Promise<void> {
  await selectComboValue(page, 'dialog-season-field', opts.season);
  // Wait for the server to enable the episode field after the season selection
  await page.locator('#dialog-episode-field:not([disabled])').waitFor({ state: 'attached', timeout: 5_000 });
  await selectComboValue(page, 'dialog-episode-field', opts.episode);
  await page.locator('#dialog-confirm-download').click();
}

/**
 * In an open download dialog: selects multiple seasons and/or multiple episodes,
 * then clicks "Add to Queue". Pass empty arrays to leave a field blank (= all).
 *
 * Note: episode selection is only available when exactly one season is selected.
 * If multiple seasons are passed, episodes are ignored (selecting entire seasons).
 */
export async function enqueueMultipleEpisodes(
  page: Page,
  opts: { seasons: number[]; episodes: number[] },
): Promise<void> {
  for (const s of opts.seasons) {
    await selectComboValue(page, 'dialog-season-field', s);
  }
  if (opts.seasons.length === 1 && opts.episodes.length > 0) {
    // Wait for episode field to be enabled after the single season selection
    await page.locator('#dialog-episode-field:not([disabled])').waitFor({ state: 'attached', timeout: 5_000 });
    for (const e of opts.episodes) {
      await selectComboValue(page, 'dialog-episode-field', e);
    }
  }
  await page.locator('#dialog-confirm-download').click();
}



