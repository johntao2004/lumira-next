import { spawnSync } from "node:child_process";
import { startOfficeWorker, stopOfficeWorker } from "./office-worker-runtime.mjs";

const configuredUrl = process.env.LUMIRA_OFFICE_WORKER_URL;
const configuredToken = process.env.LUMIRA_OFFICE_WORKER_TOKEN;
if (Boolean(configuredUrl) !== Boolean(configuredToken))
  throw new Error("Set both Office worker test URL and token, or leave both unset to start an isolated test worker.");

const consumesNext = new Set([
  "-s", "--settings", "-f", "--file", "-P", "--activate-profiles",
  "-pl", "--projects", "-rf", "--resume-from", "-t", "--toolchains",
  "-gt", "--global-toolchains", "-l", "--log-file", "-T", "--threads",
]);
const inputArgs = process.argv.slice(2);
const mavenOptions = [];
const requestedGoals = [];
for (let index = 0; index < inputArgs.length; index += 1) {
  const argument = inputArgs[index];
  if (consumesNext.has(argument)) {
    mavenOptions.push(argument);
    if (index + 1 < inputArgs.length) mavenOptions.push(inputArgs[++index]);
  } else if (argument.startsWith("-") || argument.startsWith("--")) {
    mavenOptions.push(argument);
  } else {
    requestedGoals.push(argument);
  }
}
if (requestedGoals.length === 0) requestedGoals.push("verify");

// Always package this checkout before Docker can mount/copy the worker JAR. This prevents
// a stale target/ artifact from becoming the parser child that the tests exercise.
const packageResult = spawnSync(
  "./backend/mvnw",
  ["-f", "backend/pom.xml", "-B", ...mavenOptions, "-Dmaven.test.skip=true", "package"],
  { cwd: process.cwd(), env: process.env, stdio: "inherit" },
);
if (packageResult.error) throw packageResult.error;
if (packageResult.status !== 0) process.exit(packageResult.status ?? 1);

let worker = { url: configuredUrl, token: configuredToken };
const managed = !configuredUrl;
try {
  if (managed) {
    worker = await startOfficeWorker({
      profile: "maven-tests",
      hostPort: 18092,
      testMode: true,
    });
  }
  const args = ["-f", "backend/pom.xml", "-B", ...mavenOptions, ...requestedGoals];
  const result = spawnSync("./backend/mvnw", args, {
    cwd: process.cwd(),
    env: {
      ...process.env,
      LUMIRA_OFFICE_WORKER_URL: worker.url,
      LUMIRA_OFFICE_WORKER_TOKEN: worker.token,
    },
    stdio: "inherit",
  });
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
} finally {
  if (managed) await stopOfficeWorker("maven-tests");
}
