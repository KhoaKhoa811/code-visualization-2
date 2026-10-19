import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The backend binds to loopback only; the dev server proxies /api to it so the
// browser stays same-origin and the backend needs no CORS policy.
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: { '/api': 'http://127.0.0.1:8080' },
  },
});
