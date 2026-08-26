
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { loginApi } from '@/api/auth'

const MENU_CACHE_VERSION = 'guided-flow-20260727-v6'
if (localStorage.getItem('menuCacheVersion') !== MENU_CACHE_VERSION) {
  ['token', 'username', 'nickname', 'roleCode', 'bankCode', 'permissions', 'menus']
    .forEach(key => localStorage.removeItem(key))
  localStorage.setItem('menuCacheVersion', MENU_CACHE_VERSION)
}

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const username = ref(localStorage.getItem('username') || '')
  const nickname = ref(localStorage.getItem('nickname') || '')
  const roleCode = ref(localStorage.getItem('roleCode') || '')
  const bankCode = ref(localStorage.getItem('bankCode') || '')
  const permissions = ref<string[]>(readStoredArray('permissions'))
  const menus = ref<any[]>(readStoredArray('menus'))

  const login = async (user: string, pass: string) => {
    const res = await loginApi({ username: user, password: pass })
    token.value = res.data.token
    username.value = res.data.username
    nickname.value = res.data.nickname
    roleCode.value = res.data.roleCode
    bankCode.value = res.data.bankCode
    permissions.value = res.data.permissions
    menus.value = res.data.menus
    localStorage.setItem('token', res.data.token)
    localStorage.setItem('username', res.data.username)
    localStorage.setItem('nickname', res.data.nickname || res.data.username)
    localStorage.setItem('roleCode', res.data.roleCode)
    localStorage.setItem('bankCode', res.data.bankCode || '')
    localStorage.setItem('permissions', JSON.stringify(res.data.permissions || []))
    localStorage.setItem('menus', JSON.stringify(res.data.menus || []))
    localStorage.setItem('menuCacheVersion', MENU_CACHE_VERSION)
    return res
  }

  const logout = () => {
    token.value = ''
    username.value = ''
    nickname.value = ''
    roleCode.value = ''
    bankCode.value = ''
    permissions.value = []
    menus.value = []
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('nickname')
    localStorage.removeItem('roleCode')
    localStorage.removeItem('bankCode')
    localStorage.removeItem('permissions')
    localStorage.removeItem('menus')
  }

  return {
    token,
    username,
    nickname,
    roleCode,
    bankCode,
    permissions,
    menus,
    login,
    logout
  }
})

function readStoredArray(key: string): any[] {
  try {
    const value = JSON.parse(localStorage.getItem(key) || '[]')
    return Array.isArray(value) ? value : []
  } catch {
    localStorage.removeItem(key)
    return []
  }
}
