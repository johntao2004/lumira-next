import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const web = path.join(root, "apps/web");
const stateFile = path.join(root, ".runtime/processes.json");
let webRunning = false;
if (existsSync(stateFile)) {
  const processes = JSON.parse(readFileSync(stateFile, "utf8"));
  webRunning = processes.some(({ name, pid }) => {
    if (name !== "web") return false;
    try { process.kill(pid, 0); return true; }
    catch { return false; }
  });
}

function run(script, args = []) {
  const result = spawnSync(process.execPath, [path.join(web, "node_modules", script), ...args], {
    cwd: web,
    env: process.env,
    stdio: "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}

if (!webRunning) {
  run("@umijs/max/bin/max.js", ["setup"]);
} else if (!existsSync(path.join(web, "src/.umi/exports.ts"))) {
  throw new Error("The running preview has not generated its Umi types yet. Retry once the preview is ready.");
}
// max setup removes .umi before regenerating it. The running development
// server already maintains these files, so reuse them during type checking.
run("typescript/bin/tsc", ["--noEmit"]);
