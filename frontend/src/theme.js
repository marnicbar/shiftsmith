// theme.js — injects design tokens as CSS variables on <html>.
// The neutral palette (slate) and accent (blue) are fixed; status colors are
// computed for light & dark.

const PALETTE = { hue: 256, chroma: 0.012 }; // slate
const ACCENT  = { hue: 248, c: 0.16 };       // blue

function neutralVars(hue, chroma, dark) {
  const H = hue, C = chroma;
  if (!dark) return {
    '--bg':           `oklch(0.985 ${C*0.6} ${H})`,
    '--surface':      `oklch(1 ${C*0.3} ${H})`,
    '--surface-2':    `oklch(0.965 ${C} ${H})`,
    '--border':       `oklch(0.918 ${C} ${H})`,
    '--border-strong':`oklch(0.862 ${C*1.1} ${H})`,
    '--text':         `oklch(0.255 ${C*1.4} ${H})`,
    '--text-2':       `oklch(0.46 ${C*1.3} ${H})`,
    '--text-3':       `oklch(0.6 ${C*1.1} ${H})`,
  };
  return {
    '--bg':           `oklch(0.178 ${C*1.1} ${H})`,
    '--surface':      `oklch(0.214 ${C*1.2} ${H})`,
    '--surface-2':    `oklch(0.255 ${C*1.3} ${H})`,
    '--border':       `oklch(0.302 ${C*1.4} ${H})`,
    '--border-strong':`oklch(0.382 ${C*1.5} ${H})`,
    '--text':         `oklch(0.962 ${C*0.5} ${H})`,
    '--text-2':       `oklch(0.74 ${C} ${H})`,
    '--text-3':       `oklch(0.6 ${C} ${H})`,
  };
}

function toneVars(name, hue, c, dark) {
  if (!dark) return {
    [`--${name}-soft`]:   `oklch(0.955 ${c*0.42} ${hue})`,
    [`--${name}-strong`]: `oklch(0.46 ${c*1.05} ${hue})`,
    [`--${name}-solid`]:  `oklch(0.62 ${c} ${hue})`,
  };
  return {
    [`--${name}-soft`]:   `oklch(0.30 ${c*0.55} ${hue})`,
    [`--${name}-strong`]: `oklch(0.84 ${c*0.8} ${hue})`,
    [`--${name}-solid`]:  `oklch(0.66 ${c} ${hue})`,
  };
}

function applyTheme({ dark = false } = {}) {
  const p = PALETTE;
  const a = ACCENT;
  const root = document.documentElement;
  const vars = {
    ...neutralVars(p.hue, p.chroma, dark),
    ...toneVars('green', 150, 0.13, dark),
    ...toneVars('amber', 70,  0.14, dark),
    ...toneVars('rose',  20,  0.16, dark),
    '--accent':        `oklch(${dark ? 0.62 : 0.56} ${a.c} ${a.hue})`,
    '--accent-strong': `oklch(${dark ? 0.78 : 0.48} ${a.c * (dark ? 0.9 : 1)} ${a.hue})`,
    '--accent-fg':     a.hue > 50 && a.hue < 110 ? 'oklch(0.25 0.05 80)' : 'oklch(0.99 0.01 270)',
    '--accent-soft':   `oklch(${dark ? 0.30 : 0.955} ${dark ? a.c*0.5 : a.c*0.32} ${a.hue})`,
    '--accent-ring':   `oklch(${dark ? 0.62 : 0.56} ${a.c} ${a.hue} / ${dark ? 0.35 : 0.28})`,
  };
  for (const [k, v] of Object.entries(vars)) root.style.setProperty(k, v);
  root.style.colorScheme = dark ? 'dark' : 'light';
  root.setAttribute('data-mode', dark ? 'dark' : 'light');
}

function avatarColor(seed) {
  let h = 0; for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) % 360;
  return `oklch(0.62 0.13 ${h})`;
}

export const Theme = { applyTheme, avatarColor };
