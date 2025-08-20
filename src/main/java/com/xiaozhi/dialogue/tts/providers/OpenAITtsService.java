package com.xiaozhi.dialogue.tts.providers;

import com.xiaozhi.dialogue.tts.TtsService;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.utils.AudioUtils;
import okhttp3.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OpenAI TTS服务实现
 */
public class OpenAITtsService implements TtsService {
    private static final Logger logger = LoggerFactory.getLogger(OpenAITtsService.class);
    private static final String PROVIDER_NAME = "openai";
    private static final String API_URL = "https://api.openai.com/v1/audio/speech";

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String voice;
    private final String outputPath;
    private final OkHttpClient httpClient;

    public OpenAITtsService(SysConfig config, String voiceName, String outputPath) {
        this.apiKey = config.getApiKey();
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : API_URL;
        this.model = config.getModelName() != null ? config.getModelName() : "tts-1";
        this.voice = mapVoiceName(voiceName);
        this.outputPath = outputPath;
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public String audioFormat() {
        return "wav";
    }

    @Override
    public boolean isSupportStreamTts() {
        return true;
    }

    @Override
    public String textToSpeech(String text) throws Exception {
        try {
            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("input", text);
            requestBody.put("voice", voice);
            requestBody.put("response_format", "wav");
            requestBody.put("speed", 1.0);

            RequestBody body = RequestBody.create(
                    requestBody.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(baseUrl)
                    .post(body)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("OpenAI TTS API请求失败: {}", response.code());
                    throw new Exception("API请求失败: " + response.code());
                }

                // 获取音频数据
                byte[] audioData = response.body().bytes();
                
                // 生成文件名
                String fileName = getAudioFileName();
                String filePath = outputPath + fileName;

                // 确保输出目录存在
                File outputDir = new File(outputPath);
                if (!outputDir.exists()) {
                    outputDir.mkdirs();
                }

                // 将音频数据转换为PCM并保存为WAV
                byte[] pcmData = AudioUtils.wavBytesToPcm(audioData);
                byte[] wavData = AudioUtils.pcmToWav(pcmData, AudioUtils.SAMPLE_RATE, AudioUtils.CHANNELS, 16);

                // 保存文件
                try (FileOutputStream fos = new FileOutputStream(filePath)) {
                    fos.write(wavData);
                }

                logger.info("OpenAI TTS生成音频文件: {}", fileName);
                return filePath;
            }
        } catch (Exception e) {
            logger.error("OpenAI TTS转换失败", e);
            throw e;
        }
    }

    @Override
    public void streamTextToSpeech(String text, Consumer<byte[]> audioDataConsumer) throws Exception {
        try {
            // 构建请求体（流式响应）
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("input", text);
            requestBody.put("voice", voice);
            requestBody.put("response_format", "wav");
            requestBody.put("speed", 1.0);

            RequestBody body = RequestBody.create(
                    requestBody.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(baseUrl)
                    .post(body)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            // 发送请求并流式处理响应
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("OpenAI TTS流式API请求失败: {}", response.code());
                    throw new Exception("API请求失败: " + response.code());
                }

                // 读取完整的音频数据
                byte[] audioData = response.body().bytes();
                
                // 转换为PCM格式
                byte[] pcmData = AudioUtils.wavBytesToPcm(audioData);

                // 分块发送PCM数据
                int chunkSize = AudioUtils.FRAME_SIZE * 2; // 16位音频，每个样本2字节
                for (int i = 0; i < pcmData.length; i += chunkSize) {
                    int endIndex = Math.min(i + chunkSize, pcmData.length);
                    byte[] chunk = new byte[endIndex - i];
                    System.arraycopy(pcmData, i, chunk, 0, chunk.length);
                    audioDataConsumer.accept(chunk);
                    
                    // 添加小延迟以模拟流式输出
                    try {
                        Thread.sleep(AudioUtils.OPUS_FRAME_DURATION_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                logger.info("OpenAI TTS流式转换完成");
            }
        } catch (Exception e) {
            logger.error("OpenAI TTS流式转换失败", e);
            throw e;
        }
    }

    /**
     * 映射语音名称到OpenAI支持的语音
     */
    private String mapVoiceName(String voiceName) {
        if (voiceName == null || voiceName.isEmpty()) {
            return "alloy"; // 默认语音
        }

        // 将中文语音名称映射到OpenAI的语音
        return switch (voiceName.toLowerCase()) {
            case "alloy", "echo", "fable", "onyx", "nova", "shimmer" -> voiceName.toLowerCase();
            case "女性", "female", "woman" -> "nova";
            case "男性", "male", "man" -> "onyx";
            case "清晰", "clear" -> "echo";
            case "温暖", "warm" -> "alloy";
            case "活泼", "lively" -> "shimmer";
            case "深沉", "deep" -> "fable";
            default -> "alloy"; // 默认语音
        };
    }
}