import type { PrimaryMethod } from "@/platform/auth/api-types";

export const authenticationMethodOrder: PrimaryMethod[] = ["PASSWORD", "PASSKEY", "EMAIL_OTP", "SMS_OTP", "WECHAT"];

export type AuthenticationConfigurationRoute = "passkey" | "smtp" | "sms" | "wechat";
export type ConfigurationBlockReason = "access" | "update" | "configuration";

export type AuthenticationMethodConfiguration = {
  id: number;
  method: PrimaryMethod;
  previousEnabledMethods: PrimaryMethod[];
  blockReason: ConfigurationBlockReason | null;
};

export type AuthenticationMethodDraft = {
  enabledMethods: PrimaryMethod[];
  configuration: AuthenticationMethodConfiguration | null;
};

export function readyEnabledMethods(enabledMethods: PrimaryMethod[], availableMethods: PrimaryMethod[] | undefined) {
  const available = new Set(availableMethods ?? []);
  return authenticationMethodOrder.filter(method => enabledMethods.includes(method) && available.has(method));
}

export function beginAuthenticationMethodEnable(
  draft: AuthenticationMethodDraft,
  options: {
    id: number;
    method: PrimaryMethod;
    availableMethods: PrimaryMethod[] | undefined;
    configurationRoute: AuthenticationConfigurationRoute | null;
    canAccessConfiguration: boolean;
    canUpdateConfiguration: boolean;
  },
): { draft: AuthenticationMethodDraft; result: "enabled" | "configure" | "blocked" | "busy" } {
  if (draft.configuration) return { draft, result: "busy" };

  const previousEnabledMethods = [...draft.enabledMethods];
  const nextEnabledMethods = authenticationMethodOrder.filter(method =>
    previousEnabledMethods.includes(method) || method === options.method,
  );
  if (options.availableMethods?.includes(options.method)) {
    return { draft: { enabledMethods: nextEnabledMethods, configuration: null }, result: "enabled" };
  }

  const blockReason: ConfigurationBlockReason | null = !options.configurationRoute
    ? "configuration"
    : !options.canAccessConfiguration
      ? "access"
      : !options.canUpdateConfiguration
        ? "update"
        : null;
  const configuration: AuthenticationMethodConfiguration = {
    id: options.id,
    method: options.method,
    previousEnabledMethods,
    blockReason,
  };

  return {
    draft: { enabledMethods: nextEnabledMethods, configuration },
    result: blockReason ? "blocked" : "configure",
  };
}

export function finishAuthenticationMethodConfiguration(
  draft: AuthenticationMethodDraft,
  id: number,
  serverReportsReady: boolean,
): { draft: AuthenticationMethodDraft; result: "ready" | "unavailable" | "ignored" } {
  const configuration = draft.configuration;
  if (!configuration || configuration.id !== id) return { draft, result: "ignored" };

  if (!serverReportsReady) {
    return {
      draft: { enabledMethods: [...configuration.previousEnabledMethods], configuration: null },
      result: "unavailable",
    };
  }

  return {
    draft: {
      enabledMethods: authenticationMethodOrder.filter(method =>
        draft.enabledMethods.includes(method) || method === configuration.method,
      ),
      configuration: null,
    },
    result: "ready",
  };
}

export function reconcileAuthenticationMethodDraft(
  draft: AuthenticationMethodDraft,
  availableMethods: PrimaryMethod[] | undefined,
): AuthenticationMethodDraft {
  const configuringMethod = draft.configuration?.method;
  const available = new Set(availableMethods ?? []);
  return {
    ...draft,
    enabledMethods: authenticationMethodOrder.filter(method =>
      draft.enabledMethods.includes(method) && (available.has(method) || method === configuringMethod),
    ),
  };
}
