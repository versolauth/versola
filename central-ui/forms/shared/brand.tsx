import { createSignal, Show } from 'solid-js';
import type { ClientInfo } from './config';

// The brand zone above the card: the identity provider's logo (from the system settings) on a light
// tile, or the Versola wordmark when none is set or it fails to load. A client's own logo belongs
// inside the card. The wordmark is named for assistive technology ("Versola" is a product name, not
// translatable text); a configured logo has no name to read, so it stays decorative.
export function PageBrand(props: { logo?: string }) {
  const [failed, setFailed] = createSignal(false);
  return (
    <div class="page-brand">
      <Show
        when={props.logo && !failed()}
        fallback={
          <svg class="page-wordmark" role="img" aria-label="Versola" viewBox="-2 0 286 56" fill="none" stroke="currentColor" stroke-width="8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M2 22 L18 48 L34 22" />
            <path d="M50 35 H76 A13 13 0 1 0 72.5 44.3" />
            <path d="M98 22 V48 M98 36 C98 26 104 22 114 22" />
            <path d="M158.5 27 C156.5 23 152.5 22 148.5 22 C142.5 22 138.5 25 138.5 29 C138.5 33 142.5 34.5 148.5 36 C154.5 37.5 158.5 39 158.5 42.5 C158.5 46.5 154.5 48.5 148.5 48.5 C143.5 48.5 139.5 46.5 138 42.5" />
            <circle cx="193.5" cy="35" r="13" />
            <path d="M228.5 6 V48" />
            <circle cx="263.5" cy="35" r="13" />
            <path d="M276.5 22 V48" />
          </svg>
        }
      >
        <span class="page-brand-tile" aria-hidden="true">
          <img src={props.logo} alt="" onError={() => setFailed(true)} />
        </span>
      </Show>
    </div>
  );
}

// The partner's app the user is signing in to, inside the card: its icon and name. Only for a partner's app (one with a
// consent flow); the identity provider's own apps are not announced.
export function ClientBadge(props: { client?: ClientInfo }) {
  const [failed, setFailed] = createSignal(false);
  const initial = () => Array.from(props.client?.name?.trim() ?? '')[0]?.toLocaleUpperCase();
  return (
    <Show when={props.client?.partner && (props.client.name || props.client.logo)}>
      <div class="client-badge">
        <Show
          when={props.client?.logo && !failed()}
          fallback={<Show when={initial()}>{(letter) => <span class="client-badge-icon client-badge-initial" aria-hidden="true">{letter()}</span>}</Show>}
        >
          <span class="client-badge-icon" aria-hidden="true">
            <img src={props.client?.logo} alt="" onError={() => setFailed(true)} />
          </span>
        </Show>
        <Show when={props.client?.name}>{(name) => <span class="client-badge-name">{name()}</span>}</Show>
      </div>
    </Show>
  );
}
