import { LitElement, html, css, nothing, type TemplateResult } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { methodBadgeStyles, tagStyles } from '../styles/components';
import { celHighlightStyles } from '../styles/cel-highlight';
import type { InjectRule, ResourceEndpoint, ResourceEndpointId } from '../types';
import { renderHighlightedCel } from '../utils/cel-highlight';
import { formatDurationShort } from '../utils/duration';
import { breakPath } from './break-text';

/** The step-up of an endpoint, readable on its own: the ACR values, when they are required, the age limit. */
export function hasStepUp(endpoint: Pick<ResourceEndpoint, 'stepUpAcr' | 'stepUpCondition' | 'maxAge'>): boolean {
  return !!endpoint.stepUpAcr?.trim() || !!endpoint.stepUpCondition?.trim() || endpoint.maxAge != null;
}

/** The ACR values of `stepUpAcr` (a space-separated list). */
export function acrValues(stepUpAcr: string | undefined | null): string[] {
  return (stepUpAcr ?? '').split(/\s+/).filter(value => value.length > 0);
}

/** `at most 5m`, the exact seconds in the title. */
export function maxAgeLabel(maxAge: number): TemplateResult {
  return html`<span title=${`${maxAge} seconds`}>at most ${formatDurationShort(maxAge)}</span>`;
}

/** A method badge for a row or a cell (`.method-badge.compact`); the host needs `methodBadgeStyles`. */
export function methodBadge(method: string): TemplateResult {
  return html`<span class="method-badge compact method-${method.toLowerCase()}">${method}</span>`;
}

/** The inject rules of an endpoint as `HEADER X-User-Id = token.sub` lines. */
export function renderInjectLines(inject: InjectRule[]): TemplateResult {
  return html`${inject.map(rule => html`
    <div class="inj">
      <div class="inj-head"><span class="src">${rule.target}</span><b class="inj-name">${rule.name}</b></div>
      <code class="cel-inline inj-value">= ${renderHighlightedCel(rule.expression)}</code>
    </div>`)}`;
}

/**
 * The endpoints of a resource as a table whose four columns answer what an operator asks without
 * opening an editor: which request (method, path, whether the caller's userinfo is fetched), who
 * may call it (the permissions that grant it, joined by `or`, and the `allow` rule when there is one), what step-up it
 * asks for (ACR values, the condition, the age limit; the column is drawn only when an endpoint has some) and what edge adds to the request it forwards
 * (header, query and body injections). Below 720px of room each row is a card and each cell names its
 * column. Read-only: the card around it carries `Edit endpoints`.
 */
@customElement('versola-resource-endpoints-table')
export class VersolaResourceEndpointsTable extends LitElement {
  @property({ attribute: false }) endpoints: ResourceEndpoint[] = [];
  /** Endpoint id -> the permissions that grant it (`indexPermissionsByEndpoint`). */
  @property({ attribute: false }) permissionsByEndpoint: Map<ResourceEndpointId, string[]> = new Map();
  @property({ type: String }) resourceId = '';

  static styles = [
    theme,
    tagStyles,
    methodBadgeStyles,
    celHighlightStyles,
    css`
      :host {
        display: block;
        container: ept / inline-size;
      }

      table {
        width: 100%;
        border-collapse: collapse;
        table-layout: fixed;
      }

      /* Endpoint and Who may call are narrower; the injections get the room. */
      .c-endpoint { width: 22%; min-width: 13rem; }
      .c-who { width: 27%; }
      .c-step { width: 17%; }

      th {
        text-align: left;
        font-size: var(--text-xs);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: var(--text-secondary);
        padding: 0.75rem var(--spacing-md);
        border-bottom: 1px solid var(--border-default);
      }

      th:first-child,
      td:first-child {
        padding-left: var(--spacing-lg);
        min-width: 13rem;
      }

      th:last-child,
      td:last-child {
        padding-right: var(--spacing-lg);
      }

      td {
        padding: 0.875rem var(--spacing-md);
        border-bottom: 1px solid var(--border-subtle);
        vertical-align: top;
        font-size: var(--text-base);
        color: var(--text-primary);
      }

      tbody tr:last-child td {
        border-bottom: 0;
      }

      /* The badge keeps the first line's start; a path that does not fit wraps beside it, under itself. */
      .ep-main {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr);
        align-items: baseline;
        gap: 0.25rem 0.5rem;
      }

      .path {
        font-family: var(--font-mono);
        font-size: var(--text-base);
        font-weight: 600;
        overflow-wrap: normal;
        word-break: normal;
      }

      .ep-flags {
        margin-top: 0.375rem;
      }

      /* A labelled block: the kicker above its value, in every width. */
      .line {
        display: grid;
        gap: 0.25rem;
        justify-items: start;
      }

      .line + .line {
        margin-top: 0.75rem;
      }

      /* The kicker of a line inside a cell: Permission, Allow, ACR, When, Max age. */
      .kl {
        font-size: var(--text-xs);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: var(--text-secondary);
      }

      .or {
        font-size: var(--text-xs);
        color: var(--text-secondary);
      }

      .dim {
        color: var(--text-secondary);
      }

      /* One injection: the target badge and the name, then the value under them. */
      .inj {
        display: grid;
        gap: 0.25rem;
        justify-items: start;
      }

      .inj + .inj {
        margin-top: 0.75rem;
      }

      .inj-head {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 0.25rem 0.5rem;
      }

      .inj-value {
        overflow-wrap: anywhere;
      }

      .vals {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 0.25rem 0.5rem;
        min-width: 0;
      }

      .src {
        display: inline-flex;
        padding: 0.0625rem 0.5rem;
        border: 1px solid var(--border-default);
        border-radius: var(--radius-pill);
        font-size: var(--text-xs);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--text-secondary);
      }

      .inj-name {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
      }

      .cel-inline {
        font-size: var(--text-sm);
      }

      /* Narrow: each endpoint is a card; its cells name their column. The header row stays in the
         accessibility tree, off screen, and the explicit ARIA roles keep the table semantics. */
      @container ept (max-width: 720px) {
        thead {
          position: absolute;
          left: -9999px;
        }

        table,
        tbody,
        tr,
        td {
          display: block;
        }

        colgroup {
          display: none;
        }

        /* Each labelled block is a vertical list: the kicker above its value. */
        tr {
          padding: var(--spacing-md);
          border-bottom: 1px solid var(--border-subtle);
        }

        tbody tr:last-child {
          border-bottom: 0;
        }

        td,
        td:first-child,
        td:last-child {
          padding: 0.375rem 0 0;
          border: 0;
        }

        td:first-child {
          padding-top: 0;
        }

        td:not(:first-child) {
          padding-top: 0.75rem;
        }

        /* The sub-kickers inside a block read as plain labels, so the block names stand out. */
        .kl {
          text-transform: none;
          letter-spacing: 0;
          font-size: var(--text-sm);
          font-weight: 600;
        }

        td[data-label]:not(:first-child)::before {
          content: attr(data-label);
          display: block;
          margin-bottom: 0.25rem;
          font-size: var(--text-xs);
          font-weight: 700;
          text-transform: uppercase;
          letter-spacing: 0.08em;
          color: var(--text-secondary);
        }
      }
    `,
  ];

  private permissionsCell(endpoint: ResourceEndpoint) {
    const permissions = this.permissionsByEndpoint.get(endpoint.id) ?? [];
    const allow = endpoint.allow?.trim();
    return html`
      <div class="line">
        <span class="kl">Permission</span>
        <span class="vals">${permissions.length === 0
          ? html`<span class="dim">None grants it yet</span>`
          : permissions.map((id, index) => html`${index > 0 ? html`<span class="or">or</span>` : nothing}<span class="tag">${id}</span>`)}</span>
      </div>
      ${allow ? html`
        <div class="line">
          <span class="kl">Allow</span>
          <span class="vals"><code class="cel-inline">${renderHighlightedCel(allow)}</code></span>
        </div>` : nothing}`;
  }

  private stepUpCell(endpoint: ResourceEndpoint) {
    if (!hasStepUp(endpoint)) return html`<span class="dim">—</span>`;
    const acr = acrValues(endpoint.stepUpAcr);
    const condition = endpoint.stepUpCondition?.trim();
    return html`
      ${acr.length || condition ? html`
        <div class="line">
          <span class="kl">ACR</span>
          <span class="vals">${acr.length ? acr.map(value => html`<span class="tag">${value}</span>`) : html`<span class="dim">None chosen</span>`}</span>
        </div>
        <div class="line">
          <span class="kl">When</span>
          <span class="vals">${condition ? html`<code class="cel-inline">${renderHighlightedCel(condition)}</code>` : html`<span class="dim">No condition, so never asked</span>`}</span>
        </div>` : nothing}
      ${endpoint.maxAge != null ? html`
        <div class="line"><span class="kl">Max age</span><span class="vals">${maxAgeLabel(endpoint.maxAge)}</span></div>` : nothing}`;
  }

  render() {
    // A column nothing fills is not drawn: the step-up column appears once an endpoint asks for step-up.
    const stepUp = this.endpoints.some(hasStepUp);
    return html`
      <table role="table" aria-label=${`Endpoints of ${this.resourceId}`}>
        <colgroup><col class="c-endpoint"><col class="c-who">${stepUp ? html`<col class="c-step">` : nothing}<col></colgroup>
        <thead role="rowgroup">
          <tr role="row">
            <th scope="col" role="columnheader">Endpoint</th>
            <th scope="col" role="columnheader">Who may call</th>
            ${stepUp ? html`<th scope="col" role="columnheader">Step-up</th>` : nothing}
            <th scope="col" role="columnheader">Added to the request</th>
          </tr>
        </thead>
        <tbody role="rowgroup">
          ${this.endpoints.map(endpoint => html`
            <tr role="row" data-endpoint=${`${endpoint.method} ${endpoint.path}`}>
              <td role="cell">
                <div class="ep-main">${methodBadge(endpoint.method)}<span class="path">${breakPath(endpoint.path)}</span></div>
                ${endpoint.fetchUserInfo ? html`<div class="ep-flags"><span class="tag quiet" title="Edge calls /userinfo for each request and gives the claims to this endpoint's rules as user">userinfo</span></div>` : nothing}
              </td>
              <td role="cell" data-label="Who may call">${this.permissionsCell(endpoint)}</td>
              ${stepUp ? html`<td role="cell" data-label="Step-up">${this.stepUpCell(endpoint)}</td>` : nothing}
              <td role="cell" data-label="Added to the request">
                ${endpoint.inject.length ? renderInjectLines(endpoint.inject) : html`<span class="dim">Nothing</span>`}
              </td>
            </tr>`)}
        </tbody>
      </table>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-resource-endpoints-table': VersolaResourceEndpointsTable;
  }
}
