import Cookies from 'js-cookie'

const TokenKey = 'User-Token'
const RefreshTokenKey = 'User-Refresh-Token'
const UserInfoKey = 'User-Info'
const CookieRootDomain = 'misu.chat'
const CookiePath = '/'

const getCookieDomain = () => {
  const hostname = typeof window === 'undefined' ? '' : window.location.hostname.toLowerCase()
  return hostname === CookieRootDomain || hostname.endsWith(`.${CookieRootDomain}`)
    ? `.${CookieRootDomain}`
    : undefined
}

export const getCookieOptions = (expires) => {
  const options = {
    expires,
    path: CookiePath,
    secure: typeof window === 'undefined' || window.location?.protocol === 'https:',
    sameSite: 'Lax'
  }
  const domain = getCookieDomain()
  if (domain) options.domain = domain
  return options
}

const removeSharedCookie = (key) => {
  const domain = getCookieDomain()
  if (!domain) return
  Cookies.remove(key, { domain, path: CookiePath })
  Cookies.remove(key, { domain })
}

const migrateCookie = (key, expires) => {
  if (!getCookieDomain()) return
  // Prefer the current host's session if old host and shared cookies coexist.
  const selectedValue = Cookies.get(key)
  removeSharedCookie(key)
  const hostOnlyValue = Cookies.get(key)
  const value = hostOnlyValue ?? selectedValue
  if (value === undefined) return
  Cookies.remove(key, { path: CookiePath })
  Cookies.remove(key)
  Cookies.set(key, value, getCookieOptions(expires))
}

// Move existing sessions to the shared suffix before images request api.misu.chat.
migrateCookie(TokenKey, 30)
migrateCookie(RefreshTokenKey, 30)
migrateCookie(UserInfoKey, 30)

export const removeCookie = (key) => {
  Cookies.remove(key, { path: CookiePath })
  Cookies.remove(key)
  removeSharedCookie(key)
}

export const setCookie = (key, value, expires) => {
  // Clear host-only duplicates before writing the shared cookie.
  Cookies.remove(key, { path: CookiePath })
  Cookies.remove(key)
  removeSharedCookie(key)
  return Cookies.set(key, value, getCookieOptions(expires))
}

export function getToken() {
  return Cookies.get(TokenKey)
}

export function setToken(token) {
  // Cookie retention does not extend the JWT's server-side expiry.
  return setCookie(TokenKey, token, 30)
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
