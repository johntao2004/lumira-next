import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const runtime = path.join(root, ".runtime");
const stateFile = path.join(runtime, "processes.json");

const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

const processes = existsSync(stateFile)
  ? JSON.parse(readFileSync(stateFile, "utf8"))
  : [];

for (const processInfo of processes) {
  console.log(`${processInfo.name}: ${alive(processInfo.pid) ? "running" : "stopped"} (pid ${processInfo.pid})`);
}
if (!processes.length) console.log("scaffold: stopped");

const checks = [
  ["backend", "http://127.0.0.1:8090/actuator/health/readiness"],
  ["frontend", "http://127.0.0.1:8010/user/login"],
];
for (const [name, url] of checks) {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(3000) });
    console.log(`${name} health: HTTP ${response.status}`);
  } catch (error) {
    console.log(`${name} health: unavailable (${error instanceof Error ? error.message : "unknown error"})`);
  }
}
