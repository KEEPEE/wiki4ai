import { test, expect } from '@playwright/test';

const MASTER_PASSWORD = 'super-secret-master-pw';

test.describe('Vault - create and edit password entries via webui', () => {
  test('register, set up vault, add an entry, then edit it end-to-end', async ({ page, context }) => {
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

    // --- Edit the entry ---
    await entryCard.locator('[data-testid^="vault-edit-entry-"]').click();

    // Form should be pre-filled with the existing (decrypted) values.
    await expect(page.getByTestId('vault-form-title-input')).toHaveValue(entryTitle);
    await expect(page.getByTestId('vault-form-username-input')).toHaveValue('e2e-user');
    await expect(page.getByTestId('vault-form-password-input')).toHaveValue('correct-horse-battery-staple');

    const updatedTitle = `${entryTitle} (updated)`;
    await page.getByTestId('vault-form-title-input').fill(updatedTitle);
    await page.getByTestId('vault-form-username-input').fill('e2e-user-updated');
    await page.getByTestId('vault-form-password-input').fill('new-correct-horse-battery-staple');

    await page.getByRole('button', { name: 'Save Changes' }).click();

    await expect(page.getByText(updatedTitle)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('e2e-user-updated')).toBeVisible();
    await expect(page.getByText(entryTitle, { exact: true })).toHaveCount(0);

    // Reload and unlock again to prove the edit persisted and re-decrypts correctly,
    // not just that the optimistic UI update looked right.
    await page.reload();
    await page.getByTestId('vault-unlock-password-input').fill(MASTER_PASSWORD);
    await page.getByTestId('vault-unlock-submit-button').click();

    await expect(page.getByText(updatedTitle)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('e2e-user-updated')).toBeVisible();

    const updatedCard = page.locator('[data-testid^="vault-entry-"]', { hasText: updatedTitle });
    await updatedCard.locator('[data-testid^="vault-copy-password-"]').click();
    const updatedClipboardText = await page.evaluate(() => navigator.clipboard.readText());
    expect(updatedClipboardText).toBe('new-correct-horse-battery-staple');
  });

  test('unlocking from a new browser recovers the salt from the backend (no reinit)', async ({ browser }) => {
    const runId = Date.now();
    const username = `e2e_reinit_${runId}`;
    const password = 'Register-Pass-123!';
    const entryTitle = `Reinit Test Entry ${runId}`;

    // --- First "browser": register, set up vault, add an entry ---
    const contextA = await browser.newContext();
    const pageA = await contextA.newPage();

    await pageA.goto('/register');
    await pageA.getByTestId('register-username').fill(username);
    await pageA.getByTestId('register-email').fill(`${username}@example.com`);
    await pageA.getByTestId('register-password').fill(password);
    await pageA.getByTestId('register-submit').click();
    await pageA.waitForURL((url) => url.pathname.startsWith('/login'), { timeout: 10_000 });

    await pageA.getByTestId('login-username').fill(username);
    await pageA.getByTestId('login-password').fill(password);
    await pageA.getByTestId('login-submit').click();
    await pageA.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 10_000 });

    await pageA.goto('/vault');
    await expect(pageA.getByTestId('vault-setup-password-input')).toBeVisible({ timeout: 10_000 });
    await pageA.getByTestId('vault-setup-password-input').fill(MASTER_PASSWORD);
    await pageA.getByTestId('vault-setup-confirm-input').fill(MASTER_PASSWORD);
    await pageA.getByTestId('vault-setup-submit-button').click();

    await expect(pageA.getByTestId('vault-add-entry-button')).toBeVisible({ timeout: 10_000 });
    await pageA.getByTestId('vault-add-entry-button').click();
    await pageA.getByTestId('vault-form-title-input').fill(entryTitle);
    await pageA.getByTestId('vault-form-username-input').fill('reinit-user');
    await pageA.getByTestId('vault-form-password-input').fill('reinit-password-123');
    await pageA.getByRole('button', { name: 'Add Entry' }).click();
    await expect(pageA.getByText(entryTitle)).toBeVisible({ timeout: 10_000 });

    await contextA.close();

    // --- Second "browser" (fresh storage, no vault salt locally): log in, open vault ---
    const contextB = await browser.newContext();
    const pageB = await contextB.newPage();

    await pageB.goto('/login');
    await pageB.getByTestId('login-username').fill(username);
    await pageB.getByTestId('login-password').fill(password);
    await pageB.getByTestId('login-submit').click();
    await pageB.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 10_000 });

    await pageB.goto('/vault');

    // Must show the normal unlock screen (salt recovered from backend), NOT the
    // "re-initialize vault" screen that would silently orphan the entry above.
    await expect(pageB.getByTestId('vault-unlock-password-input')).toBeVisible({ timeout: 10_000 });
    await expect(pageB.getByText(/re-initialize|initialize encryption/i)).toHaveCount(0);

    await pageB.getByTestId('vault-unlock-password-input').fill(MASTER_PASSWORD);
    await pageB.getByTestId('vault-unlock-submit-button').click();

    await expect(pageB.getByText(entryTitle)).toBeVisible({ timeout: 10_000 });
    await expect(pageB.getByText('reinit-user')).toBeVisible();

    await contextB.close();
  });
});
