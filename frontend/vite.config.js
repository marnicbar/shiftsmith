import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In Docker the backend is reachable as http://backend:8080; locally it's
// http://localhost:8080. Override with VITE_API_PROXY if needed.
const apiTarget = process.env.VITE_API_PROXY || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
    },
  },
});
