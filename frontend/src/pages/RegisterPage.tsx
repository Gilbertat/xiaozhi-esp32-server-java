import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { RegisterForm } from '../types';
import { authAPI } from '../utils/api';
import { useTranslation } from 'react-i18next';
import { motion } from 'framer-motion';
import '../styles/RegisterPage.css';

export const RegisterPage: React.FC = () => {
  const { login } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const [formData, setFormData] = useState<RegisterForm>({
    email: '',
    username: '',
    password: '',
    confirmPassword: ''
  });
  const [error, setError] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [verificationSent, setVerificationSent] = useState(false);
  const [verificationCode, setVerificationCode] = useState('');

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: value
    }));
  };

  const handleSendVerification = async () => {
    if (!formData.email) {
      setError(t('register.emailRequired', '请先输入邮箱地址'));
      return;
    }

    try {
      const response = await authAPI.sendEmailCaptcha(formData.email, 'register');
      if (response.data.code === 200) {
        setVerificationSent(true);
        alert(t('register.verificationSent', '验证码已发送到您的邮箱'));
      } else {
        setError(response.data.msg || t('register.verificationFailed', '验证码发送失败'));
      }
    } catch (err: any) {
      console.error('验证码发送错误:', err);
      setError(err.response?.data?.msg || t('register.verificationFailedMessage', '验证码发送失败，请稍后重试'));
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    // 验证密码匹配
    if (formData.password !== formData.confirmPassword) {
      setError(t('register.passwordMismatch', '密码和确认密码不匹配'));
      setLoading(false);
      return;
    }

    // 验证验证码
    try {
      const verificationResponse = await authAPI.checkCaptcha(verificationCode, formData.email);
      if (verificationResponse.data.code !== 200) {
        setError(verificationResponse.data.msg || t('register.invalidCode', '验证码无效'));
        setLoading(false);
        return;
      }
    } catch (err: any) {
      setError(err.response?.data?.msg || t('register.codeVerificationFailed', '验证码验证失败'));
      setLoading(false);
      return;
    }

    try {
      // 调用Java后端的注册API
      const response = await authAPI.register(
        formData.email, 
        formData.username, 
        formData.password
      );
      
      if (response.data.code === 200) {
        // 注册成功，自动登录
        const userData = response.data.data;
        
        // 简化用户对象以适应前端类型定义
        const simplifiedUser = {
          id: userData.userId || userData.id || '1',
          email: formData.email,
          username: formData.username,
          isGuideCompleted: false, // 新用户需要完成引导
          createdAt: new Date(),
          updatedAt: new Date()
        };
        
        // 登录新用户
        login({ 
          user: simplifiedUser, 
          token: 'session-based-auth' // Java后端使用Session而非JWT
        });
        
        // 跳转到引导页面
        navigate('/guide');
      } else {
        setError(response.data.msg || t('register.registerFailed', '注册失败'));
      }
    } catch (err: any) {
      console.error('注册错误:', err);
      setError(err.response?.data?.msg || t('register.registerFailedMessage', '注册失败，请稍后重试'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="register-page-container">
      <motion.div 
        className="register-card"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
      >
        <div className="register-header">
          <h1 className="register-title">
            {t('register.title')}
          </h1>
          <p className="register-subtitle">
            {t('register.subtitle', '创建您的Mira设备管理账户')}
          </p>
        </div>
        
        {error && (
          <motion.div 
            className="register-error-message"
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
          >
            {error}
          </motion.div>
        )}
        
        <form onSubmit={handleSubmit} className="register-form">
          <div className="register-form-group">
            <label htmlFor="email" className="register-label">
              {t('register.email')}
            </label>
            <input
              type="email"
              id="email"
              name="email"
              value={formData.email}
              onChange={handleChange}
              required
              className="register-input"
              placeholder={t('register.emailPlaceholder', '请输入邮箱地址')}
            />
            {!verificationSent ? (
              <button 
                type="button" 
                onClick={handleSendVerification}
                className="register-verification-button"
              >
                {t('register.sendVerification', '发送验证码')}
              </button>
            ) : (
              <div className="mt-2">
                <input
                  type="text"
                  value={verificationCode}
                  onChange={(e) => setVerificationCode(e.target.value)}
                  placeholder={t('register.codePlaceholder', '输入验证码')}
                  required
                  className="register-verification-input"
                />
              </div>
            )}
          </div>
          
          <div className="register-form-group">
            <label htmlFor="username" className="register-label">
              {t('register.username')}
            </label>
            <input
              type="text"
              id="username"
              name="username"
              value={formData.username}
              onChange={handleChange}
              required
              className="register-input"
              placeholder={t('register.usernamePlaceholder', '请输入用户名')}
            />
          </div>
          
          <div className="register-form-group">
            <label htmlFor="password" className="register-label">
              {t('register.password')}
            </label>
            <input
              type="password"
              id="password"
              name="password"
              value={formData.password}
              onChange={handleChange}
              required
              className="register-input"
              placeholder={t('register.passwordPlaceholder', '请输入密码')}
            />
          </div>
          
          <div className="register-form-group-last">
            <label htmlFor="confirmPassword" className="register-label">
              {t('register.confirmPassword')}
            </label>
            <input
              type="password"
              id="confirmPassword"
              name="confirmPassword"
              value={formData.confirmPassword}
              onChange={handleChange}
              required
              className="register-input"
              placeholder={t('register.confirmPasswordPlaceholder', '请确认密码')}
            />
          </div>
          
          <button 
            type="submit" 
            disabled={loading}
            className="register-submit-button"
          >
            {loading ? t('register.registering', '注册中...') : t('register.submit')}
          </button>
        </form>
        
        <div className="register-footer">
          <Link 
            to="/login" 
            className="register-footer-link"
          >
            {t('register.alreadyHaveAccount')}
          </Link>
        </div>
      </motion.div>
    </div>
  );
};