import type { User, UserRefreshToken, UserSession } from '../types';

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

export function platformLabel(platform: UserSession['platform']): string {
  switch (platform) {
    case 'ios': return 'iOS';
    case 'android': return 'Android';
    case 'desktop': return 'Desktop';
    default: return 'Unknown platform';
  }
}

export type Device = {
  /** The session's public ID; for a signed-out device, the session ID its tokens carry. */
  key: string;
  session: UserSession | null;
  /** Refresh tokens issued under this device's session. */
  tokens: UserRefreshToken[];
};

/** Devices as auth stores them: one per user agent with a live session (at most one SSO
 *  session each). A refresh token outlives its session, so tokens whose session is gone are
 *  grouped by session ID into signed-out devices: that is how an operator sees why an app keeps
 *  working after everyone signed out. */
export function buildDevices(sessions: UserSession[], tokens: UserRefreshToken[]): Device[] {
  const live = new Set(sessions.map(session => session.publicId).filter((id): id is string => !!id));
  const devices: Device[] = sessions.map((session, index) => ({
    key: session.publicId ?? `session-${index}`,
    session,
    tokens: tokens.filter(token => session.publicId !== undefined && token.sessionId === session.publicId),
  }));
  const orphans = new Map<string, UserRefreshToken[]>();
  for (const token of tokens) {
    if (live.has(token.sessionId)) continue;
    orphans.set(token.sessionId, [...(orphans.get(token.sessionId) ?? []), token]);
  }
  for (const [sessionId, held] of orphans) devices.push({ key: sessionId, session: null, tokens: held });
  return devices;
}
