export type UAParsed = {
    platform: 'ios' | 'android' | 'desktop' | 'unknown';
    browser?: string;
    version?: string;
};

export function parseUserAgent(ua?: string): UAParsed {
    if (!ua) return { platform: 'unknown' };

    let platform: UAParsed['platform'] = 'unknown';
    if (/iPhone|iPad|iPod/i.test(ua)) platform = 'ios';
    else if (/Android/i.test(ua)) platform = 'android';
    else if (/Windows|Macintosh|Linux|X11/i.test(ua)) platform = 'desktop';

    let browser: string | undefined;
    let version: string | undefined;
    let m: RegExpMatchArray | null;

    if ((m = ua.match(/Edg\/(\d+)/))) { browser = 'Edge'; version = m[1]; }
    else if ((m = ua.match(/Firefox\/(\d+)/))) { browser = 'Firefox'; version = m[1]; }
    else if ((m = ua.match(/Chrome\/(\d+)/))) { browser = 'Chrome'; version = m[1]; }
    else if ((m = ua.match(/Version\/(\d+).*Safari/i))) { browser = 'Safari'; version = m[1]; }
    else if (/Safari/i.test(ua)) { browser = 'Safari'; }

    return { platform, browser, version };
}