const isDev = import.meta.env.DEV

function noop() {}

export const logger = {
  debug: isDev ? console.debug.bind(console, '[debug]') : noop,
  info: isDev ? console.info.bind(console, '[info]') : noop,
  log: isDev ? console.log.bind(console) : noop,
  warn: console.warn.bind(console, '[warn]'),
  error: console.error.bind(console, '[error]'),
}

export default logger
