export class ApiError extends Error { constructor(public code: string, message: string, public status: number) { super(message) } }
let csrf = ''
export function setCsrf(value: string) { csrf = value }
// A hung backend (slow IMAP server, waiting on a lock) must never leave the UI spinning forever:
// after this budget the request fails loudly instead of being mistaken for a user cancellation.
const REQUEST_TIMEOUT = 90000
export async function api<T = any>(path: string, method = 'GET', data?: unknown, signal?: AbortSignal): Promise<T> {
  const form = data instanceof FormData
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => { timedOut = true; controller.abort() }, REQUEST_TIMEOUT)
  const relay = () => controller.abort()
  signal?.addEventListener('abort', relay, { once: true })
  try {
    const response = await fetch('/api/v1' + path, { signal: controller.signal, method, credentials: 'same-origin', headers: { ...(data && !form ? {'Content-Type': 'application/json'} : {}), ...(method !== 'GET' ? {'X-CSRF-Token': csrf} : {}) }, body: data === undefined ? undefined : form ? data : JSON.stringify(data) })
    const result = await response.json().catch(() => ({}))
    if (!response.ok) throw new ApiError(result.code || 'network', result.message || `服务请求失败 (${response.status})`, response.status)
    return result as T
  } catch (e) {
    if (timedOut) throw new ApiError('timeout', '服务器响应超时，请稍后重试', 0)
    throw e
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', relay)
  }
}
