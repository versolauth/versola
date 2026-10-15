// Shared markup for all variants: same DOM, different skin (mirrors how real forms share TSX + CSS).
const I = {
  eye: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>',
  key: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="15" r="4"/><path d="M10.8 12.2 20 3M16 7l3 3"/></svg>',
  check: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
  globe: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18"/></svg>',
  user: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="4"/><path d="M4 21c1-4.5 4.5-6 8-6s7 1.5 8 6"/></svg>',
  mail: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 7l9 6 9-6"/></svg>',
  refresh: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 12a8 8 0 1 1-2.4-5.7M20 4v5h-5"/></svg>',
  shield: '<svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l8 3v6c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6z"/></svg>',
  keyL: '<svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="15" r="4"/><path d="M10.8 12.2 20 3M16 7l3 3"/></svg>',
  wifi: '<svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2 9a15 15 0 0 1 20 0M5.5 12.5a10 10 0 0 1 13 0M9 16a5 5 0 0 1 6 0"/><path d="M3 3l18 18"/></svg>',
  checkL: '<svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
};
const logo = '<span class="logo" aria-hidden="true">D</span>';
const topbar = `<header class="top"><div class="brand">${logo}<span>Danceliker</span></div><button class="locale" type="button">${I.globe}<span>English</span></button></header>`;
const aside = `<aside class="aside"><div class="aside-in"><div class="brand">${logo}<span>Danceliker</span></div><div class="aside-copy"><p class="aside-title">Your classes,<br>one tap away.</p><p class="aside-sub">Schedule, booking and payments for your dance studio.</p></div><small class="aside-foot">Secured by Versola</small></div></aside>`;
const foot = (extra = '') => `<footer class="foot">${extra}<span>Secured by Versola</span><span class="foot-links"><a href="#">Privacy</a><a href="#">Terms</a></span></footer>`;

const screens = {
  signin: (err) => `
    <h1>Sign in</h1><p class="sub">to continue to Danceliker</p>
    <form onsubmit="return false" novalidate>
      <div class="field"><label for="id">Email or phone</label>
        <input class="input" id="id" name="login" autocomplete="username webauthn" inputmode="email" value="${err ? 'anna@example.com' : ''}" placeholder="name@example.com"></div>
      <div class="field${err ? ' has-error' : ''}"><label for="pw">Password</label>
        <div class="pw"><input class="input" id="pw" type="password" autocomplete="current-password" value="${err ? 'hunter2hunter' : ''}" ${err ? 'aria-invalid="true" aria-describedby="pw-err"' : ''}>
        <button class="pw-toggle" type="button" aria-label="Show password" onclick="const i=this.previousElementSibling;i.type=i.type==='password'?'text':'password'">${I.eye}</button></div>
        ${err ? '<p class="err" id="pw-err" role="alert">That email and password don’t match. Check them and try again.</p>' : ''}</div>
      <button class="btn primary" type="submit">Continue</button>
      <div class="divider"><span>or</span></div>
      <button class="btn secondary" type="button">${I.key}<span>Sign in with a passkey</span></button>
    </form>
    <p class="alt">New here? <a href="#">Create an account</a></p>`,
  otp: () => `
    <h1>Check your email</h1><p class="sub">We sent a 6-digit code to <b>a•••a@example.com</b></p>
    <form onsubmit="return false">
      <div class="field"><label for="otp">Verification code</label>
        <div class="otp" onclick="document.getElementById('otp').focus()">
          <input id="otp" class="otp-input" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]*" value="482" aria-label="6-digit code">
          ${[0,1,2,3,4,5].map(i => `<span class="cell" data-i="${i}"></span>`).join('')}
        </div></div>
      <button class="btn primary" type="submit">Verify</button>
    </form>
    <p class="alt"><span class="timer">Resend code in 0:42</span> · <a href="#">Use a different email</a></p>`,
  consent: () => `
    <div class="pair"><span class="logo lg">D</span><span class="pair-dots"></span><span class="logo lg ink">A</span></div>
    <h1>Danceliker wants access to your account</h1>
    <p class="who">Signed in as <b>anna@example.com</b> · <a href="#">Not you?</a></p>
    <form onsubmit="return false">
      <ul class="scopes">
        <li><label class="scope"><input type="checkbox" checked disabled><span class="box">${I.check}</span><span class="scope-t"><b>Your profile</b><small>Name and profile picture</small></span></label></li>
        <li><label class="scope"><input type="checkbox" checked disabled><span class="box">${I.check}</span><span class="scope-t"><b>Your email address</b><small>So the studio can send booking receipts</small></span></label></li>
        <li><label class="scope"><input type="checkbox" checked><span class="box">${I.check}</span><span class="scope-t"><b>Stay signed in</b><small>Optional · you won’t need to sign in again on this device</small></span></label></li>
      </ul>
      <div class="actions"><button class="btn primary" type="submit">Allow</button><button class="btn ghost" type="button">Deny</button></div>
    </form>
    <p class="legal">By continuing you agree to Danceliker’s <a href="#">Terms</a> and <a href="#">Privacy Policy</a>.</p>`,
  'set-password': () => `
    <h1>Create a password</h1><p class="sub">You’ll use it with <b>anna@example.com</b></p>
    <form onsubmit="return false">
      <div class="field"><label for="np">New password</label>
        <div class="pw"><input class="input" id="np" type="password" autocomplete="new-password" value="Dance2" oninput="reqs(this.value)">
        <button class="pw-toggle" type="button" aria-label="Show password" onclick="const i=this.previousElementSibling;i.type=i.type==='password'?'text':'password'">${I.eye}</button></div></div>
      <ul class="reqs" id="reqs" aria-live="polite">
        <li data-r="len"><span class="box">${I.check}</span>At least 8 characters</li>
        <li data-r="up"><span class="box">${I.check}</span>An uppercase letter</li>
        <li data-r="lo"><span class="box">${I.check}</span>A lowercase letter</li>
        <li data-r="num"><span class="box">${I.check}</span>A number</li>
      </ul>
      <button class="btn primary" type="submit">Save password</button>
    </form>`,
  'passkey-enroll': () => `
    <div class="status-ico">${I.keyL}</div>
    <h1>Sign in faster with a passkey</h1><p class="sub">Use your fingerprint, face or screen lock instead of typing a password. It takes a few seconds.</p>
    <form onsubmit="return false"><div class="actions"><button class="btn primary" type="submit">${I.key}<span>Create a passkey</span></button><button class="btn ghost" type="button">Not now</button></div></form>`,
  'access-denied': () => `
    <div class="status-ico bad">${I.shield}</div>
    <h1>Access denied</h1><p class="sub">Your account doesn’t have permission to use Danceliker. If you think this is a mistake, contact the studio.</p>
    <form onsubmit="return false"><div class="actions"><button class="btn primary" type="submit">Use a different account</button></div></form>`,
  'service-unavailable': () => `
    <div class="status-ico warn">${I.wifi}</div>
    <h1>We’ll be right back</h1><p class="sub">Sign-in is temporarily unavailable. Nothing is wrong with your account — please try again in a minute.</p>
    <form onsubmit="return false"><div class="actions"><button class="btn primary" type="submit">${I.refresh}<span>Try again</span></button></div></form>`,
  'signed-out': () => `
    <div class="status-ico ok">${I.checkL}</div>
    <h1>You’re signed out</h1><p class="sub">See you next time. It’s safe to close this tab.</p>
    <form onsubmit="return false"><div class="actions"><button class="btn secondary" type="submit">Sign in again</button></div></form>`,
};

const root = document.documentElement;
window.applyTheme = o => {
  if (o.brand) { const h = o.brand.replace('#', ''); root.style.setProperty('--brand', '#' + h);
    const L = x => { const c = [0, 2, 4].map(i => parseInt(x.slice(i, i + 2), 16) / 255).map(v => v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4); return .2126 * c[0] + .7152 * c[1] + .0722 * c[2]; };
    // text on the brand colour: whichever of white / near-black has the higher WCAG contrast
    const cr = (a, b) => (Math.max(L(a), L(b)) + .05) / (Math.min(L(a), L(b)) + .05);
    root.style.setProperty('--on-brand', cr(h, 'ffffff') >= cr(h, '1c1b19') ? '#fff' : '#1c1b19'); }
  if ('dark' in o) root.dataset.theme = o.dark ? 'dark' : 'light';
  if (o.shape) root.dataset.shape = o.shape;
  if (o.surface) root.dataset.surface = o.surface;
};
{ const q = new URLSearchParams(location.search); applyTheme({ brand: q.get('brand'), dark: q.get('dark'), shape: q.get('shape'), surface: q.get('surface') }); }
addEventListener('message', e => e.data && e.data.theme && applyTheme(e.data.theme));
const name = (location.hash || '#signin').slice(1);
const body = (screens[name.replace('-error', '')] || screens.signin)(name.endsWith('-error'));
document.body.innerHTML = `<main class="page screen-${name.replace('-error','')}">${aside}<section class="panel">${topbar}<div class="content">${body}</div>${foot()}</section></main>`;
const sync = () => {
  const inp = document.getElementById('otp'); if (!inp) return;
  inp.value = inp.value.replace(/\D/g, '');
  document.querySelectorAll('.cell').forEach((c, i) => {
    c.textContent = inp.value[i] || '';
    c.classList.toggle('on', document.activeElement === inp && i === Math.min(inp.value.length, 5));
    c.classList.toggle('filled', !!inp.value[i]);
  });
};
const inp = document.getElementById('otp');
if (inp) { ['input', 'focus', 'blur'].forEach(e => inp.addEventListener(e, sync)); sync(); }

window.reqs = v => { const ok = { len: v.length >= 8, up: /[A-Z]/.test(v), lo: /[a-z]/.test(v), num: /\d/.test(v) };
  document.querySelectorAll('#reqs li').forEach(li => li.classList.toggle('ok', ok[li.dataset.r])); };
if (document.getElementById('np')) reqs(document.getElementById('np').value);
