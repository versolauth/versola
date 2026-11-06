import { describe, expect, it } from 'vitest';
import { buildPreviewSrcdoc, escapeCssForStyle, escapeJsonForScript, escapeScriptForScript, PREVIEW_SANDBOX, type PreviewSrcdocParams } from './preview';

const params = (over: Partial<PreviewSrcdocParams> = {}): PreviewSrcdocParams => ({
  formId: 'otp',
  properties: [],
  getValue: () => '',
  localizations: { en: {} },
  locale: 'en',
  locales: ['en'],
  themeCss: '',
  style: '',
  jsCompiled: '',
  ...over,
});

/** The two script blocks the page itself has: the config and the form. Anything beyond is a text or a stylesheet
 *  that ended one of them and went on as markup. */
const scriptEnds = (doc: string) => doc.match(/<\/script>/g)?.length ?? 0;

describe('buildPreviewSrcdoc', () => {
  it('keeps a text that ends the script block inside the config', () => {
    const doc = buildPreviewSrcdoc(params({ localizations: { en: { title: '</script><script>alert(1)</script>' } } }));
    expect(scriptEnds(doc)).toBe(2);
    expect(doc).toContain('\\u003c/script\\u003e\\u003cscript\\u003ealert(1)\\u003c/script\\u003e');
  });

  it('keeps a stylesheet that ends the style block inside it', () => {
    const doc = buildPreviewSrcdoc(params({ style: 'a{}</style><script>alert(1)</script>', themeCss: 'b{}</style>' }));
    expect(scriptEnds(doc)).toBe(2);
    expect(doc.match(/<\/style>/g)).toHaveLength(1);
  });

  it('keeps a form script that ends its block inside it', () => {
    const doc = buildPreviewSrcdoc(params({ jsCompiled: 'const s = "</script><script>alert(1)</script>";' }));
    expect(scriptEnds(doc)).toBe(2);
    expect(doc).toContain('const s = "<\\/script><script>alert(1)<\\/script>";');
  });

  it('runs the preview with scripts and without the console origin', () => {
    expect(PREVIEW_SANDBOX).toBe('allow-scripts');
  });
});

describe('escapes', () => {
  it('turn the characters HTML reads into their JSON, CSS and JavaScript spellings', () => {
    expect(escapeJsonForScript('{"t":"<b>&</b>"}')).toBe('{"t":"\\u003cb\\u003e\\u0026\\u003c/b\\u003e"}');
    expect(escapeCssForStyle('a::before{content:"<"}')).toBe('a::before{content:"\\3c "}');
    expect(escapeScriptForScript('"</SCRIPT>" + "</div>"')).toBe('"<\\/SCRIPT>" + "</div>"');
  });
});
