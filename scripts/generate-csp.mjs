// Builds the Content-Security-Policy Caddy snippet for the static web image.
// Inline <script> blocks emitted into index.html are allowed by SHA-256 hash, so the policy
// never needs 'unsafe-inline' for scripts. Usage: node generate-csp.mjs <index.html> <out.caddy>
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

export function inlineScriptHashes(html) {
  const hashes = [];
  for (const match of html.matchAll(/<script(?<attrs>[^>]*)>(?<body>[\s\S]*?)<\/script>/gi)) {
    if (/\ssrc\s*=/i.test(match.groups.attrs) || match.groups.body.trim() === "") continue;
    hashes.push(`'sha256-${createHash("sha256").update(match.groups.body).digest("base64")}'`);
  }
  return [...new Set(hashes)];
}

export function buildPolicy(html) {
  return [
    "default-src 'self'",
    `script-src 'self' ${inlineScriptHashes(html).join(" ")}`.trim(),
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob: https:",
    "font-src 'self' data:",
    "connect-src 'self'",
    "frame-src 'self'",
    "media-src 'self' data: blob:",
    "worker-src 'self' blob:",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join("; ");
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const [input, output] = process.argv.slice(2);
  if (!input || !output) {
    console.error("usage: node generate-csp.mjs <index.html> <out.caddy>");
    process.exit(2);
  }
  writeFileSync(output, `header Content-Security-Policy "${buildPolicy(readFileSync(input, "utf8"))}"\n`);
}
