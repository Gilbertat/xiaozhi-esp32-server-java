import React, { createContext, useContext, useReducer, useEffect } from 'react';
import { User } from '../types';

interface AuthState {
  user: User | null;
  token: string | null;
  isAuthenticated: boolean;
  loading: boolean;
}

interface AuthAction {
  type: string;
  payload?: any;
}

const initialState: AuthState = {
  user: null,
  token: null,
  isAuthenticated: false,
  loading: true,
};

// Auth Context
const AuthContext = createContext<{
  state: AuthState;
  dispatch: React.Dispatch<AuthAction>;
  login: (userData: { user: User; token: string }) => void;
  logout: () => void;
  updateUser: (userData: Partial<User>) => void;
  completeGuide: () => void;
}>({
  state: initialState,
  dispatch: () => {},
  login: () => {},
  logout: () => {},
  updateUser: () => {},
  completeGuide: () => {},
});

// Reducer
const authReducer = (state: AuthState, action: AuthAction): AuthState => {
  switch (action.type) {
    case 'LOGIN_START':
      return { ...state, loading: true };
    case 'LOGIN_SUCCESS':
      return {
        ...state,
        user: action.payload.user,
        token: action.payload.token,
        isAuthenticated: true,
        loading: false,
      };
    case 'LOGOUT':
      return { ...initialState, loading: false };
    case 'SET_USER':
      return { ...state, user: action.payload, loading: false };
    case 'UPDATE_USER':
      return { ...state, user: { ...state.user, ...action.payload } };
    case 'COMPLETE_GUIDE':
      return { 
        ...state, 
        user: { ...state.user!, isGuideCompleted: true } as User 
      };
    case 'INITIALIZE':
      return { ...state, loading: false };
    default:
      return state;
  }
};

// AuthProvider组件
export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [state, dispatch] = useReducer(authReducer, initialState);

  // 初始化：从本地存储加载用户信息和认证状态
  useEffect(() => {
    const initializeAuth = async () => {
      const token = localStorage.getItem('jwt_token') || localStorage.getItem('token'); // 兼容新旧token存储
      const userStr = localStorage.getItem('user');
      
      if (token && userStr) {
        try {
          const user = JSON.parse(userStr);
          dispatch({
            type: 'LOGIN_SUCCESS',
            payload: { 
              user, 
              token 
            }
          });
        } catch (e) {
          console.error('解析用户信息失败:', e);
          // 清除损坏的本地存储数据
          localStorage.removeItem('jwt_token');
          localStorage.removeItem('token');
          localStorage.removeItem('user');
          dispatch({ type: 'INITIALIZE' });
        }
      } else {
        dispatch({ type: 'INITIALIZE' });
      }
    };

    initializeAuth();
    
    // 监听认证过期事件
    const handleAuthExpired = () => {
      logout();
    };
    
    window.addEventListener('authExpired', handleAuthExpired);
    
    // 清理事件监听器
    return () => {
      window.removeEventListener('authExpired', handleAuthExpired);
    };
  }, []);

  // 登录函数 - 存储认证信息到本地存储
  const login = (userData: { user: User; token: string }) => {
    const { user, token } = userData;
    
    // 保存到本地存储
    localStorage.setItem('user', JSON.stringify(user));
    if (token && token !== 'session-based-auth') {
      localStorage.setItem('jwt_token', token); // 使用新的jwt_token键名
    }
    
    dispatch({ type: 'LOGIN_SUCCESS', payload: { user, token } });
  };

  // 登出函数 - 清除本地存储
  const logout = () => {
    // 清除本地存储中的认证信息
    localStorage.removeItem('jwt_token'); // 清除JWT token
    localStorage.removeItem('token'); // 兼容旧键名
    localStorage.removeItem('user');
    
    dispatch({ type: 'LOGOUT' });
  };

  // 更新用户信息 - 同步到本地存储
  const updateUser = (userData: Partial<User>) => {
    if (state.user) {
      const updatedUser = { ...state.user, ...userData };
      localStorage.setItem('user', JSON.stringify(updatedUser));
      dispatch({ type: 'UPDATE_USER', payload: updatedUser });
    }
  };

  // 完成新手引导 - 同步到本地存储和状态
  const completeGuide = () => {
    if (state.user) {
      const updatedUser = { 
        ...state.user, 
        isGuideCompleted: true 
      };
      localStorage.setItem('user', JSON.stringify(updatedUser));
      dispatch({ type: 'COMPLETE_GUIDE' });
    }
  };

  return (
    <AuthContext.Provider
      value={{
        state,
        dispatch,
        login,
        logout,
        updateUser,
        completeGuide,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

// 自定义Hook使用AuthContext
export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth必须在AuthProvider内部使用');
  }
  return context;
};

export type { AuthState, AuthAction };