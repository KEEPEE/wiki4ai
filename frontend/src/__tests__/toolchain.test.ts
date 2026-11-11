/**
 * WIKI4AI-195 — strážny test na nástroj, ktorý KLAME.
 *
 * `frontend/tsconfig.json` má `files: []` a iba dve `references`. To znamená,
 * že `npx tsc --noEmit` bez `-p`/`-b` nekompiluje **žiadny súbor** a vráti 0
 * aj vtedy, keď má aplikácia chyby. Je to vácuová kontrola, nie úspešná.
 *
 * Zmierané naživo pri fixovaní WIKI4AI-194/195 — schválne som do
 * `src/main.tsx` vložil `const x: number = 'str'`:
 *   npx tsc --noEmit                          -> exit 0   (ticho!)
 *   npx tsc -p tsconfig.app.json --noEmit     -> exit 2   (TS2322 + TS6133)
 *
 * Kým sa to raz nepovie nahlas a testom, ďalší človec (alebo agent) napíše
 * do commit message „tsc clean" na základe kontroly, ktorá nekontrolovala
 * nič. Tento test drží, aby `package.json` mal skript, ktorý tú past obchádza,
 * a aby sa nezmenil root tsconfig na „tichý" bez toho, aby si toho niekto
 * všimol.
 */
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

const here = dirname(fileURLToPath(import.meta.url))
const frontendRoot = resolve(here, '../..')

const pkg = JSON.parse(readFileSync(join(frontendRoot, 'package.json'), 'utf8')) as {
  scripts: Record<string, string>
}
const rootTsconfig = JSON.parse(readFileSync(join(frontendRoot, 'tsconfig.json'), 'utf8')) as {
  files?: unknown[]
  references?: { path: string }[]
}

describe('typecheck skript — root `tsc --noEmit` je vácuová kontrola', () => {
  it('package.json má skript typecheck mieriaci na tsconfig.app.json', () => {
    expect(pkg.scripts.typecheck, 'chýba npm run typecheck').toBeDefined()
    expect(pkg.scripts.typecheck).toContain('-p tsconfig.app.json')
    expect(pkg.scripts.typecheck).toContain('--noEmit')
  })

  it('root tsconfig.json je zámerne reference-only — preto je bare tsc --noEmit vácuové', () => {
    expect(rootTsconfig.files, 'root tsconfig už nie je files: []').toEqual([])
    expect((rootTsconfig.references ?? []).length).toBeGreaterThan(0)
  })

  it('build beží cez tsc -b, ktorý references nasleduje — nie cez vácuové --noEmit', () => {
    expect(pkg.scripts.build).toMatch(/tsc -b/)
  })
})
