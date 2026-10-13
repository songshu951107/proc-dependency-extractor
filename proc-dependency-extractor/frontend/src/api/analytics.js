import request from './request'
export const overview = () => request.get('/analytics/overview')
export const loginTrend = () => request.get('/analytics/login-trend')
export const pageTop = () => request.get('/analytics/page-top')
export const userActivity = () => request.get('/analytics/user-activity')