import { installDemoBackend } from './install';
import { demoSeed } from './seed';

// The wrapper has to be in place before the console's first request, and the console starts
// requesting as soon as `<versola-admin>` is upgraded -- so it is imported only after.
await installDemoBackend(demoSeed);
await import('../index');

// The shell's "Log out" and "My Security" links lead to services the demo does not have. Log out
// starts the demo over; the other one does nothing. The page around the demo owns the reset control.
document.addEventListener('click', event => {
  const link = event.composedPath().find((node): node is HTMLAnchorElement => node instanceof HTMLAnchorElement);
  const href = link?.getAttribute('href') ?? '';
  if (href.startsWith('/logout')) {
    event.preventDefault();
    window.location.reload();
  } else if (href.startsWith('/resources/auth')) {
    event.preventDefault();
  }
}, true);
