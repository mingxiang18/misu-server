import Cookies from 'js-cookie'

const TokenKey = 'User-Token'
const RefreshTokenKey = 'User-Refresh-Token'

const getCookieDomain = () => {
  if (import.meta.env.VITE_COOKIE_DOMAIN) {
    return import.meta.env.VITE_COOKIE_DOMAIN
  }
  const hostname = window.location.hostname
  if (hostname === 'misu.chat' || hostname.endsWith('.misu.chat')) {
    return '.misu.chat'
  }
  return undefined
}

const getCookieOptions = (expires) => {
  const options = {
    expires,
    sameSite: 'Lax'
  }
  const domain = getCookieDomain()
  if (domain) {
    options.domain = domain
    options.secure = true
  }
  return options
}

const removeCookie = (key) => {
  Cookies.remove(key)
  const domain = getCookieDomain()
  if (domain) {
    Cookies.remove(key, { domain })
  }
}

export function getToken() {
  return Cookies.get(TokenKey)
}

export function setToken(token) {
  return Cookies.set(TokenKey, token, getCookieOptions(1))
}

export function removeToken() {
  return removeCookie(TokenKey)
}

export function getRefreshToken() {
  return Cookies.get(RefreshTokenKey)
}

export function setRefreshToken(refreshToken) {
  return Cookies.set(RefreshTokenKey, refreshToken, getCookieOptions(30))
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
