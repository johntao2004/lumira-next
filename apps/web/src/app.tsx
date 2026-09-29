import type { ReactNode } from "react";
import { useState } from "react";
import { defaultBranding } from "@/platform/branding/model";
import { Providers } from "@/platform/providers";
import {
  PREFERENCES_COOKIE,
  parsePreferences,
  type PreferencesValue,
} from "@/platform/preferences/model";
import "./app/globals.css";

function readCookie(name: string): string | null {
  const prefix = `${encodeURIComponent(name)}=`;
  const item = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix));
  return item ? item.slice(prefix.length) : null;
}

function RuntimeRoot({ children }: { children: ReactNode }) {
  const [bootstrap] = useState<{ preferences: PreferencesValue; hasSaved: boolean }>(() => {
    const saved = readCookie(PREFERENCES_COOKIE);
    const preferences = parsePreferences(saved);
    document.documentElement.lang = preferences.locale;
    document.documentElement.dataset.theme = preferences.dark ? "dark" : "light";
    return { preferences, hasSaved: Boolean(saved) };
  });

  return (
    <Providers
      initialBranding={defaultBranding}
      initialPreferences={bootstrap.preferences}
      hasSavedPreferences={bootstrap.hasSaved}
    >
      {children}
    </Providers>
  );
}

export function rootContainer(container: ReactNode) {
  return <RuntimeRoot>{container}</RuntimeRoot>;
}
