import { describe, it, expect } from 'vitest';
import { parseUserAgent } from './ua-parser';

describe('parseUserAgent', () => {
    it('returns unknown for undefined input', () => {
        expect(parseUserAgent(undefined)).toEqual({ platform: 'unknown' });
    });

    it('detects iOS', () => {
        const result = parseUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15');
        expect(result.platform).toBe('ios');
    });

    it('detects Android', () => {
        const result = parseUserAgent('Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/112.0');
        expect(result.platform).toBe('android');
    });

    it('detects desktop', () => {
        const result = parseUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0');
        expect(result.platform).toBe('desktop');
    });

    it('detects Chrome browser and version', () => {
        const result = parseUserAgent('Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36');
        expect(result.browser).toBe('Chrome');
        expect(result.version).toBe('125');
    });

    it('detects Firefox', () => {
        const result = parseUserAgent('Mozilla/5.0 (Windows NT 10.0; rv:109.0) Gecko/20100101 Firefox/109.0');
        expect(result.browser).toBe('Firefox');
        expect(result.version).toBe('109');
    });

    it('detects Edge over Chrome', () => {
        const result = parseUserAgent('Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/124.0 Safari/537.36 Edg/124.0');
        expect(result.browser).toBe('Edge');
    });

    it('detects Safari on iOS', () => {
        const result = parseUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1');
        expect(result.platform).toBe('ios');
        expect(result.browser).toBe('Safari');
        expect(result.version).toBe('17');
    });

    it('falls back to unknown for empty UA', () => {
        expect(parseUserAgent('')).toEqual({ platform: 'unknown' });
    });
});