"use client";

import { Alert, Button, Input, Space, Typography } from "antd";
import { useEffect, useRef, useState } from "react";
import { ApiError, mutate } from "@/platform/api/client";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import type { CaptchaAction, LocalCaptchaChallenge } from "./api-types";
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

type SliderSample = { x: number; y: number; elapsedMs: number };

export function CaptchaPanel({
  action: captchaAction,
  attemptId,
  onVerified,
  onCancel,
}: {
  action: CaptchaAction;
  attemptId?: string;
  onVerified: (proof: CaptchaProof) => void;
  onCancel: () => void;
}) {
  const { locale, t } = usePreferences();
  const generation = useRef(createCaptchaAttemptGuard());
  const sliderStartedAt = useRef(0);
  const sliderSamples = useRef<SliderSample[]>([]);
  const [challenge, setChallenge] = useState<LocalCaptchaChallenge | null>(null);
  const [answer, setAnswer] = useState("");
  const [sliderX, setSliderX] = useState(0);
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
    setAnswer("");
    setSliderX(0);
    sliderSamples.current = [];
    sliderStartedAt.current = 0;
  }, [captchaAction, attemptId, locale]);

  async function start(accessible: boolean) {
    const currentGeneration = generation.current.begin();
    setChallenge(null);
    setAnswer("");
    setSliderX(0);
    sliderSamples.current = [];
    sliderStartedAt.current = 0;
    actionState.setError(null);
    const isCurrent = () => generation.current.isCurrent(currentGeneration);
    await actionState.run(async () => {
      const next = await mutate<LocalCaptchaChallenge>("/auth/captcha/challenges", "POST", {
        action: captchaAction,
        ...(attemptId ? { attemptId } : {}),
        accessible,
        locale,
      });
      if (!isCurrent()) return false;
      if (!isLocalCaptchaChallenge(next, captchaAction, attemptId)) throw new Error("captchaUnavailable");
      setChallenge(next);
      sliderStartedAt.current = Date.now();
      if (next.type === "SLIDER") {
        sliderSamples.current = [{ x: 0, y: next.pieceY!, elapsedMs: 0 }];
      }
      return false;
    });
  }

  async function verify() {
    if (!challenge || actionState.pending) return;
    actionState.setError(null);
    const currentGeneration = generation.current.begin();
    const isCurrent = () => generation.current.isCurrent(currentGeneration);
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
        if (answer.length !== 6) throw new Error("captchaInvalid");
        payload = { challengeId: challenge.challengeId, answer };
      }
      const proof = await mutate<CaptchaProof>("/auth/captcha/verify", "POST", payload);
      if (!isCurrent()) return false;
      if (proof.action !== captchaAction || proof.attemptId !== (attemptId ?? null) || !proof.captchaProofId) {
        throw new Error("captchaUnavailable");
      }
      onVerified(proof);
      return true;
    });
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
    && !actionState.pending;

  return <section aria-label={t("captchaHumanVerification")} className="captcha-panel">
    <Typography.Paragraph type="secondary">{t("captchaPanelHint")}</Typography.Paragraph>
    {challenge && <Alert
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
      <img
        className="captcha-image-text"
        src={challenge.imageDataUrl}
        width={challenge.width}
        height={challenge.height}
        alt={t("captchaVisualChallengeAlt")}
      />
      <Input
        aria-label={t("captchaDigitAnswer")}
        autoComplete="off"
        autoCapitalize="off"
        autoCorrect="off"
        inputMode="numeric"
        pattern="[0-9]*"
        maxLength={6}
        placeholder={t("captchaAnswerPlaceholder")}
        value={answer}
        onChange={event => setAnswer(normalizeCaptchaAnswer(event.target.value))}
        onPressEnter={() => { if (canVerify) void verify(); }}
      />
    </div>}
    {challenge?.type === "AUDIO" && <div className="captcha-audio-challenge">
      <audio className="captcha-audio-player" controls preload="metadata" src={challenge.audioDataUrl}>
        {t("captchaAudioUnsupported")}
      </audio>
      <Typography.Text type="secondary">{t("captchaAudioAnswerHint")}</Typography.Text>
      <Input
        aria-label={t("captchaDigitAnswer")}
        autoComplete="off"
        inputMode="numeric"
        pattern="[0-9]*"
        maxLength={6}
        placeholder={t("captchaAnswerPlaceholder")}
        value={answer}
        onChange={event => setAnswer(normalizeCaptchaAnswer(event.target.value))}
        onPressEnter={() => { if (canVerify) void verify(); }}
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
        aria-describedby="captcha-slider-help"
        aria-valuetext={t("captchaSliderPosition", { value: sliderX })}
        onChange={event => updateSlider(Number(event.target.value))}
      />
      <Typography.Text id="captcha-slider-help" type="secondary">{t("captchaSliderHint")}</Typography.Text>
    </div>}
    <Space wrap>
      <AsyncButton
        type="primary"
        pending={actionState.pending}
        onClick={() => challenge ? void verify() : void start(false)}
        disabled={challenge ? !canVerify : false}
        label={t(challenge ? "captchaVerify" : "captchaHumanVerification")}
      />
      {challenge && <AsyncButton
        pending={actionState.pending}
        onClick={() => void start(challenge.type === "AUDIO")}
        label={t("captchaRefresh")}
      />}
      {challenge?.accessibleAlternativeAvailable && <AsyncButton
        pending={actionState.pending}
        onClick={() => void start(challenge.type !== "AUDIO")}
        label={t(challenge.type === "AUDIO" ? "captchaVisualVerification" : "captchaAudioVerification")}
      />}
      <Button disabled={actionState.pending} onClick={() => {
        generation.current.invalidate();
        setChallenge(null);
        setAnswer("");
        setSliderX(0);
        actionState.setError(null);
        onCancel();
      }}>{t("captchaCancel")}</Button>
    </Space>
  </section>;
}

function resolveAudioLanguage(locale: string): { languageKey: MessageKey; fallback: boolean } {
  if (locale.toLowerCase().startsWith("zh")) return { languageKey: "captchaAudioLanguageChinese", fallback: false };
  if (locale.toLowerCase().startsWith("en")) return { languageKey: "captchaAudioLanguageEnglish", fallback: false };
  return { languageKey: "captchaAudioLanguageEnglish", fallback: true };
}
