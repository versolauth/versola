import { setupConfigApiMocks, type MockConfigState } from './mock-backend';
import { installMockFetch } from '../utils/mock-fetch';

/**
 * Serves the console's API from the page itself (see `utils/mock-fetch.ts`): a request the mocked
 * backend recognises is answered in memory and anything else reaches the network untouched. The
 * state lives in this module's closure, so a reload or another visitor starts again from the seed,
 * and nothing is ever sent to a server.
 */
export async function installDemoBackend(seed: Partial<MockConfigState>): Promise<void> {
  await installMockFetch(page => setupConfigApiMocks(page, seed));
}
