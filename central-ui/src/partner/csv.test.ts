import { describe, expect, it } from 'vitest';
import { csvCell } from './csv';

describe('csvCell', () => {
  it('quotes and doubles quotes', () => {
    expect(csvCell('say "hi", ok')).toBe('"say ""hi"", ok"');
  });

  it.each(['=1+1', '+1', '-1', '@SUM(A1)', '\t=1', '\r=1', '＝1', '＋1', '－1', '＠SUM(A1)'])('neutralises a leading formula character in %j', value => {
    expect(csvCell(value)).toBe(`"'${value}"`);
  });

  it('leaves ordinary text, ids and ISO dates alone', () => {
    expect(csvCell('u_2b91d4e0')).toBe('"u_2b91d4e0"');
    expect(csvCell('2026-11-07T10:00:00Z')).toBe('"2026-11-07T10:00:00Z"');
    expect(csvCell('a=b')).toBe('"a=b"');
    expect(csvCell('')).toBe('""');
  });
});
