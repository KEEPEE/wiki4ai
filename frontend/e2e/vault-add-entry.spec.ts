import { test, expect } from '@playwright/test';

const MASTER_PASSWORD = 'super-secret-master-pw';

test.describe('Vault - add password entry via webui', () => {
  test('register, set up vault, and add a new entry end-to-end', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);

    // Register a fresh user per run so vault state (master password) never leaks
    // between runs/backends - the vault flow starts from a clean slate every time.
    const runId = Date.now();
    const username = `e2e_${runId}`;

    await page.goto('/register');
    await page.getByTestId('register-username').fill(username);
    await page.getByTestId('register-email').fill(`${username}@example.com`);
    await page.getByTestId('register-password').fill('Register-Pass-123!');
    await page.getByTestId('register-submit').click();
    await page.waitForURL((url) => url.pathname.startsWith('/login'), { timeout: 10_000 });

    // Registration does not auto-login - log in with the new account.
    await page.getByTestId('login-username').fill(username);
    await page.getByTestId('login-password').fill('Register-Pass-123!');
    await page.getByTestId('login-submit').click();
    await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 10_000 });

    await page.goto('/vault');

    // First-time setup screen (no master password set yet) is expected for a brand-new user.
    await expect(page.getByTestId('vault-setup-password-input')).toBeVisible({ timeout: 10_000 });
    await page.getByTestId('vault-setup-password-input').fill(MASTER_PASSWORD);
    await page.getByTestId('vault-setup-confirm-input').fill(MASTER_PASSWORD);
    await page.getByTestId('vault-setup-submit-button').click();

    // This is the exact step that used to crash with React error #310.
    await expect(page.getByTestId('vault-add-entry-button')).toBeVisible({ timeout: 10_000 });

    await page.getByTestId('vault-add-entry-button').click();

    const entryTitle = `E2E Test Entry ${runId}`;
    await page.getByTestId('vault-form-title-input').fill(entryTitle);
    await page.getByTestId('vault-form-username-input').fill('e2e-user');
    await page.getByTestId('vault-form-password-input').fill('correct-horse-battery-staple');

    await page.getByRole('button', { name: 'Add Entry' }).click();

    await expect(page.getByText(entryTitle)).toBeVisible({ timeout: 10_000 });
    // The stored username decrypts and renders next to the entry - proves the
    // encrypt (client) -> store (backend) -> fetch -> decrypt (client) round-trip works,
    // not just that the create request was accepted.
    await expect(page.getByText('e2e-user')).toBeVisible();

    // Reload and unlock again: re-derives the key from scratch and re-fetches/decrypts
    // from the backend, exercising the full persistence round-trip independent of client state.
    await page.reload();
    await page.getByTestId('vault-unlock-password-input').fill(MASTER_PASSWORD);
    await page.getByTestId('vault-unlock-submit-button').click();

    await expect(page.getByText(entryTitle)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('e2e-user')).toBeVisible();

    // Proves the password itself (not just the username) round-trips correctly.
    const entryCard = page.locator('[data-testid^="vault-entry-"]', { hasText: entryTitle });
    await entryCard.locator('[data-testid^="vault-copy-password-"]').click();
    const clipboardText = await page.evaluate(() => navigator.clipboard.readText());
    expect(clipboardText).toBe('correct-horse-battery-staple');
  });
});
