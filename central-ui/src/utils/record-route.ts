/**
 * The part of the URL that names an open record: `?view=scopes&id=profile` opens its panel,
 * `&edit=<group>` its group editor, `&new=1` the create form, `&layout=page` the full-page
 * fallback of the panel. `admin-app` owns the URL; a section asks for a change by firing
 * `record-route` with the fields it sets (an absent field is left as it is, `null` clears it).
 */
export type RecordRoute = {
  id: string | null;
  edit: string | null;
  create: boolean;
  layout: 'page' | null;
};

export const EMPTY_RECORD_ROUTE: RecordRoute = { id: null, edit: null, create: false, layout: null };

export function dispatchRecordRoute(target: EventTarget, route: Partial<RecordRoute>) {
  target.dispatchEvent(new CustomEvent<Partial<RecordRoute>>('record-route', { detail: route, bubbles: true, composed: true }));
}
