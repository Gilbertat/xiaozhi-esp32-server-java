package com.xiaozhi.dialogue.stt.providers;

import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.utils.AudioUtils;
import com.xiaozhi.utils.KoreanLanguageUtils;

import okhttp3.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * openai 语音识别Engine
 *
 * @author shiyue
 * @version 1.0 2025/10/11 19:39
 */
public class OpenAIStreamSttEngine {

    private static final Logger logger = LoggerFactory.getLogger(OpenAIStreamSttEngine.class);

    // 请求地址
    private final String baseUrl;
    // 请求路径
    private final String apiPath;
    // api key
    private final String apiKey;
    // 模型名称
    private final String model;

    private final OkHttpClient httpClient;

    // 构造函数
    public OpenAIStreamSttEngine(SysConfig sysConfig) {
        this.baseUrl = sysConfig.getBaseUrl();
        this.apiPath = sysConfig.getApiUrl();
        this.apiKey = sysConfig.getApiKey();
        this.model = sysConfig.getModelName();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

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

            // 构建请求体 - 先获取前几个词进行快速语言检测
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", tempFile.getName(),
                            RequestBody.create(MediaType.parse("audio/wav"), tempFile))
                    .addFormDataPart("model", model)
                    .addFormDataPart("response_format", "json")
//                    .addFormDataPart("language", "ko")
                    .build();

            Request request = new Request.Builder()
                    .url(baseUrl + apiPath)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {

                if (!response.isSuccessful()) {
                    logger.error("OpenAI STT 请求失败: code={}, message={}",
                            response.code(), response.message());
                    return "";
                }

                String body = response.body().string();
                if (body.isEmpty()) return "";

                JSONObject json = new JSONObject(body);
                String text = json.optString("text", "");
                
                // 如果API返回了语言信息，则检查语言
                String detectedLanguage = json.optString("language", "unknown");
                
                // 根据检测到的语言或文本特征判断是否为韩语
                boolean isKorean = isKoreanText(text) || "ko".equals(detectedLanguage) || isLikelyKorean(detectedLanguage);
                
                if (!isKorean) {
                    logger.info("检测到的文本不是韩语 (检测语言: {}, 文本示例: {})，跳过处理", detectedLanguage, text.substring(0, Math.min(20, text.length())));
                    return "";
                }
                
                logger.info("确认为韩语，继续处理识别结果");
                return KoreanLanguageUtils.convertNumberToKO(text);

            } catch (InterruptedIOException e) {
                Thread.interrupted(); // 清除中断状态
                logger.warn("STT 请求被中断（可能是 Flux 取消）: {}", e.getMessage());
                return "";
            }

        } catch (Exception e) {
            logger.error("STT 识别异常: {}", e.getMessage(), e);
            return "";
        }
    }


    /**
     * 真·伪流式识别
     * @param audioSink 输入音频 sink
     * @param onPartial 回调：每次识别部分结果时触发
     * @return 最终完整识别文本
     */
    public String streamRecognition(Sinks.Many<byte[]> audioSink, java.util.function.Consumer<String> onPartial) {
        logger.info("OpenAI STT: 开始伪流式语音识别");

        StringBuilder finalResult = new StringBuilder();
        AtomicBoolean active = new AtomicBoolean(true);

        try {
            Flux<String> textFlux = audioSink.asFlux()
                    .bufferTimeout(50, Duration.ofSeconds(1)) // 每秒收集一批
                    .filter(chunks -> !chunks.isEmpty())
                    .flatMap(chunks -> Mono.fromCallable(() -> {
                                        // 合并音频数据
                                        int total = chunks.stream().mapToInt(b -> b.length).sum();
                                        byte[] combined = new byte[total];
                                        int pos = 0;
                                        for (byte[] b : chunks) {
                                            System.arraycopy(b, 0, combined, pos, b.length);
                                            pos += b.length;
                                        }

                                        // 跳过太短片段
                                        if (combined.length < 4000) return "";

                                        // 调用识别
                                        String text = recognition(combined);
                                        if (!text.isEmpty()) {
                                            onPartial.accept(text);
                                        }
                                        return text;
                                    })
                                    .subscribeOn(Schedulers.boundedElastic()) // ✅ 阻塞安全
                                    .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofMillis(500))
                                            .filter(ex -> ex instanceof IOException)
                                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    )
                    .takeWhile(t -> active.get());

            textFlux
                    .doOnNext(t -> {
                        if (!t.isEmpty()) {
                            finalResult.append(t).append(" ");
                            logger.info("部分识别结果: {}", t);
                        }
                    })
                    .doFinally(sig -> {
                        active.set(false);
                        logger.info("语音流结束: {}", sig);
                    })
                    .blockLast(); // 等待识别完成

            return finalResult.toString().trim();

        } catch (Exception e) {
            logger.error("OpenAI 流式语音识别失败: {}", e.getMessage(), e);
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
            return AudioUtils.pcmToWav  (audioData, AudioUtils.SAMPLE_RATE, 1, 16);
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
     * 判断检测到的语言是否可能是韩语（处理检测不准确的情况）
     */
    private boolean isLikelyKorean(String detectedLanguage) {
        // 有些方言或口音可能被误识别为相近语言，这里可以添加处理逻辑
        return "ko".equals(detectedLanguage) || // 韩语
               "korean".equals(detectedLanguage); // 一些API可能返回完整名称
    }
    
    /**
     * 检查文本是否为韩语（基于韩文字母的特征）
     */
    private boolean isKoreanText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        
        // 韩文字符的Unicode范围是 \uAC00-\uD7AF (韩文音节) 和 \u1100-\u11FF (韩文字母)
        int koreanCharCount = 0;
        int totalCharCount = 0;
        
        for (char c : text.toCharArray()) {
            // 忽略空格和标点符号
            if (Character.isLetterOrDigit(c)) {
                totalCharCount++;
                if ((c >= 0xAC00 && c <= 0xD7AF) ||  // 韩文音节
                    (c >= 0x1100 && c <= 0x11FF)) {  // 韩文字母
                    koreanCharCount++;
                }
            }
        }
        
        // 如果韩文字符占比超过一定阈值（例如50%），则认为是韩语文本
        return totalCharCount > 0 && (double) koreanCharCount / totalCharCount > 0.5;
    }
}

