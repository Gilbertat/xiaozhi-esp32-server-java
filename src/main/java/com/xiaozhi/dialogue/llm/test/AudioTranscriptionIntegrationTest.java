package com.xiaozhi.dialogue.llm.test;

import com.xiaozhi.dialogue.llm.factory.ChatModelFactory;
import com.xiaozhi.dialogue.llm.model.AudioTranscriptionModel;
import com.xiaozhi.dialogue.llm.util.AudioTranscriptionUtils;
import com.xiaozhi.entity.SysConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 音频转录集成测试示例
 * 演示如何使用兼容ChatModel的OpenAI Whisper服务
 */
@Component
public class AudioTranscriptionIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(AudioTranscriptionIntegrationTest.class);
    
    @Autowired
    private ChatModelFactory chatModelFactory;
    
    /**
     * 测试通过ChatModelFactory创建音频转录模型
     */
    public void testTranscriptionModelCreation() {
        try {
            // 方式1: 通过配置类型获取
            ChatModel transcriptionModel = chatModelFactory.takeTranscriptionModel();
            logger.info("成功创建音频转录模型: {}", transcriptionModel.getClass().getSimpleName());
            
            // 方式2: 通过自定义配置创建
            SysConfig whisperConfig = new SysConfig()
                    .setProvider("whisper")
                    .setConfigType("transcription")
                    .setApiKey("your-openai-api-key")
                    .setApiUrl("https://api.openai.com/v1/audio/transcriptions")
                    .setConfigName("whisper-1");
            
            ChatModel customTranscriptionModel = chatModelFactory.takeTranscriptionModel(whisperConfig);
            logger.info("成功创建自定义音频转录模型: {}", customTranscriptionModel.getClass().getSimpleName());
            
        } catch (Exception e) {
            logger.error("创建音频转录模型失败", e);
        }
    }
    
    /**
     * 测试音频转录功能
     */
    public void testAudioTranscription(String audioFilePath) {
        try {
            // 读取音频文件
            byte[] audioData = Files.readAllBytes(Path.of(audioFilePath));
            logger.info("读取音频文件: {}, 大小: {} bytes", audioFilePath, audioData.length);
            
            // 创建转录模型
            SysConfig whisperConfig = new SysConfig()
                    .setProvider("whisper")
                    .setConfigType("transcription")
                    .setApiKey("your-openai-api-key")
                    .setApiUrl("https://api.openai.com/v1/audio/transcriptions")
                    .setConfigName("whisper-1");
            
            ChatModel transcriptionModel = chatModelFactory.takeTranscriptionModel(whisperConfig);
            
            // 方式1: 使用工具类的特殊标记格式
            logger.info("=== 测试特殊标记格式 ===");
            String result1 = AudioTranscriptionUtils.transcribeWithChatModel(
                    transcriptionModel, audioData, "zh", "这是一段中文语音");
            logger.info("转录结果1: {}", result1);
            
            // 方式2: 使用工具类的特殊标记格式（元数据格式已简化为特殊标记格式）
            logger.info("=== 测试特殊标记格式（方式2） ===");
            String result2 = AudioTranscriptionUtils.transcribeWithMetadata(
                    transcriptionModel, audioData, "zh", "请转录这段音频");
            logger.info("转录结果2: {}", result2);
            
            // 方式3: 直接使用AudioTranscriptionModel接口
            if (transcriptionModel instanceof AudioTranscriptionModel audioModel) {
                logger.info("=== 测试直接接口调用 ===");
                String result3 = AudioTranscriptionUtils.transcribeDirectly(
                        audioModel, audioData, "zh", "这是一段中文语音");
                logger.info("转录结果3: {}", result3);
            }
            
            // 方式4: 使用安全转录方法
            logger.info("=== 测试安全转录方法 ===");
            String result4 = AudioTranscriptionUtils.safeTranscribe(
                    transcriptionModel, audioData, "zh", "请转录这段音频");
            logger.info("转录结果4: {}", result4);
            
        } catch (IOException e) {
            logger.error("读取音频文件失败: {}", audioFilePath, e);
        } catch (Exception e) {
            logger.error("音频转录测试失败", e);
        }
    }
    
    /**
     * 测试模型兼容性检查
     */
    public void testModelCompatibility() {
        try {
            // 测试普通ChatModel
            ChatModel regularModel = chatModelFactory.takeIntentModel();
            boolean supportsTranscription1 = AudioTranscriptionUtils.supportsTranscription(regularModel);
            logger.info("普通ChatModel是否支持转录: {}", supportsTranscription1);
            
            // 测试转录模型
            ChatModel transcriptionModel = chatModelFactory.takeTranscriptionModel();
            boolean supportsTranscription2 = AudioTranscriptionUtils.supportsTranscription(transcriptionModel);
            logger.info("转录ChatModel是否支持转录: {}", supportsTranscription2);
            
        } catch (Exception e) {
            logger.error("模型兼容性测试失败", e);
        }
    }
    
    /**
     * 演示如何在现有对话流程中集成音频转录
     */
    public void demonstrateIntegrationWithExistingFlow() {
        logger.info("=== 演示与现有流程的集成 ===");
        
        try {
            // 模拟现有的ChatModel使用方式
            SysConfig whisperConfig = new SysConfig()
                    .setProvider("whisper")
                    .setConfigType("transcription")
                    .setApiKey("your-openai-api-key");
            
            ChatModel model = chatModelFactory.takeTranscriptionModel(whisperConfig);
            
            // 现有代码可以继续使用ChatModel接口
            logger.info("模型类型: {}", model.getClass().getSimpleName());
            logger.info("是否为ChatModel: {}", model instanceof ChatModel);
            logger.info("是否支持音频转录: {}", model instanceof AudioTranscriptionModel);
            
            // 在需要音频转录时，可以安全地转换
            if (model instanceof AudioTranscriptionModel transcriptionModel) {
                logger.info("可以直接使用音频转录功能");
                // transcriptionModel.transcribe(audioData, "zh", "prompt");
            }
            
        } catch (Exception e) {
            logger.error("集成演示失败", e);
        }
    }
}