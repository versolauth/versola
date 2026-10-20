import { describe, expect, it } from 'vitest';
import { breakHost, breakPath } from './break-text';

type Template = { strings?: readonly string[]; values: unknown[] };

/** Every leaf of a template, nested templates and arrays flattened: text, and a template with no values for a `<wbr>`. */
function leaves(result: Template): unknown[] {
  return result.values.flatMap(value => {
    if (Array.isArray(value)) return leaves({ values: value });
    if (typeof value === 'object' && value !== null && 'values' in value) {
      const nested = value as Template;
      return nested.values.length === 0 ? [nested] : leaves(nested);
    }
    return [value];
  });
}

/** The plain text the template carries. */
const text = (result: Template) => leaves(result).filter((part): part is string => typeof part === 'string').join('');

/** How many break opportunities (`<wbr>`) the template holds. */
const breaks = (result: Template) => leaves(result).filter(part => typeof part === 'object').length;

describe('breakPath', () => {
  it('keeps the path as written and offers a break after each slash', () => {
    const result = breakPath('/orders/{orderId}/refund');
    expect(text(result)).toBe('/orders/{orderId}/refund');
    expect(breaks(result)).toBe(3);
  });

  it('offers no break inside a segment', () => {
    expect(breaks(breakPath('/payment-intents'))).toBe(1);
  });
});

describe('breakHost', () => {
  it('breaks a host only after its dots and keeps the scheme attached', () => {
    const result = breakHost('https://orders.internal.example.com');
    expect(text(result)).toBe('https://orders.internal.example.com');
    expect(breaks(breakHost('orders.internal.example.com'))).toBe(3);
    expect(breaks(result)).toBe(3);
  });

  it('leaves a host with no dot whole', () => {
    expect(breaks(breakHost('localhost:8080'))).toBe(0);
  });
});
