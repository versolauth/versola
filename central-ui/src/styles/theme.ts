import { css } from 'lit';

export const theme = css`
  /* Versola Theme Variables */
  :host {
    /* Colors - from Versola website */
    --bg-dark: #0d1117;
    --bg-dark-card: #161b22;
    --bg-light: #f6f8fa;
    --bg-light-card: #ffffff;
    --accent: #58a6ff;
    --accent-gradient: linear-gradient(135deg, #58a6ff, #a371f7);
    --text-primary: #e6edf3;
    --text-secondary: #8b949e;
    --border-dark: #30363d;
    --border-light: #d0d7de;
    
    /* Status colors */
    --success: #3fb950;
    --warning: #d29922;
    --danger: #f85149;
    --info: #58a6ff;
    
    /* Fonts - from Versola website */
    --font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
    --font-mono: 'JetBrains Mono', 'Fira Code', monospace;
    
    /* Spacing */
    --spacing-xs: 0.25rem;
    --spacing-sm: 0.5rem;
    --spacing-md: 1rem;
    --spacing-lg: 1.5rem;
    --spacing-xl: 2rem;
    
    /* Border radius */
    --radius-sm: 4px;
    --radius-md: 8px;
    --radius-lg: 12px;
    
    /* Shadows */
    --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.12);
    --shadow-md: 0 4px 6px rgba(0, 0, 0, 0.15);
    --shadow-lg: 0 10px 20px rgba(0, 0, 0, 0.2);
    
    /* Transitions */
    --transition-fast: 0.15s ease;
    --transition-base: 0.25s ease;
    --transition-slow: 0.4s ease;
  }
`;

export const resetStyles = css`
  * {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
  }
`;

