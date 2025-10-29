import React from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageLayout } from '../components/PageLayout';
import { Card } from '../components/Card';
import { motion } from 'framer-motion';
import { SmartphoneIcon, UserIcon } from '../components/Icons';
import { staggerContainer, fadeInUp } from '../utils/animations';
import '../styles/DashboardPage.css';

export const DashboardPage: React.FC = () => {
  const { t } = useTranslation();

  return (
    <PageLayout title={t('dashboard.title', '仪表板')}>
      <motion.div 
        className="dashboard-content"
        variants={staggerContainer}
        initial="hidden"
        animate="visible"
      >
        <motion.div 
          className="dashboard-grid"
          variants={staggerContainer}
          initial="hidden"
          animate="visible"
        >
          <motion.div variants={fadeInUp}>
            <Link to="/devices" className="dashboard-link">
              <Card
                title={t('dashboard.deviceManagement', '设备管理')}
                description={t('dashboard.deviceManagementDesc', '管理您的智能设备')}
                icon={<SmartphoneIcon size={64} className="text-blue-500" />}
              />
            </Link>
          </motion.div>
          
          <motion.div variants={fadeInUp}>
            <Link to="/profile" className="dashboard-link">
              <Card
                title={t('dashboard.profile', '个人资料')}
                description={t('dashboard.profileDesc', '查看和编辑个人资料')}
                icon={<UserIcon size={64} className="text-teal-400" />}
              />
            </Link>
          </motion.div>
        </motion.div>
      </motion.div>
    </PageLayout>
  );
};