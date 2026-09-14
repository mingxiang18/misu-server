const hasOwn = (value, key) => Object.prototype.hasOwnProperty.call(value, key)

let activeRelease = null

/**
 * qBittorrent's legacy WebUI assumes that an embedded window is opened by
 * another qBittorrent window. The real parent is OpsConsole, so provide only
 * the two read-only browser contracts used by the shipped WebUI when they are
 * absent. The bridge is scoped to the lifetime of the qBittorrent iframe.
 */
export function installQbittorrentParentBridge(parentWindow = window) {
  if (activeRelease) activeRelease()

  const currentQbittorrent = parentWindow.qBittorrent
  const needsClientData = typeof currentQbittorrent?.ClientData?.get !== 'function'
  const needsCoordinates = typeof parentWindow.getCoordinates !== 'function'

  if (!needsClientData && !needsCoordinates) return () => {}

  const hadQbittorrent = hasOwn(parentWindow, 'qBittorrent')
  const previousQbittorrent = currentQbittorrent
  const hadCoordinates = hasOwn(parentWindow, 'getCoordinates')
  const previousCoordinates = parentWindow.getCoordinates

  if (needsClientData) {
    const bridgedQbittorrent = currentQbittorrent && typeof currentQbittorrent === 'object'
      ? { ...currentQbittorrent }
      : {}
    bridgedQbittorrent.ClientData = { get: () => undefined }
    parentWindow.qBittorrent = bridgedQbittorrent
  }

  if (needsCoordinates) {
    parentWindow.getCoordinates = () => ({
      left: 0,
      top: 0,
      width: parentWindow.innerWidth || 0,
      height: parentWindow.innerHeight || 0
    })
  }

  let released = false
  const release = () => {
    if (released) return
    released = true
    if (needsClientData) {
      if (hadQbittorrent) parentWindow.qBittorrent = previousQbittorrent
      else delete parentWindow.qBittorrent
    }
    if (needsCoordinates) {
      if (hadCoordinates) parentWindow.getCoordinates = previousCoordinates
      else delete parentWindow.getCoordinates
    }
    if (activeRelease === release) activeRelease = null
  }
  activeRelease = release
  return release
}

export function releaseQbittorrentParentBridge() {
  activeRelease?.()
}
