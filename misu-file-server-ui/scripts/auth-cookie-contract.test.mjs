import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const appHost = 'server.misu.chat'

function createCookieDocument(initialCookies = []) {
  const jar = new Map(initialCookies.map((cookie, index) => [
    `${cookie.domain || 'host'}|${cookie.path || '/'}|${cookie.name}`,
    { ...cookie, order: index }
  ]))
  const writes = []

  const visibleCookies = () => [...jar.values()]
    .filter((cookie) => cookie.path === '/' && (cookie.domain === 'host' || appHost === cookie.domain || appHost.endsWith(cookie.domain)))
    .sort((left, right) => left.order - right.order)
    .map((cookie) => `${encodeURIComponent(cookie.name)}=${encodeURIComponent(cookie.value)}`)
    .join('; ')

  return {
    writes,
    jar,
    get cookie() {
      return visibleCookies()
    },
    set cookie(value) {
      writes.push(value)
      const [pair, ...attributes] = value.split('; ')
      const separator = pair.indexOf('=')
      const name = decodeURIComponent(pair.slice(0, separator))
      const cookieValue = decodeURIComponent(pair.slice(separator + 1))
      const parsedAttributes = Object.fromEntries(attributes.map((attribute) => {
        const equals = attribute.indexOf('=')
        const key = attribute.slice(0, equals < 0 ? attribute.length : equals).toLowerCase()
        return [key, equals < 0 ? true : attribute.slice(equals + 1)]
      }))
      const domain = parsedAttributes.domain || 'host'
      const cookiePath = parsedAttributes.path || '/'
      const key = `${domain}|${cookiePath}|${name}`
      if (parsedAttributes.expires && new Date(parsedAttributes.expires).getTime() <= Date.now()) {
        jar.delete(key)
        return
      }
      jar.set(key, { name, value: cookieValue, domain, path: cookiePath, order: jar.size })
    }
  }
}

async function loadTokenApi(document, suffix) {
  globalThis.window = { location: { protocol: 'https:', hostname: appHost } }
  globalThis.document = document
  return import(`../src/api/auth/token.js?${suffix}`)
}

const legacyDocument = createCookieDocument([
  { name: 'User-Token', value: 'legacy-access', domain: '.misu.chat', path: '/' },
  { name: 'User-Refresh-Token', value: 'legacy-refresh', domain: '.misu.chat', path: '/' },
  { name: 'User-Info', value: '{"id":7}', domain: '.misu.chat', path: '/' }
])
const legacyApi = await loadTokenApi(legacyDocument, 'legacy-only')
assert.equal(legacyApi.getToken(), 'legacy-access', 'legacy access token must survive migration')
assert.equal(legacyApi.getRefreshToken(), 'legacy-refresh', 'legacy refresh token must survive migration')
assert.equal(legacyDocument.jar.get('host|/|User-Token')?.value, 'legacy-access')
assert.equal(legacyDocument.jar.get('host|/|User-Refresh-Token')?.value, 'legacy-refresh')
assert.equal(legacyDocument.jar.get('.misu.chat|/|User-Token'), undefined)
assert.equal(legacyDocument.jar.get('.misu.chat|/|User-Refresh-Token'), undefined)
assert.equal(legacyDocument.jar.get('.misu.chat|/|User-Info'), undefined)

const duplicateDocument = createCookieDocument([
  { name: 'User-Token', value: 'legacy-access', domain: '.misu.chat', path: '/' },
  { name: 'User-Token', value: 'host-access', domain: 'host', path: '/' }
])
const duplicateApi = await loadTokenApi(duplicateDocument, 'duplicate')
assert.equal(duplicateApi.getToken(), 'host-access', 'an existing host-only token must win after legacy cleanup')
assert.equal(duplicateDocument.jar.get('host|/|User-Token')?.value, 'host-access')
assert.equal(duplicateDocument.jar.get('.misu.chat|/|User-Token'), undefined)

const options = legacyApi.getCookieOptions(1)
assert.equal(options.path, '/')
assert.equal(options.sameSite, 'Lax')
assert.equal(options.secure, true)
assert.equal('domain' in options, false, 'main-site cookies must be host-only')
const explorerSource = fs.readFileSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src/components/fileServer/FileExplorer.vue'), 'utf8')
assert.equal(explorerSource.includes('document.cookie'), false, 'feature code must use the shared auth-token accessor')

globalThis.document = legacyDocument
legacyApi.setToken('new-access')
legacyApi.setRefreshToken('new-refresh')
legacyApi.setCookie('User-Info', '{}', 30)
for (const key of ['User-Token', 'User-Refresh-Token', 'User-Info']) {
  assert.equal(legacyDocument.jar.get(`host|/|${key}`)?.domain, 'host')
  assert.ok(legacyDocument.writes.some((value) => value.startsWith(`${key}=;`) && value.toLowerCase().includes('domain=.misu.chat') && value.toLowerCase().includes('path=/')))
}

legacyApi.removeCookie('User-Token')
assert.equal(legacyDocument.jar.get('host|/|User-Token'), undefined)
assert.ok(legacyDocument.writes.some((value) => value.startsWith('User-Token=;') && value.toLowerCase().includes('domain=.misu.chat') && value.toLowerCase().includes('path=/')))

console.log('authentication cookie behavior checks passed')
