const INTEGER_RANGES = {
  TINYINT: [-128n, 127n],
  INT: [-2147483648n, 2147483647n],
  BIGINT: [-9223372036854775808n, 9223372036854775807n]
}

function invalid(message) {
  throw new Error(`默认值${message}`)
}

/**
 * Convert the text entered in the DDL form to a JSON value accepted by the
 * backend. Decimal values deliberately stay strings so the browser never
 * rounds them through IEEE-754 before the server parses them.
 */
export function parseDefaultValue(type, rawValue) {
  const normalized = String(type || '').trim().toUpperCase()
  const value = String(rawValue ?? '').trim()
  if (!value) return undefined

  if (value === 'CURRENT_TIMESTAMP') {
    if (normalized !== 'DATETIME' && normalized !== 'TIMESTAMP') invalid('CURRENT_TIMESTAMP 仅支持 DATETIME 或 TIMESTAMP 类型')
    return value
  }

  const integerRange = INTEGER_RANGES[normalized]
  if (integerRange) {
    if (!/^[+-]?\d+$/.test(value)) invalid(`${normalized} 必须是整数`)
    let integer
    try {
      integer = BigInt(value)
    } catch {
      invalid(`${normalized} 超出范围`)
    }
    if (integer < integerRange[0] || integer > integerRange[1]) invalid(`${normalized} 超出范围`)
    return Number.isSafeInteger(Number(integer)) ? Number(integer) : integer.toString()
  }

  if (normalized.startsWith('DECIMAL')) {
    const decimalMatch = normalized.match(/^DECIMAL\((\d+),(\d+)\)$/)
    if (!decimalMatch || !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(value)) invalid('DECIMAL 必须是十进制数字')
    const [, precisionText, scaleText] = decimalMatch
    const precision = Number(precisionText)
    const scale = Number(scaleText)
    const digits = value.replace(/^[+-]/, '').replace('.', '')
    const fractionDigits = value.includes('.') ? value.split('.')[1].length : 0
    if (digits.length > precision || fractionDigits > scale) invalid('DECIMAL 超出精度或小数位范围')
    return value
  }

  if (normalized.startsWith('VARCHAR') || normalized === 'TEXT' || normalized === 'DATE' || normalized === 'DATETIME' || normalized === 'TIMESTAMP') {
    if (/[\u0000\r\n]/.test(value) || value.length > 512) invalid('文本长度或字符不受支持')
    return value
  }

  if (normalized === 'JSON') {
    if (/[\u0000\r\n]/.test(value) || value.length > 512) invalid('JSON 长度或字符不受支持')
    try {
      JSON.parse(value)
    } catch {
      invalid('JSON 格式无效')
    }
    return value
  }

  invalid('类型不受支持')
}
