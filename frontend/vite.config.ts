import { defineConfig } from 'vite'
import type { Plugin } from 'vite'
import react from '@vitejs/plugin-react'

import { buildThemeBootstrapTag } from './src/theme/themeBootstrap'

/**
 * WIKI4AI-192 (DIZ-1): FOUC guard pre témy sa generuje z `src/theme/`.
 *
 * `index.html` má len placeholder — povolené identity, legacy mapping
 * (`dark` → `glass-dark`, `light` → `glass-light`) aj predvolená téma
 * majú jediný zdroj pravdy. Ručne prepísaný inline skript mal vlastný
 * zoznam hodnôt a pri premenovaní tém by ticho spadol na default.
 */
function themeBootstrapPlugin(): Plugin {
  const PLACEHOLDER = '<!--theme-bootstrap-->'

  return {
    name: 'wiki4ai-theme-bootstrap',
    transformIndexHtml(html) {
      const tag = buildThemeBootstrapTag()
      if (html.includes(PLACEHOLDER)) return html.replace(PLACEHOLDER, tag)
      /* záchrana, ak niekto placeholder odstráni — skript musí byť v <head>
         pred prvým paintom, inak FOUC guard nemá zmysel */
      return html.replace(/<head>/i, `<head>\n    ${tag}`)
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), themeBootstrapPlugin()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
