package com.xiaozhi.dialogue.llm.model;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 音频转录模型接口
 * 扩展ChatModel以支持音频转录功能
 */
public interface AudioTranscriptionModel {
    
    /**
     * 音频转录（同步）
     * @param audioData 音频数据
     * @param language 语言代码（可选）
     * @param prompt 提示词（可选）
     * @return 转录结果
     */
    String transcribe(byte[] audioData, String language, String prompt);
    
    /**
     * 音频转录（流式）
     * @param audioData 音频数据
     * @param language 语言代码（可选）
     * @param prompt 提示词（可选）
     * @return 转录结果流
     */
    Flux<String> transcribeStream(byte[] audioData, String language, String prompt);
    
    /**
     * 检查是否支持流式转录
     */
    default boolean supportsStreamingTranscription() {
        return false;
    }
}