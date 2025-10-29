import React from 'react';
import { motion } from 'framer-motion';
import '../styles/Card.css';

interface CardProps {
  title: string;
  description: string;
  icon?: React.ReactNode;
  onClick?: () => void;
  className?: string;
}

export const Card: React.FC<CardProps> = ({ 
  title, 
  description, 
  icon, 
  onClick,
  className = '' 
}) => {
  return (
    <motion.div
      className={`card-container ${className}`}
      whileHover={{ y: -5 }}
      whileTap={{ scale: 0.98 }}
      onClick={onClick}
    >
      {icon && <div className="card-icon-container">{icon}</div>}
      <h3 className="card-title">
        {title}
      </h3>
      <p className="card-description">
        {description}
      </p>
    </motion.div>
  );
};