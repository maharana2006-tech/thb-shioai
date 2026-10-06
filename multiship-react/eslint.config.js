import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist', '**/._*']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // Downgraded 2026-10-06 — eslint-plugin-react-hooks v7.1 flags the universal
      // "fetch-with-spinner" pattern (`useEffect(() => load(), [load])` where
      // `load()` sync-calls `setLoading(true)` before awaiting) as a cascading-
      // render risk. React's own docs still recommend this pattern for data
      // fetching at mount, and refactoring 30+ pages to Suspense/TanStack-Query
      // is a separate initiative. Keeping as `warn` so new instances stay visible.
      'react-hooks/set-state-in-effect': 'warn',
    },
  },
])
