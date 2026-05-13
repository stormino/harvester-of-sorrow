import { test, expect } from '@playwright/test';

test.describe('Smoke — app boots and basic routes render', () => {
  test('search page loads with search input', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#search-input')).toBeVisible();
    await expect(page.locator('#search-button')).toBeVisible();
    await expect(page.locator('#search-results')).toBeAttached();
  });

  test('downloads page loads with queue grid', async ({ page }) => {
    await page.goto('/downloads');
    await expect(page.locator('#queue-grid')).toBeVisible();
    await expect(page.locator('#clear-completed-button')).toBeVisible();
  });

  test('settings page loads', async ({ page }) => {
    await page.goto('/settings');
    await expect(page.locator('#settings-view')).toBeVisible();
  });
});
