import assert from 'node:assert/strict'
import { installQbittorrentParentBridge, releaseQbittorrentParentBridge } from './qbittorrentParentBridge.mjs'

const parentWindow = { innerWidth: 900, innerHeight: 600 }
const release = installQbittorrentParentBridge(parentWindow)
assert.equal(typeof parentWindow.qBittorrent.ClientData.get, 'function')
assert.equal(parentWindow.qBittorrent.ClientData.get('color_scheme'), undefined)
assert.deepEqual(parentWindow.getCoordinates(), { left: 0, top: 0, width: 900, height: 600 })
release()
assert.equal('qBittorrent' in parentWindow, false)
assert.equal('getCoordinates' in parentWindow, false)

const existingClientData = { get: () => 'dark' }
const existingCoordinates = () => ({ width: 1, height: 1 })
const existingWindow = {
  innerWidth: 1,
  innerHeight: 1,
  qBittorrent: { ClientData: existingClientData, keep: true },
  getCoordinates: existingCoordinates
}
const noOpRelease = installQbittorrentParentBridge(existingWindow)
assert.equal(existingWindow.qBittorrent.ClientData, existingClientData)
assert.equal(existingWindow.getCoordinates, existingCoordinates)
noOpRelease()
releaseQbittorrentParentBridge()

console.log('qBittorrent parent bridge contract checks passed')
