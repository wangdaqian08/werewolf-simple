import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import BrowserUnsupportedView from '@/views/BrowserUnsupportedView.vue'

const WECHAT_ANDROID =
    'Mozilla/5.0 (Linux; Android 13; V2227A Build/TP1A.220624.014; wv) AppleWebKit/537.36 ' +
    '(KHTML, like Gecko) Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36 XWEB/1160065 ' +
    'MicroMessenger/8.0.47.2560(0x28002F30) WeChat/arm64 Weixin NetType/WIFI Language/zh_CN'
const FIREFOX = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0'

const ORIGINAL_UA = navigator.userAgent

function stubUa(ua: string) {
  Object.defineProperty(navigator, 'userAgent', { value: ua, configurable: true })
}

afterEach(() => stubUa(ORIGINAL_UA))

describe('BrowserUnsupportedView', () => {
  it('tells WeChat users how to open the page in their browser', () => {
    stubUa(WECHAT_ANDROID)
    const hint = mount(BrowserUnsupportedView).find('[data-testid="wechat-hint"]')
    expect(hint.exists()).toBe(true)
    expect(hint.text()).toContain('在浏览器打开')
    expect(hint.text()).toContain('Open in Browser')
  })

  it('shows no WeChat hint in other browsers', () => {
    stubUa(FIREFOX)
    expect(mount(BrowserUnsupportedView).find('[data-testid="wechat-hint"]').exists()).toBe(false)
  })
})
