import {
  existsSync,
  copyFileSync,
  readFileSync,
  writeFileSync,
  openSync,
  closeSync,
  readdirSync,
} from "node:fs";
import { spawn } from "node:child_process";
import path from "node:path";
import net from "node:net";
import { fileURLToPath } from "node:url";
import { startOfficeWorker, stopOfficeWorker } from "./office-worker-runtime.mjs";
import {
  buildChildEnvironment,
  readLocalRuntime,
  resolveLocalBackendPort,
  resolveWorkspaceFile,
  withLocalRuntimeLock,
} from "./local-runtime-config.mjs";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
await withLocalRuntimeLock(root, "start", async () => {
const runtime = path.join(root, ".runtime");
const localRuntime = readLocalRuntime(root);
const config = localRuntime.config;
const backendPort = resolveLocalBackendPort(localRuntime, process.env);
const frontendPort = 8010;
const startupTimeoutMs = (() => {
  const raw = process.env.LUMIRA_LOCAL_STARTUP_TIMEOUT_MS;
  if (raw === undefined || raw === "") return 600000;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 30000 || value > 1800000)
    throw new Error("LUMIRA_LOCAL_STARTUP_TIMEOUT_MS must be an integer from 30000 to 1800000.");
  return value;
})();
const stateFile = path.join(runtime, "processes.json");
const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};
if (
  existsSync(stateFile) &&
  JSON.parse(readFileSync(stateFile)).some((p) => alive(p.pid))
)
  throw new Error("Scaffold is already running. Use scripts/stop.mjs first.");
for (const port of [frontendPort, backendPort])
  await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", () => reject(new Error(`Port ${port} is occupied`)));
    server.listen(port, "127.0.0.1", () => server.close(resolve));
  });
const workerUrl = localRuntime.pinned
  ? config.LUMIRA_OFFICE_WORKER_URL
  : process.env.LUMIRA_OFFICE_WORKER_URL;
const workerToken = localRuntime.pinned
  ? config.LUMIRA_OFFICE_WORKER_TOKEN
  : process.env.LUMIRA_OFFICE_WORKER_TOKEN;
if (Boolean(workerUrl) !== Boolean(workerToken))
  throw new Error("Set both LUMIRA_OFFICE_WORKER_URL and LUMIRA_OFFICE_WORKER_TOKEN, or leave both unset to start the local isolated worker.");
let managedOfficeWorker = false;
let officeWorker;
const javaHome = process.env.LUMIRA_JAVA_HOME ?? process.env.JAVA_HOME;
if (!javaHome) throw new Error("Set JAVA_HOME to the system JDK before starting Lumira.");
const java = path.join(javaHome, "bin/java");
const node = process.execPath;
const nodeMajor = Number(process.versions.node.split(".")[0]);
if (nodeMajor !== 24)
  throw new Error(`Lumira requires the native Node.js 24.x installation; received ${process.versions.node}.`);
let backendJar;
if (localRuntime.pin?.artifact) {
  backendJar = resolveWorkspaceFile(root, localRuntime.pin.artifact.path, "backend JAR");
} else {
  const jarDir = path.join(root, "backend/target");
  const jars = readdirSync(jarDir).filter((f) => f.endsWith(".jar"));
  if (jars.length !== 1) throw new Error("Build exactly one backend jar first.");
  // Run an isolated artifact: Maven may replace target JARs while this process is alive.
  backendJar = path.join(runtime, `platform-server-${Date.now()}.jar`);
  copyFileSync(path.join(jarDir, jars[0]), backendJar);
}
const pluginInventory = localRuntime.pinned
  ? await import("./local-plugin-inventory.mjs")
  : null;
if (localRuntime.pinned) {
  await pluginInventory.assertLocalPluginInventory({ root, config });
}
const processes = [];
function launch(name, command, args, cwd, extraEnv = {}) {
  const fd = openSync(path.join(runtime, `${name}.log`), "w", 0o600);
  const cookieSecure = localRuntime.pinned
    ? config.LUMIRA_COOKIE_SECURE ?? "false"
    : process.env.LUMIRA_COOKIE_SECURE ?? config.LUMIRA_COOKIE_SECURE ?? "false";
  const childEnv = buildChildEnvironment(process.env, config, {
    pinned: localRuntime.pinned,
    service: name,
    extra: { ...extraEnv, LUMIRA_COOKIE_SECURE: cookieSecure },
  });
  const child = spawn(command, args, {
    cwd,
    env: childEnv,
    detached: true,
    stdio: ["ignore", fd, fd],
  });
  child.on("error", (error) => console.error(name, error.message));
  closeSync(fd);
  child.unref();
  if (!child.pid) throw new Error(`Unable to start ${name}`);
  processes.push({ name, pid: child.pid });
  writeFileSync(stateFile, JSON.stringify(processes));
}
try {
  if (workerUrl) officeWorker = { url: workerUrl, token: workerToken };
  else {
    managedOfficeWorker = true;
    officeWorker = await startOfficeWorker();
  }
  launch("backend", java, ["-jar", backendJar], root, {
    LUMIRA_OFFICE_WORKER_URL: officeWorker.url,
    LUMIRA_OFFICE_WORKER_TOKEN: officeWorker.token,
  });
  const productionWeb = process.env.LUMIRA_WEB_MODE === "production";
  const maxCli = path.join(root, "apps/web/node_modules/@umijs/max/bin/max.js");
  if (productionWeb) {
    const productionIndex = path.join(root, "apps/web/dist/index.html");
    if (!existsSync(productionIndex)) throw new Error("Build the Umi web app before starting the production preview.");
    launch("web", node, [maxCli, "preview", "--host", "127.0.0.1", "--port", String(frontendPort)], path.join(root, "apps/web"), { NODE_ENV: "production" });
  } else {
    launch(
      "web",
      node,
      [maxCli, "dev"],
      path.join(root, "apps/web"),
      { PORT: String(frontendPort), HOST: "127.0.0.1", STRICT_PORT: String(frontendPort) },
    );
  }
  for (const [url, timeoutMs, verifyWebAssets] of [
    [`http://127.0.0.1:${backendPort}/actuator/health/readiness`, Math.min(startupTimeoutMs, 180000), false],
    [`http://127.0.0.1:${frontendPort}/user/login`, startupTimeoutMs, !productionWeb],
  ]) {
    const deadline = Date.now() + timeoutMs;
    let ready = false;
    while (Date.now() < deadline) {
      if (processes.some((p) => !alive(p.pid)))
        throw new Error("A service exited; inspect .runtime logs");
      try {
        const response = await fetch(url, {
          signal: AbortSignal.timeout(3000),
        });
        let assetsReady = true;
        if (response.status === 200 && verifyWebAssets) {
          for (const asset of ["/umi.js", "/umi.css"]) {
            try {
              const assetResponse = await fetch(`http://127.0.0.1:${frontendPort}${asset}`, {
                signal: AbortSignal.timeout(3000),
                redirect: "error",
              });
              if (assetResponse.status !== 200) assetsReady = false;
            } catch {
              assetsReady = false;
            }
          }
        }
        if (response.status === 200 && assetsReady) {
          ready = true;
          break;
        }
      } catch {}
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    if (!ready) {
      const error = new Error(`Readiness timeout: ${url}; services retained. Check health and logs before retrying.`);
      error.code = "READINESS_TIMEOUT";
      throw error;
    }
  }
  if (localRuntime.pinned)
    await pluginInventory.assertLocalPluginInventory({ root, config });
  console.log("Lumira scaffold ready: http://127.0.0.1:8010/user/login");
} catch (error) {
  if (error.code === "READINESS_TIMEOUT") throw error;
  for (const p of processes) {
    try {
      process.kill(-p.pid, "SIGTERM");
    } catch {}
  }
  if (managedOfficeWorker) {
    try {
      await stopOfficeWorker();
    } catch (cleanupError) {
      console.error(`Office worker cleanup failed: ${cleanupError.message}`);
    }
  }
  throw error;
}
});
