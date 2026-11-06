import { createSignal, Match, Switch } from 'solid-js';
import { Listbox } from './listbox';
import { applyScheme, readScheme, type SchemeChoice } from './scheme';

// English fallbacks: a form version stored before this control existed has no strings for it.
const FALLBACK: Record<string, string> = {
  scheme_label: 'Colour scheme',
  scheme_auto: 'Auto',
  scheme_light: 'Light',
  scheme_dark: 'Dark',
};

const SvgIcon = (props: { children: any }) => (
  <svg class="scheme-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    {props.children}
  </svg>
);

/** The glyph of the current choice: a sun, a moon, or a half-filled circle for "follow the device". */
function SchemeIcon(props: { choice: SchemeChoice }) {
  return (
    <Switch>
      <Match when={props.choice === 'light'}>
        <SvgIcon><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></SvgIcon>
      </Match>
      <Match when={props.choice === 'dark'}>
        <SvgIcon><path d="M20 14.5A8.5 8.5 0 1 1 9.5 4a6.7 6.7 0 0 0 10.5 10.5z"/></SvgIcon>
      </Match>
      <Match when={props.choice === 'auto'}>
        <SvgIcon><circle cx="12" cy="12" r="9"/><path d="M12 3a9 9 0 0 1 0 18z" fill="currentColor"/></SvgIcon>
      </Match>
    </Switch>
  );
}

/**
 * The visitor's Auto / Light / Dark choice for this browser, as an icon next to the language switcher.
 * Not rendered when the theme fixes the scheme (the lock marker, see `schemeLocked`). The choice is applied
 * to the page at once and remembered in localStorage; the page shell applies it again before first paint.
 */
export function SchemeToggle(props: { t: Record<string, string> }) {
  const [choice, setChoice] = createSignal<SchemeChoice>(readScheme());
  const text = (key: string) => props.t[key] ?? FALLBACK[key];
  const items = () => (['auto', 'light', 'dark'] as const).map(value => ({ value, label: text(`scheme_${value}`) }));

  return (
    <Listbox
      class="scheme-dropdown"
      triggerClass="scheme-trigger"
      label={text('scheme_label')}
      items={items()}
      current={choice()}
      onChange={value => { const next = value as SchemeChoice; setChoice(next); applyScheme(next); }}
    >
      <SchemeIcon choice={choice()} />
    </Listbox>
  );
}
