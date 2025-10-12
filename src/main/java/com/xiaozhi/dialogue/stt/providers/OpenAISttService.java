package com.xiaozhi.dialogue.stt.providers;

import com.xiaozhi.dialogue.stt.SttService;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.utils.AudioUtils;
import com.xiaozhi.utils.KoreanNumberConverter;
import okhttp3.*;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;


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

    private final OpenAIStreamSttEngine STT_ENGINE;

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

        this.STT_ENGINE = new OpenAIStreamSttEngine(config);
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
        return STT_ENGINE.recognition(audioData);
    }

    @Override
    public String streamRecognition(Sinks.Many<byte[]> audioSink) {
        return STT_ENGINE.streamRecognition(audioSink, partialResult -> {
            logger.info("OpenAI STT: 获取到实时识别结果：{}", partialResult);
        });
    }

}