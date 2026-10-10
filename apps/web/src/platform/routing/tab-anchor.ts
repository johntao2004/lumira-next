/** Namespaced fragment fields let independent tab groups share one URL. */
export function tabAnchorName(scope = ""): string {
  return scope ? `tab.${scope}` : "tab";
}

export function readTabAnchor(hash: string, scope: string, keys: readonly string[], fallback: string): string {
  const value = new URLSearchParams(hash.replace(/^#/, "")).get(tabAnchorName(scope));
  return value !== null && keys.includes(value) ? value : fallback;
}

export function updateFragment(hash: string, changes: Record<string, string | null>): string {
  const params = new URLSearchParams(hash.replace(/^#/, ""));
  for (const [name, value] of Object.entries(changes)) {
    if (value === null) params.delete(name);
    else params.set(name, value);
  }
  const fragment = params.toString();
  return fragment ? `#${fragment}` : "";
}
