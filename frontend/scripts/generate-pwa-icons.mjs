/**
 * Generates PWA icons from icon-source.svg using sharp.
 *
 * Run once, commit the output PNGs alongside this script and the SVG source.
 * sharp is a devDependency so it's available locally and in CI; it is NOT
 * bundled into the frontend build.
 *
 * Usage: node scripts/generate-pwa-icons.mjs
 */

import { createRequire } from 'module'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'
import { readFileSync } from 'fs'

const require = createRequire(import.meta.url)
const sharp = require('sharp')

const __dirname = dirname(fileURLToPath(import.meta.url))
const publicDir = join(__dirname, '..', 'public')
const svgPath = join(__dirname, 'icon-source.svg')

const svgBuffer = readFileSync(svgPath)

// Parchment background color as RGB for compositing onto opaque canvas.
const BG = { r: 237, g: 232, b: 223, alpha: 1 }

async function makePng(size, outputPath, paddingFraction = 0) {
  // Render the SVG at the target size, then flatten onto an opaque background
  // so iOS never sees alpha (alpha renders as black on Home Screen icons).
  const padding = Math.round(size * paddingFraction)
  const innerSize = size - padding * 2

  const rendered = await sharp(svgBuffer, { density: Math.ceil((innerSize / 512) * 72 * 4) })
    .resize(innerSize, innerSize, { fit: 'contain', background: BG })
    .png()
    .toBuffer()

  await sharp({
    create: {
      width: size,
      height: size,
      channels: 4,
      background: BG,
    },
  })
    .composite([{ input: rendered, gravity: 'center' }])
    .png()
    .toFile(outputPath)

  console.log(`Generated ${outputPath}`)
}

await makePng(192, join(publicDir, 'icon-192.png'))
await makePng(512, join(publicDir, 'icon-512.png'))
// Maskable: ~20% safe-area padding so the glyph clears the circular clip mask.
await makePng(512, join(publicDir, 'icon-512-maskable.png'), 0.1)
// apple-touch-icon must be 180x180 with solid background (iOS renders alpha as black).
await makePng(180, join(publicDir, 'apple-touch-icon.png'))

console.log('All icons generated successfully.')
