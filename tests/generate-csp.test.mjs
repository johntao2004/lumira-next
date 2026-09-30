import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { buildPolicy, inlineScriptHashes } from "../scripts/generate-csp.mjs";

const sha = (body) => `'sha256-${createHash("sha256").update(body).digest("base64")}'`;

test("inline scripts are allowed by hash, external scripts are not hashed", () => {
  const html = `<script>window.a=1</script><script src="/umi.js"></script><script>  </script>`;
  assert.deepEqual(inlineScriptHashes(html), [sha("window.a=1")]);
});

test("policy never permits unsafe script execution or framing", () => {
  const policy = buildPolicy(`<script>x()</script>`);
  const script = policy.split("; ").find((d) => d.startsWith("script-src"));
  assert.ok(script.includes(sha("x()")));
  assert.ok(!script.includes("unsafe-inline") && !script.includes("unsafe-eval"));
  assert.ok(policy.includes("frame-ancestors 'none'"));
  assert.ok(policy.includes("object-src 'none'"));
  assert.ok(policy.includes("default-src 'self'"));
});
