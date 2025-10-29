// 定义用户类型
export interface User {
  id: string;
  email: string;
  username: string;
  name?: string; // 昵称
  isGuideCompleted?: boolean; // 是否完成新手引导
  createdAt?: Date;
  updatedAt?: Date;
  avatar?: string; // 头像
  totalMessage?: number; // 对话次数
  aliveNumber?: number; // 参加人数
  totalDevice?: number; // 总设备数
  state?: string; // 用户状态
  isAdmin?: string; // 用户类型
  roleId?: number; // 角色ID
  tel?: string; // 手机号
  loginIp?: string; // 上次登录IP
  loginTime?: Date; // 上次登录时间
}

// 设备类型
export interface Device {
  id: string;
  deviceId: string;
  name: string;
  status: 'online' | 'offline' | 'connecting';
  userId: string;
  roleId?: number; // 角色ID
  roleName?: string; // 角色名称
  createdAt: Date;
}

// 表单数据类型
export interface RegisterForm {
  email: string;
  username: string;
  password: string;
  confirmPassword: string;
}

export interface LoginForm {
  emailOrUsername: string;
  password: string;
}

// API 响应类型
export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  message: string;
  error?: string;
}

// 登录响应类型
export interface LoginResponse {
  token: string;
  user: User;
}

// 注册响应类型
export interface RegisterResponse {
  user: User;
}