import React, { useState, useEffect } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { authAPI } from '../utils/api';
import { useTranslation } from 'react-i18next';
import { PageLayout } from '../components/PageLayout';
import { ProfileCard } from '../components/ProfileCard';
import { motion } from 'framer-motion';
import { staggerContainer, fadeInUp } from '../utils/animations';
import '../styles/ProfilePage.css';

export const ProfilePage: React.FC = () => {
  const { state, updateUser } = useAuth();
  const { t } = useTranslation();
  const [formData, setFormData] = useState({
    currentName: '',  // 姓名字段（可编辑）
    currentEmail: '',
    password: '',
    confirmPassword: ''
  });
  const [avatarFile, setAvatarFile] = useState<File | null>(null);
  // Note: avatarFile is stored but not yet implemented in the API call
  const [loading, setLoading] = useState(true);
  const [updateSuccess, setUpdateSuccess] = useState(false);
  const [error, setError] = useState('');

  // 初始化表单数据
  useEffect(() => {
    const fetchProfile = async () => {
      try {
        const response = await authAPI.getProfile();
        if (response.data && response.data.code === 200) {
          const userData = response.data.data || response.data;
          setFormData(prev => ({
            ...prev,
            currentName: userData.name || userData.username || userData.id || '未知用户',
            currentEmail: userData.email || '未设置邮箱'
          }));
        } else {
          setError(response.data.msg || t('profile.loadFailed', '加载用户信息失败'));
        }
      } catch (err: any) {
        console.error('获取用户资料错误:', err);
        setError(err.response?.data?.msg || t('profile.loadFailedMessage', '加载用户信息失败，请稍后重试'));
      } finally {
        setLoading(false);
      }
    };

    fetchProfile();
  }, [t]);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: value
    }));
  };

  const handleAvatarChange = (file: File) => {
    setAvatarFile(file);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setUpdateSuccess(false);

    // 验证密码匹配
    if (formData.password && formData.password !== formData.confirmPassword) {
      setError(t('profile.passwordMismatch', '密码和确认密码不匹配'));
      return;
    }

    try {
      // 发送昵称和密码字段到后端
      const profileData: any = {};
      if (formData.password) {
        profileData.password = formData.password;
      }
      // 始终发送当前昵称，即使没有更改
      profileData.name = formData.currentName;

      const response = await authAPI.updateUserProfile(profileData);

      if (response.data && response.data.code === 200) {
        setUpdateSuccess(true);
        
        // 更新上下文中的用户信息
        if (state.user) {
          const updatedUser = {
            ...state.user,
            name: formData.currentName  // 更新昵称
          };
          
          updateUser(updatedUser);
        }
        
        // 重置密码字段（但保留昵称）
        setFormData(prev => ({
          ...prev,
          password: '',
          confirmPassword: ''
        }));
        
        // 3秒后隐藏成功消息
        setTimeout(() => setUpdateSuccess(false), 3000);
      } else {
        setError(response.data.msg || t('profile.updateFailed', '更新失败'));
      }
    } catch (err: any) {
      console.error('更新用户信息错误:', err);
      setError(err.response?.data?.msg || t('profile.updateFailedMessage', '更新失败，请稍后重试'));
    }
  };

  if (loading) {
    return (
      <PageLayout title={t('profile.title', '个人资料')}>
        <div className="profile-loading-container">
          <motion.div 
            className="profile-spinner"
            animate={{ rotate: 360 }}
            transition={{ duration: 1, repeat: Infinity, ease: 'linear' }}
          />
        </div>
      </PageLayout>
    );
  }

  return (
    <PageLayout title={t('profile.title', '个人资料')}>
      <motion.div 
        className="py-4"
        variants={staggerContainer}
        initial="hidden"
        animate="visible"
      >
        {updateSuccess && (
          <motion.div 
            className="profile-update-success"
            initial={{ opacity: 0, y: -20, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            transition={{ duration: 0.3 }}
          >
            {t('profile.updateSuccess', '个人资料更新成功!')}
          </motion.div>
        )}
        
        {error && (
          <motion.div 
            className="profile-error-message"
            initial={{ opacity: 0, y: -20, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            transition={{ duration: 0.3 }}
          >
            {error}
          </motion.div>
        )}
        <div className="profile-layout">
          <motion.div 
            className="profile-card-section"
            variants={fadeInUp}
          >
            <ProfileCard 
              name={formData.currentName} 
              email={formData.currentEmail} 
              onAvatarChange={handleAvatarChange}
            />
          </motion.div>
          
          <motion.div 
            className="profile-form-section"
            variants={fadeInUp}
          >
            <motion.div 
              className="profile-form-card"
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.2 }}
            >
              <h2 className="profile-form-title">{t('profile.editProfile', '编辑资料')}</h2>
              <motion.form 
                onSubmit={handleSubmit} 
                className="profile-form"
                variants={staggerContainer}
                initial="hidden"
                animate="visible"
              >
                <motion.div variants={fadeInUp}>
                  <label className="profile-form-label">
                    {t('profile.email', '邮箱')}
                  </label>
                  <div className="profile-input-wrapper">
                    <input
                      type="email"
                      value={formData.currentEmail}
                      readOnly
                      className="profile-input-email"
                    />
                  </div>
                </motion.div>

                <motion.div variants={fadeInUp}>
                  <label className="profile-form-label">
                    {t('profile.name', '昵称')}
                  </label>
                  <div className="profile-input-wrapper">
                    <input
                      type="text"
                      name="currentName"
                      value={formData.currentName}
                      onChange={handleChange}
                      className="profile-input-name"
                    />
                  </div>
                </motion.div>

                <motion.div variants={fadeInUp}>
                  <label htmlFor="password" className="profile-form-label">
                    {t('profile.newPassword', '新密码')}
                  </label>
                  <div className="profile-input-wrapper">
                    <input
                      type="password"
                      id="password"
                      name="password"
                      value={formData.password}
                      onChange={handleChange}
                      placeholder={t('profile.passwordPlaceholder', '留空则不修改密码')}
                      className="profile-input-password"
                    />
                  </div>
                </motion.div>

                <motion.div variants={fadeInUp}>
                  <label htmlFor="confirmPassword" className="profile-form-label">
                    {t('profile.confirmPassword', '确认密码')}
                  </label>
                  <div className="profile-input-wrapper">
                    <input
                      type="password"
                      id="confirmPassword"
                      name="confirmPassword"
                      value={formData.confirmPassword}
                      onChange={handleChange}
                      placeholder={t('profile.confirmPasswordPlaceholder', '确认新密码')}
                      className="profile-input-confirm-password"
                    />
                  </div>
                </motion.div>

                <motion.button 
                  type="submit" 
                  className="profile-submit-button md:w-auto hover:shadow-lg hover:scale-105"
                  whileHover={{ scale: 1.03 }}
                  whileTap={{ scale: 0.98 }}
                >
                  {t('profile.updateButton', '更新资料')}
                </motion.button>
              </motion.form>
            </motion.div>
          </motion.div>
        </div>
      </motion.div>
    </PageLayout>
  );
};