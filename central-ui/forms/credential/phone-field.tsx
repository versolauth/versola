import { createMemo, createSignal, For, onCleanup, onMount, Show } from 'solid-js';
import {
  detect,
  fits,
  format,
  groupByDialCode,
  matches,
  nationalDigits,
  regionName,
  SEARCH_FROM,
  type DialCodeGroup,
  type PhoneRegion,
} from './phone';

export type PhoneFieldApi = {
  /** Marks the field invalid and focuses it when the number fits none of the tenant's regions. */
  validate: () => boolean;
};

const Chevron = () => (
  <svg class="phone-chevron" viewBox="0 0 10 6" fill="none" aria-hidden="true">
    <path d="M1 1l4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
  </svg>
);

const Check = () => (
  <svg viewBox="0 0 14 14" fill="none" aria-hidden="true">
    <path d="M2.5 7.5l3 3 6-6.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
  </svg>
);

/**
 * Phone input for a tenant that lists the countries it takes: one dial code is shown locked, several
 * are picked from a selector (with a search box once the list is long). The submitted `phone` is the
 * dial code and the digits, which is what the server checks again.
 */
export function PhoneField(props: {
  /** Id of the number input, so the form's visible label can name it. */
  id: string;
  regions: PhoneRegion[];
  t: () => Record<string, string>;
  locale: () => string;
  apiRef: (api: PhoneFieldApi) => void;
}) {
  const groups = groupByDialCode(props.regions);
  const locked = groups.length === 1;
  const searchable = groups.length >= SEARCH_FROM;

  const [group, setGroup] = createSignal<DialCodeGroup>(groups[0]);
  const [digits, setDigits] = createSignal('');
  const [open, setOpen] = createSignal(false);
  const [query, setQuery] = createSignal('');
  const [invalid, setInvalid] = createSignal(false);

  let root!: HTMLDivElement;
  let input!: HTMLInputElement;
  let trigger: HTMLButtonElement | undefined;
  let search: HTMLInputElement | undefined;
  let list: HTMLUListElement | undefined;

  const iso = (g: DialCodeGroup) => g.regions.map((r) => r.region).join('·');
  const names = (g: DialCodeGroup) => g.regions.map((r) => regionName(r.region, props.locale())).join(', ');
  const visible = createMemo(() => (searchable ? groups.filter((g) => matches(g, query(), props.locale())) : groups));
  const placeholder = () => format('701234567890'.slice(0, group().maxLength), group().maxLength);
  const options = () => Array.from(list?.querySelectorAll<HTMLElement>('[role="option"]') ?? []);

  const show = (value: string) => {
    input.value = format(value, group().maxLength);
  };

  const onInput = () => {
    const hit = detect(groups, input.value);
    if (hit) setGroup(hit.group);
    const next = nationalDigits(group(), hit ? hit.national : input.value);
    setDigits(next);
    show(next);
    setInvalid(false);
  };

  const close = (refocus = false) => {
    setOpen(false);
    setQuery('');
    if (refocus) trigger?.focus();
  };

  const openMenu = () => {
    setOpen(true);
    queueMicrotask(() => {
      if (search) search.focus();
      else (list?.querySelector<HTMLElement>('[aria-selected="true"]') ?? options()[0])?.focus();
    });
  };

  const select = (g: DialCodeGroup) => {
    setGroup(g);
    const next = digits().slice(0, g.maxLength);
    setDigits(next);
    show(next);
    setInvalid(false);
    close();
    input.focus();
  };

  const moveFocus = (from: HTMLElement | null, step: number) => {
    const items = options();
    const at = from ? items.indexOf(from) : -1;
    const target = items[at + step];
    if (target) target.focus();
    else if (step < 0) search?.focus();
  };

  const onMenuKeyDown = (e: KeyboardEvent) => {
    const target = e.target as HTMLElement;
    if (e.key === 'Escape') {
      e.preventDefault();
      close(true);
    } else if (e.key === 'Tab') {
      close();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      moveFocus(target.getAttribute('role') === 'option' ? target : null, 1);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      moveFocus(target.getAttribute('role') === 'option' ? target : null, -1);
    } else if (e.key === 'Enter' && target === search) {
      e.preventDefault();
      const first = visible()[0];
      if (first) select(first);
    }
  };

  const onOutside = (e: MouseEvent) => {
    if (open() && !root.contains(e.target as Node)) close();
  };
  onMount(() => document.addEventListener('mousedown', onOutside));
  onCleanup(() => document.removeEventListener('mousedown', onOutside));

  props.apiRef({
    validate: () => {
      const ok = fits(group(), digits());
      setInvalid(!ok);
      if (!ok) input.focus();
      return ok;
    },
  });

  return (
    <div class="phone-group" ref={root}>
      <input type="hidden" name="phone" value={group().dialCode + digits()} />
      <div class={`phone-field${open() ? ' is-open' : ''}${invalid() ? ' input-error' : ''}`}>
        <Show
          when={!locked}
          fallback={
            <div class="phone-code phone-code-locked" aria-label={`${iso(group())} ${group().dialCode}, ${props.t().phone_country_code}`}>
              <span class="phone-iso">{iso(group())}</span>
              {group().dialCode}
            </div>
          }
        >
          <button
            type="button"
            class="phone-code"
            ref={trigger}
            aria-haspopup="listbox"
            aria-expanded={open()}
            aria-label={`${iso(group())} ${group().dialCode}, ${props.t().phone_country_code}`}
            onClick={() => (open() ? close() : openMenu())}
          >
            <span class="phone-iso">{iso(group())}</span>
            {group().dialCode}
            <Chevron />
          </button>
        </Show>
        <input
          ref={input}
          id={props.id}
          class="phone-input"
          type="tel"
          inputmode="tel"
          autocomplete="tel-national"
          placeholder={placeholder()}
          aria-invalid={invalid()}
          required
          onInput={onInput}
        />
      </div>
      <Show when={open()}>
        <div class="phone-backdrop" onClick={() => close(true)} />
        <div class="phone-menu" onKeyDown={onMenuKeyDown}>
          <div class="phone-grab" />
          <div class="phone-sheet-title">{props.t().phone_country_code}</div>
          <Show when={searchable}>
            <input
              ref={search}
              class="phone-search"
              type="search"
              autocomplete="off"
              placeholder={props.t().phone_search}
              aria-label={props.t().phone_search}
              value={query()}
              onInput={(e) => setQuery(e.currentTarget.value)}
            />
          </Show>
          <ul class="phone-options" role="listbox" ref={list} aria-label={props.t().phone_country_code}>
            <For each={visible()}>
              {(g) => (
                <li
                  class="phone-option"
                  role="option"
                  tabindex="-1"
                  aria-selected={g.dialCode === group().dialCode}
                  onClick={() => select(g)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      select(g);
                    }
                  }}
                >
                  <span class="phone-iso">{iso(g)}</span>
                  <span class="phone-option-name">{names(g)}</span>
                  <span class="phone-option-code">{g.dialCode}</span>
                  <Show when={g.dialCode === group().dialCode}>
                    <Check />
                  </Show>
                </li>
              )}
            </For>
            <Show when={visible().length === 0}>
              <li class="phone-empty">{props.t().phone_no_results}</li>
            </Show>
          </ul>
        </div>
      </Show>
    </div>
  );
}
