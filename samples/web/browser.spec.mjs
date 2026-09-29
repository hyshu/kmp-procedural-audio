import { test, expect } from '@playwright/test';

test('the real worklet loads and the sample plays, switches, pauses and resumes', async ({
  page,
}) => {
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  // A favicon is outside this sample and would otherwise produce an unrelated 404.
  await page.route('**/favicon.ico', (route) => route.fulfill({ status: 204 }));
  await page.goto('/');

  const status = page.getByRole('status');
  await expect(status).toHaveText('Ready. Tap Play to enable audio.');
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  // Playing is reported only after AudioWorklet.addModule and resume resolve.
  await expect(status).toHaveText('Playing · sine');

  await page.getByLabel('Sound').selectOption('noise');
  await expect(status).toHaveText('Playing · noise');
  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  await expect(status).toHaveText('Paused · noise');
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await expect(status).toHaveText('Playing · noise');

  // Keep the graph active across multiple real callbacks before checking for
  // asynchronous processor failures. PCM values are covered by compiled tests.
  await page.waitForTimeout(250);
  await expect(status).toHaveText('Playing · noise');
  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  await expect(status).toHaveText('Paused · noise');
  expect(errors).toEqual([]);
});
