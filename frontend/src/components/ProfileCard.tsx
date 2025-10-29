import React, { useState } from 'react';
import { motion } from 'framer-motion';
import { UserIcon, CameraIcon } from './Icons';
import { useTranslation } from 'react-i18next';
import '../styles/ProfileCard.css';

interface ProfileCardProps {
  name: string;
  email: string;
  avatar?: string;
  onAvatarChange?: (file: File) => void;
}

export const ProfileCard: React.FC<ProfileCardProps> = ({ 
  name, 
  email, 
  avatar, 
  onAvatarChange 
}) => {
  const { t } = useTranslation();
  const [preview, setPreview] = useState<string | null>(null);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      const file = e.target.files[0];
      const filePreview = URL.createObjectURL(file);
      setPreview(filePreview);
      
      if (onAvatarChange) {
        onAvatarChange(file);
      }
    }
  };

  return (
    <motion.div 
      className="profile-card-container"
      initial={{ opacity: 0, scale: 0.95 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ duration: 0.3 }}
    >
      <div className="profile-card-content">
        <div className="profile-card-avatar-wrapper">
          <div className="profile-card-avatar">
            {preview ? (
              <img 
                src={preview} 
                alt="Avatar preview" 
                className="profile-card-avatar-img"
              />
            ) : avatar ? (
              <img 
                src={avatar} 
                alt="User avatar" 
                className="profile-card-avatar-img"
              />
            ) : (
              <UserIcon className="w-12 h-12 text-blue-500" />
            )}
          </div>
          <label className="profile-card-avatar-upload">
            <CameraIcon size={16} className="text-white" />
            <input 
              type="file" 
              className="hidden"
              accept="image/*"
              onChange={handleFileChange}
            />
          </label>
        </div>
        
        <div className="profile-card-info">
          <div className="profile-card-info-group">
            <label className="profile-card-label">{t('profile.name', 'Nickname')}</label>
            <div className="profile-card-value">
              {name}
            </div>
          </div>
          
          <div className="profile-card-info-group-last">
            <label className="profile-card-label">{t('profile.email', 'Email')}</label>
            <div className="profile-card-value">
              {email}
            </div>
          </div>
        </div>
      </div>
    </motion.div>
  );
};