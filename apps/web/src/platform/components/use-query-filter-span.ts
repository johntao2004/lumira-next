import { useEffect, useState, type RefObject } from "react";

/** Keep QueryFilter fields readable as their own container changes width. */
export function useQueryFilterSpan(containerRef: RefObject<HTMLElement | null>): number {
  const [span, setSpan] = useState(24);
  useEffect(() => {
    const element = containerRef.current;
    if (!element) return;
    const updateSpan = (width: number) => {
      // QueryFilter's 24-column grid is measured against its own container so
      // a persistent sidebar or drawer does not leave the fields compressed.
      setSpan(width < 440 ? 24 : width < 960 ? 12 : 8);
    };
    updateSpan(element.getBoundingClientRect().width);
    const observer = new ResizeObserver(entries => {
      const width = entries[0]?.contentRect.width;
      if (width !== undefined) updateSpan(width);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [containerRef]);
  return span;
}
