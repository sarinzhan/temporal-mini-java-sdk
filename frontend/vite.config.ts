import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

// During dev: Vite serves on :5173 and proxies API calls to Spring on :8080.
// During build: outputs straight into the Spring jar's resource path so `mvn package`
// bundles the SPA at /temporal-mini/ui/. The frontend-maven-plugin (see pom.xml)
// triggers `npm ci && npm run build` automatically during generate-resources.
export default defineConfig({
  plugins: [react()],
  base: '/temporal-mini/ui/',
  server: {
    port: 5173,
    proxy: {
      '/temporal-mini/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: {
    outDir: resolve(__dirname, '../src/main/resources/META-INF/resources/temporal-mini/ui'),
    emptyOutDir: true,
    sourcemap: true,
  },
});
