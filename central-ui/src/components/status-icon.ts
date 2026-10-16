import { html } from 'lit';

/** Stroke icons for state. The console loads no icon font, and a glyph that the font does not
 *  carry (a circled dot, say) falls back to whatever the system has, at its own size and weight. */
const PATHS = {
  lock: 'M7 11V8a5 5 0 0 1 10 0v3M6 11h12v9H6z',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  x: 'M6 6l12 12M18 6L6 18',
  alert: 'M12 4l9 16H3zM12 10v4M12 17v.5',
  shield: 'M12 3l8 3v6c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6zM8.5 12l2.5 2.5L15.5 10',
  dash: 'M6 12h12',
  pick: 'M12 5l7 7-7 7-7-7z',
  info: 'M12 11v6M12 7.5v.5M4 12a8 8 0 1 0 16 0 8 8 0 0 0-16 0',
} as const;

export type StatusIconName = keyof typeof PATHS;

export function statusIcon(name: StatusIconName, className = '', size = 16) {
  return html`<svg class="status-icon ${className}" viewBox="0 0 24 24" width=${size} height=${size} fill="none"
    stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d=${PATHS[name]}></path></svg>`;
}
