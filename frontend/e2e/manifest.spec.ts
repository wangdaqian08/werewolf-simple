import { test, expect } from '@playwright/test'

test('PWA manifest + Apple meta tags wire up', async ({ page, request, baseURL }) => {
  // Manifest fetch
  const manifestRes = await request.get(`${baseURL}/manifest.webmanifest`)
  expect(manifestRes.status()).toBe(200)
  expect(manifestRes.headers()['content-type']).toContain('json')
  const m = await manifestRes.json()
  expect(m.display).toBe('standalone')
  expect(m.start_url).toBe('/')
  expect(m.icons.length).toBeGreaterThanOrEqual(1)

  // Apple meta tags + manifest link in index.html
  await page.goto('/')
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', '/manifest.webmanifest')
  await expect(page.locator('meta[name="apple-mobile-web-app-capable"]')).toHaveAttribute(
    'content',
    'yes',
  )
  await expect(page.locator('meta[name="apple-mobile-web-app-status-bar-style"]')).toHaveAttribute(
    'content',
    'default',
  )
  await expect(page.locator('meta[name="apple-mobile-web-app-title"]')).toHaveAttribute(
    'content',
    '狼人杀',
  )
  await expect(page.locator('link[rel="apple-touch-icon"]')).toHaveAttribute(
    'href',
    '/apple-touch-icon.png',
  )
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#ede8df')
})
