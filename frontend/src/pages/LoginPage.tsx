import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { LoginForm } from '../types';
import { authAPI } from '../utils/api';
import { useTranslation } from 'react-i18next';
import { motion } from 'framer-motion';
import '../styles/LoginPage.css';

export const LoginPage: React.FC = () => {
  const { login } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const [formData, setFormData] = useState<LoginForm>({
    emailOrUsername: '',
    password: ''
  });
  const [error, setError] = useState<string>('');
  const [loading, setLoading] = useState(false);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: value
    }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    try {
      const response = await authAPI.login(formData.emailOrUsername, formData.password);
      
      if (response.data && response.data.code === 200) { // Java后端返回AjaxResult格式
        // 从Java后端获取用户数据
        const tokenData = response.data.data; // 现在是包含token和user的对象
        
        // 保存JWT token到localStorage
        localStorage.setItem('jwt_token', tokenData.token);
        
        // 简化用户对象以适应前端类型定义
        const simplifiedUser = {
          id: tokenData.user.userId || tokenData.user.id || '1',
          email: tokenData.user.email || formData.emailOrUsername,
          username: tokenData.user.username || formData.emailOrUsername,
          isGuideCompleted: tokenData.user.isGuideCompleted || false,
          createdAt: new Date(),
          updatedAt: new Date()
        };
        
        // 登录成功，保存用户信息和JWT token
        login({ 
          user: simplifiedUser, 
          token: tokenData.token
        });
        
        // 如果用户未完成引导，则跳转到引导页面，否则跳转到仪表板
        if (!simplifiedUser.isGuideCompleted) {
          navigate('/guide');
        } else {
          navigate('/dashboard');
        }
      } else {
        setError(response.data.msg || response.data.message || t('login.loginFailed', '登录失败'));
      }
    } catch (err: any) {
      console.error('登录错误:', err);
      setError(err.response?.data?.msg || err.response?.data?.message || t('login.loginFailedMessage', '登录失败，请稍后重试'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page-container">
      <motion.div 
        className="login-card"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
      >
        <div className="login-header">
          <h1 className="login-title">
            {t('login.title')}
          </h1>
          <p className="login-subtitle">
            {t('login.subtitle', '欢迎使用Mira设备管理系统')}
          </p>
        </div>
        
        {error && (
          <motion.div 
            className="login-error-message"
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
          >
            {error}
          </motion.div>
        )}
        
        <form onSubmit={handleSubmit} className="login-form">
          <div className="login-form-group">
            <label htmlFor="emailOrUsername" className="login-label">
              {t('login.emailOrUsername')}
            </label>
            <input
              type="text"
              id="emailOrUsername"
              name="emailOrUsername"
              value={formData.emailOrUsername}
              onChange={handleChange}
              required
              className="login-input"
              placeholder={t('login.emailOrUsernamePlaceholder', '请输入邮箱或用户名')}
            />
          </div>
          <div className="login-form-group-last">
            <label htmlFor="password" className="login-label">
              {t('login.password')}
            </label>
            <input
              type="password"
              id="password"
              name="password"
              value={formData.password}
              onChange={handleChange}
              required
              className="login-input"
              placeholder={t('login.passwordPlaceholder', '请输入密码')}
            />
          </div>
          <button 
            type="submit" 
            disabled={loading}
            className="login-submit-button"
          >
            {loading ? t('login.loggingIn', '登录中...') : t('login.submit')}
          </button>
        </form>
        
        <div className="login-footer">
          <Link 
            to="/register" 
            className="login-footer-link"
          >
            {t('login.dontHaveAccount')}
          </Link>
          <span className="divider">|</span>
          <Link 
            to="#" 
            className="login-footer-link"
          >
            {t('login.forgotPassword')}
          </Link>
        </div>
      </motion.div>
    </div>
  );
};