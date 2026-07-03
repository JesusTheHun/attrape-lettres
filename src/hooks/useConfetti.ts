import { useCallback, useEffect, useRef } from "react";

interface Particle {
  x: number; y: number; vx: number; vy: number; g: number;
  r: number; rot: number; vr: number; life: number; color: string;
}

const COLORS = ["#FF8A65", "#FFD54F", "#4FC3F7", "#AED581", "#BA9EE8", "#F06292"];

/**
 * Returns a canvas ref (mount it as a full-bleed overlay) and a `fire()` that
 * bursts confetti. All animation runs on a rAF loop that never touches React
 * state, so celebrations cost nothing on the render path.
 */
export function useConfetti() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const partsRef = useRef<Particle[]>([]);
  const rafRef = useRef(0);
  const reduced = useRef(false);

  useEffect(() => {
    reduced.current = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d")!;
    const dpr = window.devicePixelRatio || 1;

    const resize = () => {
      canvas.width = canvas.offsetWidth * dpr;
      canvas.height = canvas.offsetHeight * dpr;
    };
    resize();
    window.addEventListener("resize", resize);

    const loop = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      const parts = partsRef.current;
      for (let i = parts.length - 1; i >= 0; i--) {
        const p = parts[i];
        p.vy += p.g;
        p.x += p.vx;
        p.y += p.vy;
        p.rot += p.vr;
        p.life -= 0.008;
        if (p.life <= 0 || p.y > canvas.height + 40) {
          parts.splice(i, 1);
          continue;
        }
        ctx.save();
        ctx.globalAlpha = Math.max(0, p.life);
        ctx.translate(p.x, p.y);
        ctx.rotate(p.rot);
        ctx.fillStyle = p.color;
        ctx.fillRect(-p.r / 2, -p.r / 2, p.r, p.r * 0.6);
        ctx.restore();
      }
      rafRef.current = parts.length ? requestAnimationFrame(loop) : 0;
    };
    (canvas as any).__loop = loop;
    (canvas as any).__dpr = dpr;

    return () => {
      window.removeEventListener("resize", resize);
      cancelAnimationFrame(rafRef.current);
      rafRef.current = 0;
    };
  }, []);

  const fire = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || reduced.current) return;
    const dpr = (canvas as any).__dpr ?? 1;
    const cx = canvas.width / 2;
    for (let i = 0; i < 90; i++) {
      const a = Math.random() * Math.PI - Math.PI;
      const sp = (4 + Math.random() * 7) * dpr;
      partsRef.current.push({
        x: cx + (Math.random() - 0.5) * 120 * dpr,
        y: canvas.height * 0.42,
        vx: Math.cos(a) * sp,
        vy: Math.sin(a) * sp - 3 * dpr,
        g: 0.22 * dpr,
        r: (5 + Math.random() * 6) * dpr,
        rot: Math.random() * 6.28,
        vr: (Math.random() - 0.5) * 0.4,
        life: 1,
        color: COLORS[(Math.random() * COLORS.length) | 0],
      });
    }
    if (!rafRef.current) rafRef.current = requestAnimationFrame((canvas as any).__loop);
  }, []);

  return { canvasRef, fire };
}
