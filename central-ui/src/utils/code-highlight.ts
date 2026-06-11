import Prism from 'prismjs';
import 'prismjs/components/prism-css';
import 'prismjs/components/prism-markup';
import 'prismjs/components/prism-javascript';
import 'prismjs/components/prism-typescript';
import 'prismjs/components/prism-jsx';
import 'prismjs/components/prism-tsx';

export type EditorLanguage = 'html' | 'css' | 'typescript';

export interface CodeToken {
  kind: string;
  value: string;
}

function flattenTokens(tokens: (string | Prism.Token)[], parentKind?: string): CodeToken[] {
  const result: CodeToken[] = [];
  for (const token of tokens) {
    if (typeof token === 'string') {
      result.push({ kind: parentKind ?? 'text', value: token });
    } else {
      const content = token.content;
      if (typeof content === 'string') {
        result.push({ kind: token.type, value: content });
      } else if (Array.isArray(content)) {
        result.push(...flattenTokens(content, token.type));
      }
    }
  }
  return result;
}

export function tokenize(source: string, language: EditorLanguage): CodeToken[] {
  const grammar = language === 'typescript'
    ? Prism.languages.tsx
    : language === 'css'
      ? Prism.languages.css
      : Prism.languages.markup;
  return flattenTokens(Prism.tokenize(source, grammar));
}

// Keep old exports for any remaining callers
export const tokenizeHtml = (source: string) => tokenize(source, 'html');
export const tokenizeCss = (source: string) => tokenize(source, 'css');
