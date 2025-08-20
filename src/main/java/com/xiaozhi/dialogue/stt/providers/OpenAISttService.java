package com.xiaozhi.dialogue.stt.providers;

import com.xiaozhi.dialogue.stt.SttService;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.utils.AudioUtils;
import okhttp3.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Whisper STT服务实现
 */
public class OpenAISttService implements SttService {
    private static final Logger logger = LoggerFactory.getLogger(OpenAISttService.class);
    private static final String PROVIDER_NAME = "openai";
    private static final String API_URL = "https://api.openai.com/v1/audio/transcriptions";
    
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OkHttpClient httpClient;

    public OpenAISttService(SysConfig config) {
        this.apiKey = config.getApiKey();
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : API_URL;
        this.model = config.getModelName() != null ? config.getModelName() : "whisper-1";
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supportsStreaming() {
        // OpenAI Whisper API 目前不支持真正的流式处理
        return false;
    }

    @Override
    public String recognition(byte[] audioData) {
        try {
            // 将音频数据转换为临时文件
            File tempFile = createTempAudioFile(audioData);
            
            try {
                // 构建请求
                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", tempFile.getName(),
                                RequestBody.create(tempFile, MediaType.parse("audio/wav")))
                        .addFormDataPart("model", model)
                        .addFormDataPart("language", "zh")
                        .addFormDataPart("response_format", "json")
                        .build();

                Request request = new Request.Builder()
                        .url(baseUrl)
                        .post(requestBody)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .build();

                // 发送请求
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        logger.error("OpenAI STT API请求失败: {}", response.code());
                        throw new IOException("API请求失败: " + response.code());
                    }

                    String responseBody = response.body().string();
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    String text = jsonResponse.optString("text", "");
                    
                    logger.info("OpenAI语音识别结果: {}", text);
                    return text;
                }
            } finally {
                // 清理临时文件
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
        } catch (Exception e) {
            logger.error("OpenAI语音识别失败", e);
            return "";
        }
    }

    @Override
    public String streamRecognition(Sinks.Many<byte[]> audioSink) {
        // OpenAI Whisper API 不支持真正的流式处理
        // 这里收集所有音频数据后进行一次性识别
        logger.info("OpenAI STT: 收集音频数据进行批量识别");
        
        try {
            // 收集所有音频数据
            StringBuilder audioBuffer = new StringBuilder();
            
            audioSink.asFlux()
                    .buffer(Duration.ofSeconds(3)) // 每3秒收集一批数据
                    .subscribe(chunks -> {
                        try {
                            // 合并音频块
                            int totalLength = chunks.stream().mapToInt(chunk -> chunk.length).sum();
                            byte[] combinedAudio = new byte[totalLength];
                            int offset = 0;
                            for (byte[] chunk : chunks) {
                                System.arraycopy(chunk, 0, combinedAudio, offset, chunk.length);
                                offset += chunk.length;
                            }
                            
                            // 识别文本
                            String text = recognition(combinedAudio);
                            if (!text.isEmpty()) {
                                audioBuffer.append(text).append(" ");
                            }
                        } catch (Exception e) {
                            logger.error("处理音频块时出错", e);
                        }
                    });

            return audioBuffer.toString().trim();
        } catch (Exception e) {
            logger.error("OpenAI流式语音识别失败", e);
            return "";
        }
    }

    /**
     * 创建临时音频文件
     */
    private File createTempAudioFile(byte[] audioData) throws IOException {
        // 确保音频数据是WAV格式
        byte[] wavData = ensureWavFormat(audioData);
        
        File tempFile = File.createTempFile("openai_stt_", ".wav");
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
}