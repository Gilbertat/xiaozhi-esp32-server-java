import React from 'react';
import { Navigation } from '../components/Navigation';
import { motion } from 'framer-motion';
import '../styles/PageLayout.css';

interface PageLayoutProps {
  title?: string;
  children: React.ReactNode;
  className?: string;
}

export const PageLayout: React.FC<PageLayoutProps> = ({ 
  title, 
  children, 
  className = '' 
}) => {
  return (
    <div className="page-layout-container">
      <Navigation />
      <main className="page-layout-main">
        <div className="page-layout-content">
          {title && (
            <motion.h1 
              className="page-layout-title"
              initial={{ opacity: 0, y: -20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5 }}
            >
              {title}
            </motion.h1>
          )}
          <motion.div 
            className={`page-layout-content-wrapper ${className}`}
            initial={{ opacity: 0, scale: 0.95 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 0.3 }}
          >
            {children}
          </motion.div>
        </div>
      </main>
    </div>
  );
};