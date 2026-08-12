import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    // Sprint 48 — first React test scaffold. Vitest picks up any file
    // matching src/**/*.{test,spec}.{ts,tsx}. jsdom env is required so
    // Testing Library can render into a browser-like document.
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
})
