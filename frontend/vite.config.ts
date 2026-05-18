import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { resolve } from 'node:path';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: '/workflow/ui/',
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/workflow/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: {
    outDir: resolve(__dirname, '../src/main/resources/META-INF/resources/workflow/ui'),
    emptyOutDir: true,
    sourcemap: true,
  },
});
