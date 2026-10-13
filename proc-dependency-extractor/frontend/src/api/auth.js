import request from './request'

export const login = (data) => request.post('/auth/login', data)
export const getCurrentUser = () => request.get('/auth/me')
export const changePassword = (data) => request.post('/auth/change-password', data)
export const logout = () => request.post('/auth/logout')