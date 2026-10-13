import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const supportSource = readFileSync(path.join(projectRoot, 'scripts', 'smoke_support.py'), 'utf8');
const probe = supportSource.match(/_SESSION_BINDING_PROBE = r'''([\s\S]*?)'''/)?.[1];
assert.ok(probe, 'smoke_support.py must expose the guarded bounded session probe');

const sessionId = '550e8400-e29b-41d4-a716-446655440000';
const cookie = Buffer.from(sessionId, 'ascii').toString('base64');
const body = JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' });
let responseMode = 'content-length';

test('bounded QA session probe handles fixed/chunked EOF and rejects oversized/slow responses', async () => {
  const server = createServer((_request, response) => {
    const headers = {
      'content-type': 'application/json',
      'set-cookie': `LUMIRA_NEXT_SESSION=${cookie}; Path=/; HttpOnly; SameSite=Lax`,
    };
    if (responseMode === 'oversized') {
      const oversized = 'x'.repeat(256);
      response.writeHead(200, { ...headers, 'content-length': Buffer.byteLength(oversized) });
      response.end(oversized);
      return;
    }
    if (responseMode === 'slow') {
      response.writeHead(200, { ...headers, 'content-length': Buffer.byteLength(body) });
      response.write(body.slice(0, 8));
      setTimeout(() => {
        if (!response.destroyed) response.end(body.slice(8));
      }, 250);
      return;
    }
    if (responseMode === 'content-length') {
      response.writeHead(200, { ...headers, 'content-length': Buffer.byteLength(body) });
      response.end(body);
      return;
    }
    response.writeHead(200, headers);
    response.write(body.slice(0, 8));
    response.end(body.slice(8));
  });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  assert.ok(address && typeof address === 'object');

  function runProbe({ maxBytes = 1024, socketTimeout = 0.5 } = {}) {
    const env = {
      ...process.env,
      QA_TARGET_ORIGIN: `http://127.0.0.1:${address.port}`,
      QA_TARGET_HTTP_TIMEOUT: String(socketTimeout),
      QA_TARGET_TOTAL_TIMEOUT: '2',
      QA_TARGET_MAX_BYTES: String(maxBytes),
      QA_TARGET_READ_CHUNK_BYTES: '16',
      SSL_CERT_FILE: '',
      SSL_CERT_DIR: '',
    };
    return new Promise((resolve, reject) => {
      const child = spawn('python3', ['-c', probe], { env });
      let output = '';
      child.stdout.setEncoding('utf8').on('data', chunk => { output += chunk; });
      child.stderr.resume();
      const timeout = setTimeout(() => child.kill('SIGKILL'), 4_000);
      child.once('error', reject);
      child.once('close', code => {
        clearTimeout(timeout);
        resolve({ code, output: output.trim() });
      });
    });
  }

  try {
    responseMode = 'content-length';
    assert.deepEqual(await runProbe(), { code: 0, output: sessionId });

    responseMode = 'chunked';
    assert.deepEqual(await runProbe(), { code: 0, output: sessionId });

    responseMode = 'oversized';
    assert.equal((await runProbe({ maxBytes: 64 })).code, 2);

    responseMode = 'slow';
    assert.equal((await runProbe({ socketTimeout: 0.05 })).code, 2);
  } finally {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});
