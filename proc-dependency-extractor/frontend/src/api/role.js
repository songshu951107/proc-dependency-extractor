import request from './request'
export const listRoles = () => request.get('/roles')
export const menuTree = () => request.get('/menus/tree')
export const roleMenus = (id) => request.get(`/roles/${id}/menus`)
export const assignRoleMenus = (id, menuIds) => request.put(`/roles/${id}/menus`, { menuIds })
export const createRole = (data) => request.post('/roles', data)
export const deleteRole = (id) => request.delete(`/roles/${id}`)