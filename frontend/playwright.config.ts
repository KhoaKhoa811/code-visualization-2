import { defineConfig } from '@playwright/test';

// Browser check of the full slice: installed Microsoft Edge (no browser
// download), the Vite dev server and the real backend jar, which runs Java in
// Docker. Build the backend from the repository root first; Docker Desktop must run.
const java = process.env.JAVA_HOME ? `"${process.env.JAVA_HOME}/bin/java"` : 'java';

export default defineConfig({
  testDir: 'e2e',
  timeout: 120_000,
  expect: { timeout: 60_000 },
  workers: 1,
  reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:5173', channel: 'msedge', viewport: { width: 1600, height: 900 }, // Part 23: three columns need a desktop width
    headless: true, trace: 'retain-on-failure' },
  webServer: [
    {
      command: `${java} -jar ../backend/target/backend-0.0.1-SNAPSHOT.jar`,
      port: 8080,
      timeout: 60_000,
      reuseExistingServer: false,
      stdout: 'ignore',
    },
    {
      command: 'npm run dev',
      url: 'http://127.0.0.1:5173',
      timeout: 60_000,
      reuseExistingServer: false,
    },
  ],
});
