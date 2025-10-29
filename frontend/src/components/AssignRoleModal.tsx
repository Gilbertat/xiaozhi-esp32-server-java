import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { roleAPI } from '../utils/api';
import '../styles/AssignRoleModal.css';

interface Role {
  roleId: number;
  roleName: string;
  roleDesc?: string;
}

interface AssignRoleModalProps {
  deviceId: string;
  deviceName: string; // 设备名称
  currentRoleId?: number;
  currentRoleName?: string;
  isOpen: boolean;
  onClose: () => void;
  onRoleAssigned: (deviceId: string, roleId: number, roleName: string) => void;
}

export const AssignRoleModal: React.FC<AssignRoleModalProps> = ({
  deviceId,
  deviceName,
  currentRoleId,
  currentRoleName,
  isOpen,
  onClose,
  onRoleAssigned
}) => {
  const { t } = useTranslation();
  const [roles, setRoles] = useState<Role[]>([]);
  const [selectedRoleId, setSelectedRoleId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 获取角色列表
  useEffect(() => {
    if (isOpen) {
      fetchRoles();
    }
  }, [isOpen]);

  const fetchRoles = async () => {
    try {
      setLoading(true);
      setError(null);
      const response = await roleAPI.getRoles();
      
      if (response.data.code === 200 && response.data.data && response.data.data.list) {
        setRoles(response.data.data.list);
        setSelectedRoleId(currentRoleId || null);
      } else {
        setRoles([]);
        setSelectedRoleId(null);
      }
    } catch (err: any) {
      console.error('获取角色列表失败:', err);
      setError(err.response?.data?.msg || t('device.fetchFailedMessage', '获取设备列表失败，请稍后重试'));
      setRoles([]);
      setSelectedRoleId(null);
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    if (selectedRoleId === null) {
      setError(t('device.selectRole', '请先选择一个角色'));
      return;
    }

    try {
      // 调用API更新设备角色
      await roleAPI.updateDeviceRole(deviceId, selectedRoleId);
      
      // 获取角色名称
      const selectedRole = roles.find(role => role.roleId === selectedRoleId);
      const roleName = selectedRole ? selectedRole.roleName : `Role ${selectedRoleId}`;
      
      // 通知父组件角色已分配
      onRoleAssigned(deviceId, selectedRoleId, roleName);
      
      // 关闭模态框
      onClose();
    } catch (err: any) {
      console.error('分配角色失败:', err);
      setError(err.response?.data?.msg || t('device.roleAssignmentFailedMessage', '角色分配失败，请稍后重试'));
    }
  };

  if (!isOpen) return null;

  return (
    <div 
      className="assign-role-modal-overlay"
      onClick={onClose}
    >
      <div 
        className="assign-role-modal-content"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="assign-role-modal-title">
          {t('device.assignRole', '分配角色')}
        </h2>
        
        {/* 显示设备名称 */}
        <div className="assign-role-modal-device-info">
          <span className="assign-role-modal-device-name-label">
            {t('device.deviceName', '设备名称')}:
          </span>
          <span>{deviceName}</span>
        </div>
        
        <div className="assign-role-modal-current-role">
          <p className="assign-role-modal-current-role-text">
            {t('device.currentRole', '当前角色')}: {currentRoleName || t('device.noAvailableRoles', '无')}
          </p>
        </div>
        
        {error && (
          <div className="assign-role-modal-error">
            {error}
          </div>
        )}
        
        {loading ? (
          <div className="assign-role-modal-loading">
            {t('common.loading', '加载中...')}
          </div>
        ) : roles.length === 0 ? (
          <div className="assign-role-modal-no-roles">
            {t('device.noAvailableRolesHint', '请先创建角色')}
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className="assign-role-modal-role-group">
              <label className="assign-role-modal-role-label">
                {t('device.selectRole', '选择角色')}
              </label>
              
              <div className="assign-role-modal-role-list">
                {roles.map(role => (
                  <label 
                    key={role.roleId}
                    className={`assign-role-modal-role-item ${
                      selectedRoleId === role.roleId ? 'assign-role-modal-role-item-selected' : ''
                    }`}
                  >
                    <input
                      type="radio"
                      name="role"
                      value={role.roleId}
                      checked={selectedRoleId === role.roleId}
                      onChange={() => setSelectedRoleId(role.roleId)}
                      className="assign-role-modal-role-input"
                    />
                    <div>
                      <div className="assign-role-modal-role-name">
                        {role.roleName}
                      </div>
                      {role.roleDesc && (
                        <div className="assign-role-modal-role-desc">
                          {role.roleDesc}
                        </div>
                      )}
                    </div>
                  </label>
                ))}
              </div>
            </div>
            
            <div className="assign-role-modal-button-container">
              <button
                type="button"
                onClick={onClose}
                className="assign-role-modal-cancel-button hover:opacity-90"
              >
                {t('common.cancel', '取消')}
              </button>
              <button
                type="submit"
                className="assign-role-modal-save-button hover:opacity-90"
              >
                {t('common.save', '保存')}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
};