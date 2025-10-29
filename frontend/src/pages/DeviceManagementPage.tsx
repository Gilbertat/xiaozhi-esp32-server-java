import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Device } from '../types';
import { deviceAPI, roleAPI } from '../utils/api';
import { PageLayout } from '../components/PageLayout';
import { DeviceCard } from '../components/DeviceCard';
import { motion } from 'framer-motion';
import { DiscIcon } from '../components/Icons';
import { staggerContainer, fadeInUp } from '../utils/animations';
import '../styles/DeviceManagementPage.css';

export const DeviceManagementPage: React.FC = () => {
  const { t } = useTranslation();
  const [devices, setDevices] = useState<Device[]>([]);
  const [newDeviceCode, setNewDeviceCode] = useState(''); 
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>('');

  const fetchDevices = useCallback(async () => {
    try {
      const response = await deviceAPI.getDevices();
      if (response.data.code === 200) {
        // 处理Java后端返回的数据格式
        const deviceData = response.data.data || response.data;
        const deviceList = Array.isArray(deviceData.list) ? deviceData.list : deviceData;
        
        // 将Java后端的设备数据转换为前端格式
        const formattedDevices = deviceList.map((device: any) => ({
          id: device.id || device.deviceId,
          deviceId: device.deviceId,
          name: device.deviceName || device.name || t('device.unknownDevice', '未知设备'),
          status: device.state === '1' ? 'online' : 'offline', // 假设1表示在线
          userId: device.userId,
          roleId: device.roleId, // 设备关联的角色ID
          roleName: device.roleName, // 设备关联的角色名称
          createdAt: new Date(device.createTime || device.createdAt)
        }));
        
        setDevices(formattedDevices);
      } else {
        setError(response.data.msg || t('device.fetchFailed', '获取设备列表失败'));
      }
    } catch (err: any) {
      console.error('获取设备列表错误:', err);
      setError(err.response?.data?.msg || t('device.fetchFailedMessage', '获取设备列表失败，请稍后重试'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  // 从API获取设备列表
  useEffect(() => {
    fetchDevices();
  }, [fetchDevices]);

  const handleBindDevice = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newDeviceCode.trim()) {
      setError(t('device.codeRequired', '请输入设备验证码'));
      return;
    }

    try {
      const response = await deviceAPI.bindDevice(newDeviceCode);
      
      if (response.data.code === 200) {
        // 绑定成功，刷新设备列表
        setNewDeviceCode('');
        setError('');
        alert(t('device.bindSuccess'));
        fetchDevices(); // 刷新设备列表
      } else {
        setError(response.data.msg || t('device.bindFailed', '绑定设备失败'));
      }
    } catch (err: any) {
      console.error('绑定设备错误:', err);
      setError(err.response?.data?.msg || t('device.bindFailedMessage', '绑定设备失败，请稍后重试'));
    }
  };

  const handleUnbindDevice = async (deviceId: string) => {
    if (!window.confirm(t('device.unbindConfirm', '确定要解绑此设备吗？'))) {
      return;
    }

    try {
      const response = await deviceAPI.unbindDevice(deviceId);
      
      if (response.data.code === 200) {
        // 解绑成功，刷新设备列表
        fetchDevices(); // 刷新设备列表
      } else {
        setError(response.data.msg || t('device.unbindFailed', '解绑设备失败'));
      }
    } catch (err: any) {
      console.error('解绑设备错误:', err);
      setError(err.response?.data?.msg || t('device.unbindFailedMessage', '解绑设备失败，请稍后重试'));
    }
  };

  // 处理设备角色变更
  const handleDeviceRoleChange = (deviceId: string, newRoleId: number, newRoleName: string) => {
    setDevices(prevDevices => 
      prevDevices.map(device => 
        device.deviceId === deviceId 
          ? { ...device, roleId: newRoleId, roleName: newRoleName } 
          : device
      )
    );
  };

  if (loading) {
    return (
      <PageLayout title={t('device.title', '设备管理')}>
        <div className="device-management-loading-container">
          <motion.div 
            className="device-management-spinner"
            animate={{ rotate: 360 }}
            transition={{ duration: 1, repeat: Infinity, ease: 'linear' }}
          />
        </div>
      </PageLayout>
    );
  }

  return (
    <PageLayout title={t('device.title', '设备管理')}>
      <motion.div 
        className="device-management-content"
        variants={staggerContainer}
        initial="hidden"
        animate="visible"
      >
        {error && (
          <motion.div 
            className="device-management-error-message"
            initial={{ opacity: 0, y: -20, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            transition={{ duration: 0.3 }}
          >
            {error}
          </motion.div>
        )}

        {/* 绑定新设备 */}
        <motion.div 
          className="device-management-bind-section"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
          variants={fadeInUp}
        >
          <h2 className="device-management-bind-title">
            <DiscIcon size={24} className="mr-2 text-blue-500" /> {/* text-blue-500 */}
            {t('device.bindDevice')}
          </h2>
          <form 
            onSubmit={handleBindDevice} 
            className="device-management-bind-form"
          >
            <div className="device-management-bind-input-container">
              <div className="device-management-bind-input-group">
                <label htmlFor="deviceCode" className="device-management-bind-label">
                  {t('device.verificationCode', '设备验证码')}
                </label>
                <div className="device-management-bind-input-wrapper">
                  <input
                    type="text"
                    id="deviceCode"
                    value={newDeviceCode}
                    onChange={(e) => setNewDeviceCode(e.target.value)}
                    placeholder={t('device.deviceIdPlaceholder', '输入设备验证码')}
                    className="device-management-bind-input"
                    onFocus={(e) => {
                      e.target.style.boxShadow = '0 0 0 3px rgba(59, 130, 246, 0.3)'; // focus:ring-2 focus:ring-blue-500
                      e.target.style.borderColor = '#3b82f6'; // focus:border-blue-500
                    }}
                    onBlur={(e) => {
                      e.target.style.boxShadow = '';
                      e.target.style.borderColor = '#d1d5db';
                    }}
                    required
                  />
                  <motion.button 
                    type="submit" 
                    className="device-management-bind-button hover:shadow-lg hover:scale-105"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    {t('device.bind')}
                  </motion.button>
                </div>
              </div>
            </div>
          </form>
        </motion.div>

        {/* 设备列表 */}
        <motion.div 
          className="device-management-device-list-section"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2 }}
          variants={fadeInUp}
        >
          <div className="device-management-device-list-header">
            <h2 className="device-management-device-list-title">
              <DiscIcon size={24} className="mr-2 text-teal-500" /> {/* text-teal-500 */}
              {t('device.deviceList')}
            </h2>
            <span className="device-management-device-count">
              {devices.length} {t('device.devices')}
            </span>
          </div>
          
          {devices.length === 0 ? (
            <div className="device-management-no-devices-container">
              <div className="device-management-no-devices-icon"> 
                <DiscIcon size={48} className="mx-auto" />
              </div>
              <p className="device-management-no-devices-text">{t('device.noDevices', '暂无绑定设备')}</p>
              <p className="device-management-no-devices-hint">{t('device.noDevicesHint', '添加您的第一个设备开始使用')}</p>
            </div>
          ) : (
            <div className="device-management-device-list">
              {devices.map((device, index) => (
                <DeviceCard
                  key={device.id}
                  id={device.id}
                  name={device.name}
                  status={device.status}
                  deviceId={device.deviceId}
                  roleId={device.roleId}
                  roleName={device.roleName}
                  createdAt={device.createdAt}
                  onUnbind={handleUnbindDevice}
                  onRoleChange={handleDeviceRoleChange}
                />
              ))}
            </div>
          )}
        </motion.div>
      </motion.div>
    </PageLayout>
  );
};