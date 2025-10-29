import axios from 'axios';

// 创建axios实例
const apiClient = axios.create({
  baseURL: process.env.REACT_APP_API_URL || 'http://localhost:8091/api', // API基础URL - Java后端使用8091端口
  timeout: 10000, // 请求超时时间
  headers: {
    'Content-Type': 'application/json',
  },
});

// 请求拦截器 - 在请求发送前添加JWT token
apiClient.interceptors.request.use(
  (config) => {
    // 从localStorage获取JWT token
    const token = localStorage.getItem('jwt_token');
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// 响应拦截器 - 处理响应
apiClient.interceptors.response.use(
  (response) => {
    return response;
  },
  (error) => {
    // 处理认证错误
    if (error.response?.status === 401) {
      // 认证失败，清除本地token并重定向到登录页
      localStorage.removeItem('jwt_token');
      // 可以通过事件发布者模式通知应用全局状态变化
      window.dispatchEvent(new Event('authExpired'));
    }
    return Promise.reject(error);
  }
);

// 用户认证相关API
export const authAPI = {
  // 登录
  login: (emailOrUsername: string, password: string) => {
    // Java后端的登录API使用username字段
    return apiClient.post('/user/login', { 
      username: emailOrUsername, 
      password 
    });
  },
  
  // 注册
  register: (email: string, username: string, password: string, name?: string, tel?: string) => {
    // Java后端的注册API
    return apiClient.post('/user/add', {
      email,
      username,
      password,
      name: name || username,  // 如果没有提供name，使用username
      tel: tel || ''  // 电话号码可选
    });
  },
  
  // 检查用户名和邮箱是否已存在
  checkUser: (username?: string, email?: string) => {
    let params = new URLSearchParams();
    if (username) params.append('username', username);
    if (email) params.append('email', email);
    
    return apiClient.get(`/user/checkUser?${params.toString()}`);
  },
  
  // 发送邮箱验证码
  sendEmailCaptcha: (email: string, type: string = 'register') => {
    return apiClient.post('/user/sendEmailCaptcha', {
      email,
      type
    });
  },
  
  // 验证验证码
  checkCaptcha: (code: string, email: string) => {
    return apiClient.get(`/user/checkCaptcha?code=${code}&email=${email}`);
  },
  
  // 更新用户信息（旧方法）
  updateProfile: (userData: any) => {
    return apiClient.post('/user/update', userData);
  },
  
  // 更新用户个人资料（新方法，仅支持姓名和密码）
  updateUserProfile: (profileData: { name?: string; password?: string }) => {
    return apiClient.post('/user/updateProfile', profileData);
  },
  
  // 获取当前用户资料
  getProfile: () => {
    return apiClient.get('/user/profile');
  }
};

// 设备相关API
export const deviceAPI = {
  // 获取设备列表
  getDevices: () => {
    return apiClient.get('/device/query');
  },
  
  // 添加设备 (通过验证码绑定)
  bindDevice: (code: string) => {
    return apiClient.post('/device/add', null, {
      params: { code }
    });
  },
  
  // 更新设备
  updateDevice: (deviceId: string, deviceData: any) => {
    return apiClient.post('/device/update', {
      deviceId,
      ...deviceData
    });
  },
  
  // 删除设备
  unbindDevice: (deviceId: string) => {
    return apiClient.post('/device/delete', {
      deviceId
    });
  },

  // 修改设备名称
  renameDevice: (deviceId: string, deviceName: string) => {
    return apiClient.post('/device/rename', {
      deviceId,
      deviceName
    });
  }
};

// 角色相关API
export const roleAPI = {
  // 获取角色列表
  getRoles: () => {
    return apiClient.get('/role/query');
  },
  
  // 更新设备角色
  updateDeviceRole: (deviceId: string, roleId: number) => {
    return apiClient.post('/device/update', {
      deviceId,
      roleId
    });
  }
};

export default apiClient;