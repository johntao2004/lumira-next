import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const file = path.join(root, ".runtime/local.json");
mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
const config = existsSync(file)
  ? JSON.parse(readFileSync(file, "utf8"))
  : {
      LUMIRA_DB_URL: "jdbc:postgresql://127.0.0.1:5432/lumira_next",
      LUMIRA_DB_USER: "lumira_next",
      LUMIRA_DB_PASSWORD: randomBytes(32).toString("hex"),
      LUMIRA_BOOTSTRAP_PASSWORD: randomBytes(24).toString("base64url"),
      LUMIRA_COOKIE_SECURE: "false",
    };
if (!existsSync(file))
  writeFileSync(file, JSON.stringify(config, null, 2) + "\n", { mode: 0o600 });
const psql = process.env.PSQL_BIN ?? "psql";
function sql(statement) {
  const result = spawnSync(
    psql,
    ["-X", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-At"],
    { input: statement, encoding: "utf8" },
  );
  if (result.status !== 0)
    throw new Error(
      result.stderr || "PostgreSQL client unavailable; set PSQL_BIN",
    );
  return result.stdout.trim();
}
const literal = (s) => "'" + s.replaceAll("'", "''") + "'";
if (!sql("SELECT 1 FROM pg_roles WHERE rolname='lumira_next'"))
  sql(
    `CREATE ROLE lumira_next LOGIN PASSWORD ${literal(config.LUMIRA_DB_PASSWORD)}`,
  );
if (!sql("SELECT 1 FROM pg_database WHERE datname='lumira_next'"))
  sql("CREATE DATABASE lumira_next OWNER lumira_next");
console.log(
  "Independent database lumira_next is ready. Local credentials: .runtime/local.json (mode 0600).",
);
