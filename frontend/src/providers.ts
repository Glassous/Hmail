/** 已适配的邮箱服务商图标（public 目录）：按邮箱域名匹配，未适配的服务商返回空串，调用方不占位。 */
const PROVIDER_ICONS: Record<string, string> = {
  'gmail.com': '/google-icon.svg',
  'googlemail.com': '/google-icon.svg',
  'outlook.com': '/Microsoft_Outlook_Icon.svg',
  'hotmail.com': '/Microsoft_Outlook_Icon.svg',
  'live.com': '/Microsoft_Outlook_Icon.svg',
  'msn.com': '/Microsoft_Outlook_Icon.svg',
  'qq.com': '/QQ邮箱.svg',
  'vip.qq.com': '/QQ邮箱.svg',
  'foxmail.com': '/QQ邮箱.svg',
  '163.com': '/网易邮箱.svg',
  'vip.163.com': '/网易邮箱.svg',
  '126.com': '/网易邮箱.svg',
  'vip.126.com': '/网易邮箱.svg',
  'yahoo.com': '/yahoo-icon.svg',
  'yahoo.co.jp': '/yahoo-icon.svg',
  'ymail.com': '/yahoo-icon.svg',
  'icloud.com': '/ICloud_logo.svg',
  'me.com': '/ICloud_logo.svg',
  'mac.com': '/ICloud_logo.svg',
}
const providerDomains = Object.keys(PROVIDER_ICONS)

export function providerIcon(email: string) {
  const domain = email.split('@')[1]?.trim().toLowerCase() || ''
  const matched = providerDomains.find(value => domain === value || domain.endsWith('.' + value))
  return matched ? PROVIDER_ICONS[matched] : ''
}
