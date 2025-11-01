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

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class OpenAIStreamSttEngine {

    private static final Logger logger = LoggerFactory.getLogger(OpenAIStreamSttEngine.class);

    private final String baseUrl;
    private final String apiPath;
    private final String apiKey;
    private final String model;

    private final OkHttpClient httpClient;

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

    /**
     * 主识别逻辑 - 仅对韩语进行响应
     */
    public String recognition(byte[] audioData) {
        File tempFile = null;
        try {
            if (audioData == null || audioData.length == 0) {
                logger.error("recognition: 输入的音频数据为空");
                return "";
            }

            tempFile = createTempAudioFile(audioData);
            if (!tempFile.exists()) {
                logger.error("recognition: 创建临时音频文件失败");
                return "";
            }

            // 策略1: 同时进行自动检测和韩语强制识别
            File finalTempFile = tempFile;
            CompletableFuture<String> autoDetectFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return doRecognition(finalTempFile, false, model);
                } catch (Exception e) {
                    logger.error("自动检测识别失败: {}", e.getMessage());
                    return "";
                }
            });

            File finalTempFile1 = tempFile;
            CompletableFuture<String> koreanForcedFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return doRecognition(finalTempFile1, true, model);
                } catch (Exception e) {
                    logger.error("韩语强制识别失败: {}", e.getMessage());
                    return "";
                }
            });

            // 等待两个结果
            String autoDetectResult = autoDetectFuture.get(10, TimeUnit.SECONDS);
            String koreanForcedResult = koreanForcedFuture.get(10, TimeUnit.SECONDS);

            // 分析结果并决定最终输出
            return analyzeAndDecideResult(autoDetectResult, koreanForcedResult, tempFile);

        } catch (TimeoutException e) {
            logger.error("识别超时");
            return "";
        } catch (Exception e) {
            logger.error("STT 识别异常: {}", e.getMessage(), e);
            return "";
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * 分析两个识别结果并决定最终输出
     */
    private String analyzeAndDecideResult(String autoDetectResult, String koreanForcedResult, File tempFile) {
        // 解析两个结果
        JSONObject autoJson = parseResult(autoDetectResult);
        JSONObject koreanJson = parseResult(koreanForcedResult);

        String autoText = autoJson.optString("text", "").trim();
        String autoLanguage = autoJson.optString("language", "unknown");
        String koreanText = koreanJson.optString("text", "").trim();

        logger.info("识别结果分析 - 自动检测: [语言: {}, 文本: {}], 韩语强制: [文本: {}]",
                autoLanguage,
                autoText.substring(0, Math.min(20, autoText.length())) + "...",
                koreanText.substring(0, Math.min(20, koreanText.length())) + "...");

        // 情况1: 自动检测明确是韩语
        if (isKoreanLanguage(autoLanguage, autoText)) {
            logger.info("自动检测确认为韩语，使用自动检测结果");
            return KoreanLanguageUtils.convertNumberToKO(autoText);
        }

        // 情况2: 自动检测不是韩语，但韩语强制识别产生了有意义的文本
        if (isMeaningfulKoreanText(koreanText) && !koreanText.equals(autoText)) {
            // 检查韩语强制识别结果的质量
            double koreanConfidence = calculateKoreanConfidence(koreanText);
            if (koreanConfidence > 0.7) {
                logger.info("韩语强制识别产生高质量结果，置信度: {:.2f}", koreanConfidence);
                return KoreanLanguageUtils.convertNumberToKO(koreanText);
            }
        }

        // 情况3: 两个结果都失败，尝试Whisper回退
        if (autoText.isEmpty() && koreanText.isEmpty()) {
            logger.info("两个识别结果都为空，尝试Whisper回退");
            return tryFallbackWhisper(tempFile);
        }

        // 情况4: 自动检测不是韩语，且韩语强制识别结果不理想
        logger.info("未检测到有效的韩语内容，自动检测语言: {}", autoLanguage);
        return "";
    }

    /**
     * 解析识别结果
     */
    private JSONObject parseResult(String result) {
        if (result == null || result.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(result);
        } catch (Exception e) {
            logger.error("解析识别结果失败: {}", e.getMessage());
            return new JSONObject();
        }
    }

    /**
     * 检查是否为有意义的韩语文本
     */
    private boolean isMeaningfulKoreanText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        // 检查文本长度
        if (text.length() < 2) {
            return false;
        }

        // 检查韩文字符比例
        return calculateKoreanConfidence(text) > 0.3;
    }

    /**
     * 计算韩语置信度
     */
    private double calculateKoreanConfidence(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0.0;
        }

        int koreanCharCount = 0;
        int totalCharCount = 0;

        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                totalCharCount++;
                // 韩文字符范围
                if ((c >= 0xAC00 && c <= 0xD7AF) ||
                        (c >= 0x1100 && c <= 0x11FF)) {
                    koreanCharCount++;
                }
            }
        }

        if (totalCharCount == 0) {
            return 0.0;
        }

        return (double) koreanCharCount / totalCharCount;
    }

    /**
     * whisper-large-v3 回退机制
     */
    private String tryFallbackWhisper(File audioFile) {
        try {
            String fallbackModel = "whisper-large-v3";
            logger.info("开始使用回退模型: {}", fallbackModel);

            // 同时尝试两种模式
            CompletableFuture<String> autoFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return doRecognition(audioFile, false, fallbackModel);
                } catch (Exception e) {
                    return "";
                }
            });

            CompletableFuture<String> koreanFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return doRecognition(audioFile, true, fallbackModel);
                } catch (Exception e) {
                    return "";
                }
            });

            String autoResult = autoFuture.get(10, TimeUnit.SECONDS);
            String koreanResult = koreanFuture.get(10, TimeUnit.SECONDS);

            return analyzeAndDecideResult(autoResult, koreanResult, audioFile);

        } catch (Exception e) {
            logger.error("Whisper 回退识别失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 实际执行 STT 请求
     */
    private String doRecognition(File audioFile, boolean forceKorean, String modelName) throws IOException {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(),
                        RequestBody.create(MediaType.parse("audio/wav"), audioFile))
                .addFormDataPart("model", modelName)
                .addFormDataPart("response_format", "json");

        if (forceKorean) {
            builder.addFormDataPart("language", "ko");
        }

        Request request = new Request.Builder()
                .url(baseUrl + apiPath)
                .post(builder.build())
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.warn("STT 请求失败: code={}, message={}", response.code(), response.message());
                return "";
            }
            return response.body().string();
        }
    }

    /**
     * 流式识别
     */
    public String streamRecognition(Sinks.Many<byte[]> audioSink, java.util.function.Consumer<String> onPartial) {
        logger.info("OpenAI STT: 开始韩语专用流式语音识别");

        StringBuilder finalResult = new StringBuilder();
        AtomicBoolean active = new AtomicBoolean(true);

        try {
            Flux<String> textFlux = audioSink.asFlux()
                    .bufferTimeout(50, Duration.ofSeconds(1))
                    .filter(chunks -> !chunks.isEmpty())
                    .flatMap(chunks -> Mono.fromCallable(() -> {
                                        int total = chunks.stream().mapToInt(b -> b.length).sum();
                                        byte[] combined = new byte[total];
                                        int pos = 0;
                                        for (byte[] b : chunks) {
                                            System.arraycopy(b, 0, combined, pos, b.length);
                                            pos += b.length;
                                        }

                                        if (combined.length < 4000) {
                                            logger.debug("音频数据过短，跳过识别: {} bytes", combined.length);
                                            return "";
                                        }

                                        String text = recognition(combined);
                                        if (!text.isEmpty()) {
                                            onPartial.accept(text);
                                        }
                                        return text;
                                    })
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofMillis(500))
                                            .filter(ex -> ex instanceof IOException)
                                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    )
                    .takeWhile(t -> active.get());

            textFlux
                    .doOnNext(t -> {
                        if (!t.isEmpty()) {
                            finalResult.append(t).append(" ");
                            logger.info("韩语部分识别结果: {}", t);
                        }
                    })
                    .doFinally(sig -> {
                        active.set(false);
                        logger.info("韩语语音流结束: {}", sig);
                    })
                    .blockLast();

            return finalResult.toString().trim();

        } catch (Exception e) {
            logger.error("OpenAI 韩语流式语音识别失败: {}", e.getMessage(), e);
            return "";
        }
    }

    private File createTempAudioFile(byte[] audioData) throws IOException {
        byte[] wavData = ensureWavFormat(audioData);
        File tempFile = File.createTempFile("openai_stt_", ".wav");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(wavData);
        }
        return tempFile;
    }

    private byte[] ensureWavFormat(byte[] audioData) {
        try {
            if (isWavFormat(audioData)) return audioData;
            return AudioUtils.pcmToWav(audioData, AudioUtils.SAMPLE_RATE, 1, 16);
        } catch (Exception e) {
            logger.warn("音频格式转换失败，使用原始数据", e);
            return audioData;
        }
    }

    private boolean isWavFormat(byte[] audioData) {
        if (audioData.length < 12) return false;
        return audioData[0] == 'R' && audioData[1] == 'I' &&
                audioData[2] == 'F' && audioData[3] == 'F' &&
                audioData[8] == 'W' && audioData[9] == 'A' &&
                audioData[10] == 'V' && audioData[11] == 'E';
    }

    /**
     * 综合判断是否为韩语
     */
    private boolean isKoreanLanguage(String detectedLanguage, String text) {
        // 检查API返回的语言代码
        boolean byLanguageCode = isLikelyKoreanByCode(detectedLanguage);

        // 检查文本内容中的韩文字符
        boolean byTextContent = calculateKoreanConfidence(text) > 0.4;

        logger.debug("韩语验证 - 语言代码: {} -> {}, 文本内容: {}",
                detectedLanguage, byLanguageCode, byTextContent);

        return byLanguageCode && byTextContent;
    }

    /**
     * 通过语言代码判断是否为韩语
     */
    private boolean isLikelyKoreanByCode(String detectedLanguage) {
        return "ko".equalsIgnoreCase(detectedLanguage) ||
                "korean".equalsIgnoreCase(detectedLanguage) ||
                "kr".equalsIgnoreCase(detectedLanguage) ||
                "kor".equalsIgnoreCase(detectedLanguage);
    }
}