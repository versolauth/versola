import { describe, expect, it } from 'vitest';
import indexHtml from '../../index.html?raw';
import partnerHtml from '../../partner.html?raw';

/** The theme tokens and the pre-paint script are written once in index.html; partner.html carries a copy that must not drift. */
describe('partner.html', () => {
  const block = (html: string) => html.slice(html.indexOf('<style>'), html.indexOf('</script>\n</head>') + '</script>'.length);

  it('carries the same token block and pre-paint script as index.html', () => {
    expect(block(partnerHtml)).toBe(block(indexHtml));
  });

  it('boots the partner entry, not the operator one', () => {
    expect(partnerHtml).toContain('/src/partner/main.ts');
    expect(partnerHtml).not.toContain('/src/index.ts');
    expect(partnerHtml).toContain('<versola-partner-console>');
  });
});
