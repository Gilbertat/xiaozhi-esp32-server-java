import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useTranslation } from 'react-i18next';
import { motion } from 'framer-motion';
import '../styles/GuidePage.css';

export const GuidePage: React.FC = () => {
  const { completeGuide } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const [currentStep, setCurrentStep] = useState(0);

  const steps = [
    {
      title: t('guide.step1'),
      description: t('guide.step1Desc'),
    },
    {
      title: t('guide.step2'),
      description: t('guide.step2Desc'),
    },
    {
      title: t('guide.step3'),
      description: t('guide.step3Desc'),
    },
  ];

  const handleNext = () => {
    if (currentStep < steps.length - 1) {
      setCurrentStep(currentStep + 1);
    } else {
      handleComplete();
    }
  };

  const handlePrev = () => {
    if (currentStep > 0) {
      setCurrentStep(currentStep - 1);
    }
  };

  const handleComplete = () => {
    completeGuide(); // 更新用户状态为已完成引导
    navigate('/dashboard'); // 跳转到仪表板
  };

  const handleSkip = () => {
    completeGuide(); // 即使跳过也标记为完成
    navigate('/dashboard');
  };

  return (
    <div className="guide-page-container">
      <motion.div 
        className="guide-card"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
      >
        <h1 className="guide-title">
          {t('guide.welcome')}
        </h1>
        <p className="guide-subtitle">
          {t('guide.welcomeMessage')}
        </p>
        
        <div className="guide-progress-container">
          {steps.map((_, index) => (
            <div
              key={index}
              className={`guide-progress-dot ${index <= currentStep ? 'guide-progress-dot-active' : ''}`}
              onClick={() => setCurrentStep(index)}
            />
          ))}
        </div>
        
        <motion.div 
          key={currentStep}
          initial={{ opacity: 0, x: 20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ duration: 0.3 }}
          className="guide-step-content"
        >
          <h2 className="guide-step-title">
            {steps[currentStep].title}
          </h2>
          <p className="guide-step-description">
            {steps[currentStep].description}
          </p>
        </motion.div>
        
        <div className="guide-footer">
          <button 
            onClick={handleSkip} 
            className="guide-skip-button"
          >
            {t('guide.skip')}
          </button>
          
          <div className="guide-nav-container">
            <button 
              onClick={handlePrev} 
              disabled={currentStep === 0}
              className="guide-prev-button"
            >
              {t('common.prev')}
            </button>
            
            <button 
              onClick={handleNext}
              className="guide-next-button"
            >
              {currentStep === steps.length - 1 ? t('guide.finish') : t('common.next')}
            </button>
          </div>
        </div>
      </motion.div>
    </div>
  );
};