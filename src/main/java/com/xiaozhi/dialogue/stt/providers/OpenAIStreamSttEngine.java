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
import java.util.stream.Collectors;

public class OpenAIStreamSttEngine {

    private static final Logger logger = LoggerFactory.getLogger(OpenAIStreamSttEngine.class);

    private final String baseUrl;
    private final String apiPath;
    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;

    // ✅ 缓存上次检测语言，减少重复调用 Python
    private String cachedLanguage = null;

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

            // ✅ 使用 Whisper Python 检测语言（仅第一次）
            if (cachedLanguage == null) {
                cachedLanguage = detectLanguageWithPython(tempFile);
                logger.info("Whisper语言检测结果: {}", cachedLanguage);
            }

            // ✅ 若检测结果不是韩语，则直接忽略
            if (!"ko".equalsIgnoreCase(cachedLanguage)) {
                logger.info("非韩语语音（检测结果：{}），忽略此次识别", cachedLanguage);
                return "";
            }

            // ✅ 执行主识别（韩语）
            String mainResult = doRecognition(tempFile, true, model);

            // 若主识别失败，执行回退
            if (mainResult.isEmpty()) {
                logger.warn("主模型识别失败，尝试 Whisper 回退模型");
                mainResult = tryFallbackWhisper(tempFile);
            }

            if (mainResult.isEmpty()) {
                logger.warn("所有识别尝试均失败");
                return "";
            }

            JSONObject json = new JSONObject(mainResult);
            String text = json.optString("text", "").trim();

            if (isKoreanText(text)) {
                logger.info("✅ 韩语识别成功: {}", text);
                return KoreanLanguageUtils.convertNumberToKO(text);
            } else {
                logger.debug("识别结果非韩语内容，丢弃: {}", text);
                return "";
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
     * whisper-large-v3 回退
     */
    private String tryFallbackWhisper(File audioFile) {
        try {
            String fallbackModel = "whisper-large-v3";
            logger.info("使用回退模型: {}", fallbackModel);
            return doRecognition(audioFile, true, fallbackModel);
        } catch (Exception e) {
            logger.error("Whisper 回退识别失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 调用 Python 脚本进行 Whisper 语言检测
     */
    private String detectLanguageWithPython(File audioFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "python3", "detect_lang.py", audioFile.getAbsolutePath());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining()).trim();
            }

            int exitCode = process.waitFor();
            if (exitCode != 0 || output.isEmpty()) {
                logger.warn("语言检测失败，返回unknown");
                return "unknown";
            }

            logger.info("检测到语言: {}", output);
            return output;

        } catch (Exception e) {
            logger.error("调用 Python 语言检测失败: {}", e.getMessage());
            return "unknown";
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
     * 流式识别
     */
    public String streamRecognition(Sinks.Many<byte[]> audioSink, java.util.function.Consumer<String> onPartial) {
        logger.info("🔊 开始韩语专用流式语音识别");

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
                        cachedLanguage = null; // ✅ 重置语言缓存
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