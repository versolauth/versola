import { defineConfig } from '@playwright/test';
import base from './playwright.config.ts';
export default defineConfig({
  ...base,
  testDir: './tests',
  use: { ...base.use, baseURL: 'http://127.0.0.1:4291' },
  webServer: { ...(base.webServer as object), command: 'npm run dev -- --host 127.0.0.1 --port 4291 --strictPort', url: 'http://127.0.0.1:4291', reuseExistingServer: false, cwd: '/Users/gosha/IdeaProjects/versola-root/versola/.claude/worktrees/affectionate-edison-595047/central-ui' } as never,
});
