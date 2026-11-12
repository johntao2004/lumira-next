import { build } from 'esbuild';
import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import path from 'node:path';

// Exercise real React mount/unmount and error boundaries without any business database.
const root = process.cwd();
const bundle = await build({
  stdin: {
    contents: `
      import React, {useEffect, useState} from 'react';
      import {createRoot} from 'react-dom/client';
      import {WorkspaceModuleActions} from './src/platform/shell/workspace-module-actions';
      import {registry} from 'qa-action-registry';
      window.cleaned = 0;
      function Control() {
        const [count, setCount] = useState(0);
        useEffect(() => () => { window.cleaned++; }, []);
        return <button onClick={() => setCount(value => value + 1)}>Count {count}</button>;
      }
      function Broken() { throw new Error('intentional action failure'); }
      let dispose;
      window.install = (broken = false) => {
        dispose = registry.registerPlugin({id:'probe',version:'1',required:false,dependencies:[],routes:[],pages:{},
          workspaceActions:[{id:'counter',component:Control}, ...(broken ? [{id:'broken',component:Broken}] : [])]});
      };
      window.uninstall = () => dispose?.();
      createRoot(document.getElementById('root')).render(<><span>Shell available</span><WorkspaceModuleActions /></>);
    `,
    resolveDir: path.join(root, 'apps/web'),
    loader: 'tsx',
  },
  bundle: true,
  write: false,
  format: 'iife',
  jsx: 'automatic',
  define: { 'process.env.NODE_ENV': '"production"' },
  plugins: [{
    name: 'isolated-action-registry',
    setup(builder) {
      builder.onResolve({ filter: /^(qa-action-registry|\.\.\/modules\/registry)$/ }, () => ({ path: 'actions', namespace: 'qa' }));
      builder.onLoad({ filter: /.*/, namespace: 'qa' }, () => ({
        contents: `import {createFrontendPageRegistry} from './src/platform/modules/contracts';
          export const registry = createFrontendPageRegistry([]);
          export const frontendWorkspaceActions = registry.workspaceActions;
          export const subscribeFrontendWorkspaceActions = registry.subscribeWorkspaceActions;`,
        resolveDir: path.join(root, 'apps/web'),
        loader: 'ts',
      }));
    },
  }],
});
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
try {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.setContent('<div id="root"></div>');
  await page.addScriptTag({ content: bundle.outputFiles[0].text });
  await expect(page.getByText('Shell available')).toBeVisible();
  await page.evaluate(() => window.install());
  await page.getByRole('button', { name: 'Count 0', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Count 1', exact: true })).toBeVisible();
  await page.evaluate(() => window.uninstall());
  await expect(page.getByRole('button')).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => window.cleaned)).toBe(1);
  await page.evaluate(() => window.install(true));
  await expect(page.getByRole('button', { name: 'Count 0', exact: true })).toBeVisible();
  await expect(page.getByText('Shell available')).toBeVisible();
  await page.getByRole('button', { name: 'Count 0', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Count 1', exact: true })).toBeVisible();
  await page.evaluate(() => window.uninstall());
  await expect.poll(() => page.evaluate(() => window.cleaned)).toBe(2);
  await expect(page.getByRole('button')).toHaveCount(0);
  assert.deepEqual(errors, [], 'an optional action failure must remain within its boundary');
  console.log('PASS: dynamic header controls mount, update, dispose effects, remount fresh and isolate rendering failures');
} finally {
  await browser.close();
}
