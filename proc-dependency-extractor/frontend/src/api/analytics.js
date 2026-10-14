import request from './request'

export const overview = (days) => request.get('/analytics/overview', { params: { days } })
export const trend = (days) => request.get('/analytics/trend', { params: { days } })
export const hourDistribution = (days) => request.get('/analytics/hour-distribution', { params: { days } })
export const pageTop = (days) => request.get('/analytics/page-top', { params: { days } })
export const pageTrend = (days, top = 3) => request.get('/analytics/page-trend', { params: { days, top } })
export const userActivity = (days, keyword) => request.get('/analytics/user-activity', { params: { days, keyword } })
export const drill = (params) => request.get('/analytics/drill', { params })
