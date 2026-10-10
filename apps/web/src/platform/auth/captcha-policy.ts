import type { CaptchaMode } from "./api-types";

export const captchaPolicyModes = ["OFF", "RISK", "ALWAYS"] as const satisfies readonly CaptchaMode[];

/** Only ALWAYS policies should open a challenge before sending the protected request. */
export function captchaRequiresPreflight(
  action: string,
  alwaysActions: readonly string[] | null | undefined,
): boolean {
  return alwaysActions?.includes(action) ?? false;
}
