import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../contexts/AuthContext';
import { motion } from 'framer-motion';
import '../styles/Navigation.css';

export const Navigation: React.FC = () => {
  const { t } = useTranslation();
  const location = useLocation();
  const { logout } = useAuth();

  const navItems = [
    { path: '/dashboard', label: t('navigation.dashboard', '仪表板') },
    { path: '/devices', label: t('navigation.devices', '设备') },
    { path: '/profile', label: t('navigation.profile', '个人资料') }
  ];

  const handleLogout = () => {
    if (window.confirm(t('navigation.logoutConfirm', '确定要退出登录吗？'))) {
      logout();
    }
  };

  return (
    <motion.nav 
      className="navigation"
      initial={{ y: -100 }}
      animate={{ y: 0 }}
      transition={{ duration: 0.3 }}
    >
      <div className="navigation-content">
        <div className="navigation-brand">
          <div className="navigation-logo">
            <span className="navigation-logo-text">M</span>
          </div>
          <Link 
            to="/" 
            className="navigation-title"
          >
            MIRA
          </Link>
        </div>
        <div className="hidden md:flex navigation-menu">
          {navItems.map(item => (
            <Link
              key={item.path}
              to={item.path}
              className={`navigation-menu-item ${
                location.pathname === item.path 
                  ? 'navigation-menu-item-active' 
                  : 'navigation-menu-item-inactive'
              }`}
            >
              {item.label}
            </Link>
          ))}
        </div>
      </div>
      
      <div className="navigation-actions">
        <button 
          className="navigation-logout-button hover:shadow-lg hover:scale-105"
          onClick={handleLogout}
        >
          {t('navigation.logout', '退出')}
        </button>
      </div>
    </motion.nav>
  );
};