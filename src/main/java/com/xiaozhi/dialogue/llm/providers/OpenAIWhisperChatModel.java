package com.xiaozhi.dialogue.llm.providers;

import com.xiaozhi.dialogue.llm.model.AudioTranscriptionModel;
import com.xiaozhi.utils.AudioUtils;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Whisper ChatModel实现
 * 兼容ChatModel接口的音频转录服务
 */
public class OpenAIWhisperChatModel implements ChatModel, AudioTranscriptionModel {
    private static final Logger logger = LoggerFactory.getLogger(OpenAIWhisperChatModel.class);
    
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OkHttpClient httpClient;
    
    // 特殊标记，用于识别音频转录请求
    private static final String AUDIO_TRANSCRIPTION_PREFIX = "[AUDIO_TRANSCRIPTION]";
    private static final String AUDIO_DATA_KEY = "audio_data";
    private static final String LANGUAGE_KEY = "language";
    
    public OpenAIWhisperChatModel(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.openai.com/v1/audio/transcriptions";
        this.model = model != null ? model : "whisper-1";
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @NotNull
    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            // 检查是否为音频转录请求
            AudioTranscriptionRequest request = parseAudioTranscriptionRequest(prompt);
            if (request != null) {
                String result = transcribe(request.audioData, request.language, request.prompt);
                AssistantMessage assistantMessage = new AssistantMessage(result);
                Generation generation = new Generation(assistantMessage);
                return new ChatResponse(List.of(generation));
            }
            
            // 如果不是音频转录请求，返回错误信息
            String errorMsg = "此模型仅支持音频转录功能。请使用正确的音频转录格式。";
            AssistantMessage assistantMessage = new AssistantMessage(errorMsg);
            Generation generation = new Generation(assistantMessage);
            return new ChatResponse(List.of(generation));
            
        } catch (Exception e) {
            logger.error("音频转录失败", e);
            String errorMsg = "音频转录失败: " + e.getMessage();
            AssistantMessage assistantMessage = new AssistantMessage(errorMsg);
            Generation generation = new Generation(assistantMessage);
            return new ChatResponse(List.of(generation));
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // OpenAI Whisper API 不支持流式转录，返回单个结果
        return Flux.just(call(prompt));
    }

    @Override
    public String transcribe(byte[] audioData, String language, String prompt) {
        try {
            // 创建临时音频文件
            File tempFile = createTempAudioFile(audioData);
            
            try {
                // 构建multipart/form-data请求
                MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", tempFile.getName(),
                                RequestBody.create(tempFile, MediaType.parse("audio/wav")))
                        .addFormDataPart("model", model)
                        .addFormDataPart("response_format", "json");
                
                // 添加可选参数
                if (language != null && !language.isEmpty()) {
                    bodyBuilder.addFormDataPart("language", language);
                }
                if (prompt != null && !prompt.isEmpty()) {
                    bodyBuilder.addFormDataPart("prompt", prompt);
                }
                
                RequestBody requestBody = bodyBuilder.build();

                Request request = new Request.Builder()
                        .url(baseUrl)
                        .post(requestBody)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .build();

                // 发送请求
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body().string();
                        logger.error("OpenAI Whisper API请求失败: {} - {}", response.code(), errorBody);
                        throw new IOException("API请求失败: " + response.code() + " - " + errorBody);
                    }

                    String responseBody = response.body().string();
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    String text = jsonResponse.optString("text", "");
                    
                    logger.info("OpenAI Whisper转录结果: {}", text);
                    return text;
                }
            } finally {
                // 清理临时文件
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
        } catch (Exception e) {
            logger.error("OpenAI Whisper转录失败", e);
            throw new RuntimeException("音频转录失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<String> transcribeStream(byte[] audioData, String language, String prompt) {
        // OpenAI Whisper API 不支持真正的流式转录
        return Flux.just(transcribe(audioData, language, prompt));
    }

    /**
     * 解析音频转录请求
     * 支持特殊标记格式：[AUDIO_TRANSCRIPTION] + JSON配置
     */
    private AudioTranscriptionRequest parseAudioTranscriptionRequest(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        if (messages.isEmpty()) {
            return null;
        }
        
        for (Message message : messages) {
            // 检查特殊标记格式
            if (message instanceof UserMessage userMessage) {
                String text = userMessage.getText();
                if (text.startsWith(AUDIO_TRANSCRIPTION_PREFIX)) {
                    try {
                        String jsonPart = text.substring(AUDIO_TRANSCRIPTION_PREFIX.length()).trim();
                        JSONObject config = new JSONObject(jsonPart);
                        
                        // 从配置中提取音频数据（Base64编码）
                        String audioDataBase64 = config.optString(AUDIO_DATA_KEY);
                        if (audioDataBase64.isEmpty()) {
                            continue;
                        }
                        
                        byte[] audioData = java.util.Base64.getDecoder().decode(audioDataBase64);
                        String language = config.optString(LANGUAGE_KEY, "zh");
                        String transcriptionPrompt = config.optString("prompt", "");
                        
                        return new AudioTranscriptionRequest(audioData, language, transcriptionPrompt);
                    } catch (Exception e) {
                        logger.warn("解析音频转录请求失败", e);
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * 创建临时音频文件
     */
    private File createTempAudioFile(byte[] audioData) throws IOException {
        // 确保音频数据是WAV格式
        byte[] wavData = ensureWavFormat(audioData);
        
        File tempFile = File.createTempFile("whisper_transcription_", ".wav");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(wavData);
        }
        return tempFile;
    }
    
    /**
     * 确保音频数据是WAV格式
     */
    private byte[] ensureWavFormat(byte[] audioData) {
        try {
            // 检查是否已经是WAV格式
            if (isWavFormat(audioData)) {
                return audioData;
            }
            
            // 如果是原始PCM数据，转换为WAV格式
            return AudioUtils.pcmToWav(audioData, AudioUtils.SAMPLE_RATE, 1, 16);
        } catch (Exception e) {
            logger.warn("音频格式转换失败，使用原始数据", e);
            return audioData;
        }
    }
    
    /**
     * 检查是否为WAV格式
     */
    private boolean isWavFormat(byte[] audioData) {
        if (audioData.length < 12) {
            return false;
        }
        // 检查WAV文件头
        return audioData[0] == 'R' && audioData[1] == 'I' && 
               audioData[2] == 'F' && audioData[3] == 'F' &&
               audioData[8] == 'W' && audioData[9] == 'A' && 
               audioData[10] == 'V' && audioData[11] == 'E';
    }
    
    /**
     * 音频转录请求数据类
     */
    private static class AudioTranscriptionRequest {
        final byte[] audioData;
        final String language;
        final String prompt;
        
        AudioTranscriptionRequest(byte[] audioData, String language, String prompt) {
            this.audioData = audioData;
            this.language = language;
            this.prompt = prompt;
        }
    }
}