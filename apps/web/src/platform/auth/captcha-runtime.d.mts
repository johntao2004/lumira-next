import type { CaptchaAction, LocalCaptchaChallenge } from "./api-types";

export type CaptchaResolverHolder = { current: ((value: string | null) => void) | null };

export function createCaptchaAttemptGuard(): {
  begin(): number;
  invalidate(): number;
  isCurrent(value: number): boolean;
};

export function replaceCaptchaResolver(
  holder: CaptchaResolverHolder,
  resolver: (value: string | null) => void,
): void;

export function resolveCaptchaResolver(holder: CaptchaResolverHolder, value?: string | null): void;

export type CaptchaProofOutcome<T> =
  | { status: "completed"; value: T }
  | { status: "cancelled" };

export interface CaptchaProofRunOptions {
  shouldPreflight(action: CaptchaAction): boolean;
  supported: boolean;
  unavailableError(): Error;
  refreshSupported?: () => Promise<boolean>;
  isCaptchaRequired(cause: unknown): boolean;
}

export interface CaptchaProofController {
  run<T>(
    action: CaptchaAction,
    options: CaptchaProofRunOptions,
    operation: (proofId?: string) => Promise<T>,
  ): Promise<CaptchaProofOutcome<T>>;
  finish(proof: { action: CaptchaAction; captchaProofId: string } | null): boolean;
  cancel(): void;
  dispose(): void;
  isMounted(): boolean;
}

export function createCaptchaProofController(
  onActionChange?: (action: CaptchaAction | null) => void,
): CaptchaProofController;

export function normalizeCaptchaAnswer(value: unknown): string;

export function appendSliderSample(
  samples: readonly SliderTrailPoint[],
  sample: SliderTrailPoint,
  maxSamples?: number,
): SliderTrailPoint[];

export function isLocalCaptchaChallenge(
  challenge: unknown,
  action: CaptchaAction,
  attemptId?: string,
  now?: number,
): challenge is LocalCaptchaChallenge;

export interface SliderTrailPoint {
  x: number;
  y: number;
  elapsedMs: number;
}

export function buildSliderTrail(
  sliderX: number,
  pieceY: number,
  durationMs: number,
  samples?: readonly SliderTrailPoint[],
): SliderTrailPoint[];
