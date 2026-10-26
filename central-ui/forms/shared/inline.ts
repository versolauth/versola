import { solve } from '../proof/pow';

/** What `auth` answers a submit that carries `Versola-Inline: 1`: where a plain one would have redirected, and, when
 * the request must prove itself first, the challenge to answer meanwhile. */
type InlineStep = { next: string; challenge?: { kind: string; token: string; params: Record<string, string> } };

const post = (url: string, params: URLSearchParams) =>
  fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Versola-Inline': '1' },
    body: params,
    credentials: 'same-origin',
  });

/** Challenges answered in a row before the page is left to the `proof` form. */
const ROUNDS = 3;

/**
 * Submits a step from script, so that a challenge can be answered without the person seeing anything but the submit
 * they made: the answer to the POST says where to go next (`next`) and, if the request has to prove itself, what to
 * solve. A kind it cannot solve (a visible widget, say) is left to the page `next` names, which draws it.
 *
 * Rejects only if the first request could not be made, when the caller submits the form the plain way.
 */
export async function submitInline(action: string, params: URLSearchParams, csrf: string, locale: string): Promise<void> {
  let response = await post(action, params);
  for (let round = 0; ; round++) {
    if (!(response.headers.get('content-type') ?? '').includes('application/json')) {
      // Not an answer in the inline shape (an error page, say): show it as the page.
      document.open();
      document.write(await response.text());
      document.close();
      return;
    }
    const step: InlineStep = await response.json();
    const challenge = step.challenge;
    if (!challenge || challenge.kind !== 'pow' || round >= ROUNDS) {
      location.assign(step.next);
      return;
    }
    const nonce = await solve(challenge.params.seed, Number(challenge.params.difficulty));
    if (nonce === null) return;
    try {
      response = await post(`/challenge/proof?ui_locale=${locale}`, new URLSearchParams({ token: challenge.token, solution: nonce, csrf }));
    } catch (_) {
      location.assign(step.next);
      return;
    }
  }
}

/** Whether a form may be submitted from script: not in the console's preview, and where `fetch` exists. */
export const canSubmitInline = (config: { previewId?: string; preview?: boolean }): boolean =>
  !config.previewId && !config.preview && typeof fetch === 'function';
