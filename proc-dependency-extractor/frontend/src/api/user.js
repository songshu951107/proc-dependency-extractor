import request from './request'
export const pageUsers = (params) => request.get('/users', { params })
export const createUser = (data) => request.post('/users', data)
export const updateUser = (data) => request.put('/users', data)
export const deleteUser = (id) => request.delete(`/users/${id}`)
export const resetPassword = (id) => request.post(`/users/${id}/reset-password`)
export const importUsers = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/users/import', fd)
}