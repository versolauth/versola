import { installDemoBackend } from './install';
import { demoSeed } from './seed';

// The wrapper has to be in place before the console's first request, and the console starts
// requesting as soon as `<versola-admin>` is upgraded -- so it is imported only after.
await installDemoBackend(demoSeed);
await import('../index');

const note = document.createElement('div');
note.setAttribute('role', 'status');
note.style.cssText = [
  'position:fixed', 'right:16px', 'bottom:16px', 'z-index:2147483647', 'display:flex', 'gap:12px', 'align-items:center',
  'padding:8px 8px 8px 14px', 'border-radius:12px', 'font:500 13px/1.3 Inter,-apple-system,sans-serif',
  'background:var(--bg-dark-card)', 'color:var(--text-primary)', 'border:1px solid var(--border-dark)',
  'box-shadow:var(--surface-overlay-shadow)',
].join(';');
const text = document.createElement('span');
const idle = 'Demo data: changes stay in this tab';
text.textContent = idle;
const reset = document.createElement('button');
reset.type = 'button';
reset.textContent = 'Reset';
reset.style.cssText = 'font:inherit;padding:6px 12px;border-radius:8px;border:1px solid var(--border-dark);background:transparent;color:inherit;cursor:pointer';
reset.addEventListener('click', () => window.location.reload());
note.append(text, reset);
document.body.append(note);

// The shell's "Log out" and "My Security" links lead to services the demo does not have. Log out
// starts the demo over; the other one says why nothing happened.
document.addEventListener('click', event => {
  const link = event.composedPath().find((node): node is HTMLAnchorElement => node instanceof HTMLAnchorElement);
  const href = link?.getAttribute('href') ?? '';
  if (href.startsWith('/logout')) {
    event.preventDefault();
    window.location.reload();
  } else if (href.startsWith('/resources/auth')) {
    event.preventDefault();
    text.textContent = 'Account settings are not part of the demo';
    setTimeout(() => { text.textContent = idle; }, 3500);
  }
}, true);
