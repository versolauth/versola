/**
 * The client's half of the proof-of-work challenge `auth` puts to a request it does not trust (see
 * `PowChallengeVerifier`): find a decimal `nonce` for which SHA-256 of `seed:nonce` starts with `difficulty` zero bits.
 *
 * Plain JavaScript on purpose: `crypto.subtle` is asynchronous and an order of magnitude slower per hash. The search
 * runs in Web Workers so that the page never stalls, and falls back to slices on the page's own thread where workers
 * are not available.
 */

/**
 * Everything the search needs, inside one function so that its source can be handed to a worker (`kernel.toString()`):
 * it must not refer to anything outside itself.
 */
function kernel() {
  const K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ]);
  const w = new Uint32Array(64);

  /** Compresses the block in `w[0..15]` from the initial state and leaves the first two words of the digest in `out`. */
  function compress(out: Uint32Array) {
    for (let i = 16; i < 64; i++) {
      const x = w[i - 15], y = w[i - 2];
      w[i] = (w[i - 16] + (((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3)) + w[i - 7] + (((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10))) | 0;
    }
    let a = 0x6a09e667, b = 0xbb67ae85, c = 0x3c6ef372, d = 0xa54ff53a, e = 0x510e527f, f = 0x9b05688c, g = 0x1f83d9ab, h = 0x5be0cd19;
    for (let i = 0; i < 64; i++) {
      const t1 = (h + (((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7))) + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
      const t2 = ((((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10))) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    out[0] = (0x6a09e667 + a) | 0;
    out[1] = (0xbb67ae85 + b) | 0;
  }

  /** The zero bits a digest starts with, from its first two words. */
  function zeroBits(h0: number, h1: number): number {
    return h0 !== 0 ? Math.clz32(h0) : 32 + Math.clz32(h1);
  }

  /**
   * Tries `count` nonces `from`, `from + step`, ...; returns the first that gives `difficulty` zero bits, or -1.
   * The message `seed:nonce` is built in place in one 64-byte block (it is always shorter than 56 bytes).
   */
  function search(seed: string, difficulty: number, from: number, count: number, step: number): number {
    const block = new Uint8Array(64);
    const prefix = seed + ':';
    for (let i = 0; i < prefix.length; i++) block[i] = prefix.charCodeAt(i);
    const out = new Uint32Array(2);
    const digits = new Uint8Array(20);
    let nonce = from;
    for (let n = 0; n < count; n++, nonce += step) {
      let length = 0;
      for (let v = nonce; ; v = Math.floor(v / 10)) {
        digits[length++] = 48 + (v % 10);
        if (v < 10) break;
      }
      const end = prefix.length + length;
      for (let i = 0; i < length; i++) block[prefix.length + i] = digits[length - 1 - i];
      block[end] = 0x80;
      for (let i = end + 1; i < 62; i++) block[i] = 0;
      const bits = end * 8;
      block[62] = bits >>> 8;
      block[63] = bits & 0xff;
      for (let i = 0; i < 16; i++) w[i] = (block[i * 4] << 24) | (block[i * 4 + 1] << 16) | (block[i * 4 + 2] << 8) | block[i * 4 + 3];
      compress(out);
      if (zeroBits(out[0], out[1]) >= difficulty) return nonce;
    }
    return -1;
  }

  return { search, zeroBits };
}

const local = kernel();

/** The same search the server's check is made of, for tests and for the page's own thread. */
export function isSolution(seed: string, nonce: string, difficulty: number): boolean {
  return local.search(seed, difficulty, Number(nonce), 1, 1) === Number(nonce);
}

/** Tries `count` nonces from `from` on this thread. */
export function search(seed: string, difficulty: number, from: number, count: number, step = 1): number {
  return local.search(seed, difficulty, from, count, step);
}

const CHUNK = 4096;

const workerSource = () =>
  `const k=(${kernel.toString()})();onmessage=(e)=>{const{seed,difficulty,start,step}=e.data;` +
  `for(let from=start;;from+=${CHUNK}*step){const n=k.search(seed,difficulty,from,${CHUNK},step);if(n>=0){postMessage(n);return}}};`;

/**
 * Finds the nonce in several Web Workers, each taking every n-th number, so the page is never held up and the wall
 * time falls with the cores. Resolves `null` when `cancelled` says so; rejects if workers cannot be started.
 */
function solveInWorkers(seed: string, difficulty: number, cancelled: () => boolean): Promise<string | null> {
  const url = URL.createObjectURL(new Blob([workerSource()], { type: 'text/javascript' }));
  const count = Math.max(1, Math.min(4, navigator.hardwareConcurrency || 2));
  const workers: Worker[] = [];
  return new Promise((resolve, reject) => {
    const finish = (value: string | null) => {
      workers.forEach((worker) => worker.terminate());
      URL.revokeObjectURL(url);
      clearInterval(watch);
      resolve(value);
    };
    const watch = setInterval(() => cancelled() && finish(null), 100);
    try {
      for (let i = 0; i < count; i++) {
        const worker = new Worker(url);
        worker.onmessage = (event) => finish(String(event.data));
        worker.onerror = () => {
          workers.forEach((other) => other.terminate());
          URL.revokeObjectURL(url);
          clearInterval(watch);
          reject(new Error('worker failed'));
        };
        workers.push(worker);
        worker.postMessage({ seed, difficulty, start: i, step: count });
      }
    } catch (error) {
      workers.forEach((worker) => worker.terminate());
      URL.revokeObjectURL(url);
      clearInterval(watch);
      reject(error);
    }
  });
}

/** On the page's own thread, in slices of about `sliceMs`. */
async function solveOnPage(seed: string, difficulty: number, cancelled: () => boolean, sliceMs: number): Promise<string | null> {
  let from = 0;
  for (;;) {
    const until = Date.now() + sliceMs;
    do {
      const found = local.search(seed, difficulty, from, 256, 1);
      if (found >= 0) return String(found);
      from += 256;
    } while (Date.now() < until);
    if (cancelled()) return null;
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

/** Finds a nonce, in workers where it can and on the page where it cannot. Resolves `null` if cancelled. */
export async function solve(
  seed: string,
  difficulty: number,
  cancelled: () => boolean = () => false,
  sliceMs = 25,
): Promise<string | null> {
  if (typeof Worker !== 'undefined' && typeof Blob !== 'undefined' && typeof URL.createObjectURL === 'function') {
    try {
      return await solveInWorkers(seed, difficulty, cancelled);
    } catch (_) {
      // Workers blocked or failing: the page's own thread still gets there.
    }
  }
  return solveOnPage(seed, difficulty, cancelled, sliceMs);
}
