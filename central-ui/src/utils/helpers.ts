import { AuthFlow, PaginatedResponse, Permission, Resource, SortConfig } from '../types';

/**
 * Default authentication flow: phone primary credential + required OTP.
 * Used until other challenges are implemented.
 */
export function createDefaultAuthFlow(): AuthFlow {
  return {
    primaryCredentials: ['phone'],
    inlinePassword: false,
    passkey: false,
    factors: [{ type: 'otp', required: true }],
    passkeyFactors: [],
  };
}

export type ResolvedPermissionEndpoint = {
  method: string;
  path: string;
};

export type ResolvedPermissionEndpointGroup = {
  key: string;
  title: string;
  endpoints: ResolvedPermissionEndpoint[];
};

/**
 * Paginate an array of items
 */
export function paginate<T>(items: T[], offset: number, limit: number): PaginatedResponse<T> {
  const paginatedItems = items.slice(offset, offset + limit);
  return {
    items: paginatedItems,
    total: items.length,
    offset,
    limit,
  };
}

/**
 * Sort array by field
 */
export function sortBy<T>(items: T[], config: SortConfig): T[] {
  const { field, direction } = config;
  return [...items].sort((a, b) => {
    const aVal = (a as any)[field];
    const bVal = (b as any)[field];
    
    if (aVal === bVal) return 0;
    
    const comparison = aVal < bVal ? -1 : 1;
    return direction === 'asc' ? comparison : -comparison;
  });
}

/**
 * Format date to readable string
 */
export function formatDate(dateString: string): string {
  const date = new Date(dateString);
  return date.toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

/**
 * Format duration in seconds to human-readable
 */
export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`;
  return `${Math.floor(seconds / 86400)}d`;
}

/**
 * Debounce function
 */
export function debounce<T extends (...args: any[]) => any>(
  func: T,
  wait: number
): (...args: Parameters<T>) => void {
  let timeout: ReturnType<typeof setTimeout> | null = null;
  return (...args: Parameters<T>) => {
    if (timeout) clearTimeout(timeout);
    timeout = setTimeout(() => func(...args), wait);
  };
}

/**
 * Copy text to clipboard
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch (err) {
    console.error('Failed to copy:', err);
    return false;
  }
}

/**
 * Get localized description
 */
export function getLocalizedDescription(
  descriptions: Record<string, string>,
  locale: string = 'en'
): string {
  return descriptions[locale] || descriptions['en'] || Object.values(descriptions)[0] || '';
}

export function formatResourceLabel(resourceUri: string): string {
  return resourceUri.replace(/^[a-z]+:\/\//i, '');
}

/**
 * Compute the next selection for an AnyOf (multi-select) control. Values listed
 * in `exclusive` cannot be combined with any other value. Returns null when the
 * toggle would be a no-op (deselecting the last remaining value).
 */
export function toggleAnyOf(selected: string[], value: string, exclusive: string[] = []): string[] | null {
  if (selected.includes(value)) {
    if (selected.length === 1) return null;
    return selected.filter(x => x !== value);
  }
  if (exclusive.includes(value)) return [value];
  return [...selected.filter(x => !exclusive.includes(x)), value];
}

/**
 * Values that act as a standalone path within an AnyOf property and cannot be
 * combined with the other allowed values (e.g. credential `login` is its own
 * path, separate from email/phone).
 */
export function exclusiveAnyOfValues(propName: string): string[] {
  return propName === 'primaryCredentials' ? ['login'] : [];
}

/**
 * Humanize a camelCase identifier into spaced words (e.g. "primaryCredentials" -> "Primary Credentials")
 */
export function humanizeLabel(name: string): string {
  return name
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .replace(/^./, c => c.toUpperCase());
}

export function resolvePermissionEndpointLabels(permission: Permission, resources: Resource[]): string[] {
  const endpointIds = permission.endpointIds ?? [];

  return endpointIds.map(endpointId => {
    for (const resource of resources) {
      const endpoint = resource.endpoints.find(candidate => candidate.id === endpointId);
      if (endpoint) {
        return `${endpoint.method} ${endpoint.path}`;
      }
    }

    return `Unknown endpoint (#${endpointId})`;
  });
}

export function resolvePermissionEndpointGroups(permission: Permission, resources: Resource[]): ResolvedPermissionEndpointGroup[] {
  const endpointIds = permission.endpointIds ?? [];
  const groups = new Map<string, ResolvedPermissionEndpointGroup>();

  for (const endpointId of endpointIds) {
    let matched = false;

    for (const resource of resources) {
      const endpoint = resource.endpoints.find(candidate => candidate.id === endpointId);
      if (!endpoint) continue;

      const key = resource.resource;
      const existing = groups.get(key) ?? {
        key,
        title: formatResourceLabel(resource.resource),
        endpoints: [],
      };

      existing.endpoints.push({ method: endpoint.method, path: endpoint.path });
      groups.set(key, existing);
      matched = true;
      break;
    }

    if (!matched) {
      const key = `unknown:${endpointId}`;
      const existing = groups.get(key) ?? {
        key,
        title: 'Unknown resource',
        endpoints: [],
      };

      existing.endpoints.push({ method: 'UNKNOWN', path: `Detached endpoint (#${endpointId})` });
      groups.set(key, existing);
    }
  }

  return [...groups.values()].sort((a, b) => a.title.localeCompare(b.title));
}

