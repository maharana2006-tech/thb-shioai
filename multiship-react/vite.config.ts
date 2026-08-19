import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // Sprint 51 FE-M1 — fail the build if a production bundle would otherwise
  // ship with no VITE_API_BASE_URL. Runtime code used to fall back to
  // `${hostname}:8080`, which is a dev-only assumption (in prod the API
  // lives at a proxied /api on the same origin, not on port 8080).
  if (mode === 'production' && !process.env.VITE_API_BASE_URL) {
    throw new Error(
      'VITE_API_BASE_URL is required for production builds. ' +
        'Set it to the deployed API origin (e.g. https://app.example.com/api/v1) ' +
        'or leave empty to use a relative /api/v1 by exporting VITE_API_BASE_URL="/api/v1".',
    )
  }

  return {
  plugins: [react()],
  // Pre-bundle deps that the router-code-split path pulls in lazily.
  // Vite's dep-optimizer discovers deps on first import; when a lazy
  // route hits `zod` (via dashboardService) mid-session, Vite kicks
  // off a re-optimize and the current tab's already-buffered request
  // is aborted with `504 Outdated Optimize Dep` — the app then renders
  // as a white page because dashboardService.ts throws on module load.
  // Explicitly including it here forces the pre-bundle up front so no
  // lazy discovery is needed.
  optimizeDeps: {
    include: ['zod'],
  },
  // Local dev proxy — routes /api/* to the Spring Boot backend on :8080
  // so SPA + API share origin. Required for the httpOnly JWT cookie from
  // Sprint 50 Q1/Q2/Q3 to be sent by the browser (cross-origin cookies on
  // HTTP localhost need SameSite=None+Secure, which needs HTTPS).
  server: {
    proxy: {
      '/api': {
        target: process.env.VITE_DEV_BACKEND_URL || 'http://localhost:8081',
        changeOrigin: false,
      },
    },
  },
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
  }
})
