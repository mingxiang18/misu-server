import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const source = readFileSync(resolve(root, 'src/views/ops/DatabaseManagement.vue'), 'utf8')

assert.match(source, /function handlePageChange\(page\)[\s\S]*?loadRows\(\)/)
assert.match(source, /@current-change="handlePageChange"/)
assert.doesNotMatch(source, /@current-change="loadRows"/)
assert.match(source, /watch\(statusQuery, /)
assert.doesNotMatch(source, /watch\(\[rowQuery, statusQuery\]/)
assert.match(source, /status 精确筛选/)

console.log('database UI contract checks passed')
