export class ApiError extends Error { constructor(public code: string, message: string, public status: number) { super(message) } }
let csrf = ''
export function setCsrf(value: string) { csrf = value }
export async function api<T = any>(path: string, method = 'GET', data?: unknown, signal?: AbortSignal): Promise<T> {
  const form = data instanceof FormData
  const response = await fetch('/api/v1' + path, { signal, method, credentials: 'same-origin', headers: { ...(data && !form ? {'Content-Type': 'application/json'} : {}), ...(method !== 'GET' ? {'X-CSRF-Token': csrf} : {}) }, body: data === undefined ? undefined : form ? data : JSON.stringify(data) })
  const result = await response.json().catch(() => ({}))
  if (!response.ok) throw new ApiError(result.code || 'network', result.message || `服务请求失败 (${response.status})`, response.status)
  return result as T
}
