import { canonicalLocale } from "./model.ts";

type LocaleResponse = { locale: string | null };
type Transport = {
  load: (signal: AbortSignal) => Promise<LocaleResponse>;
  save: (locale: string, signal: AbortSignal) => Promise<LocaleResponse>;
  apply: (locale: string) => void;
};

/** Scope requests to the current account and serialize writes so the last selection wins. */
export function createAccountLocaleSync(transport: Transport) {
  let owner: string | null = null;
  let generation = 0;
  let revision = 0;
  let controller = new AbortController();
  let pending: Promise<unknown> = Promise.resolve();

  async function setAccount(account: string | null) {
    if (owner === account) return;
    owner = account;
    generation += 1;
    revision += 1;
    controller.abort();
    controller = new AbortController();
    pending = Promise.resolve();
    if (!owner) return;
    const expectedGeneration = generation;
    const expectedRevision = revision;
    try {
      const result = await transport.load(controller.signal);
      const locale = canonicalLocale(result.locale);
      if (locale && generation === expectedGeneration && revision === expectedRevision) transport.apply(locale);
    } catch {
      // A failed read keeps the browser preference, without creating an account write.
    }
  }

  function select(input: string): Promise<void> {
    const locale = canonicalLocale(input);
    if (!locale) return Promise.reject(new Error("invalidLocale"));
    transport.apply(locale);
    revision += 1;
    if (!owner) return Promise.resolve();
    const expectedGeneration = generation;
    const expectedRevision = revision;
    const signal = controller.signal;
    const saved = pending.catch(() => {}).then(async () => {
      if (generation !== expectedGeneration || signal.aborted) return;
      try {
        await transport.save(locale, signal);
      } catch (error) {
        if (generation === expectedGeneration && revision === expectedRevision) throw error;
      }
    });
    pending = saved;
    return saved;
  }

  return { setAccount, select, dispose: () => { owner = null; generation += 1; controller.abort(); } };
}
