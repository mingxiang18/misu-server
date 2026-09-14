/**
 * Tracks the session currently owned by a terminal socket and deduplicates
 * best-effort DELETE calls from user actions and socket lifecycle events.
 */
export function createAiCliSessionLifecycle(revoke) {
  const revokePromises = new Map()
  let active = null

  function revokeOnce(sessionId) {
    if (!sessionId) return Promise.resolve()
    if (!revokePromises.has(sessionId)) {
      const promise = Promise.resolve()
          .then(() => revoke(sessionId))
          .catch(() => {})
      revokePromises.set(sessionId, promise)
    }
    return revokePromises.get(sessionId)
  }

  function bind(socket, sessionId, generation) {
    active = { socket, sessionId, generation }
  }

  function close(socket, sessionId, generation) {
    const current = active?.socket === socket
        && active.sessionId === sessionId
        && active.generation === generation
    if (current) active = null
    return { current, promise: revokeOnce(sessionId) }
  }

  return {
    bind,
    close,
    revokeOnce,
    isCurrent: (socket, sessionId, generation) => active?.socket === socket
        && active.sessionId === sessionId
        && active.generation === generation
  }
}

export function isCurrentAiCliSocket(socket, currentSocket, generation, currentGeneration) {
  return socket === currentSocket && generation === currentGeneration
}
