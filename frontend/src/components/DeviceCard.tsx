import React, { useState, useRef, useEffect } from 'react';
import { motion } from 'framer-motion';
import { useTranslation } from 'react-i18next';
import { deviceAPI } from '../utils/api';
import { AssignRoleModal } from './AssignRoleModal';
import '../styles/DeviceCard.css';

interface DeviceCardProps {
  id: string;
  name: string;
  status: 'online' | 'offline' | 'connecting';
  deviceId: string;
  createdAt: Date;
  roleId?: number; // 当前设备的角色ID
  roleName?: string; // 当前设备的角色名称
  onUnbind: (id: string) => void;
  onRoleChange: (deviceId: string, roleId: number, roleName: string) => void; // 角色变更回调
}

export const DeviceCard: React.FC<DeviceCardProps> = ({ 
  id,
  name, 
  status, 
  deviceId, 
  createdAt,
  roleId,
  roleName,
  onUnbind,
  onRoleChange
}) => {
  const { t } = useTranslation();
  const [isEditing, setIsEditing] = useState(false);
  const [deviceName, setDeviceName] = useState(name);
  const [originalName, setOriginalName] = useState(name);
  const [showRoleModal, setShowRoleModal] = useState(false);
  const nameInputRef = useRef<HTMLInputElement>(null);
  const nameDisplayRef = useRef<HTMLHeadingElement>(null);
  
  const statusColors = {
    online: 'bg-green-500',
    offline: 'bg-red-500',
    connecting: 'bg-yellow-500'
  };

  const statusText = {
    online: t('device.status_online', 'Online'),
    offline: t('device.status_offline', 'Offline'),
    connecting: t('device.status_connecting', 'Connecting')
  };

  // 点击设备名称进入编辑模式
  const handleNameClick = () => {
    setOriginalName(deviceName);
    setIsEditing(true);
  };

  // 失去焦点时保存修改
  const handleBlur = async () => {
    setIsEditing(false);
    
    // 如果名称没有改变，不发送请求
    if (deviceName === originalName) {
      return;
    }

    try {
      // 调用后端API更新设备名称
      await deviceAPI.renameDevice(deviceId, deviceName);
    } catch (error) {
      console.error('更新设备名称失败:', error);
      // 如果更新失败，恢复原来的名称
      setDeviceName(originalName);
    }
  };

  // 按Enter键保存修改
  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleBlur();
    }
  };

  // 点击其他地方时保存修改
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (isEditing && 
          nameInputRef.current && 
          !nameInputRef.current.contains(e.target as Node) &&
          nameDisplayRef.current && 
          !nameDisplayRef.current.contains(e.target as Node)) {
        handleBlur();
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isEditing, deviceName, originalName]);

  // 当props中的name改变时，更新state
  useEffect(() => {
    setDeviceName(name);
  }, [name]);

  // 显示角色分配模态框
  const handleShowRoleModal = () => {
    setShowRoleModal(true);
  };

  // 关闭角色分配模态框
  const handleCloseRoleModal = () => {
    setShowRoleModal(false);
  };

  // 角色分配成功回调
  const handleRoleAssigned = (deviceId: string, roleId: number, roleName: string) => {
    onRoleChange(deviceId, roleId, roleName);
    setShowRoleModal(false);
  };

  return (
    <>
      <motion.div 
        className="device-card-container"
        whileHover={{ y: -3 }}
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.2 }}
      >
        <div className="device-card-header">
          <div className="device-card-info">
            {isEditing ? (
              <input
                ref={nameInputRef}
                type="text"
                value={deviceName}
                onChange={(e) => setDeviceName(e.target.value)}
                onBlur={handleBlur}
                onKeyDown={handleKeyPress}
                autoFocus
                className="device-card-name-editing"
              />
            ) : (
              <h3 
                ref={nameDisplayRef}
                onClick={handleNameClick}
                className="device-card-name-display"
                onMouseEnter={(e) => e.currentTarget.style.textDecoration = 'underline'}
                onMouseLeave={(e) => e.currentTarget.style.textDecoration = 'none'}
              >
                {deviceName}
              </h3>
            )}
            <div className="device-card-status-container">
              <span className={`device-card-status-indicator ${
                status === 'online' ? 'device-card-status-online' : 
                status === 'offline' ? 'device-card-status-offline' : 
                'device-card-status-connecting'
              }`}></span>
              <span className="device-card-status-text">{statusText[status]}</span>
            </div>
            {/* 显示当前角色信息 */}
            {roleName && (
              <div className="device-card-role">
                <span className="device-card-role-label">
                  {t('device.currentRole', '角色')}: 
                </span>
                <span>{roleName}</span>
              </div>
            )}
          </div>
          <div className="device-card-actions">
            {/* 分配角色按钮 */}
            <button 
              className="device-card-role-button hover:opacity-90"
              onClick={handleShowRoleModal}
            >
              {t('device.assignRole', 'Assign Role')}
            </button>
            
            {/* 解绑按钮 */}
            <button 
              className="device-card-unbind-button hover:opacity-90"
              onClick={() => onUnbind(deviceId)}
            >
              {t('device.unbind', 'Unbind')}
            </button>
          </div>
        </div>
        
        <div className="device-card-details">
          <div className="device-card-details-grid">
            <div><span className="device-card-detail-item">{t('device.deviceId', 'Device ID')}:</span> {deviceId}</div>
            <div><span className="device-card-detail-item">{t('device.bindingDate', 'Binding Date')}:</span> {createdAt.toLocaleDateString()}</div>
          </div>
        </div>
      </motion.div>
      
      {/* 角色分配模态框 */}
      {showRoleModal && (
        <AssignRoleModal
          deviceId={deviceId}
          deviceName={deviceName}  // 传递设备名称
          currentRoleId={roleId}
          currentRoleName={roleName}
          isOpen={showRoleModal}
          onClose={handleCloseRoleModal}
          onRoleAssigned={handleRoleAssigned}
        />
      )}
    </>
  );
};