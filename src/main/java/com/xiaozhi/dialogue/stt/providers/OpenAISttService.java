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

import java.io.ByteArrayInputStream;
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
                    .addFormDataPart("language", "ko")
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
                if (response == null) {
                    logger.error("recognition: httpClient 执行返回 null response");
                    return "";
                }

                logger.debug("recognition: response.code = {}", response.code());

                if (!response.isSuccessful()) {
                    logger.error("OpenAI STT API 请求失败: code={}, message={}",
                            response.code(), response.message());
                    return "";
                }

                ResponseBody body = response.body();
                if (body == null) {
                    logger.error("OpenAI STT API 返回空响应体 (response.body == null)");
                    return "";
                }

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

        try {
            // 持续收集音频数据，每1秒合并一次识别
            audioSink.asFlux()
                    .bufferTimeout(50, Duration.ofSeconds(1)) // 每1秒收集一批，最多50个chunk
                    .filter(chunks -> !chunks.isEmpty())
                    .map(chunks -> {
                        try {
                            // 合并这一批音频
                            int totalLength = chunks.stream().mapToInt(arr -> arr.length).sum();
                            byte[] combined = new byte[totalLength];
                            int offset = 0;
                            for (byte[] arr : chunks) {
                                System.arraycopy(arr, 0, combined, offset, arr.length);
                                offset += arr.length;
                            }
                            // 调用识别
                            String partialText = recognition(combined);
                            return KoreanNumberConverter.convertNumberToKO(partialText);
                        } catch (Exception e) {
                            logger.error("处理音频块失败", e);
                            return "";
                        }
                    })
                    .doOnNext(partialText -> {
                        if (partialText != null && !partialText.isEmpty()) {
                            logger.info("部分识别结果: {}", partialText);
                            finalResult.append(partialText).append(" ");
                        }
                    })
                    .blockLast(); // 等待整个流消费完毕

            return finalResult.toString().trim();
        } catch (Exception e) {
            logger.error("OpenAI伪流式语音识别失败", e);
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