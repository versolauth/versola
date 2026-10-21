import { html, nothing, type TemplateResult } from 'lit';
import type { FormPreviewScenario } from '../types';
import { scenarioTitle } from '../utils/preview';

/**
 * The control that picks which state of a built-in form to preview, in the toolbar the host
 * component already styles (`ctrl-group`, `seg-control`, `seg-btn`). It replaces the per-property
 * controls: central lists the states, so nobody assembles them by hand.
 */
export function renderScenarioControl(
  scenarios: FormPreviewScenario[],
  selectedId: string,
  onSelect: (id: string) => void,
): TemplateResult | typeof nothing {
  if (scenarios.length === 0) return nothing;
  return html`
    <div class="ctrl-group">
      <span class="ctrl-label">State</span>
      <div class="seg-control" role="group" aria-label="Preview state">
        ${scenarios.map(s => html`
          <button class="seg-btn ${selectedId === s.id ? 'seg-active' : ''}" aria-pressed=${selectedId === s.id ? 'true' : 'false'}
            @click=${() => onSelect(s.id)}>${scenarioTitle(s)}</button>
        `)}
      </div>
    </div>
  `;
}

/** The selected scenario, or the first one when nothing is selected (or the selection is gone). */
export function pickScenario(scenarios: FormPreviewScenario[], selectedId: string | undefined): FormPreviewScenario | undefined {
  return scenarios.find(s => s.id === selectedId) ?? scenarios[0];
}
