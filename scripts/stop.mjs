import { existsSync, readFileSync, unlinkSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { processGroupRunning } from "./process-group.mjs";
import { stopOfficeWorker } from "./office-worker-runtime.mjs";
const file = fileURLToPath(
  new URL("../.runtime/processes.json", import.meta.url),
);
if (existsSync(file)) {
  const processes = JSON.parse(readFileSync(file));
  for (const { pid } of processes) {
    try {
      process.kill(-pid, "SIGTERM");
    } catch (error) {
      if (error.code !== "ESRCH") throw error;
    }
  }
  // Graceful shutdown is asynchronous; retain state until every process group exits.
  const deadline = Date.now() + 30000;
  for (;;) {
    const running = processes.filter(({ pid }) => processGroupRunning(pid));
    if (!running.length) break;
    if (Date.now() >= deadline) throw new Error("Scaffold is still stopping; process state retained. Retry after shutdown completes.");
    await delay(250);
  }
  unlinkSync(file);
}
await stopOfficeWorker();
console.log("Stopped scaffold processes only.");
