/**
 * The partner console's entry (#549 decision 5): its own bundle, with no operator screen and no
 * admin API client in it. In the plain dev server (`npm run dev`, `/partner.html`) there is no
 * partner edge to talk to, so the mocked partner API is installed in the page from the seed;
 * Playwright routes the API itself and sets `PLAYWRIGHT`, so nothing is installed there.
 */
if (import.meta.env.DEV && !import.meta.env.PLAYWRIGHT) {
  const [{ installMockFetch }, { setupPartnerApiMocks }, { partnerSeed }] = await Promise.all([
    import('../utils/mock-fetch'),
    import('./mock-backend'),
    import('./seed'),
  ]);
  await installMockFetch(page => setupPartnerApiMocks(page, {}, partnerSeed()));
}
await import('./partner-app');
