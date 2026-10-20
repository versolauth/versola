import type { User, UserDevice, UserRefreshToken, UserSession } from '../types';

/** A person's name from the claims central keeps: `name`, else given/family (family first when
 *  there is no middle name, the order the console has always shown), else empty. */
export function userDisplayName(user: Pick<User, 'claims'>): string {
  const claims = user.claims ?? {};
  const text = (key: string) => {
    const value = claims[key];
    return typeof value === 'string' ? value.trim() : '';
  };
  const name = text('name');
  if (name) return name;
  const given = text('given_name');
  const family = text('family_name');
  const middle = text('middle_name');
  const parts = middle ? [given, middle, family] : [family, given];
  return parts.filter(Boolean).join(' ');
}

/** What names the user where a person is wanted: the name, else an identifier, else the ID. */
export function userLabel(user: Pick<User, 'id' | 'email' | 'phone' | 'login' | 'claims'>): string {
  return userDisplayName(user) || user.login || user.email || user.phone || user.id;
}

/** `Chrome 130 on macOS 14.6`: the user agent as auth stored it. */
export function deviceName(session: Pick<UserSession, 'browser' | 'version' | 'os'>): string {
  const browser = [session.browser, session.version].filter(Boolean).join(' ');
  const os = session.os?.trim();
  if (browser && os) return `${browser} on ${os}`;
  return browser || os || 'Unknown device';
}

/** What names a device: its user agent as auth stored it (`Chrome 130 on macOS 14.6`), the
 *  session's own copy when the user agent row is gone, and for a device no user agent names only the
 *  tokens that outlived its session, a plain `Signed-out device`. */
export function deviceLabel(device: Pick<Device, 'userAgent' | 'session'>): string {
  const source = device.userAgent ?? device.session;
  return source ? deviceName(source) : 'Signed-out device';
}

export function platformLabel(platform: UserSession['platform']): string {
  switch (platform) {
    case 'ios': return 'iOS';
    case 'android': return 'Android';
    case 'desktop': return 'Desktop';
    default: return 'Unknown platform';
  }
}

export type Device = {
  /** The user agent's ID; the session's public ID for a live session whose user agent is gone; for
   *  a device nothing names, the session ID its tokens carry. */
  key: string;
  userAgent: UserDevice['userAgent'] | null;
  session: UserSession | null;
  /** Refresh tokens issued under this device's session, or one of its ended sessions. */
  tokens: UserRefreshToken[];
};

/** Devices as auth stores them: one per user agent, with the session under it while signed in (at
 *  most one SSO session each) and the refresh tokens issued on it, matched by session ID. A refresh
 *  token outlives its session; one whose session no remembered device names is grouped by session ID
 *  into an unnamed signed-out device, which is how an operator sees why an app keeps working after
 *  everyone signed out. */
export function buildDevices(devices: UserDevice[], tokens: UserRefreshToken[]): Device[] {
  const claimed = new Set<string>();
  const built: Device[] = devices.map((device, index) => {
    const sessionIds = [device.session?.publicId, ...device.endedSessionIds].filter((id): id is string => !!id);
    sessionIds.forEach(id => claimed.add(id));
    return {
      key: device.userAgent?.id ?? device.session?.publicId ?? `device-${index}`,
      userAgent: device.userAgent ?? null,
      session: device.session,
      tokens: tokens.filter(token => sessionIds.includes(token.sessionId)),
    };
  });
  const orphans = new Map<string, UserRefreshToken[]>();
  for (const token of tokens) {
    if (claimed.has(token.sessionId)) continue;
    orphans.set(token.sessionId, [...(orphans.get(token.sessionId) ?? []), token]);
  }
  for (const [sessionId, held] of orphans) built.push({ key: sessionId, userAgent: null, session: null, tokens: held });
  return built;
}
