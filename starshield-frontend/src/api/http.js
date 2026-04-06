import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 20000,
  headers: {
    'Content-Type': 'application/json;charset=utf-8'
  }
})

http.interceptors.response.use(
  (response) => response.data,
  (error) => Promise.reject(error)
)

export default http
