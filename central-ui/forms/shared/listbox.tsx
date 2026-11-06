import { createSignal, createUniqueId, For, onCleanup, onMount, Show, type JSX } from 'solid-js';

export type ListboxItem = { value: string; label: string; lang?: string };

// A button that opens a listbox (the ARIA "select-only combobox" pattern without the text field):
// Enter, Space or the arrows open it with the current item focused; the arrows, Home and End move,
// Enter or Space picks, Escape closes and returns to the button, Tab leaves. Focus sits on the option
// itself, so a pick is a click or a key, never a pointer-down, and leaving the dropdown (a click
// elsewhere, Tab) closes it without any timer. Shared by the language and the colour scheme pickers;
// the popover is styled by the `.locale-dropdown` / `.locale-options` / `.locale-option` rules.
export function Listbox(props: {
  items: ListboxItem[];
  current: string;
  onChange: (value: string) => void;
  /** Classes of the trigger button. */
  triggerClass: string;
  /** The trigger's accessible name when its content is not text (an icon). */
  label?: string;
  /** Extra class on the wrapper, for rules that size one popover differently. */
  class?: string;
  children: JSX.Element;
}) {
  const [open, setOpen] = createSignal(false);
  const listId = createUniqueId();
  const triggerId = createUniqueId();
  let root!: HTMLDivElement;
  let trigger!: HTMLButtonElement;
  let list: HTMLUListElement | undefined;

  const options = () => Array.from(list?.querySelectorAll<HTMLElement>('[role="option"]') ?? []);
  const focusOption = (index: number) => {
    const all = options();
    all[(index + all.length) % all.length]?.focus();
  };
  const show = (index?: number) => {
    setOpen(true);
    queueMicrotask(() => focusOption(index ?? Math.max(0, props.items.findIndex(item => item.value === props.current))));
  };
  const close = (returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) trigger.focus();
  };
  const pick = (value: string) => {
    props.onChange(value);
    close(true);
  };

  const onTriggerKey = (e: KeyboardEvent) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); show(); }
    else if (e.key === 'Escape' && open()) { e.preventDefault(); close(false); }
  };
  const onOptionKey = (e: KeyboardEvent, index: number, value: string) => {
    switch (e.key) {
      case 'ArrowDown': focusOption(index + 1); break;
      case 'ArrowUp': focusOption(index - 1); break;
      case 'Home': focusOption(0); break;
      case 'End': focusOption(-1); break;
      case 'Enter': case ' ': pick(value); break;
      case 'Escape': close(true); break;
      case 'Tab': close(false); return;
      default: return;
    }
    e.preventDefault();
  };

  const outside = (e: Event) => { if (!root.contains(e.target as Node)) setOpen(false); };
  onMount(() => document.addEventListener('click', outside));
  onCleanup(() => document.removeEventListener('click', outside));

  return (
    <div class={`locale-dropdown${props.class ? ` ${props.class}` : ''}`} ref={root}>
      <button
        type="button"
        class={props.triggerClass}
        id={triggerId}
        ref={trigger}
        aria-label={props.label}
        aria-haspopup="listbox"
        aria-expanded={open()}
        aria-controls={open() ? listId : undefined}
        onClick={() => (open() ? close(false) : show())}
        onKeyDown={onTriggerKey}
      >
        {props.children}
      </button>
      <Show when={open()}>
        <ul class="locale-options" id={listId} role="listbox" ref={list} aria-labelledby={triggerId}>
          <For each={props.items}>
            {(item, index) => (
              <li
                role="option"
                tabindex="-1"
                lang={item.lang}
                class={`locale-option${item.value === props.current ? ' locale-option-active' : ''}`}
                aria-selected={item.value === props.current}
                onClick={() => pick(item.value)}
                onKeyDown={(e) => onOptionKey(e, index(), item.value)}
              >
                {item.label}
              </li>
            )}
          </For>
        </ul>
      </Show>
    </div>
  );
}
