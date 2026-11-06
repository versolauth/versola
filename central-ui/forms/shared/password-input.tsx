import { createSignal, Show } from 'solid-js';

/** A labelled password field with a show/hide toggle and its own inline error. */
export function PasswordInput(props: {
  id: string;
  name: string;
  label: string;
  autocomplete: string;
  error?: string;
  showLabel: string;
  hideLabel: string;
  onInput?: (value: string, input: HTMLInputElement) => void;
}) {
  const [visible, setVisible] = createSignal(false);
  return (
    <div class="field">
      <label class="field-label" for={props.id}>{props.label}</label>
      <div class="password-field">
        <input
          id={props.id}
          type={visible() ? 'text' : 'password'}
          name={props.name}
          class={`input-field${props.error ? ' input-error' : ''}`}
          placeholder=" "
          autocomplete={props.autocomplete}
          required
          aria-invalid={props.error ? 'true' : undefined}
          aria-describedby={props.error ? `${props.id}-error` : undefined}
          onInput={(e) => props.onInput?.(e.currentTarget.value, e.currentTarget)}
        />
        <button
          type="button"
          class="password-toggle"
          aria-label={visible() ? props.hideLabel : props.showLabel}
          onClick={() => setVisible(!visible())}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <Show
              when={visible()}
              fallback={<><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></>}
            >
              <path d="M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.2 4.1M6.6 6.6A17 17 0 0 0 2 12s3.6 7 10 7c1.7 0 3.2-.4 4.5-1M9.9 9.9a3 3 0 0 0 4.2 4.2"/>
            </Show>
          </svg>
        </button>
      </div>
      <Show when={props.error}>
        <div id={`${props.id}-error`} class="error-text" role="alert">{props.error}</div>
      </Show>
    </div>
  );
}
