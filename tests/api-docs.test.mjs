import assert from 'node:assert/strict';
import test from 'node:test';
import { filterOpenApiDocument, OPENAPI_DOWNLOAD_FILENAME, readOnlyApiDocsConfig, serializeOpenApiDocument } from '../apps/web/src/features/monitoring/api-docs-spec.ts';
import { monitoringManifest } from '../apps/web/src/features/monitoring/manifest.ts';
import { availableNavigation, routes } from '../apps/web/src/platform/shell/routes.ts';

const spec = {
  openapi: '3.1.0',
  info: { title: 'Lumira API' },
  paths: {
    '/api/users/{id}': {
      summary: 'User endpoint',
      parameters: [{ name: 'id', in: 'path' }],
      get: { tags: ['Users'], summary: 'Read a user', responses: { 200: { description: 'User' } } },
      post: { tags: ['Users'], summary: 'Create a user', responses: { 201: { description: 'Created' } } },
    },
    '/api/health': {
      get: { tags: ['System'], summary: 'Health status', responses: { 200: { description: 'Healthy' } } },
    },
  },
};

test('API documentation search retains only matching operations and preserves path metadata', () => {
  const result = filterOpenApiDocument(spec, 'create a user');
  assert.equal(result.operationCount, 1);
  const path = result.spec.paths['/api/users/{id}'];
  assert.equal(path.summary, 'User endpoint');
  assert.deepEqual(path.parameters, [{ name: 'id', in: 'path' }]);
  assert.equal('post' in path, true);
  assert.equal('get' in path, false);
  assert.equal('/api/health' in result.spec.paths, false);
});

test('API documentation search includes paths and tags and returns all operations when cleared', () => {
  const pathResult = filterOpenApiDocument(spec, '/api/users');
  assert.equal(pathResult.operationCount, 2);
  assert.equal('get' in pathResult.spec.paths['/api/users/{id}'], true);
  assert.equal('post' in pathResult.spec.paths['/api/users/{id}'], true);
  assert.equal(filterOpenApiDocument(spec, 'system').operationCount, 1);
  assert.equal(filterOpenApiDocument(spec, '').spec, spec);
});

test('read-only Swagger UI configuration disables submission, auth storage, and external validation', () => {
  assert.deepEqual(readOnlyApiDocsConfig.supportedSubmitMethods, []);
  assert.equal(readOnlyApiDocsConfig.tryItOutEnabled, false);
  assert.equal(readOnlyApiDocsConfig.persistAuthorization, false);
  assert.equal(readOnlyApiDocsConfig.validatorUrl, null);
  assert.equal(readOnlyApiDocsConfig.oauth2RedirectUrl, null);
  assert.equal(readOnlyApiDocsConfig.queryConfigEnabled, false);
});

test('OpenAPI download keeps the complete fetched spec and a stable filename after filtering', () => {
  const filtered = filterOpenApiDocument(spec, 'create a user');
  const downloaded = JSON.parse(serializeOpenApiDocument(spec));
  assert.equal(filtered.operationCount, 1);
  assert.equal(Object.keys(downloaded.paths['/api/users/{id}']).includes('get'), true);
  assert.equal(Object.keys(downloaded.paths['/api/users/{id}']).includes('post'), true);
  assert.equal(OPENAPI_DOWNLOAD_FILENAME, 'lumira-next-openapi.json');
});

test('API documentation is in operations and requires its own global page-access grant', () => {
  assert.deepEqual(
    (({ label, permission, global }) => ({ label, permission, global }))(routes['api-docs']),
    { label: 'apiDocs', permission: 'platform:api-docs:access', global: true },
  );
  assert.equal(monitoringManifest.routes.find(route => route.id === 'api-docs').group, 'operations');

  const regularAuditPages = availableNavigation(['platform:api-docs:access'], [], ['platform:monitoring:access'])
    .flatMap(group => group.pages.map(page => page.id));
  assert.ok(regularAuditPages.includes('monitoring'));
  assert.ok(!regularAuditPages.includes('api-docs'));

  const globalAuditPages = availableNavigation([], [], ['platform:monitoring:access', 'platform:api-docs:access'])
    .flatMap(group => group.pages.map(page => page.id));
  assert.ok(globalAuditPages.includes('api-docs'));
});

test('Swagger uses the official core build with shared React rather than an embedded React bundle', async () => {
  const { readFileSync } = await import('node:fs');
  const config = readFileSync(new URL('../apps/web/.umirc.ts', import.meta.url), 'utf8');
  assert.match(config, /"swagger-ui\$": realpathSync\(resolve\(process\.cwd\(\), "node_modules\/swagger-ui\/dist\/swagger-ui\.js"\)\)/);
  const core = readFileSync(new URL('../apps/web/node_modules/swagger-ui/dist/swagger-ui.js', import.meta.url), 'utf8');
  assert.match(core, /require\("react"\)/);
  assert.match(core, /require\("lodash\/memoize"\)/);
  assert.doesNotMatch(core, /ReactCurrentDispatcher/);
});
