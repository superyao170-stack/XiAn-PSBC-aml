
import axios from 'axios'

const instance = axios.create({
  baseURL: '',
  timeout: 30000
})

instance.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

instance.interceptors.response.use(
  (response) => {
    const payload = response.data
    if (payload?.code === 401) {
      localStorage.removeItem('token')
      window.location.href = '/login'
    }
    // Backend business errors are returned in the common response envelope while
    // the HTTP status may still be 200. Reject them so callers cannot display a
    // success message for an operation that was refused by the server.
    if (payload && typeof payload === 'object' && typeof payload.code === 'number' && payload.code !== 200) {
      const error = new Error(payload.message || `请求失败（${payload.code}）`) as Error & {
        code?: number
        data?: unknown
      }
      error.code = payload.code
      error.data = payload.data
      return Promise.reject(error)
    }
    return payload
  },
  (error) => {
    return Promise.reject(error)
  }
)

export default instance
