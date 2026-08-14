import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    // Sprint 51 T6d (audit finding #15) — pull the biggest vendor libs
    // into their own chunks so:
    //   (a) route-code cache hits don't invalidate vendor cache across
    //       app deploys (vendor hashes only change when the dep does)
    //   (b) the initial /login chunk stays lean — settings-only libs
    //       (@dnd-kit, @tanstack) don't load until the operator opens
    //       Settings.
    // React itself stays in the app chunk so the initial paint isn't
    // gated on an extra network round-trip.
    rollupOptions: {
      output: {
        // Vite 8 / Rolldown requires the function form (object form is not
        // supported). Return a chunk name for each node_modules id we want
        // to hoist; anything else falls into the default chunk.
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('@dnd-kit')) return 'vendor-dnd'
          if (id.includes('@tanstack')) return 'vendor-table'
          if (id.includes('formik') || id.includes('yup')) return 'vendor-forms'
          if (id.includes('@reduxjs/toolkit') || id.includes('react-redux')) return 'vendor-redux'
          if (id.includes('react-router')) return 'vendor-router'
          if (id.includes('react-icons')) return 'vendor-icons'
        },
      },
    },
  },
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
