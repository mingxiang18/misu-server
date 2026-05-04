import Cookies from 'js-cookie'

const TokenKey = 'User-Token'
const RefreshTokenKey = 'User-Refresh-Token'

export function getToken() {
  return Cookies.get(TokenKey)
}

export function setToken(token) {
  return Cookies.set(TokenKey, token, { expires: 1 })
}

export function removeToken() {
  return Cookies.remove(TokenKey)
}

export function getRefreshToken() {
  return Cookies.get(RefreshTokenKey)
}

export function setRefreshToken(refreshToken) {
  return Cookies.set(RefreshTokenKey, refreshToken, { expires: 30 })
}

export function removeRefreshToken() {
  return Cookies.remove(RefreshTokenKey)
}

export function setLoginTokens(token, refreshToken) {
  setToken(token)
  setRefreshToken(refreshToken)
}

export function removeLoginTokens() {
  removeToken()
  removeRefreshToken()
}
