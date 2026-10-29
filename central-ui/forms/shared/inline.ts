import { solve } from './pow';

/** What `auth` answers a submit sent from script: the challenge to answer (nothing was sent), or where a plain submit
 * would have redirected. */
export type InlineStep = {
  next?: string;
  challenge?: { kind: string; token: string; params: Record<string, string> };
  /** `next` is the code step, where a code was just sent. */
  otp?: boolean;
};

const INLINE = 'proof_inline';
const TOKEN = 'proof_token';
const SOLUTION = 'proof_solution';

const post = (url: string, params: URLSearchParams) =>
  fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: params,
    credentials: 'same-origin',
  });

/** Challenges answered in a row before the page gives up. */
const ROUNDS = 3;

/**
 * Submits a step from script, so that a challenge can be answered without the person seeing anything but the submit
 * they made. If the server asks for a proof, nothing has been sent yet: the page solves it and makes the same request
 * again with the token and the solution added, and the answer to that says where to go.
 *
 * `onNext` may take the answer that says where to go and deal with it in place (the code page, when a code was sent),
 * returning true; otherwise the page navigates.
 *
 * Rejects when the request cannot be completed here (no way to make it, or a challenge of a kind this page cannot
 * answer, which a widget would be): the caller then submits the form the plain way, which the server refuses if a
 * challenge is due, so that a client that cannot answer is no better off than one that would not.
 */
export async function submitInline(
  action: string,
  params: URLSearchParams,
  onNext: (step: InlineStep) => boolean = () => false,
): Promise<void> {
  const base = new URLSearchParams(params);
  base.set(INLINE, '1');
  let response = await post(action, base);
  for (let round = 0; ; round++) {
    if (!(response.headers.get('content-type') ?? '').includes('application/json')) {
      // Not an answer in the inline shape (an error page, say): show it as the page.
      document.open();
      document.write(await response.text());
      document.close();
      return;
    }
    const step: InlineStep = await response.json();
    if (step.next) {
      if (!onNext(step)) location.assign(step.next);
      return;
    }
    const challenge = step.challenge;
    if (!challenge || challenge.kind !== 'pow' || round >= ROUNDS) throw new Error('challenge not answered');
    const nonce = await solve(challenge.params.seed, Number(challenge.params.difficulty));
    if (nonce === null) return;
    const answered = new URLSearchParams(base);
    answered.set(TOKEN, challenge.token);
    answered.set(SOLUTION, nonce);
    response = await post(action, answered);
  }
}

/** Whether a form may be submitted from script: not in the console's preview, and where `fetch` exists. */
export const canSubmitInline = (config: { previewId?: string; preview?: boolean }): boolean =>
  !config.previewId && !config.preview && typeof fetch === 'function';
