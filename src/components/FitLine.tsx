import { useLayoutEffect, useRef, type ReactNode } from "react";

interface FitLineProps {
  children: ReactNode;
  /** Outer wrapper — margins live here. */
  className?: string;
  /** Inner no-wrap row — gaps live here. */
  rowClassName?: string;
  ariaLabel?: string;
}

/**
 * The word presented to the child must always fit on ONE line. FitLine lays
 * its children out in a no-wrap row at their natural size and, when that row
 * would overflow the available width, shrinks the whole row with a transform
 * so it still fits. Measurement and scale are written straight to the DOM
 * (no React state), so fitting never re-renders the exercise.
 */
export function FitLine({ children, className = "", rowClassName = "", ariaLabel }: FitLineProps) {
  const outerRef = useRef<HTMLDivElement>(null);
  const innerRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const outer = outerRef.current;
    const inner = innerRef.current;
    if (!outer || !inner) return;
    const fit = () => {
      // offsetWidth is a layout metric: the row's natural (untransformed)
      // width, so re-fitting after a resize starts from the true size.
      const natural = inner.offsetWidth;
      const available = outer.clientWidth;
      const k = natural > available ? available / natural : 1;
      inner.style.transform = k < 1 ? `scale(${k})` : "";
      // Shrink the wrapper too, so the layout below stays snug against the row.
      outer.style.height = k < 1 ? `${inner.offsetHeight * k}px` : "";
    };
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(outer);
    ro.observe(inner);
    return () => ro.disconnect();
  }, []);

  return (
    <div ref={outerRef} className={`flex w-full justify-center ${className}`} aria-label={ariaLabel}>
      <div
        ref={innerRef}
        className={`flex w-max flex-none flex-nowrap items-center justify-center whitespace-nowrap ${rowClassName}`}
        style={{ transformOrigin: "center top" }}
      >
        {children}
      </div>
    </div>
  );
}
