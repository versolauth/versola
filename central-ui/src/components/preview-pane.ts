import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';

const NARROW = '(max-width: 899.98px)';
const BAND_HEIGHT = 260;

/**
 * The live preview of a record that is its own editor (a form, a theme): a card with a toolbar
 * (slot `toolbar`), the stage and a note under it (slot `note`). The previewed page is the default
 * slot, an `<iframe>` the host owns; the pane gives it `logicalWidth` x `logicalHeight` and scales
 * it down to the room it has, so a desktop-width preview fits a 400px column.
 *
 * It sits in a column that stays on screen while the controls beside it scroll. Below 900px it is a
 * band above the controls: a toggle collapses it, and the stage is 260px tall (the page scrolls
 * inside the frame).
 */
@customElement('versola-preview-pane')
export class VersolaPreviewPane extends LitElement {
  @property({ type: String }) heading = 'Preview';
  @property({ type: Number }) logicalWidth = 390;
  @property({ type: Number }) logicalHeight = 600;
  @property({ type: Boolean, reflect: true }) busy = false;
  /** The picture is older than the controls (a render failed): drawn dimmed. */
  @property({ type: Boolean, reflect: true }) stale = false;

  @state() private open = true;
  @state() private scale = 1;
  @state() private narrow = false;

  private observer?: ResizeObserver;
  private readonly media = window.matchMedia(NARROW);
  private readonly onMedia = () => { this.narrow = this.media.matches; };

  static styles = [
    theme,
    css`
      :host {
        display: block;
        position: sticky;
        top: var(--spacing-md);
        align-self: start;
        min-width: 0;
      }

      .pane {
        background: var(--surface);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-card);
        overflow: hidden;
      }

      /* Taller than the window, the pane scrolls inside itself so it stays pinned. */
      @media (min-width: 900px) {
        .pane {
          max-height: calc(100vh - 2 * var(--spacing-md));
          overflow-y: auto;
        }
      }

      .toggle {
        display: none;
        width: 100%;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-sm);
        min-height: 2.75rem;
        padding: 0 var(--spacing-md);
        background: none;
        border: 0;
        font: inherit;
        font-weight: 700;
        color: var(--text-primary);
        cursor: pointer;
      }

      .toggle-state {
        font-size: var(--text-sm);
        font-weight: 600;
        color: var(--text-secondary);
      }

      .toggle:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: -2px;
      }

      .toolbar {
        padding: var(--spacing-md);
        border-bottom: 1px solid var(--border-default);
      }

      .stage {
        background: var(--surface-inset);
        padding: var(--spacing-md);
        display: flex;
        justify-content: center;
        transition: opacity var(--transition-fast);
      }

      .stage.stale {
        opacity: 0.55;
      }

      .frame {
        position: relative;
        flex: none;
      }

      ::slotted(iframe) {
        position: absolute;
        top: 0;
        left: 0;
        border: 0;
        border-radius: var(--radius-md);
        background: var(--surface);
        transform-origin: top left;
      }

      .note {
        padding: var(--spacing-sm) var(--spacing-md) var(--spacing-md);
        font-size: var(--text-xs);
        line-height: 2;
        color: var(--text-secondary);
      }

      @media (max-width: 899.98px) {
        :host {
          position: static;
        }

        .toggle {
          display: flex;
        }

        .pane.collapsed .toolbar,
        .pane.collapsed .stage,
        .pane.collapsed .note {
          display: none;
        }

        .toolbar {
          border-top: 1px solid var(--border-default);
        }
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    this.narrow = this.media.matches;
    this.media.addEventListener('change', this.onMedia);
  }

  disconnectedCallback() {
    this.media.removeEventListener('change', this.onMedia);
    this.observer?.disconnect();
    super.disconnectedCallback();
  }

  firstUpdated() {
    const stage = this.renderRoot.querySelector<HTMLElement>('.stage');
    if (!stage) return;
    this.observer = new ResizeObserver(() => this.measure(stage));
    this.observer.observe(stage);
    this.measure(stage);
  }

  private measure(stage: HTMLElement) {
    const style = getComputedStyle(stage);
    const room = stage.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
    if (room <= 0) return;
    const scale = Math.min(1, room / this.logicalWidth);
    if (Math.abs(scale - this.scale) > 0.001) this.scale = scale;
  }

  render() {
    // A band is as tall as its content allows, so the controls stay in view: the page scrolls inside the frame.
    const height = this.narrow ? BAND_HEIGHT / this.scale : this.logicalHeight;
    const frameWidth = this.logicalWidth * this.scale;
    const frameHeight = height * this.scale;
    return html`
      <section class="pane ${this.open ? '' : 'collapsed'}" aria-label=${this.heading}>
        <button type="button" class="toggle" aria-expanded=${this.open ? 'true' : 'false'} @click=${() => { this.open = !this.open; }}>
          <span>${this.heading}</span><span class="toggle-state">${this.open ? 'Hide' : 'Show'}</span>
        </button>
        <div class="toolbar"><slot name="toolbar"></slot></div>
        <div class="stage ${this.stale ? 'stale' : ''}" aria-busy=${this.busy ? 'true' : 'false'}>
          <div class="frame" style=${`width:${frameWidth}px;height:${frameHeight}px`}>
            <slot @slotchange=${() => this.requestUpdate()}></slot>
          </div>
        </div>
        <div class="note"><slot name="note"></slot></div>
      </section>
    `;
  }

  updated() {
    // The iframe is the host's: size and scale it here so the host never repeats the arithmetic.
    const height = this.narrow ? BAND_HEIGHT / this.scale : this.logicalHeight;
    for (const frame of this.querySelectorAll<HTMLElement>(':scope > iframe')) {
      frame.style.width = `${this.logicalWidth}px`;
      frame.style.height = `${height}px`;
      frame.style.transform = `scale(${this.scale})`;
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-preview-pane': VersolaPreviewPane;
  }
}
