const MAX_SLIDER_TRAIL_POINTS = 32;

export function createCaptchaAttemptGuard() {
  let generation = 0;
  return {
    begin() {
      generation += 1;
      return generation;
    },
    invalidate() {
      generation += 1;
      return generation;
    },
    isCurrent(value) {
      return value === generation;
    },
  };
}

export function replaceCaptchaResolver(holder, resolver) {
  holder.current?.(null);
  holder.current = resolver;
}

export function resolveCaptchaResolver(holder, value = null) {
  const resolver = holder.current;
  holder.current = null;
  resolver?.(value);
}

/** Owns the proof prompt and its one permitted captchaRequired retry. */
export function createCaptchaProofController(onActionChange = () => {}) {
  const resolver = { current: null };
  let mounted = true;
  let runRevision = 0;
  let pendingProof = null;
  let activeRunCancellation = null;

  function setAction(action) {
    if (mounted) onActionChange(action);
  }

  function cancelPendingProof() {
    if (!pendingProof) return;
    pendingProof = null;
    setAction(null);
    resolveCaptchaResolver(resolver, null);
  }

  function stopActiveRun() {
    const cancel = activeRunCancellation;
    activeRunCancellation = null;
    cancel?.();
  }

  function requestProof(action) {
    if (!mounted) return Promise.resolve(null);
    cancelPendingProof();
    const request = { action };
    pendingProof = request;
    setAction(action);
    return new Promise(resolve => {
      replaceCaptchaResolver(resolver, value => {
        if (pendingProof !== request) {
          resolve(value);
          return;
        }
        pendingProof = null;
        setAction(null);
        resolve(value);
      });
    });
  }

  function finish(proof) {
    if (!pendingProof) return false;
    if (proof && (proof.action !== pendingProof.action || typeof proof.captchaProofId !== "string" || !proof.captchaProofId)) {
      return false;
    }
    resolveCaptchaResolver(resolver, proof?.captchaProofId ?? null);
    return true;
  }

  async function run(action, options, operation) {
    const revision = ++runRevision;
    stopActiveRun();
    // Starting a newer action supersedes any proof prompt owned by an older run.
    cancelPendingProof();
    const isCurrent = () => mounted && revision === runRevision;
    let interruptRun;
    const interrupted = new Promise(resolve => {
      interruptRun = () => resolve({ kind: "cancelled" });
    });
    activeRunCancellation = interruptRun;
    const acquire = force => {
      if (!force && !options.shouldPreflight(action)) return Promise.resolve(undefined);
      if (!options.supported) return Promise.reject(options.unavailableError());
      return requestProof(action);
    };
    const cancelled = () => ({ status: "cancelled" });
    try {
      const firstProof = await acquire(false);
      if (!isCurrent() || firstProof === null) return cancelled();
      const execute = proofId => Promise.race([
        Promise.resolve().then(() => {
          // Cancellation can land after proof acquisition but before this
          // microtask. Check and invoke in the same turn so a retired run
          // never starts its network operation.
          if (!isCurrent()) return { kind: "cancelled" };
          let result;
          try {
            result = operation(proofId);
          } catch (error) {
            return { kind: "error", error };
          }
          return Promise.resolve(result).then(
            value => ({ kind: "value", value }),
            error => ({ kind: "error", error }),
          );
        }),
        interrupted,
      ]);

      const firstResult = await execute(firstProof ?? undefined);
      if (firstResult.kind === "cancelled" || !isCurrent()) return cancelled();
      if (firstResult.kind === "error") {
        if (!options.isCaptchaRequired(firstResult.error)) throw firstResult.error;

        const retryProof = await acquire(true);
        if (!isCurrent() || retryProof === null) return cancelled();
        const retryResult = await execute(retryProof ?? undefined);
        if (retryResult.kind === "cancelled" || !isCurrent()) return cancelled();
        if (retryResult.kind === "error") throw retryResult.error;
        return { status: "completed", value: retryResult.value };
      }

      return { status: "completed", value: firstResult.value };
    } finally {
      if (activeRunCancellation === interruptRun) activeRunCancellation = null;
    }
  }

  function cancel() {
    if (!mounted) return;
    runRevision += 1;
    stopActiveRun();
    cancelPendingProof();
  }

  function dispose() {
    if (!mounted) return;
    mounted = false;
    runRevision += 1;
    stopActiveRun();
    const wasPending = pendingProof !== null;
    pendingProof = null;
    if (wasPending) resolveCaptchaResolver(resolver, null);
  }

  return {
    run,
    finish,
    cancel,
    dispose,
    isMounted: () => mounted,
  };
}

export function normalizeCaptchaAnswer(value) {
  return String(value ?? "").replace(/[^0-9]/g, "").slice(0, 6);
}

export function appendSliderSample(samples, sample, maxSamples = MAX_SLIDER_TRAIL_POINTS - 2) {
  const next = [...samples, sample].filter(point => point
    && isFiniteNumber(point.x) && isFiniteNumber(point.y) && isFiniteNumber(point.elapsedMs));
  return downsample(next, Math.max(2, maxSamples));
}

export function isLocalCaptchaChallenge(challenge, action, attemptId, now = Date.now()) {
  if (!challenge || typeof challenge !== "object"
    || challenge.provider !== "LOCAL"
    || challenge.action !== action
    || challenge.attemptId !== (attemptId ?? null)
    || typeof challenge.challengeId !== "string"
    || !challenge.challengeId
    || !Number.isFinite(Date.parse(challenge.expiresAt))
    || Date.parse(challenge.expiresAt) <= now
    || typeof challenge.accessibleAlternativeAvailable !== "boolean") return false;

  if (challenge.type === "IMAGE_TEXT") {
    return isPngDataUrl(challenge.imageDataUrl)
      && isPositive(challenge.width)
      && isPositive(challenge.height);
  }
  if (challenge.type === "AUDIO") {
    return isWavDataUrl(challenge.audioDataUrl);
  }
  if (challenge.type === "SLIDER") {
    return isPngDataUrl(challenge.backgroundDataUrl)
      && isPngDataUrl(challenge.pieceDataUrl)
      && isPositive(challenge.width)
      && isPositive(challenge.height)
      && isPositive(challenge.pieceWidth)
      && isPositive(challenge.pieceHeight)
      && isFiniteNumber(challenge.pieceY)
      && challenge.pieceY >= 0
      && challenge.pieceY + challenge.pieceHeight <= challenge.height
      && challenge.pieceWidth < challenge.width
      && challenge.pieceHeight <= challenge.height;
  }
  return false;
}

export function buildSliderTrail(sliderX, pieceY, durationMs, samples = []) {
  const duration = Math.max(0, Math.floor(Number(durationMs) || 0));
  const endpoint = clampNonNegative(sliderX);
  const y = clampNonNegative(pieceY);
  const trail = [{ x: 0, y, elapsedMs: 0 }];
  let lastElapsed = 0;

  for (const sample of samples) {
    if (!sample || !isFiniteNumber(sample.x) || !isFiniteNumber(sample.y) || !isFiniteNumber(sample.elapsedMs)) continue;
    const elapsedMs = Math.max(lastElapsed, Math.min(duration, Math.floor(sample.elapsedMs)));
    trail.push({ x: Math.max(0, Number(sample.x)), y: Math.max(0, Number(sample.y)), elapsedMs });
    lastElapsed = elapsedMs;
  }

  const last = trail[trail.length - 1];
  if (last.x !== endpoint || last.elapsedMs !== duration) {
    trail.push({ x: endpoint, y, elapsedMs: duration });
  }
  return downsample(trail, MAX_SLIDER_TRAIL_POINTS);
}

function isPngDataUrl(value) {
  return typeof value === "string" && /^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/.test(value);
}

function isWavDataUrl(value) {
  return typeof value === "string" && /^data:audio\/wav;base64,[A-Za-z0-9+/]+={0,2}$/.test(value);
}

function isFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function isPositive(value) {
  return isFiniteNumber(value) && value > 0;
}

function clampNonNegative(value) {
  return isFiniteNumber(Number(value)) ? Math.max(0, Number(value)) : 0;
}

function downsample(points, maxPoints) {
  if (points.length <= maxPoints) return points;
  const output = [];
  for (let index = 0; index < maxPoints; index += 1) {
    const sourceIndex = Math.round(index * (points.length - 1) / (maxPoints - 1));
    output.push(points[sourceIndex]);
  }
  return output;
}
