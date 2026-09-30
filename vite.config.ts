import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Server-only configuration. `run.py --port` sets this so the browser can always use
// relative /api and /ws URLs rather than trying to reach localhost itself.
const backendTarget = process.env.AURUM_BACKEND_PROXY || 'http://127.0.0.1:8000';
const backendWebSocketTarget = backendTarget.replace(/^http/, 'ws');

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    proxy: {
      '/api': backendTarget,
      '/ws': { target: backendWebSocketTarget, ws: true },
    },
  },
});
