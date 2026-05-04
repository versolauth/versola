import { PaginatedResponse, Permission, Resource, SortConfig } from '../types';

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

