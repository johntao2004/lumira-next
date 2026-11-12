import http from 'node:http';
import { performance } from 'node:perf_hooks';

// Called by the database/PID-bound Python suite after it installs its own public probe.
const target = new URL(process.argv[2]);
if (target.protocol !== 'http:' || target.hostname !== '127.0.0.1'
    || target.pathname !== '/api/plugins/qa-plugin-load/status'
    || target.username || target.password || target.search || target.hash) {
  throw new Error('Only the isolated loopback plugin load fixture is supported');
}
const concurrency = 1000;
const requests = 10000;
const agent = new http.Agent({ keepAlive: true, maxSockets: concurrency, maxFreeSockets: concurrency });
const statuses = {};
const samples = [];
let completed = 0;
let unexpected = 0;
let active = 0;
let peakInFlight = 0;

function request() {
  return new Promise(resolve => {
    const start = performance.now();
    active++;
    peakInFlight = Math.max(peakInFlight, active);
    let finished = false;
    const finish = (status, valid) => {
      if (finished) return;
      finished = true;
      active--;
      completed++;
      statuses[status] = (statuses[status] || 0) + 1;
      if (!valid) unexpected++;
      if (status === 200 && valid) samples.push(performance.now() - start);
      resolve();
    };
    const call = http.get(target, { agent }, response => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', chunk => {
        body += chunk;
        if (body.length > 16384) call.destroy(new Error('Response exceeded probe budget'));
      });
      response.on('error', () => finish(0, false));
      response.on('end', () => {
        let payload;
        try { payload = JSON.parse(body); } catch { finish(response.statusCode, false); return; }
        const valid = response.statusCode === 200 ? payload.code === 'new-runtime-code'
          : response.statusCode === 503 && payload.code === 'serverBusy';
        finish(response.statusCode, valid);
      });
    });
    call.on('error', () => finish(0, false));
    call.setTimeout(10000, () => call.destroy(new Error('Probe timeout')));
  });
}

const start = performance.now();
try {
  await Promise.all(Array.from({ length: concurrency }, async () => {
    for (let iteration = 0; iteration < requests / concurrency; iteration++) await request();
  }));
} finally {
  agent.destroy();
}
const duration = (performance.now() - start) / 1000;
samples.sort((a, b) => a - b);
const percentile = fraction => samples.length ? Math.round(samples[Math.ceil(samples.length * fraction) - 1] * 100) / 100 : null;
process.stdout.write(`${JSON.stringify({
  model: 'closed-loop synthetic public plugin probe; not authenticated users or mixed CRUD',
  requests, completed, concurrency, peakInFlight, statuses, unexpected,
  seconds: Math.round(duration * 100) / 100,
  successfulRps: Math.round(samples.length / duration * 100) / 100,
  totalResponseRps: Math.round(completed / duration * 100) / 100,
  successfulP95Ms: percentile(0.95), successfulP99Ms: percentile(0.99),
})}\n`);
if (unexpected || completed !== requests || !samples.length) process.exitCode = 1;
