import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  base: './',
  plugins: [
    react(),
    {
      name: 'att-csp-nonce-placeholder',
      transformIndexHtml: {
        order: 'post',
        handler(html) {
          return html.replace(/<script(?=[^>]*\bsrc=)[^>]*>/, tag =>
            tag.replace(/>$/, ' nonce="__ATT_CSP_NONCE__">'));
        },
      },
    },
  ],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets',
    manifest: true,
    sourcemap: false,
  },
});
