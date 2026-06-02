/// <reference types="vitest/config" />
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
  // Vitest: jsdom for component tests, jest-dom matchers via the setup file.
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
    include: ['src/**/*.{test,spec}.{js,jsx}'],
    css: false,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{js,jsx}'],
      exclude: ['src/main.jsx', 'src/**/*.{test,spec}.{js,jsx}', 'src/test/**'],
    },
  },
});
