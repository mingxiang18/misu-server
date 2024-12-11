import axios from 'axios'
import { getToken, removeToken } from '@/api/auth/token'
import { ElMessage, ElMessageBox } from 'element-plus'
import errorCode from '@/api/errorCode'

// 是否显示重新登录
export let isRelogin = { show: false };

axios.defaults.headers['Content-Type'] = 'application/json;charset=utf-8'
// 创建axios实例
const instance = axios.create({
  // axios中请求配置有baseURL选项，表示请求URL公共部分
  baseURL: import.meta.env.VITE_BASE_API,
  // 超时
  timeout: 30000
})

// 请求拦截器
instance.interceptors.request.use(
    config => {
      // 是否需要设置 token
      const isToken = (config.headers || {}).isToken === false
      if (getToken() && !isToken) {
        config.headers['Authorization'] = 'Bearer ' + getToken() // 让每个请求携带自定义token 请根据实际情况自行修改
      }

    // 如果是 FormData 请求，不设置 Content-Type
    if (config.data instanceof FormData) {
        config.headers['Content-Type'] = 'multipart/form-data';  // 这里可以不设置，因为 FormData 会自动设置 Content-Type
    }

      // get请求映射params参数
      if (config.method === 'get' && config.params) {
        let url = config.url + '?' + tansParams(config.params);
        url = url.slice(0, -1);
        config.params = {};
        config.url = url;
      }

      return config;
    },
    error => {
      console.log(error)
      return Promise.reject(error);
    }
);

// 响应拦截器
instance.interceptors.response.use(
    response => {
        // 未设置状态码则默认成功状态
        const code = response.data.code || 200;
        // 获取错误信息
        const msg = errorCode[code] || response.data.msg || errorCode['default']
        // 二进制数据则直接返回
        if (response.request.responseType ===  'blob' || response.request.responseType ===  'arraybuffer') {
            return response.data
        }

        if (code === 401 || code === 403) {
            if (!isRelogin.show) {
                isRelogin.show = true;
                ElMessageBox.confirm('登录状态已过期，您可以继续留在该页面，或者重新登录', '系统提示', { confirmButtonText: '重新登录', cancelButtonText: '取消', type: 'warning' }).then(() => {
                    isRelogin.show = false;
                    removeToken();
                    this.$router.push('/login');
                }).catch(() => {
                    isRelogin.show = false;
                });
            }
            return Promise.reject('无效的会话，或者会话已过期，请重新登录。')
        } else if (code === 500) {
            ElMessage({ message: msg, type: 'error' })
            return Promise.reject(new Error(msg))
        } else if (code === 601) {
            ElMessage({ message: msg, type: 'warning' })
            return Promise.reject('error')
        } else if (code !== 200) {
            ElMessage({ message: msg, type: 'error' })
            return Promise.reject('error')
        } else {
            return response.data
        }
    },
    error => {
        console.log('err' + error)
        let { message } = error;
        if (message === "Network Error") {
            message = "后端接口连接异常";
        } else if (message.includes("403")) {
            message = "登录状态已过期，您可以继续留在该页面，或者重新登录";
            removeToken();
        } else if (message.includes("timeout")) {
            message = "系统接口请求超时";
        } else if (message.includes("Request failed with status code")) {
            message = "系统接口" + message.substr(message.length - 3) + "异常";
        }
        ElMessage({ message: message, type: 'error', duration: 5 * 1000 })
        return Promise.reject(error)
    }
);

/**
 * 参数处理
 * @param {*} params  参数
 */
export function tansParams(params) {
  let result = ''
  for (const propName of Object.keys(params)) {
    const value = params[propName];
    var part = encodeURIComponent(propName) + "=";
    if (value !== null && value !== "" && typeof (value) !== "undefined") {
      if (typeof value === 'object') {
        for (const key of Object.keys(value)) {
          if (value[key] !== null && value[key] !== "" && typeof (value[key]) !== 'undefined') {
            let params = propName + '[' + key + ']';
            var subPart = encodeURIComponent(params) + "=";
            result += subPart + encodeURIComponent(value[key]) + "&";
          }
        }
      } else {
        result += part + encodeURIComponent(value) + "&";
      }
    }
  }
  return result
}

export default instance;