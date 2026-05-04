/**
 * Validation utilities for Versola Central UI
 */

/**
 * Validates resource/action format: lowercase letters, numbers, underscore, starting with letter
 * Examples: users, user_profile, api_v2
 */
export function validateResourceAction(value: string): boolean {
  return /^[a-z][a-z0-9_]*$/.test(value);
}

/**
 * Validates permission format: lowercase segments separated by dots or colons
 * Each segment must start with a letter and may include numbers/underscores
 * Examples: users.read, users:read, users:read:managed
 */
export function validatePermission(permission: string): boolean {
  return /^[a-z][a-z0-9_]*([.:][a-z][a-z0-9_]*)*$/.test(permission);
}

/**
 * Validates role ID format: same as resource/action
 * Lowercase letters, numbers, underscore, starting with letter
 * Examples: admin, support_user, api_manager
 */
export function validateRoleId(roleId: string): boolean {
  return validateResourceAction(roleId);
}

/**
 * Validates scope ID format: same as resource/action
 * Lowercase letters, numbers, underscore, starting with letter
 * Examples: openid, profile, user_data
 */
export function validateScopeId(scopeId: string): boolean {
  return validateResourceAction(scopeId);
}

/**
 * Validates client ID format: lowercase letters, numbers, hyphen, starting with letter
 * Examples: web-app, mobile-client, admin-dashboard-v2
 */
export function validateClientId(clientId: string): boolean {
  return /^[a-z][a-z0-9-]*$/.test(clientId);
}

/**
 * Validates tenant ID format: same as client ID
 * Lowercase letters, numbers, hyphen, starting with letter
 * Examples: acme-prod, internal-admin, team-alpha-2
 */
export function validateTenantId(tenantId: string): boolean {
  return validateClientId(tenantId);
}

/**
 * Validates edge ID format: must start with "edge-" followed by lowercase letters, numbers, hyphen
 * Examples: edge-us-east-1, edge-prod, edge-eu-west-2
 */
export function validateEdgeId(edgeId: string): boolean {
  return /^edge-[a-z0-9-]+$/.test(edgeId);
}

/**
 * Validates redirect URI according to OAuth 2.1 and RFC 6749
 * - Must be absolute URI
 * - Must not contain fragment (#)
 * - Must use https:// (or http://localhost for dev)
 * - Custom schemes allowed for native apps (com.example.app://)
 */
export function validateRedirectUri(uri: string): { valid: boolean; error?: string } {
  if (!uri || uri.trim() === '') {
    return { valid: false, error: 'URI cannot be empty' };
  }

  // Check for fragment
  if (uri.includes('#')) {
    return { valid: false, error: 'URI must not contain fragment (#)' };
  }

  // Check if it's a valid URI format
  try {
    const url = new URL(uri);
    
    // Allow https, http (only for localhost), and custom schemes
    if (url.protocol === 'https:') {
      return { valid: true };
    }
    
    if (url.protocol === 'http:') {
      // Only allow http for localhost/127.0.0.1
      if (url.hostname === 'localhost' || url.hostname === '127.0.0.1') {
        return { valid: true };
      }
      return { valid: false, error: 'HTTP only allowed for localhost' };
    }
    
    // Allow custom schemes (for native apps like com.example.app://)
    if (url.protocol && url.protocol.endsWith(':')) {
      return { valid: true };
    }
    
    return { valid: false, error: 'Invalid protocol (use https:// or custom scheme)' };
  } catch (e) {
    return { valid: false, error: 'Invalid URI format' };
  }
}

/**
 * Validates resource URI according to backend ResourceUri rules
 * - Must be absolute URI
 * - Must not contain a path
 * - Must not contain query parameters
 * - Must not contain fragment (#)
 */
export function validateResourceUri(uri: string): { valid: boolean; error?: string } {
  const trimmed = uri.trim();
  if (!trimmed) {
    return { valid: false, error: 'Resource URI cannot be empty' };
  }

  try {
    const url = new URL(trimmed);
    if (url.search) {
      return { valid: false, error: 'Resource URI query must be empty' };
    }
    if (url.hash) {
      return { valid: false, error: 'Resource URI fragment must be empty' };
    }
    if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/?#]+$/.test(trimmed)) {
      return { valid: false, error: 'Resource URI path must be empty' };
    }
    return { valid: true };
  } catch {
    return { valid: false, error: 'Resource URI must be absolute' };
  }
}

/**
 * Validates audience (same as client ID validation)
 * Lowercase letters, numbers, hyphen, starting with letter
 */
export function validateAudience(audience: string): boolean {
  return validateClientId(audience);
}

/**
 * Convert TTL value and unit to seconds
 */
export function ttlToSeconds(value: number, unit: 'minutes' | 'hours'): number {
  return unit === 'hours' ? value * 3600 : value * 60;
}

/**
 * Convert seconds to TTL value and unit (prefer hours if evenly divisible)
 */
export function secondsToTtl(seconds: number): { value: number; unit: 'minutes' | 'hours' } {
  if (seconds % 3600 === 0 && seconds >= 3600) {
    return { value: seconds / 3600, unit: 'hours' };
  }
  return { value: seconds / 60, unit: 'minutes' };
}

/**
 * Get error message for validation type
 */
export function getValidationError(type: string): string {
  const errors: Record<string, string> = {
    permission: 'Lowercase letters, numbers, underscore, dot or colon separators, start each segment with letter',
    role: 'Lowercase letters, numbers, underscore, start with letter',
    scope: 'Lowercase letters, numbers, underscore, start with letter',
    clientId: 'Lowercase letters, numbers, hyphen, start with letter',
    tenantId: 'Lowercase letters, numbers, hyphen, start with letter',
    audience: 'Lowercase letters, numbers, hyphen, start with letter',
  };
  return errors[type] || 'Invalid format';
}

