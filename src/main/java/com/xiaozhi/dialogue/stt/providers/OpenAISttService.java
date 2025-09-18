package com.xiaozhi.dialogue.stt.providers;

import com.xiaozhi.dialogue.stt.SttService;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.utils.AudioUtils;
import com.xiaozhi.utils.KoreanNumberConverter;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                .readTimeout(90, TimeUnit.SECONDS)  // 增加读取超时时间
                .writeTimeout(90, TimeUnit.SECONDS) // 增加写入超时时间
                .callTimeout(120, TimeUnit.SECONDS) // 添加总调用超时时间
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
        File tempFile = null;
        try {
            // 检查传入数据
            if (audioData == null || audioData.length == 0) {
                logger.error("recognition: 输入的音频数据为空");
                return "";
            }

            // 将音频数据转换为临时文件
            tempFile = createTempAudioFile(audioData);
            if (!tempFile.exists()) {
                logger.error("recognition: 创建临时音频文件失败");
                return "";
            }

            // 构建请求体
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", tempFile.getName(),
                            RequestBody.create(MediaType.parse("audio/wav"), tempFile))
                    .addFormDataPart("model", model)
//                    .addFormDataPart("language", "ko")
                    .addFormDataPart("response_format", "json")
                    .build();

            // 构建请求
            Request request = new Request.Builder()
                    .url(baseUrl)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .build();

            logger.debug("recognition: request = {}", request);

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {

                logger.debug("recognition: response.code = {}", response.code());

                if (!response.isSuccessful()) {
                    logger.error("OpenAI STT API 请求失败: code={}, message={}, body={}",
                            response.code(), response.message(), response.body().string());
                    return "";
                }

                ResponseBody body = response.body();

                String responseBody = body.string();
                if (responseBody.isEmpty()) {
                    logger.error("OpenAI STT API 返回空字符串响应");
                    return "";
                }

                JSONObject jsonResponse = new JSONObject(responseBody);
                String text = jsonResponse.optString("text", "");
                text = KoreanNumberConverter.convertNumberToKO(text);
                logger.info("OpenAI 语音识别结果: {}", text);
                return text;
            }

        } catch (Exception e) {
            logger.error("OpenAI 语音识别失败: {}", e.getMessage(), e);
            return "";
        } finally {
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                logger.warn("临时文件删除失败: {}", tempFile.getAbsolutePath());
                tempFile.deleteOnExit();
            }
        }
    }

    @Override
    public String streamRecognition(Sinks.Many<byte[]> audioSink) {
        logger.info("OpenAI STT: 开始伪流式语音识别");

        StringBuilder finalResult = new StringBuilder();

        try (ByteArrayOutputStream currentBuffer = new ByteArrayOutputStream()) {
            try {
                final long[] lastRecognitionTime = {System.currentTimeMillis()};
                final long RECOGNITION_INTERVAL = 3000; // 3秒识别间隔
                final int BUFFER_SIZE_THRESHOLD = 32000; // 1秒音频数据 (16kHz * 2字节 * 1秒)
                // 订阅音频流
                audioSink.asFlux()
                        .publishOn(Schedulers.boundedElastic())
                        .doOnNext(chunk -> {
                            try {
                                // 将音频块添加到缓冲区
                                currentBuffer.write(chunk);

                                long currentTime = System.currentTimeMillis();
                                // 检查是否需要进行识别：
                                // 1. 缓冲区数据足够大 (超过1秒音频)
                                // 2. 距离上次识别已超过设定间隔
                                if (currentBuffer.size() > BUFFER_SIZE_THRESHOLD &&
                                        (currentTime - lastRecognitionTime[0]) > RECOGNITION_INTERVAL) {

                                    // 获取当前缓冲区的数据
                                    byte[] audioData = currentBuffer.toByteArray();

                                    // 优先在检测到静音时进行识别，避免在句子中间切断
                                    // 如果没有检测到静音但距离上次识别时间足够长，也进行识别
                                    if (isSilence(getLastSecond(audioData)) ||
                                            (currentTime - lastRecognitionTime[0]) > RECOGNITION_INTERVAL * 2) {
                                        // 进行识别
                                        String partialText = recognition(audioData);
                                        partialText = KoreanNumberConverter.convertNumberToKO(partialText);

                                        if (!partialText.trim().isEmpty()) {
                                            logger.info("部分识别结果: {}", partialText);
                                            finalResult.append(partialText).append(" ");
                                        }

                                        // 清空缓冲区并更新时间戳
                                        currentBuffer.reset();
                                        lastRecognitionTime[0] = currentTime;
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("处理音频块失败", e);
                            }
                        })
                        .doOnError(error -> {
                            logger.error("音频流发生错误", error);
                        })
                        .doOnComplete(() -> {
                            // 流结束时处理剩余的音频数据
                            try {
                                if (currentBuffer.size() > 0) {
                                    byte[] remainingAudio = currentBuffer.toByteArray();
                                    // 只有当剩余数据足够大时才进行识别，避免处理过短的音频
                                    if (remainingAudio.length > 1600) { // 至少50毫秒的数据 (16kHz * 2字节 * 0.05秒)
                                        String partialText = recognition(remainingAudio);
                                        partialText = KoreanNumberConverter.convertNumberToKO(partialText);

                                        if (!partialText.trim().isEmpty()) {
                                            logger.info("最终识别结果: {}", partialText);
                                            finalResult.append(partialText);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("处理剩余音频数据失败", e);
                            }
                        })
                        .subscribe();

                // 等待流处理完成
                audioSink.asFlux().blockLast();

                return finalResult.toString().trim();
            } catch (Exception e) {
                logger.error("OpenAI伪流式语音识别失败: {}", e.getMessage(), e);
                return "";
            }
        } catch (IOException e) {
            logger.warn("关闭字节输出流失败", e);
        }
        return "";
    }

    /**
     * 简单的静音检测方法
     * @param audioData 音频数据
     * @return 是否检测到静音
     */
    private boolean isSilence(byte[] audioData) {
        if (audioData.length < 2) {
            return true;
        }
        
        // 计算音频的RMS值（均方根）
        long sum = 0;
        for (int i = 0; i < audioData.length; i += 2) {
            // 16位音频数据，两个字节为一个采样点
            int sample = (audioData[i] & 0xFF) | ((audioData[i + 1] & 0xFF) << 8);
            if (sample > 32767) {
                sample -= 65536; // 转换为有符号值
            }
            sum += sample * sample;
        }
        
        double rms = Math.sqrt((double) sum / ((double) audioData.length / 2));
        // 如果RMS值低于阈值，则认为是静音
        return rms < 500; // 静音阈值，可根据需要调整
    }
    
    /**
     * 获取音频数据的最后1秒
     * @param audioData 音频数据
     * @return 最后1秒的音频数据
     */
    private byte[] getLastSecond(byte[] audioData) {
        // 16kHz采样率，16位深度，单声道 = 32000字节/秒
        int bytesPerSecond = 32000;
        if (audioData.length <= bytesPerSecond) {
            return audioData;
        }
        // 返回最后1秒的数据
        return java.util.Arrays.copyOfRange(audioData, audioData.length - bytesPerSecond, audioData.length);
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