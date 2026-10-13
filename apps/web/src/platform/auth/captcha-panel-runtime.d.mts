import type { CaptchaAction } from "./api-types";

export interface CaptchaPanelAutoLoadContext {
  action: CaptchaAction;
  attemptId?: string;
  locale: string;
}

export function createCaptchaPanelAutoLoader(): {
  schedule(context: CaptchaPanelAutoLoadContext, load: () => void): () => void;
  defer(load: () => void): void;
  flush(): void;
  cancel(): void;
};
