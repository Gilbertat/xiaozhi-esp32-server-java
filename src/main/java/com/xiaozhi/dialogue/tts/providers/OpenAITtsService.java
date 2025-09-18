package com.xiaozhi.dialogue.tts.providers;

import com.xiaozhi.dialogue.tts.TtsService;
import com.xiaozhi.entity.SysConfig;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.util.Base64;
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
    private final double speed; // 添加速度参数
    private final String outputPath;
    private final OkHttpClient httpClient;

    public OpenAITtsService(SysConfig config, String voiceName, String outputPath) {
        this.apiKey = config.getApiKey();
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : API_URL;
        this.model = config.getModelName() != null ? config.getModelName() : "tts-1";
        this.voice = mapVoiceName(voiceName);
        this.speed = 1.3; // 默认速度
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

//    @Override
//    public String textToSpeech(String text) throws Exception {
//        try {
//            // 构建请求体
//            String processedText = naturalizeText(text);
//            JSONObject requestBody = new JSONObject();
//            requestBody.put("model", model);
//            requestBody.put("input", processedText);
//            requestBody.put("voice", voice);
//            requestBody.put("response_format", "wav");
//
//            RequestBody body = RequestBody.create(
//                    requestBody.toString(),
//                    MediaType.parse("application/json; charset=utf-8")
//            );
//
//            Request request = new Request.Builder()
//                    .url(baseUrl)
//                    .post(body)
//                    .addHeader("Authorization", "Bearer " + apiKey)
//                    .addHeader("Content-Type", "application/json")
//                    .build();
//
//            // 发送请求
//            try (Response response = httpClient.newCall(request).execute()) {
//                if (!response.isSuccessful()) {
//                    logger.error("OpenAI TTS API请求失败: {}", response.code());
//                    throw new Exception("API请求失败: " + response.code());
//                }
//
//                // 获取音频数据
//                byte[] audioData = response.body().bytes();
//
//                // 生成文件名
//                String fileName = getAudioFileName();
//                String filePath = outputPath + fileName;
//
//                // 确保输出目录存在
//                File outputDir = new File(outputPath);
//                if (!outputDir.exists()) {
//                    outputDir.mkdirs();
//                }
//
//                // 将音频数据转换为PCM并保存为WAV
//                byte[] pcmData = AudioUtils.wavBytesToPcm(audioData);
//                byte[] wavData = AudioUtils.pcmToWav(pcmData, AudioUtils.SAMPLE_RATE, AudioUtils.CHANNELS, 16);
//
//                // 保存文件
//                try (FileOutputStream fos = new FileOutputStream(filePath)) {
//                    fos.write(audioData);
//                }
//
//                logger.info("OpenAI TTS生成音频文件: {}", fileName);
//                return filePath;
//            }
//        } catch (Exception e) {
//            logger.error("OpenAI TTS转换失败", e);
//            throw e;
//        }
//    }

    @Override
    public String textToSpeech(String text) throws Exception {
        try {
            // 构建请求体
//            String processedText = naturalizeText(text);
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("input", text);
            requestBody.put("voice", voice);
            requestBody.put("speed", speed);
            requestBody.put("response_format", "wav");

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
                    String errorBody = response.body().string();
                    logger.error("OpenAI TTS API请求失败: {} - {}", response.code(), errorBody);
                    throw new Exception("API请求失败: " + response.code() + " - " + errorBody);
                }

                // 获取音频数据 (原始24kHz wav)
                byte[] audioData = response.body().bytes();

                // 直接用 ByteArrayInputStream 包装，不再写临时文件
                try (AudioInputStream originalStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audioData))) {
                    AudioFormat targetFormat = getAudioFormat(originalStream);
                    // 转换流
                    try (AudioInputStream convertedStream = AudioSystem.getAudioInputStream(targetFormat, originalStream)) {

                        // 生成最终文件名
                        String fileName = getAudioFileName();
                        String filePath = outputPath + fileName;

                        // 确保输出目录存在
                        File outputDir = new File(outputPath);
                        if (!outputDir.exists()) {
                            outputDir.mkdirs();
                        }

                        // 写入16kHz wav
                        File outputFile = new File(filePath);
                        AudioSystem.write(convertedStream, AudioFileFormat.Type.WAVE, outputFile);

                        logger.info("OpenAI TTS生成并重采样音频文件: {}", fileName);
                        return filePath;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("OpenAI TTS转换失败", e);
            throw e;
        }
    }

    @NotNull
    private AudioFormat getAudioFormat(AudioInputStream originalStream) {
        AudioFormat originalFormat = originalStream.getFormat();

        // 目标格式：16kHz，小端，保持位深/声道
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                16000,                     // 采样率
                originalFormat.getSampleSizeInBits(), // 位深
                originalFormat.getChannels(),         // 声道
                originalFormat.getChannels() * (originalFormat.getSampleSizeInBits() / 8),
                16000,                                // frame rate
                false                                 // 强制小端字节序
        );
    }


    @Override
    public void streamTextToSpeech(String text, Consumer<byte[]> audioDataConsumer) throws Exception {
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=gpt-4o-mini-tts")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, @NotNull Response response) {
                System.out.println("✅ WebSocket 连接成功，开始发送TTS请求");

                // 构造请求消息
                JSONObject requestBody = new JSONObject();
                requestBody.put("type", "response.create");
                JSONObject responseObj = new JSONObject();
                responseObj.put("modalities", new JSONArray().put("audio"));
                responseObj.put("instructions", text);
                responseObj.put("voice", voice);
                responseObj.put("format", "pcm16"); // 直接要 PCM，避免自己转
                requestBody.put("response", responseObj);

                webSocket.send(requestBody.toString());
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                try {
                    JSONObject msg = new JSONObject(text);
                    String type = msg.optString("type");

                    if ("audio.delta".equals(type)) {
                        // Base64 解码
                        String b64 = msg.getString("delta");
                        byte[] pcmChunk = Base64.getDecoder().decode(b64);

                        // 交给上层消费
                        audioDataConsumer.accept(pcmChunk);
                    } else if ("response.completed".equals(type)) {
                        logger.info("✅ TTS 完成");
                        webSocket.close(1000, "done");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
                // OpenAI Realtime 主要用 JSON，不走 binary
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("❌ WebSocket 错误: " + t.getMessage());
            }
        };

        client.newWebSocket(request, listener);
        client.dispatcher().executorService().shutdown();
    }


//    @Override
//    public void streamTextToSpeech(String text, Consumer<byte[]> audioDataConsumer) throws Exception {
//        try {
//            // 构建请求体
//            JSONObject requestBody = new JSONObject();
//            requestBody.put("model", model);
//            requestBody.put("input", text);
//            requestBody.put("voice", voice);
//            requestBody.put("response_format", "wav"); // 先拿 wav，再转 PCM
//
//            RequestBody body = RequestBody.create(
//                    requestBody.toString(),
//                    MediaType.parse("application/json; charset=utf-8")
//            );
//
//            Request request = new Request.Builder()
//                    .url(baseUrl)
//                    .post(body)
//                    .addHeader("Authorization", "Bearer " + apiKey)
//                    .addHeader("Content-Type", "application/json")
//                    .build();
//
//            // 发送请求并流式处理响应
//            try (Response response = httpClient.newCall(request).execute()) {
//                if (!response.isSuccessful()) {
//                    logger.error("OpenAI TTS流式API请求失败: {}", response.code());
//                    throw new Exception("API请求失败: " + response.code());
//                }
//
//                InputStream inputStream = response.body().byteStream();
//                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
//
//                byte[] chunk = new byte[8192];
//                int bytesRead;
//                while ((bytesRead = inputStream.read(chunk)) != -1) {
//                    // 写入缓存（可能不是完整wav帧）
//                    buffer.write(chunk, 0, bytesRead);
//
//                    // 尝试把已有的数据转为 PCM
//                    byte[] currentData = buffer.toByteArray();
//                    try {
//                        byte[] pcmData = AudioUtils.wavBytesToPcm(currentData);
//                        audioDataConsumer.accept(pcmData);
//                        buffer.reset(); // 已消费的数据清空，避免重复处理
//                    } catch (Exception ignore) {
//                        // 如果不是完整 wav 帧，继续等更多字节
//                    }
//                }
//
//                logger.info("OpenAI TTS流式转换完成");
//            }
//        } catch (Exception e) {
//            logger.error("OpenAI TTS流式转换失败", e);
//            throw e;
//        }
//    }

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