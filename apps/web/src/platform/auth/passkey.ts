import type { PasskeyOptions } from "@/platform/auth/api-types";

export interface PasskeyRegistrationPayload {
  challengeId: string;
  id: string;
  rawId: string;
  type: string;
  response: {
    clientDataJSON: string;
    attestationObject: string;
  };
  authenticatorAttachment?: string;
  transports?: string[];
  label?: string;
}

export interface PasskeyAuthenticationPayload {
  challengeId: string;
  id: string;
  rawId: string;
  type: string;
  response: {
    clientDataJSON: string;
    authenticatorData: string;
    signature: string;
    userHandle?: string;
  };
  authenticatorAttachment?: string;
}

function fromBase64Url(value: string): ArrayBuffer {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  const decoded = atob(padded);
  const bytes = Uint8Array.from(decoded, (character) => character.charCodeAt(0));
  const result = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(result).set(bytes);
  return result;
}

function toBase64Url(value: ArrayBuffer): string {
  const bytes = new Uint8Array(value);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function requiredString(value: unknown, name: string): string {
  if (typeof value !== "string" || !value) throw new Error(`Invalid passkey option: ${name}`);
  return value;
}

function record(value: unknown, name: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`Invalid passkey option: ${name}`);
  return value as Record<string, unknown>;
}

/** Feature detection is kept in one place so unsupported browsers get a localized UI message. */
export function isPasskeySupported(): boolean {
  return typeof window !== "undefined"
    && typeof window.PublicKeyCredential !== "undefined"
    && typeof navigator.credentials?.create === "function"
    && typeof navigator.credentials?.get === "function";
}

export function toPublicKeyCreationOptions(options: PasskeyOptions): PublicKeyCredentialCreationOptions {
  const source = record(options.publicKey, "publicKey");
  const rp = record(source.rp, "rp");
  const user = record(source.user, "user");
  const parameters = Array.isArray(source.pubKeyCredParams) ? source.pubKeyCredParams : [];
  const exclude = Array.isArray(source.excludeCredentials) ? source.excludeCredentials : [];
  return {
    challenge: fromBase64Url(requiredString(source.challenge, "challenge")),
    rp: {
      id: requiredString(rp.id, "rp.id"),
      name: requiredString(rp.name, "rp.name"),
    },
    user: {
      id: fromBase64Url(requiredString(user.id, "user.id")),
      name: requiredString(user.name, "user.name"),
      displayName: requiredString(user.displayName, "user.displayName"),
    },
    pubKeyCredParams: parameters.map((parameter) => {
      const item = record(parameter, "pubKeyCredParams");
      if (item.type !== "public-key" || typeof item.alg !== "number") throw new Error("Invalid passkey algorithm");
      return { type: "public-key", alg: item.alg };
    }),
    timeout: typeof source.timeout === "number" ? source.timeout : undefined,
    excludeCredentials: exclude.map((descriptor) => {
      const item = record(descriptor, "excludeCredentials");
      return {
        type: "public-key",
        id: fromBase64Url(requiredString(item.id, "excludeCredentials.id")),
        transports: Array.isArray(item.transports) ? item.transports.filter((value): value is AuthenticatorTransport => typeof value === "string") : undefined,
      };
    }),
    authenticatorSelection: (() => {
      const value = source.authenticatorSelection;
      if (!value) return undefined;
      const selection = record(value, "authenticatorSelection");
      return {
        residentKey: typeof selection.residentKey === "string" ? selection.residentKey as ResidentKeyRequirement : undefined,
        requireResidentKey: typeof selection.requireResidentKey === "boolean" ? selection.requireResidentKey : undefined,
        userVerification: typeof selection.userVerification === "string" ? selection.userVerification as UserVerificationRequirement : undefined,
      };
    })(),
    attestation: typeof source.attestation === "string" ? source.attestation as AttestationConveyancePreference : undefined,
  };
}

export function toPublicKeyRequestOptions(options: PasskeyOptions): PublicKeyCredentialRequestOptions {
  const source = record(options.publicKey, "publicKey");
  return {
    challenge: fromBase64Url(requiredString(source.challenge, "challenge")),
    rpId: typeof source.rpId === "string" ? source.rpId : undefined,
    timeout: typeof source.timeout === "number" ? source.timeout : undefined,
    userVerification: typeof source.userVerification === "string" ? source.userVerification as UserVerificationRequirement : undefined,
  };
}

/** Let the browser cancel a ceremony after the server challenge's lifetime. */
export async function createPasskeyCredential(
  publicKey: PublicKeyCredentialCreationOptions | PublicKeyCredentialRequestOptions,
  timeoutMs = 60_000,
): Promise<PublicKeyCredential> {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const credential = "user" in publicKey
      ? await navigator.credentials.create({ publicKey, signal: controller.signal })
      : await navigator.credentials.get({ publicKey, signal: controller.signal });
    if (!(credential instanceof PublicKeyCredential)) throw new Error("passkeyCredentialMissing");
    return credential;
  } catch (error) {
    if (controller.signal.aborted) throw new Error("passkeyTimeout");
    throw error;
  } finally {
    window.clearTimeout(timer);
  }
}

export function toRegistrationPayload(credential: PublicKeyCredential, challengeId: string, label?: string): PasskeyRegistrationPayload {
  const response = credential.response as AuthenticatorAttestationResponse;
  return {
    challengeId,
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: toBase64Url(response.clientDataJSON),
      attestationObject: toBase64Url(response.attestationObject),
    },
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
    transports: response.getTransports?.(),
    label,
  };
}

export function toAuthenticationPayload(credential: PublicKeyCredential, challengeId: string): PasskeyAuthenticationPayload {
  const response = credential.response as AuthenticatorAssertionResponse;
  return {
    challengeId,
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: toBase64Url(response.clientDataJSON),
      authenticatorData: toBase64Url(response.authenticatorData),
      signature: toBase64Url(response.signature),
      userHandle: response.userHandle ? toBase64Url(response.userHandle) : undefined,
    },
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
  };
}
