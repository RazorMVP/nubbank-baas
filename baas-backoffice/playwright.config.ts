import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  use: { baseURL: 'http://localhost:3001', trace: 'on-first-retry' },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3001',
    reuseExistingServer: !process.env.CI,
    env: {
      VITE_DEV_AUTH: 'true',
      VITE_DEV_AUTHORITIES: 'READ_CUSTOMER,CREATE_CUSTOMER',
      VITE_API_BASE_URL: 'http://localhost:8080',
    },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
