/**
 * What an OTP or temporary-password template must contain: the placeholders of its purpose, and
 * for the email channel a body that is well-formed HTML without anything executable. Central
 * validates the same on PUT; checking here keeps the error next to the field.
 */
export const CODE_PLACEHOLDER = '{{code}}';
export const PASSWORD_PLACEHOLDER = '{{password}}';
export const PASSWORD_EXPIRY_PLACEHOLDER = '{{expiresHours}}';

export type TemplatePurpose = 'otp' | 'password';
export type TemplateChannel = 'sms' | 'email';

export function templatePlaceholders(purpose: TemplatePurpose): string[] {
  return purpose === 'password' ? [PASSWORD_PLACEHOLDER, PASSWORD_EXPIRY_PLACEHOLDER] : [CODE_PLACEHOLDER];
}

export function missingPlaceholders(template: string, purpose: TemplatePurpose): string[] {
  return templatePlaceholders(purpose).filter(placeholder => !template.includes(placeholder));
}

const VOID_ELEMENTS = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
const BOOLEAN_ATTRIBUTES = new Set(['allowfullscreen', 'async', 'autofocus', 'autoplay', 'checked', 'controls', 'default', 'defer', 'disabled', 'formnovalidate', 'hidden', 'inert', 'ismap', 'itemscope', 'loop', 'multiple', 'muted', 'nomodule', 'novalidate', 'open', 'playsinline', 'readonly', 'required', 'reversed', 'selected']);

/** Empty when the body is acceptable; otherwise the first problem found. */
export function emailHtmlError(template: string): string {
  if (!template.trim().startsWith('<')) return 'HTML must start with an element';
  const document = new DOMParser().parseFromString(template, 'text/html');
  if (document.querySelector('parsererror')) return 'HTML could not be parsed';
  if (!document.body.querySelector('*')) return 'HTML must contain an element';
  if (document.querySelector('script')) return 'script elements are not allowed';
  if (document.querySelector('[onclick], [onload], [onerror], iframe, object, embed')) {
    return 'scripts and executable embedded content are not allowed';
  }
  return htmlStructureError(template);
}

/** Tag balance and attribute shape, checked by hand because the browser's parser repairs them silently. */
export function htmlStructureError(template: string): string {
  const tags = /<!--[\s\S]*?-->|<!doctype\s+[^>]*>|<\/?([A-Za-z][A-Za-z0-9:-]*)(?:\s[^<>]*?)?\/?>/gi;
  const openElements: string[] = [];
  let cursor = 0;
  let match: RegExpExecArray | null;

  while ((match = tags.exec(template)) !== null) {
    if (template.slice(cursor, match.index).includes('<')) return 'HTML contains an invalid tag';
    cursor = tags.lastIndex;
    const token = match[0];
    const name = match[1]?.toLowerCase();
    if (!name || token.startsWith('<!--') || token.toLowerCase().startsWith('<!doctype')) continue;

    if (token.startsWith('</')) {
      if (openElements[openElements.length - 1] !== name) return `Unexpected closing tag </${name}>`;
      openElements.pop();
    } else {
      const attributeError = htmlAttributeError(token, name);
      if (attributeError) return attributeError;
      if (!token.endsWith('/>') && !VOID_ELEMENTS.has(name)) openElements.push(name);
    }
  }

  if (template.slice(cursor).includes('<')) return 'HTML contains an invalid tag';
  if (openElements.length > 0) return `Unclosed tag <${openElements[openElements.length - 1]}>`;
  return '';
}

function htmlAttributeError(token: string, tagName: string): string {
  const body = token.slice(1, token.endsWith('/>') ? -2 : -1).trim();
  const firstWhitespace = body.search(/\s/);
  if (firstWhitespace < 0) return '';

  const attributes = body.slice(firstWhitespace).trim();
  let index = 0;
  while (index < attributes.length) {
    while (/\s/.test(attributes[index] ?? '')) index += 1;
    if (index >= attributes.length) break;

    const nameStart = index;
    while (index < attributes.length && !/[\s=]/.test(attributes[index])) index += 1;
    const attributeName = attributes.slice(nameStart, index);
    if (!attributeName) return `Malformed attribute on <${tagName}>`;
    while (/\s/.test(attributes[index] ?? '')) index += 1;

    if (attributes[index] !== '=') {
      if (BOOLEAN_ATTRIBUTES.has(attributeName.toLowerCase())) continue;
      return `Attribute "${attributeName}" on <${tagName}> must have a value`;
    }

    index += 1;
    while (/\s/.test(attributes[index] ?? '')) index += 1;
    if (index >= attributes.length || attributes[index] === '=') {
      return `Attribute "${attributeName}" on <${tagName}> must have a value`;
    }

    const quote = attributes[index];
    if (quote === '"' || quote === "'") {
      index += 1;
      const valueEnd = attributes.indexOf(quote, index);
      if (valueEnd < 0) return `Attribute "${attributeName}" on <${tagName}> has an unterminated value`;
      index = valueEnd + 1;
    } else {
      while (index < attributes.length && !/\s/.test(attributes[index])) index += 1;
    }
  }
  return '';
}

/** The error shown under one localization of a template, or empty. */
export function localizationError(template: string, purpose: TemplatePurpose, channel: TemplateChannel): string {
  const text = template.trim();
  if (!text) return 'Required for every active locale.';
  const missing = missingPlaceholders(text, purpose);
  if (missing.length) return `Must include ${missing.join(' and ')}.`;
  if (channel === 'email') {
    const htmlError = emailHtmlError(text);
    if (htmlError) return `Must contain valid HTML: ${htmlError}.`;
  }
  return '';
}
