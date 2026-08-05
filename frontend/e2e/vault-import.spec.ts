import { test, expect } from '@playwright/test';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const MASTER_PASSWORD = 'super-secret-master-pw';
// Synthetic fixture with dummy entries (same one backend's VaultImportServiceTest uses) -
// NOT a real credential store.
const KDBX_FIXTURE = path.resolve(__dirname, '../../backend/src/test/resources/test-sample.kdbx');
const KDBX_PASSWORD = 'testpass12345678';

// Regression test for the KDBX import 500 error: imports a KDBX database
// through the actual webui flow, hitting the real backend endpoint end-to-end.
test('import a KDBX database via the webui', async ({ page }) => {
  const runId = Date.now();
  const username = `e2e_import_${runId}`;
  const password = 'Register-Pass-123!';

  await page.goto('/register');
  await page.getByTestId('register-username').fill(username);
  await page.getByTestId('register-email').fill(`${username}@example.com`);
  await page.getByTestId('register-password').fill(password);
  await page.getByTestId('register-submit').click();
  await page.waitForURL((url) => url.pathname.startsWith('/login'), { timeout: 10_000 });

  await page.getByTestId('login-username').fill(username);
  await page.getByTestId('login-password').fill(password);
  await page.getByTestId('login-submit').click();
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 10_000 });

  await page.goto('/vault');
  await expect(page.getByTestId('vault-setup-password-input')).toBeVisible({ timeout: 10_000 });
  await page.getByTestId('vault-setup-password-input').fill(MASTER_PASSWORD);
  await page.getByTestId('vault-setup-confirm-input').fill(MASTER_PASSWORD);
  await page.getByTestId('vault-setup-submit-button').click();
  await expect(page.getByTestId('vault-add-entry-button')).toBeVisible({ timeout: 10_000 });

  await page.getByTestId('vault-import-button').click();
  await expect(page.getByTestId('vault-import-modal')).toBeVisible();

  await page.getByTestId('vault-import-file-input').setInputFiles(KDBX_FIXTURE);
  await page.getByTestId('vault-import-password-input').fill(KDBX_PASSWORD);
  await page.getByTestId('vault-import-submit-button').click();

  await expect(page.getByTestId('vault-import-modal')).toHaveCount(0, { timeout: 30_000 });

  await expect(page.getByText('Test Entry 1')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText('user1@example.com')).toBeVisible();
});
