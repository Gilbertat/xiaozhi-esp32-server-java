package com.xiaozhi.dialogue.llm.util;

import com.xiaozhi.dialogue.llm.model.AudioTranscriptionModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.json.JSONObject;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 音频转录工具类
 * 提供便捷的音频转录方法，兼容ChatModel接口
 */
public class AudioTranscriptionUtils {
    
    // 特殊标记，用于识别音频转录请求
    private static final String AUDIO_TRANSCRIPTION_PREFIX = "[AUDIO_TRANSCRIPTION]";
    private static final String AUDIO_DATA_KEY = "audio_data";
    private static final String LANGUAGE_KEY = "language";
    
    /**
     * 使用ChatModel进行音频转录（方式1：特殊标记格式）
     * 
     * @param chatModel ChatModel实例（必须支持音频转录）
     * @param audioData 音频数据
     * @param language 语言代码（可选，默认为"zh"）
     * @param prompt 提示词（可选）
     * @return 转录结果
     */
    public static String transcribeWithChatModel(ChatModel chatModel, byte[] audioData, String language, String prompt) {
        try {
            // 将音频数据编码为Base64
            String audioDataBase64 = Base64.getEncoder().encodeToString(audioData);
            
            // 构建JSON配置
            JSONObject config = new JSONObject();
            config.put(AUDIO_DATA_KEY, audioDataBase64);
            config.put(LANGUAGE_KEY, language != null ? language : "zh");
            if (prompt != null && !prompt.isEmpty()) {
                config.put("prompt", prompt);
            }
            
            // 构建特殊格式的消息
            String messageText = AUDIO_TRANSCRIPTION_PREFIX + config.toString();
            UserMessage userMessage = new UserMessage(messageText);
            Prompt chatPrompt = new Prompt(List.of(userMessage));
            
            // 调用ChatModel
            ChatResponse response = chatModel.call(chatPrompt);
            return response.getResult().getOutput().getText();
            
        } catch (Exception e) {
            throw new RuntimeException("音频转录失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 使用ChatModel进行音频转录（方式2：元数据格式）
     * 
     * @param chatModel ChatModel实例（必须支持音频转录）
     * @param audioData 音频数据
     * @param language 语言代码（可选，默认为"zh"）
     * @param prompt 提示词（可选）
     * @return 转录结果
     */
    public static String transcribeWithMetadata(ChatModel chatModel, byte[] audioData, String language, String prompt) {
        try {
            // 构建元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(AUDIO_DATA_KEY, audioData);
            metadata.put(LANGUAGE_KEY, language != null ? language : "zh");
            
            // 创建带元数据的用户消息 - 使用正确的构造方式
            // 通过反射或其他方式设置元数据，或者直接在OpenAIWhisperChatModel中处理
            // 由于Spring AI的UserMessage构造函数限制，我们改用特殊标记格式
            return transcribeWithChatModel(chatModel, audioData, language, prompt);
            
        } catch (Exception e) {
            throw new RuntimeException("音频转录失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 直接使用AudioTranscriptionModel进行转录
     * 
     * @param model AudioTranscriptionModel实例
     * @param audioData 音频数据
     * @param language 语言代码（可选）
     * @param prompt 提示词（可选）
     * @return 转录结果
     */
    public static String transcribeDirectly(AudioTranscriptionModel model, byte[] audioData, String language, String prompt) {
        return model.transcribe(audioData, language, prompt);
    }
    
    /**
     * 检查ChatModel是否支持音频转录
     * 
     * @param chatModel ChatModel实例
     * @return 是否支持音频转录
     */
    public static boolean supportsTranscription(ChatModel chatModel) {
        return chatModel instanceof AudioTranscriptionModel;
    }
    
    /**
     * 安全的音频转录方法，自动检查模型类型
     * 
     * @param chatModel ChatModel实例
     * @param audioData 音频数据
     * @param language 语言代码（可选）
     * @param prompt 提示词（可选）
     * @return 转录结果
     * @throws UnsupportedOperationException 如果模型不支持音频转录
     */
    public static String safeTranscribe(ChatModel chatModel, byte[] audioData, String language, String prompt) {
        if (chatModel instanceof AudioTranscriptionModel transcriptionModel) {
            return transcriptionModel.transcribe(audioData, language, prompt);
        } else {
            // 尝试使用特殊标记格式
            return transcribeWithChatModel(chatModel, audioData, language, prompt);
        }
    }
}