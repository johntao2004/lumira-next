import test from "node:test";
import assert from "node:assert/strict";
import { readdirSync } from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const virtualStore = path.join(root, "node_modules/.pnpm");
const require = createRequire(import.meta.url);
function appPackage(entryPrefix, packageName) {
  const entries = readdirSync(virtualStore).filter((entry) => entry.startsWith(entryPrefix));
  assert.equal(entries.length, 1, `expected one installed ${entryPrefix} package`);
  return require(path.join(virtualStore, entries[0], "node_modules", packageName));
}

const router = appPackage("react-router@6.30.6_react@19.2.8", "react-router");
const domRouter = appPackage("react-router-dom@6.30.6_react-dom@19.2.8", "react-router-dom");

test("react-router normalizes mixed slash path inputs while preserving controls", () => {
  for (const to of ["//foo", String.raw`\\foo`, String.raw`/\foo`, String.raw`\/foo`]) {
    assert.deepEqual(router.resolvePath(to), {
      pathname: "/foo",
      search: "",
      hash: "",
    }, JSON.stringify(to));
  }

  assert.deepEqual(router.resolvePath("/foo?tab=1#summary"), {
    pathname: "/foo",
    search: "?tab=1",
    hash: "#summary",
  });
  assert.equal(router.resolvePath("../foo", "/nested/start").pathname, "/nested/foo");
  assert.equal(router.resolvePath("https://example.com/docs").pathname, "https://example.com/docs");
});

test("data-router redirects with mixed separators stay on the intended same-origin route", async () => {
  for (const location of [String.raw`\\localhost/foo`, String.raw`/\localhost/foo`, String.raw`\/localhost/foo`, "/foo"]) {
    const memoryRouter = domRouter.createMemoryRouter([
      {
        path: "/",
        children: [
          { path: "start", loader: () => router.redirect(location) },
          { path: "foo", loader: () => null },
        ],
      },
    ], { initialEntries: ["/"] });

    await memoryRouter.navigate("/start");
    assert.equal(memoryRouter.state.location.pathname, "/foo", JSON.stringify(location));
    assert.equal(memoryRouter.state.location.state?._isRedirect, true);
    memoryRouter.dispose();
  }
});
