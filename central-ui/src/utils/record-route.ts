/**
 * The part of the URL that names an open record: `?view=scopes&id=profile` opens its panel,
 * `&edit=<group>` its group editor, `&new=1` the create form, `&layout=page` the full-page
 * fallback of the panel, `&tab=<tab>` the tab of a record page with tabs (its own state is `params`). `admin-app` owns the URL; a section asks for a change by firing
 * `record-route` with the fields it sets (an absent field is left as it is, `null` clears it).
 */
export type RecordRoute = {
  id: string | null;
  edit: string | null;
  create: boolean;
  layout: 'page' | null;
  /** The selected tab of a record page that has tabs (`?tab=devices`); null is its first tab. */
  tab: string | null;
  /** The selected tab's own view state, as query parameters (the Activity tab's interval, filters and cursors), so a
   *  link reproduces the view. Meaningful only with `tab`. */
  params: Record<string, string>;
};

export const EMPTY_RECORD_ROUTE: RecordRoute = { id: null, edit: null, create: false, layout: null, tab: null, params: {} };

/** The query parameters `admin-app` itself owns; every other one belongs to a tab's `params`. */
export const RESERVED_ROUTE_PARAMS: ReadonlySet<string> = new Set(['view', 'tenant', 'id', 'edit', 'new', 'layout', 'tab', 'expandClient', 'expandEdge']);

export function dispatchRecordRoute(target: EventTarget, route: Partial<RecordRoute>) {
  target.dispatchEvent(new CustomEvent<Partial<RecordRoute>>('record-route', { detail: route, bubbles: true, composed: true }));
}
