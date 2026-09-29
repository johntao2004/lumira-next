import { useEffect, useState } from "react";

export type DisplayScale = {
  fontSize: number;
  controlHeight: number;
  controlHeightSM: number;
  controlHeightLG: number;
  formItemGap: number;
  siderWidth: number;
  collapsedSiderWidth: number;
  accountAvatarSize: number;
};

const STANDARD: DisplayScale = {
  fontSize: 14,
  controlHeight: 32,
  controlHeightSM: 24,
  controlHeightLG: 40,
  formItemGap: 16,
  siderWidth: 256,
  collapsedSiderWidth: 64,
  accountAvatarSize: 28,
};

const QHD: DisplayScale = {
  fontSize: 15,
  controlHeight: 34,
  controlHeightSM: 26,
  controlHeightLG: 42,
  formItemGap: 18,
  siderWidth: 272,
  collapsedSiderWidth: 72,
  accountAvatarSize: 30,
};

const FOUR_K: DisplayScale = {
  fontSize: 16,
  controlHeight: 36,
  controlHeightSM: 28,
  controlHeightLG: 44,
  formItemGap: 20,
  siderWidth: 288,
  collapsedSiderWidth: 80,
  accountAvatarSize: 32,
};

type DisplayTier = "standard" | "qhd" | "four-k";

function tierForWidth(width: number): DisplayTier {
  // Scale from usable CSS layout width only. DPR does not increase UI sizes.
  if (width >= 3840) return "four-k";
  if (width >= 2560) return "qhd";
  return "standard";
}

function scaleForTier(tier: DisplayTier): DisplayScale {
  if (tier === "four-k") return FOUR_K;
  if (tier === "qhd") return QHD;
  return STANDARD;
}

function effectiveViewportWidth(): number {
  if (typeof window === "undefined") return 1440;
  return Math.max(0, Math.round(window.innerWidth));
}

/** Keeps the interface comfortably readable on large desktop viewports. */
export function useDisplayScale(): DisplayScale {
  const [scale, setScale] = useState(() => scaleForTier(tierForWidth(effectiveViewportWidth())));

  useEffect(() => {
    const update = () => {
      const tier = tierForWidth(effectiveViewportWidth());
      const nextScale = scaleForTier(tier);
      document.documentElement.dataset.displayScale = tier;
      setScale((current) => current === nextScale ? current : nextScale);
    };
    update();
    window.addEventListener("resize", update);
    return () => window.removeEventListener("resize", update);
  }, []);

  return scale;
}
