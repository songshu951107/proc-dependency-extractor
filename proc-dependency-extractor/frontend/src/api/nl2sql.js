import request from './request'

export function generate(data) {
  return request({ url: '/nl2sql/generate', method: 'post', data })
}

export function runEval() {
  return request({ url: '/nl2sql/eval', method: 'post', timeout: 600000 })
}