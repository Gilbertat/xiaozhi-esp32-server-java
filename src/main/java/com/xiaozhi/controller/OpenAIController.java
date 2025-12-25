package com.xiaozhi.controller;

import com.xiaozhi.utils.HttpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;

/**
 * OpenAI API 透传控制器
 * 直接透传请求到 OpenAI API，不进行任何处理
 *
 * @author xiaozhi
 */
@RestController
@RequestMapping("/api/openai")
@Tag(name = "OpenAI API", description = "OpenAI API透传服务")
public class OpenAIController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(OpenAIController.class);
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String API_KEY = "";

    /**
     * STT - 语音转文字
     * 透传到 OpenAI Audio Transcriptions API
     *
     *
     * @param file 音频文件
     * @return 透传 OpenAI 响应
     */
    @PostMapping("/stt")
    @Operation(summary = "语音转文字 (STT)", description = "透传到 OpenAI Audio Transcriptions API")
    public ResponseEntity<String> stt(
            @RequestParam("file") MultipartFile file) {

        try {
            // 构建 multipart 请求体
            okhttp3.RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getOriginalFilename(),
                            okhttp3.RequestBody.create(file.getBytes(), okhttp3.MediaType.parse(file.getContentType())))
                    .addFormDataPart("model", "gpt-4o-transcribe")
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(OPENAI_BASE_URL + "/audio/transcriptions")
                    .post(requestBody)
                    .addHeader("Authorization", API_KEY)
                    .build();

            // 执行请求
            try (okhttp3.Response response = HttpUtil.client.newCall(request).execute()) {
                String responseBody = response.body().string();
                HttpHeaders headers = new HttpHeaders();

                // 透传响应头
                for (String name : response.headers().names()) {
                    headers.set(name, response.headers().get(name));
                }

                return ResponseEntity.status(response.code())
                        .headers(headers)
                        .body(responseBody);
            }
        } catch (Exception e) {
            logger.error("STT透传失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"STT透传失败: \" + e.getMessage()}");
        }
    }

    /**
     * LLM - 对话生成
     * 透传到 OpenAI Chat Completions API
     *
     * @param requestBody 请求体 (JSON)
     * @return 透传 OpenAI 响应
     */
    @PostMapping("/llm")
    @Operation(summary = "对话生成 (LLM)", description = "透传到 OpenAI Chat Completions API")
    public ResponseEntity<String> llm(
            @RequestBody String requestBody) {

        try {
            okhttp3.RequestBody body = okhttp3.RequestBody.create(
                    requestBody,
                    okhttp3.MediaType.parse("application/json")
            );

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(OPENAI_BASE_URL + "/chat/completions")
                    .post(body)
                    .addHeader("Authorization", API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .build();

            // 执行请求
            try (okhttp3.Response response = HttpUtil.client.newCall(request).execute()) {
                String responseBody = response.body().string();
                HttpHeaders headers = new HttpHeaders();

                // 透传响应头
                for (String name : response.headers().names()) {
                    headers.set(name, response.headers().get(name));
                }

                return ResponseEntity.status(response.code())
                        .headers(headers)
                        .body(responseBody);
            }
        } catch (Exception e) {
            logger.error("LLM透传失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"LLM透传失败: \" + e.getMessage()}");
        }
    }

    /**
     * TTS - 文字转语音
     * 透传到 OpenAI Text-to-Speech API
     *
     * @param requestBody 请求体 (JSON)
     * @return 透传 OpenAI 响应 (音频数据)
     */
    @PostMapping(value = "/tts", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "文字转语音 (TTS)", description = "透传到 OpenAI Text-to-Speech API")
    public ResponseEntity<byte[]> tts(
            @RequestBody String requestBody) {

        try {
            okhttp3.RequestBody body = okhttp3.RequestBody.create(
                    requestBody,
                    okhttp3.MediaType.parse("application/json")
            );

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(OPENAI_BASE_URL + "/audio/speech")
                    .post(body)
                    .addHeader("Authorization", API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .build();

            // 执行请求
            try (okhttp3.Response response = HttpUtil.client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body().string();
                    logger.error("TTS API请求失败: {} - {}", response.code(), errorBody);
                    return ResponseEntity.status(response.code())
                            .body(errorBody.getBytes());
                }

                byte[] responseBody = response.body().bytes();
                HttpHeaders headers = new HttpHeaders();

                // 透传响应头
                for (String name : response.headers().names()) {
                    headers.set(name, response.headers().get(name));
                }

                return ResponseEntity.status(response.code())
                        .headers(headers)
                        .body(responseBody);
            }
        } catch (Exception e) {
            logger.error("TTS透传失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("{\"error\": \"TTS透传失败: " + e.getMessage() + "\"}").getBytes());
        }
    }

    /**
     * Realtime - 实时对话
     * 透传到 OpenAI Realtime API (WebSocket)
     *
     * @param apiKey OpenAI API Key
     * @return WebSocket连接响应
     */
    @GetMapping("/realtime")
    @Operation(summary = "实时对话 (Realtime)", description = "透传到 OpenAI Realtime API")
    public ResponseEntity<String> realtime(
            @RequestHeader("Authorization") String apiKey) {

        try {
            // 构建 WebSocket URL
            String realtimeUrl = "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview-2024-12-17";

            // 返回连接信息，客户端需要自行建立 WebSocket 连接
            String connectionInfo = String.format(
                    "{\"url\": \"%s\", \"headers\": {\"Authorization\": \"%s\", \"OpenAI-Beta\": \"realtime=v1\"}}",
                    realtimeUrl, apiKey
            );

            return ResponseEntity.ok(connectionInfo);
        } catch (Exception e) {
            logger.error("Realtime透传失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Realtime透传失败: \" + e.getMessage()}");
        }
    }
}