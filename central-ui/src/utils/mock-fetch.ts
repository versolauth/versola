import type { MockPage, MockRequest, MockRoute } from '../demo/mock-backend';

type Handler = (route: MockRoute) => Promise<void>;

/** `**` matches anything, including "/"; every other character is literal. Enough for the mocks' patterns. */
function globToRegExp(glob: string): RegExp {
  const escaped = glob.split('**').map(part => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*');
  return new RegExp(`^${escaped}$`);
}

/**
 * Serves a mocked backend from the page itself: `window.fetch` is wrapped so a request the mock
 * recognises is answered in memory and anything else reaches the network untouched. The state
 * lives in the mock's closure, so it is born with the page and dies with it. `setup` registers the
 * mock's routes on a `MockPage` the way a Playwright `Page` would.
 */
export async function installMockFetch(setup: (page: MockPage) => Promise<unknown>): Promise<void> {
  const routes: Array<{ pattern: RegExp; handler: Handler }> = [];
  const page: MockPage = {
    async route(glob, handler) {
      routes.push({ pattern: globToRegExp(glob), handler });
    },
  };
  await setup(page);

  const networkFetch = window.fetch.bind(window);

  window.fetch = async (input, init) => {
    const request = new Request(input, init);
    const url = request.url;
    const body = request.method === 'GET' || request.method === 'HEAD' ? null : await request.clone().text();
    const mockRequest: MockRequest = { url: () => url, method: () => request.method, postData: () => body || null };

    // Playwright runs the most recently registered matching route first; `fallback()` moves on to the next.
    const matching = routes.filter(route => route.pattern.test(url)).reverse();
    for (const route of matching) {
      let outcome: Response | undefined;
      let passed = false;
      await route.handler({
        request: () => mockRequest,
        fulfill: async ({ status = 200, contentType, body: payload = '' }) => {
          // A 204 or 205 may not carry a body, and `new Response('', { status: 204 })` is the one form that throws.
          const empty = status === 204 || status === 205;
          outcome = new Response(empty ? null : payload, {
            status,
            headers: contentType ? { 'Content-Type': contentType } : undefined,
          });
        },
        fallback: async () => {
          passed = true;
        },
      });
      if (outcome) return outcome;
      if (!passed) break;
    }

    return networkFetch(input, init);
  };
}
