import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './contexts/AuthContext';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { DashboardPage } from './pages/DashboardPage';
import { GuidePage } from './pages/GuidePage';
import { DeviceManagementPage } from './pages/DeviceManagementPage';
import { ProfilePage } from './pages/ProfilePage';

import './i18n'; // 引入国际化配置
import './App.css';

// 受保护的路由组件
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { state } = useAuth();
  return state.isAuthenticated ? <>{children}</> : <Navigate to="/login" />;
};

// 引导路由组件
const GuideRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { state } = useAuth();
  if (state.isAuthenticated && state.user && !state.user.isGuideCompleted) {
    return <Navigate to="/guide" />;
  }
  return <>{children}</>;
};

// 认证加载组件
const AuthLoader: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { state } = useAuth();
  
  if (state.loading) {
    return <div className="loading">加载中...</div>; // 或者使用更漂亮的加载组件
  }
  
  return <>{children}</>;
};

const App: React.FC = () => {
  return (
    <AuthProvider>
      <Router>
        <AuthLoader>
          <div className="app">
            <Routes>
              {/* 公开路由 */}
              <Route path="/login" element={<LoginPage />} />
              <Route path="/register" element={<RegisterPage />} />
              
              {/* 引导页面 - 只有未完成引导的用户可以访问 */}
              <Route 
                path="/guide" 
                element={
                  <ProtectedRoute>
                    <GuidePage />
                  </ProtectedRoute>
                } 
              />
              
              {/* 受保护的路由 */}
              <Route 
                path="/" 
                element={
                  <ProtectedRoute>
                    <GuideRoute>
                      <DashboardPage />
                    </GuideRoute>
                  </ProtectedRoute>
                } 
              />
              <Route 
                path="/dashboard" 
                element={
                  <ProtectedRoute>
                    <GuideRoute>
                      <DashboardPage />
                    </GuideRoute>
                  </ProtectedRoute>
                } 
              />
              <Route 
                path="/devices" 
                element={
                  <ProtectedRoute>
                    <GuideRoute>
                      <DeviceManagementPage />
                    </GuideRoute>
                  </ProtectedRoute>
                } 
              />
              
              <Route 
                path="/profile" 
                element={
                  <ProtectedRoute>
                    <GuideRoute>
                      <ProfilePage />
                    </GuideRoute>
                  </ProtectedRoute>
                } 
              />
              
              {/* 默认重定向 */}
              <Route path="*" element={<Navigate to="/dashboard" />} />
            </Routes>
          </div>
        </AuthLoader>
      </Router>
    </AuthProvider>
  );
};

export default App;