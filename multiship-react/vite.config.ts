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
  server: {
    // FE-M1 dropped the runtime `${hostname}:8080` fallback so prod can't
    // ship a dev assumption — correct, but it left BASE_URL as a relative
    // `/api/v1` with nothing serving it in dev, so every call 404'd against
    // the Vite server. This proxy is the dev half of that change: it
    // forwards /api to the local backend, matching how prod fronts the API
    // on the same origin. Bonus: requests become same-origin, so the auth
    // and XSRF cookies stop being a cross-origin concern locally.
    // Override the target with VITE_DEV_API_TARGET when the backend runs
    // on another host or port.
    proxy: {
      '/api': {
        target: process.env.VITE_DEV_API_TARGET || 'http://localhost:8080',
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
