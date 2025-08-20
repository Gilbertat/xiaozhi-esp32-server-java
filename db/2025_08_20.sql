-- 为sys_config表添加支持OpenAI的新字段
-- 添加于2025-08-20 用于支持OpenAI语音服务

ALTER TABLE `xiaozhi`.`sys_config` 
ADD COLUMN `baseUrl` varchar(255) DEFAULT NULL COMMENT '服务提供商的基础URL（用于支持自定义API端点）' AFTER `apiUrl`;

ALTER TABLE `xiaozhi`.`sys_config` 
ADD COLUMN `modelName` varchar(100) DEFAULT NULL COMMENT '模型名称（如gpt-3.5-turbo、whisper-1、tts-1等）' AFTER `baseUrl`;