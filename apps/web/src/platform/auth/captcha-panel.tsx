"use client";

import { Alert, Button, Input, Skeleton, Space, Typography } from "antd";
import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import { ApiError, mutate } from "@/platform/api/client";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import type { CaptchaAction, LocalCaptchaChallenge } from "./api-types";
import { createCaptchaPanelAutoLoader } from "./captcha-panel-runtime.mjs";
import {
  appendSliderSample,
  buildSliderTrail,
  createCaptchaAttemptGuard,
  isLocalCaptchaChallenge,
  normalizeCaptchaAnswer,
} from "./captcha-runtime.mjs";

export type { CaptchaAction } from "./api-types";

export type CaptchaProof = {
  captchaProofId: string;
  action: CaptchaAction;
  attemptId: string | null;
  expiresAt: string;
};

export type CaptchaPanelHandle = {
  verifyForSubmit: () => Promise<CaptchaProof | null>;
  refresh: () => void;
};

type CaptchaPanelProps = {
  action: CaptchaAction;
  attemptId?: string;
  mode?: "panel" | "password-login";
  disabled?: boolean;
  onVerified: (proof: CaptchaProof) => void;
  onCancel: () => void;
  onSubmitReady?: (ready: boolean) => void;
};

type SliderSample = { x: number; y: number; elapsedMs: number };

export const CaptchaPanel = forwardRef<CaptchaPanelHandle, CaptchaPanelProps>(function CaptchaPanel({
  action: captchaAction,
  attemptId,
  mode = "panel",
  disabled = false,
  onVerified,
  onCancel,
  onSubmitReady,
}, ref) {
  const { locale, t } = usePreferences();
  const generation = useRef(createCaptchaAttemptGuard());
  const autoLoader = useRef(createCaptchaPanelAutoLoader());
  const sliderStartedAt = useRef(0);
  const sliderSamples = useRef<SliderSample[]>([]);
  const [challenge, setChallenge] = useState<LocalCaptchaChallenge | null>(null);
  const [answer, setAnswer] = useState("");
  const answerRef = useRef("");
  const [sliderX, setSliderX] = useState(0);
  const [loadAttempted, setLoadAttempted] = useState(false);
  const actionState = useSubmitAction({
    errorKeys: [
      "captchaUnavailable", "captchaInvalid", "captchaChallengeExpired", "captchaChallengeLocked",
      "captchaRateLimited", "captchaPolicyChanged", "captchaAccessibleAlternativeUnavailable",
      "forbidden", "unavailable",
    ],
    fallback: "captchaUnavailable",
    mapError: cause => {
      if (cause instanceof ApiError && cause.status === 403) return "forbidden";
      if (cause instanceof ApiError && cause.status === 429) return "captchaRateLimited";
      if (cause instanceof ApiError && [
        "captchaInvalid", "captchaChallengeExpired", "captchaChallengeLocked",
        "captchaPolicyChanged", "captchaAccessibleAlternativeUnavailable",
      ].includes(cause.message)) return cause.message as MessageKey;
      return "captchaUnavailable";
    },
  });

  useEffect(() => () => {
    generation.current.invalidate();
  }, []);

  useEffect(() => {
    generation.current.invalidate();
    setChallenge(null);
    answerRef.current = "";
    setAnswer("");
    setSliderX(0);
    setLoadAttempted(false);
    sliderSamples.current = [];
    sliderStartedAt.current = 0;
    if (mode === "password-login") onSubmitReady?.(false);
    return autoLoader.current.schedule({ action: captchaAction, attemptId, locale }, () => {
      void start(false);
    });
  }, [captchaAction, attemptId, locale, mode, onSubmitReady]);

  async function start(accessible: boolean) {
    if (actionState.isPending()) {
      autoLoader.current.defer(() => { void start(accessible); });
      return;
    }
    const currentGeneration = generation.current.begin();
    setLoadAttempted(true);
    setChallenge(null);
    answerRef.current = "";
    setAnswer("");
    setSliderX(0);
    sliderSamples.current = [];
    sliderStartedAt.current = 0;
    actionState.setError(null);
    const isCurrent = () => generation.current.isCurrent(currentGeneration);
    try {
      await actionState.run(async () => {
        let next: LocalCaptchaChallenge;
        try {
          next = await mutate<LocalCaptchaChallenge>("/auth/captcha/challenges", "POST", {
            action: captchaAction,
            ...(attemptId ? { attemptId } : {}),
            accessible,
            locale,
          });
        } catch (cause) {
          if (!isCurrent()) return false;
          throw cause;
        }
        if (!isCurrent()) return false;
        if (!isLocalCaptchaChallenge(next, captchaAction, attemptId)) throw new Error("captchaUnavailable");
        setChallenge(next);
        sliderStartedAt.current = Date.now();
        if (next.type === "SLIDER") {
          sliderSamples.current = [{ x: 0, y: next.pieceY!, elapsedMs: 0 }];
        }
        return false;
      });
    } finally {
      autoLoader.current.flush();
    }
  }

  async function verify(): Promise<CaptchaProof | null> {
    if (!challenge || actionState.isPending()) return null;
    actionState.setError(null);
    const currentGeneration = generation.current.begin();
    const isCurrent = () => generation.current.isCurrent(currentGeneration);
    let verifiedProof: CaptchaProof | null = null;
    try {
      await actionState.run(async () => {
        let payload: Record<string, unknown>;
        if (challenge.type === "SLIDER") {
          const durationMs = Math.max(0, Date.now() - sliderStartedAt.current);
          payload = {
            challengeId: challenge.challengeId,
            sliderX,
            durationMs,
            trail: buildSliderTrail(sliderX, challenge.pieceY!, durationMs, sliderSamples.current),
          };
        } else {
          const latestAnswer = answerRef.current;
          if (latestAnswer.length !== 6) throw new Error("captchaInvalid");
          payload = { challengeId: challenge.challengeId, answer: latestAnswer };
        }
        let proof: CaptchaProof;
        try {
          proof = await mutate<CaptchaProof>("/auth/captcha/verify", "POST", payload);
        } catch (cause) {
          if (!isCurrent()) return false;
          throw cause;
        }
        if (!isCurrent()) return false;
        if (proof.action !== captchaAction
          || proof.attemptId !== (attemptId ?? null)
          || typeof proof.captchaProofId !== "string"
          || !proof.captchaProofId
          || !Number.isFinite(Date.parse(proof.expiresAt))
          || Date.parse(proof.expiresAt) <= Date.now()) {
          throw new Error("captchaUnavailable");
        }
        onVerified(proof);
        verifiedProof = proof;
        return true;
      });
    } finally {
      autoLoader.current.flush();
    }
    return verifiedProof;
  }

  async function verifyForSubmit() {
    if (!canVerify) return null;
    return verify();
  }

  function refresh() {
    if (challenge) void start(challenge.type === "AUDIO");
  }

  function updateAnswer(value: string) {
    const latestAnswer = normalizeCaptchaAnswer(value);
    answerRef.current = latestAnswer;
    setAnswer(latestAnswer);
  }

  function updateSlider(value: number) {
    if (!challenge || challenge.type !== "SLIDER") return;
    const nextX = Math.max(0, Math.min(challenge.width! - challenge.pieceWidth!, Math.round(value)));
    const elapsedMs = Math.max(0, Date.now() - sliderStartedAt.current);
    sliderSamples.current = appendSliderSample(
      sliderSamples.current,
      { x: nextX, y: challenge.pieceY!, elapsedMs },
    );
    setSliderX(nextX);
  }

  const audioLanguage = resolveAudioLanguage(locale);
  const canVerify = Boolean(challenge)
    && (challenge?.type === "SLIDER" ? sliderX > 0 : answer.length === 6)
    && !actionState.pending
    && !disabled;

  useImperativeHandle(ref, () => ({ verifyForSubmit, refresh }), [verifyForSubmit, refresh]);

  useEffect(() => {
    if (mode === "password-login") onSubmitReady?.(canVerify);
  }, [canVerify, mode, onSubmitReady]);

  const compactImageChallenge = mode === "password-login" && challenge?.type === "IMAGE_TEXT";
  const hasAccessibleAlternative = challenge?.accessibleAlternativeAvailable === true;
  const compactLoading = mode === "password-login" && !challenge && (actionState.pending || !loadAttempted);
  const compactRetry = Boolean(mode === "password-login" && !challenge && !compactLoading);
  const compactRefreshChallenge = mode === "password-login" && challenge && challenge.type !== "IMAGE_TEXT"
    ? challenge
    : null;
  const compactRefresh = compactRefreshChallenge !== null;
  const refreshChallenge = mode === "panel" ? challenge : compactRefreshChallenge;

  return <section aria-label={t("captchaHumanVerification")} className={`captcha-panel${mode === "password-login" ? " captcha-panel-login" : ""}`}>
    {!compactImageChallenge && !(mode === "password-login" && !challenge) && <Typography.Paragraph type="secondary">{t("captchaPanelHint")}</Typography.Paragraph>}
    {compactLoading && <div className="captcha-answer captcha-answer-loading" role="status" aria-label={t("loading")}>
      <Skeleton.Input active block />
      <Skeleton.Button active block />
    </div>}
    {challenge && !compactImageChallenge && <Alert
      type="info"
      showIcon
      title={t(challenge.type === "AUDIO" ? "captchaAudioVerification" : "captchaVisualVerification")}
      description={challenge.type === "AUDIO" ? <>
        <span>{t("captchaAudioLanguageHint", { language: t(audioLanguage.languageKey) })}</span>
        {audioLanguage.fallback && <span> {t("captchaAudioFallbackHint")}</span>}
      </> : undefined}
    />}
    <FeedbackBubble id="captcha-error" content={actionState.error ? t(actionState.error as MessageKey) : undefined} />
    {challenge?.type === "IMAGE_TEXT" && <div className="captcha-answer">
      <Input
        aria-label={t("captchaDigitAnswer")}
        data-testid={mode === "password-login" ? "password-login-captcha-answer" : undefined}
        autoComplete="off"
        autoCapitalize="off"
        autoCorrect="off"
        inputMode="numeric"
        pattern="[0-9]*"
        maxLength={6}
        placeholder={t("captchaAnswerPlaceholder")}
        value={answer}
        disabled={disabled || actionState.pending}
        onChange={event => updateAnswer(event.target.value)}
        onPressEnter={() => { if (mode !== "password-login" && canVerify) void verify(); }}
      />
      <button
        className="captcha-image-refresh"
        type="button"
        aria-label={t("captchaRefresh")}
        title={t("captchaRefresh")}
        data-testid={mode === "password-login" ? "password-login-captcha-image-refresh" : undefined}
        disabled={disabled || actionState.pending}
        onClick={() => void start(false)}
      >
        <img
          className="captcha-image-text"
          src={challenge.imageDataUrl}
          width={challenge.width}
          height={challenge.height}
          alt={t("captchaVisualChallengeAlt")}
        />
      </button>
    </div>}
    {challenge?.type === "AUDIO" && <div className="captcha-audio-challenge">
      <audio className="captcha-audio-player" controls preload="metadata" src={challenge.audioDataUrl}>
        {t("captchaAudioUnsupported")}
      </audio>
      <Typography.Text type="secondary">{t("captchaAudioAnswerHint")}</Typography.Text>
      <Input
        aria-label={t("captchaDigitAnswer")}
        data-testid={mode === "password-login" ? "password-login-captcha-answer" : undefined}
        autoComplete="off"
        inputMode="numeric"
        pattern="[0-9]*"
        maxLength={6}
        placeholder={t("captchaAnswerPlaceholder")}
        value={answer}
        disabled={disabled || actionState.pending}
        onChange={event => updateAnswer(event.target.value)}
        onPressEnter={() => { if (mode !== "password-login" && canVerify) void verify(); }}
      />
    </div>}
    {challenge?.type === "SLIDER" && <div className="captcha-slider-challenge">
      <div
        className="captcha-slider-frame"
        style={{ aspectRatio: `${challenge.width} / ${challenge.height}` }}
      >
        <img className="captcha-slider-background" src={challenge.backgroundDataUrl} alt={t("captchaVisualChallengeAlt")} />
        <img
          className="captcha-slider-piece"
          src={challenge.pieceDataUrl}
          alt=""
          draggable={false}
          style={{
            left: `${(sliderX / challenge.width!) * 100}%`,
            top: `${(challenge.pieceY! / challenge.height!) * 100}%`,
            width: `${(challenge.pieceWidth! / challenge.width!) * 100}%`,
            height: `${(challenge.pieceHeight! / challenge.height!) * 100}%`,
          }}
        />
      </div>
      <label className="captcha-slider-label" htmlFor="captcha-slider-range">{t("captchaSliderLabel")}</label>
      <input
        id="captcha-slider-range"
        className="captcha-slider-range"
        type="range"
        min={0}
        max={challenge.width! - challenge.pieceWidth!}
        step={1}
        value={sliderX}
        disabled={disabled || actionState.pending}
        aria-describedby="captcha-slider-help"
        aria-valuetext={t("captchaSliderPosition", { value: sliderX })}
        onChange={event => updateSlider(Number(event.target.value))}
      />
      <Typography.Text id="captcha-slider-help" type="secondary">{t("captchaSliderHint")}</Typography.Text>
    </div>}
    {(mode === "panel" || hasAccessibleAlternative || compactRetry || compactRefresh) && <Space wrap>
      {compactRetry && <Button
        data-testid="password-login-captcha-retry"
        disabled={disabled || actionState.pending}
        onClick={() => void start(false)}
      >{t("retry")}</Button>}
      {mode === "panel" && <AsyncButton
        type="primary"
        pending={actionState.pending}
        onClick={() => challenge ? void verify() : void start(false)}
        disabled={challenge ? !canVerify : false}
        label={t(challenge ? "captchaVerify" : "captchaHumanVerification")}
      />}
      {refreshChallenge && <AsyncButton
        pending={actionState.pending}
        disabled={disabled}
        onClick={() => void start(refreshChallenge.type === "AUDIO")}
        label={t("captchaRefresh")}
      />}
      {challenge?.accessibleAlternativeAvailable && <AsyncButton
        pending={actionState.pending}
        disabled={disabled}
        onClick={() => void start(challenge.type !== "AUDIO")}
        label={t(challenge.type === "AUDIO" ? "captchaVisualVerification" : "captchaAudioVerification")}
      />}
      {mode === "panel" && <Button onClick={() => {
        autoLoader.current.cancel();
        generation.current.invalidate();
        setChallenge(null);
        answerRef.current = "";
        setAnswer("");
        setSliderX(0);
        actionState.setError(null);
        onCancel();
      }}>{t("captchaCancel")}</Button>}
    </Space>}
  </section>;
});

function resolveAudioLanguage(locale: string): { languageKey: MessageKey; fallback: boolean } {
  if (locale.toLowerCase().startsWith("zh")) return { languageKey: "captchaAudioLanguageChinese", fallback: false };
  if (locale.toLowerCase().startsWith("en")) return { languageKey: "captchaAudioLanguageEnglish", fallback: false };
  return { languageKey: "captchaAudioLanguageEnglish", fallback: true };
}
