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
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
     * 主识别逻辑 - 仅响应韩语
     */
    public String recognition(byte[] audioData) {
        File tempFile = null;
        try {
            if (audioData == null || audioData.length == 0) {
                logger.error("recognition: 输入音频数据为空");
                return "";
            }

            tempFile = createTempAudioFile(audioData);
            if (!tempFile.exists()) {
                logger.error("recognition: 创建临时音频文件失败");
                return "";
            }
            // ✅ 执行主识别（韩语）
            String mainResult = doRecognition(tempFile, true, model);
            JSONObject json = new JSONObject(mainResult);
            String text = json.optString("text", "").trim();
            logger.info("✅ 识别成功: {}", text);
            if (isKoreanText(text)) {
                return KoreanLanguageUtils.convertNumberToKO(text);
            } else {
                return text;
            }

        } catch (Exception e) {
            logger.error("STT 识别异常: {}", e.getMessage(), e);
            return "";
        } finally {
            if (tempFile != null && tempFile.exists()) tempFile.delete();
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
     * 简单检测文本是否为韩语
     */
    private boolean isKoreanText(String text) {
        if (text == null || text.isEmpty()) return false;
        long koreanChars = text.chars().filter(c -> c >= 0xAC00 && c <= 0xD7AF).count();
        double ratio = (double) koreanChars / text.length();
        return ratio >= 0.6;
    }

    /**
     * 创建临时音频文件
     */
    private File createTempAudioFile(byte[] audioData) throws IOException {
        byte[] wavData = AudioUtils.ensureWavFormat(audioData);
        File tempFile = File.createTempFile("openai_stt_", ".wav");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(wavData);
        }
        return tempFile;
    }

    /**
     * 流式识别
     */
    public String streamRecognition(Sinks.Many<byte[]> audioSink, Consumer<String> onPartial) {
        logger.info("🔊 开始流式语音识别");

        StringBuilder finalResult = new StringBuilder();
        AtomicBoolean active = new AtomicBoolean(true);

        try {
            Flux<String> textFlux = audioSink.asFlux()
                    .bufferTimeout(100, Duration.ofSeconds(3))  // ✅ 增加到3秒，减少重复识别
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
                            logger.info("语音部分识别结果: {}", t);
                        }
                    })
                    .doFinally(sig -> {
                        active.set(false);
                        logger.info("语音流结束: {}", sig);
                    })
                    .blockLast();

            return KoreanLanguageUtils.convertNumberToKO(finalResult.toString().trim());

        } catch (Exception e) {
            logger.error("OpenAI 韩语流式语音识别失败: {}", e.getMessage(), e);
            return "";
        }
    }
}