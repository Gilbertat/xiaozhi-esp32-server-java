package com.xiaozhi.dialogue.stt.providers;

import com.xiaozhi.dialogue.stt.SttService;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.utils.AudioUtils;
import okhttp3.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Navar 提供的 clova ai stt服务，用于韩语识别增强
 *
 * @author shiyue
 * @version 1.0 2025/12/19 14:58
 */
public class ClovaSTTService implements SttService {

    private static final Logger logger = LoggerFactory.getLogger(ClovaSTTService.class);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OkHttpClient httpClient;
    private final String language = "Kor";
    private final String apiUrl;

    public ClovaSTTService(SysConfig sysConfig) {
        this.apiKey = sysConfig.getApiKey();
        this.baseUrl = sysConfig.getBaseUrl();
        this.model = ""; // clova 未提供模型选项因此使用默认模型去识别
        this.apiUrl = sysConfig.getApiUrl();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getProviderName() {
        return model;
    }

    @Override
    public String recognition(byte[] audioData) {
        try {
            if (audioData == null || audioData.length == 0) {
                return "";
            }

            byte[] wav = AudioUtils.ensureWavFormat(audioData);
            RequestBody body = RequestBody.create(wav, MediaType.parse("application/octet-stream"));

            HttpUrl parsedUrl = HttpUrl.parse(baseUrl + apiUrl);
            if (parsedUrl == null) {
                logger.error("Invalid URL: {}{}", baseUrl, apiUrl);
                return "";
            }

            HttpUrl url = parsedUrl.newBuilder()
                    .addQueryParameter("lang", language)
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("X-NCP-APIGW-API-KEY", apiKey)
                    .addHeader("Content-Type", "application/octet-stream")
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("CLOVA STT 失败: {} {}", response.code(), response.message());
                    return "";
                }

                JSONObject json = new JSONObject(response.body().string());
                String text = json.optString("text", "").trim();
                logger.info("✅ CLOVA STT 结果: {}", text);
                return text;
            }

        } catch (Exception e) {
            logger.error("CLOVA STT 异常", e);
            return "";
        }
    }


    @Override
    public String streamRecognition(Sinks.Many<byte[]> audioSink) {
        return this.streamRecognition(audioSink, partialResult -> {
            logger.info("Clova STT: 获取到实时识别结果：{}", partialResult);
        });
    }

    /**
     * 分片 + 合并（准流式）
     */
    public String streamRecognition(Sinks.Many<byte[]> audioSink,
                                    Consumer<String> onPartial) {

        StringBuilder finalText = new StringBuilder();
        AtomicBoolean active = new AtomicBoolean(true);

        try {
            audioSink.asFlux()
                    .bufferTimeout(100, Duration.ofSeconds(4))
                    .filter(chunks -> !chunks.isEmpty())
                    .flatMap(chunks -> Mono.fromCallable(() -> {

                        int total = chunks.stream().mapToInt(b -> b.length).sum();
                        if (total < 6000) return "";

                        byte[] merged = new byte[total];
                        int pos = 0;
                        for (byte[] c : chunks) {
                            System.arraycopy(c, 0, merged, pos, c.length);
                            pos += c.length;
                        }

                        String text = recognition(merged);
                        if (!text.isEmpty()) {
                            onPartial.accept(text);
                        }
                        return text;
                    }).subscribeOn(Schedulers.boundedElastic()))
                    .takeWhile(v -> active.get())
                    .doOnNext(t -> {
                        if (!t.isEmpty()) {
                            finalText.append(t).append(" ");
                        }
                    })
                    .doFinally(sig -> active.set(false))
                    .blockLast();

            return finalText.toString().trim();

        } catch (Exception e) {
            logger.error("CLOVA STT 流式异常", e);
            return "";
        }
    }

}
