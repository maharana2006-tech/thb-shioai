/**
 * Minimal Code 128 (subset B) encoder — the same linear symbology real
 * carrier labels use for tracking numbers. Returns alternating bar/space
 * module widths ready to render as SVG rects.
 */

// Standard Code 128 width patterns, index 0–106 (106 = stop, 7 elements).
const PATTERNS = [
  '212222', '222122', '222221', '121223', '121322', '131222', '122213', '122312', '132212', '221213',
  '221312', '231212', '112232', '122132', '122231', '113222', '123122', '123221', '223211', '221132',
  '221231', '213212', '223112', '312131', '311222', '321122', '321221', '312212', '322112', '322211',
  '212123', '212321', '232121', '111323', '131123', '131321', '112313', '132113', '132311', '211313',
  '231113', '231311', '112133', '112331', '132131', '113123', '113321', '133121', '313121', '211331',
  '231131', '213113', '213311', '213131', '311123', '311321', '331121', '312113', '312311', '332111',
  '314111', '221411', '431111', '111224', '111422', '121124', '121421', '141122', '141221', '112214',
  '112412', '122114', '122411', '142112', '142211', '241211', '221114', '413111', '241112', '134111',
  '111242', '121142', '121241', '114212', '124112', '124211', '411212', '421112', '421211', '212141',
  '214121', '412121', '111143', '111341', '131141', '114113', '114311', '411113', '411311', '113141',
  '114131', '311141', '411131', '211412', '211214', '211232', '2331112',
]

const START_B = 104
const STOP = 106

export interface BarcodeModule {
  /** x offset in modules */
  x: number
  /** width in modules */
  width: number
}

/**
 * Encode text as Code 128B. Returns the dark-bar modules and total width in
 * modules, or null when the text contains characters outside the B charset.
 */
export const encodeCode128B = (text: string): { bars: BarcodeModule[]; totalWidth: number } | null => {
  if (!text) {
    return null
  }

  const values: number[] = [START_B]

  for (const char of text) {
    const code = char.charCodeAt(0)

    if (code < 32 || code > 126) {
      return null
    }

    values.push(code - 32)
  }

  let checksum = START_B
  for (let i = 1; i < values.length; i += 1) {
    checksum += values[i] * i
  }
  values.push(checksum % 103, STOP)

  const bars: BarcodeModule[] = []
  let x = 0

  values.forEach((value) => {
    const pattern = PATTERNS[value]

    for (let i = 0; i < pattern.length; i += 1) {
      const width = Number(pattern[i])

      // Even indices are dark bars, odd are spaces.
      if (i % 2 === 0) {
        bars.push({ x, width })
      }

      x += width
    }
  })

  return { bars, totalWidth: x }
}
