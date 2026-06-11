import { Environment } from '@marcbachmann/cel-js';

export interface CelValidationError {
  message: string;
  position: number;
}

export type CelValidationResult =
  | { valid: true }
  | { valid: false; error: CelValidationError };

const env = new Environment({ homogeneousAggregateLiterals: false })
  .registerVariable('token', 'dyn')
  .registerVariable('user', 'dyn')
  .registerVariable('request', 'dyn');

export function validateCel(source: string): CelValidationResult {
  if (source.trim().length === 0) return { valid: true };

  try {
    const result = env.check(source);
    if (result.valid) return { valid: true };
    return { valid: false, error: toError(result.error) };
  } catch (cause) {
    return { valid: false, error: toError(cause) };
  }
}

function toError(cause: unknown): CelValidationError {
  if (cause instanceof Error) {
    const range = (cause as { range?: { start: number } }).range;
    const summary = (cause as { summary?: string }).summary;
    return {
      message: summary ?? cause.message.split('\n')[0],
      position: range?.start ?? 0,
    };
  }
  return { message: String(cause), position: 0 };
}
