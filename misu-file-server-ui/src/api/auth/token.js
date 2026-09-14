import Cookies from 'js-cookie'

const TokenKey = 'User-Token'
const RefreshTokenKey = 'User-Refresh-Token'
const UserInfoKey = 'User-Info'
const LegacyCookieDomain = '.misu.chat'
const CookiePath = '/'

export const getCookieOptions = (expires) => {
  // Keep the main-site credentials host-only. A Domain=.misu.chat cookie is
  // readable and sendable by every sibling subdomain, including services that
  // are not part of the main application.
  return {
    expires,
    path: CookiePath,
    secure: typeof window === 'undefined' || window.location?.protocol === 'https:',
    sameSite: 'Lax'
  }
}

const removeLegacyCookie = (key) => {
  // The explicit path handles cookies created by the former implementation;
  // the no-path attempt preserves cleanup for cookies created with the browser
  // default path.
  Cookies.remove(key, { domain: LegacyCookieDomain, path: CookiePath })
  Cookies.remove(key, { domain: LegacyCookieDomain })
}

const migrateCookie = (key, expires) => {
  // document.cookie does not reveal a cookie's Domain attribute. Capture the
  // browser-selected value first, remove the legacy scope, then prefer any
  // host-only value that remains. This preserves an existing host cookie when
  // duplicate host/domain names are present; if the legacy cookie was the only
  // value, its session survives in the new host-only scope.
  const selectedValue = Cookies.get(key)
  removeLegacyCookie(key)
  const hostOnlyValue = Cookies.get(key)
  if (hostOnlyValue !== undefined || selectedValue === undefined) return
  Cookies.set(key, selectedValue, getCookieOptions(expires))
}

// Migrate already-open sessions as soon as the auth module is loaded, before
// the router or request interceptor reads a potentially attacker-controlled
// Domain=.misu.chat cookie.
migrateCookie(TokenKey, 1)
migrateCookie(RefreshTokenKey, 30)
migrateCookie(UserInfoKey, 30)

export const removeCookie = (key) => {
  Cookies.remove(key, { path: CookiePath })
  Cookies.remove(key)
  removeLegacyCookie(key)
}

export const setCookie = (key, value, expires) => {
  // Remove a legacy domain cookie before creating the host-only replacement;
  // otherwise both cookies can be sent to server.misu.chat with ambiguous
  // same-name ordering during the migration window.
  removeLegacyCookie(key)
  return Cookies.set(key, value, getCookieOptions(expires))
}

export function getToken() {
  return Cookies.get(TokenKey)
}

export function setToken(token) {
  return setCookie(TokenKey, token, 1)
}

export function removeToken() {
  return removeCookie(TokenKey)
}

export function getRefreshToken() {
  return Cookies.get(RefreshTokenKey)
}

export function setRefreshToken(refreshToken) {
  return setCookie(RefreshTokenKey, refreshToken, 30)
}

export function removeRefreshToken() {
  return removeCookie(RefreshTokenKey)
}

export function setLoginTokens(token, refreshToken) {
  setToken(token)
  setRefreshToken(refreshToken)
}

export function removeLoginTokens() {
  removeToken()
  removeRefreshToken()
}
