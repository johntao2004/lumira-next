/* 检查前端关键规范；历史依赖和样式警告可见，结构与 Hooks 错误阻断构建。
 * Check frontend contracts: keep legacy dependency/style warnings visible and block structural or Hooks errors.
 */
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = process.env.LUMIRA_REACT_LINT_REPORT;
const require = createRequire(path.join(root, 'package.json'));
const { Linter } = require('eslint');
const parser = require('@babel/eslint-parser');
const react = require('eslint-plugin-react');
const hooks = require('eslint-plugin-react-hooks');
const linter = new Linter();
linter.defineParser('babel-diagnostic', parser);
for (const [name, rule] of Object.entries(react.rules)) linter.defineRule(`react/${name}`, rule);
for (const [name, rule] of Object.entries(hooks.rules)) linter.defineRule(`react-hooks/${name}`, rule);
const config = {
  parser: 'babel-diagnostic',
  parserOptions: {
    requireConfigFile: false,
    babelOptions: { parserOpts: { plugins: ['typescript', 'jsx'] } },
    ecmaVersion: 2022,
    sourceType: 'module',
  },
  settings: { react: { version: '19.2.8' } },
  rules: {
    'react-hooks/rules-of-hooks': 'error',
    'react-hooks/exhaustive-deps': 'warn',
    'react/jsx-no-duplicate-props': 'error',
    'react/no-string-refs': 'error',
    'react/no-deprecated': 'error',
    'react/no-danger-with-children': 'error',
    'react/void-dom-elements-no-children': 'error',
    'react/style-prop-object': 'error',
    'react/jsx-no-target-blank': 'error',
    'react/no-children-prop': 'error',
    'react/jsx-boolean-value': 'warn',
    'react/jsx-tag-spacing': ['warn', { beforeSelfClosing: 'always' }],
    'react/jsx-key': 'error',
    'react/no-array-index-key': 'warn',
  },
};
function filesIn(dir) {
  const files = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (['node_modules', 'dist', '.umi', '.umi-production', 'target'].includes(entry.name)) continue;
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) files.push(...filesIn(file));
    else if (/\.(?:[jt]sx?|mjs)$/.test(entry.name) && !/\.(?:test|spec)\.(?:[jt]sx?|mjs)$|\.d\.ts$/.test(entry.name)) files.push(file);
  }
  return files;
}
const appFiles = filesIn(path.join(root, 'apps/web/src'));
const pluginFiles = filesIn(path.join(root, 'plugins')).filter(file => file.split(path.sep).includes('frontend'));
const files = [...new Set([...appFiles, ...pluginFiles])].sort();
const result = [];
const counts = {};
let errors = 0;
for (const file of files) {
  const relative = path.relative(root, file);
  const messages = linter.verify(readFileSync(file, 'utf8'), config, { filename: relative });
  if (messages.length) result.push({ file: relative, messages });
  for (const message of messages) {
    const name = message.ruleId ?? 'parse-error';
    counts[name] = (counts[name] ?? 0) + 1;
    if (message.severity === 2) errors++;
  }
}
const report = {
  note: 'Selected React correctness rules; warnings remain visible. Not a comprehensive React 19 or accessibility audit.',
  files: files.length,
  counts,
  errors,
  result,
};
if (output) writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify({ files: files.length, affected: result.length, counts, errors, output }, null, 2));
process.exitCode = errors ? 1 : 0;
