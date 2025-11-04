package com.xiaozhi.dialogue.service;

import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.communication.common.SessionManager;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.service.SysConfigService;

import com.xiaozhi.utils.OpusProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * OpenAI Realtime API服务
 * 提供实时双向语音对话功能
 */
@Service
public class RealtimeService implements org.springframework.beans.factory.DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(RealtimeService.class);
    
    private static final String REALTIME_API_URL = "wss://api.openai.com/v1/realtime";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;
    
    @Resource
    private SessionManager sessionManager;
    
    @Resource
    private SysConfigService configService;
    
    @Resource
    private AudioService audioService;
    
    @Resource
    private OpusProcessor opusProcessor;

    @Resource
    private VadService vadService;
    
    // 存储每个设备的Realtime连接
    private final ConcurrentHashMap<String, RealtimeConnection> realtimeConnections = new ConcurrentHashMap<>();
    
    // 虚拟线程执行器（将在init()中初始化）
    private ExecutorService virtualThreadExecutor;
    
    // 音频处理专用虚拟线程执行器（将在init()中初始化）
    private ExecutorService audioProcessingExecutor;
    
    // 文件I/O专用执行器（将在init()中初始化）
    private ExecutorService fileIOExecutor;


    
    // 音频保存配置
    @Value("${audio.save.path:audio/}")
    private String audioSavePath;
    
    @Value("${audio.save.enabled:true}")
    private boolean audioSaveEnabled;

    // 共享的OkHttpClient实例
    private OkHttpClient sharedClient;


    // 初始化方法
    @PostConstruct
    public void init() {
        // 初始化共享的OkHttpClient
        sharedClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // 实时连接不设读取超时
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(300, TimeUnit.SECONDS) // 添加调用总超时
                .retryOnConnectionFailure(true)
                .build();

        // 初始化执行器
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.audioProcessingExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.fileIOExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "realtime-file-io-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 开始Realtime对话
     */
    public boolean startRealtimeConversation(String sessionId) {
        try {
            // 参数验证
            if (sessionId == null || sessionId.trim().isEmpty()) {
                logger.error("SessionId is null or empty");
                return false;
            }
            
            ChatSession chatSession = sessionManager.getSession(sessionId);
            if (chatSession == null) {
                logger.error("ChatSession not found for sessionId: {}", sessionId);
                return false;
            }
            
            SysDevice device = sessionManager.getDeviceConfig(sessionId);
            if (device == null || device.getRoleId() == null) {
                logger.error("Device or role not configured for sessionId: {}", sessionId);
                return false;
            }
            
            // 获取Realtime配置
            SysConfig realtimeConfig = getRealtimeConfig(device);
            if (realtimeConfig == null) {
                logger.error("Realtime configuration not found for device: {}", device.getDeviceId());
                return false;
            }
            
            // 创建Realtime连接
            RealtimeConnection connection = createRealtimeConnection(sessionId, realtimeConfig);
            if (connection == null) {
                return false;
            }
            
            // 清除旧连接（如果存在）
            RealtimeConnection oldConnection = realtimeConnections.put(sessionId, connection);
            if (oldConnection != null) {
                try {
                    oldConnection.close();
                } catch (Exception e) {
                    logger.error("Failed to close old realtime connection for sessionId: {}", sessionId, e);
                }
            }
            
            logger.info("Realtime conversation started for sessionId: {}", sessionId);
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to start realtime conversation for sessionId: {}", sessionId, e);
            return false;
        }
    }
    
    /**
     * 停止Realtime对话
     */
    public void stopRealtimeConversation(String sessionId) {
        RealtimeConnection connection = realtimeConnections.remove(sessionId);
        if (connection != null) {
            connection.close();
            logger.info("Realtime conversation stopped for sessionId: {}", sessionId);
        }
    }
    
    /**
     * 发送音频数据到OpenAI Realtime
     * 输入的opusData是OPUS编码的音频数据，需要先解码为PCM16
     */
    public void sendAudioData(String sessionId, byte[] opusData) {
        // 参数验证
        if (sessionId == null || sessionId.trim().isEmpty()) {
            logger.error("SessionId is null or empty when sending audio data");
            return;
        }
        
        if (opusData == null || opusData.length == 0) {
            logger.warn("Audio data is null or empty for sessionId: {}", sessionId);
            return;
        }
        
        try {
            RealtimeConnection connection = realtimeConnections.get(sessionId);
            if (connection != null) {
                if (!connection.isConnected()) {
                    // 移除无效连接并重新创建
                    realtimeConnections.remove(sessionId);
                } else {
                    connection.sendAudioData(opusData);
                    return;
                }
            }

            // 启动连接
            if (startRealtimeConversation(sessionId)) {
                // 等待连接建立
                int attempts = 0;
                while (attempts < 10) { // 最多等待5秒
                    connection = realtimeConnections.get(sessionId);
                    if (connection != null && connection.isConnected()) {
                        connection.sendAudioData(opusData);
                        return;
                    }
                    try {
                        Thread.sleep(500); // 等待500ms
                        attempts++;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("Interrupted while waiting for connection for sessionId: {}", sessionId);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to send audio data for sessionId: {}", sessionId, e);
        }
    }
    
    /**
     * 发送文本到OpenAI Realtime
     */
    public void sendTextInput(String sessionId, String text) {
        // 参数验证
        if (sessionId == null || sessionId.trim().isEmpty()) {
            logger.error("SessionId is null or empty when sending text input");
            return;
        }
        
        if (text == null || text.trim().isEmpty()) {
            logger.warn("Text input is null or empty for sessionId: {}", sessionId);
            return;
        }
        
        try {
            RealtimeConnection connection = realtimeConnections.get(sessionId);
            if (connection != null) {
                connection.sendTextInput(text);
            } else {
                logger.warn("No realtime connection found for sessionId: {}", sessionId);
            }
        } catch (Exception e) {
            logger.error("Failed to send text input for sessionId: {}", sessionId, e);
        }
    }
    
    /**
     * 检查是否有活跃的Realtime连接
     */
    public boolean hasActiveRealtimeConnection(String sessionId) {
        RealtimeConnection connection = realtimeConnections.get(sessionId);
        if (null == connection) {
            return startRealtimeConversation(sessionId);
        }
        return connection.isConnected();
    }
    
    /**
     * 处理语音唤醒 - Realtime模式专用
     */
    public void handleWakeWord(ChatSession session, String text) {
        logger.info("Realtime模式检测到唤醒词: \"{}\"", text);
        try {
            String sessionId = session.getSessionId();
            SysDevice device = sessionManager.getDeviceConfig(sessionId);
            if (device == null) {
                logger.warn("Device not found for sessionId: {}", sessionId);
                return;
            }

            // 更新最后活动时间
            sessionManager.updateLastActivity(sessionId);
            
            // 确保Realtime连接已建立
            if (!hasActiveRealtimeConnection(sessionId)) {
                logger.error("Failed to establish realtime connection for wake word processing, sessionId: {}", sessionId);
                return;
            }
            // 反馈设备状态，状态为start使设备停止录音
            audioService.sendStart(session);
            // 直接发送文本到Realtime API
            sendTextInput(sessionId, text);
            
        } catch (Exception e) {
            logger.error("处理Realtime唤醒词失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 清理会话相关资源
     */
    public void cleanupSession(String sessionId) {
        logger.debug("Cleaning up realtime session for sessionId: {}", sessionId);
        
        RealtimeConnection connection = realtimeConnections.remove(sessionId);
        if (connection != null) {
            connection.close();
            logger.info("Realtime conversation stopped and cleaned up for sessionId: {}", sessionId);
        } else {
            logger.debug("No realtime connection found for cleanup, sessionId: {}", sessionId);
        }
        
        // 清理 VAD 会话
        vadService.resetSession(sessionId);
    }
    
    /**
     * 清理所有会话资源
     * 用于服务关闭时的资源清理
     */
    public void cleanupAllSessions() {
        logger.info("Cleaning up all realtime sessions");
        
        for (String sessionId : realtimeConnections.keySet()) {
            RealtimeConnection connection = realtimeConnections.remove(sessionId);
            if (connection != null) {
                connection.close();
                logger.info("Cleaned up realtime connection for sessionId: {}", sessionId);
            }
            // 清理 VAD 会话
            vadService.resetSession(sessionId);
        }
        
        logger.info("All realtime sessions have been cleaned up");
    }
    
    /**
     * 获取最后保存的音频文件路径
     * @param sessionId 会话ID
     * @return 音频文件路径，如果没有保存的文件则返回null
     */
    public String getLastSavedAudioFilePath(String sessionId) {
        RealtimeConnection connection = realtimeConnections.get(sessionId);
        if (connection != null) {
            return connection.getCurrentAudioFilePath();
        }
        return null;
    }
    
    /**
     * 切换到实时模式
     * @param sessionId 会话ID
     * @return 切换是否成功
     */
    public boolean switchToRealtimeMode(String sessionId) {
        logger.info("Switching to Realtime mode for sessionId: {}", sessionId);
        
        // 停止现有的连接（如果存在）
        stopRealtimeConversation(sessionId);
        
        // 启动新的实时对话
        boolean success = startRealtimeConversation(sessionId);
        if (success) {
            logger.info("Successfully switched to Realtime mode for sessionId: {}", sessionId);
        } else {
            logger.error("Failed to switch to Realtime mode for sessionId: {}", sessionId);
        }
        
        return success;
    }
    
    /**
     * 获取Realtime配置
     */
    private SysConfig getRealtimeConfig(SysDevice device) {
        // 查找专门的realtime配置
        SysConfig config = configService.selectConfigByConditions(new SysConfig()
                .setUserId(device.getUserId())
                .setConfigType("realtime")
                .setProvider("openai"));
        
        if (config == null) {
            // 如果没有专门的realtime配置，尝试使用LLM配置
            config = configService.selectConfigByCondition(new SysConfig()
                    .setUserId(device.getUserId())
                    .setConfigType("llm")
                    .setProvider("openai"));
        }
        
        return config;
    }
    
    /**
     * 创建Realtime连接
     */
    private RealtimeConnection createRealtimeConnection(String sessionId, SysConfig config) {
        try {
            String apiKey = config.getApiKey();
            String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : REALTIME_API_URL;
            String model = config.getModelName() != null ? config.getModelName() : "gpt-4o-realtime-preview-2025-06-03";
            String apiUrl = config.getApiUrl();
            
            logger.info("Creating Realtime connection for sessionId: {}, baseUrl: {}, model: {}", sessionId, baseUrl, model);
            
            // 验证配置参数
            if (apiKey == null || apiKey.trim().isEmpty()) {
                logger.error("API key is null or empty for sessionId: {}", sessionId);
                return null;
            }
            
            if (apiUrl == null || apiUrl.trim().isEmpty()) {
                logger.error("API URL is null or empty for sessionId: {}", sessionId);
                return null;
            }
            
            // 检查API密钥格式
            String keyPrefix = apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : apiKey;
            logger.info("Using API key starting with: {} (length: {})", keyPrefix, apiKey.length());
            
            // 构建WebSocket URL
            String wsUrl = baseUrl + apiUrl + "?model=" + model;
            logger.info("WebSocket URL: {}", wsUrl);
            
            Request request = new Request.Builder()
                    .url(wsUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("OpenAI-Beta", "realtime=v1")
                    .build();
            
            RealtimeConnection connection = new RealtimeConnection(sessionId, sharedClient, request);
            
            // 初始化 VAD 会话
            try {
                vadService.initSession(sessionId);
            } catch (Exception e) {
                logger.error("Failed to initialize VAD session for sessionId: {}", sessionId, e);
                return null;
            }
            
            connection.connect();
            
            return connection;
            
        } catch (Exception e) {
            logger.error("Failed to create realtime connection for sessionId: {}", sessionId, e);
            return null;
        }
    }
    
    /**
     * Realtime连接管理类
     */
    private class RealtimeConnection {
        private final String sessionId;
        private final OkHttpClient client;
        private final Request request;
        private WebSocket webSocket;
        private volatile boolean connected = false;
        private volatile int retryAttempts = 0;
        private final AtomicBoolean reconnecting = new AtomicBoolean(false);
        
        // 音频保存相关字段
        private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
        private volatile String currentAudioFilePath = null;
        private final Object audioBufferLock = new Object();
        
        // Opus转换锁，确保OpusProcessor的线程安全
        private final Object opusConversionLock = new Object();
        
        // 跟踪会话状态
        private volatile boolean sessionConfigured = false;
        
        // 确保会话清理只执行一次
        private final AtomicBoolean sessionClosed = new AtomicBoolean(false);
        
        // 跟踪音频缓冲区是否包含有效数据
        private final AtomicBoolean hasAudioDataInBuffer = new AtomicBoolean(false);
        
        // 跟踪是否已有活跃响应
        private final AtomicBoolean hasActiveResponse = new AtomicBoolean(false);
        
        // 跟踪响应创建时间，用于超时处理
        private volatile long responseCreationTime = 0L;
        
        // 音频数据直接发送以保持实时性，不使用队列
        
        public RealtimeConnection(String sessionId, OkHttpClient client, Request request) {
            this.sessionId = sessionId;
            this.client = client;
            this.request = request;
        }
        
        public void connect() {
            logger.info("Attempting to connect Realtime WebSocket for sessionId: {}", sessionId);
            webSocket = client.newWebSocket(request, new RealtimeWebSocketListener());
        }
        
        public boolean isConnected() {
            return connected && webSocket != null;
        }
        
        public void sendAudioData(byte[] audioData) {
            if (!isConnected()) {
                return;
            }
            
            try {
                // 确保 VAD 会话已准备好
                if (!vadService.isSessionInitialized(sessionId)) {
                    vadService.initSession(sessionId);
                }
                
                // 验证和转换音频格式到OpenAI Realtime要求的格式
                VadService.VadResult vadResult = vadService.processAudio(sessionId, audioData);
                if (vadResult == null || vadResult.getStatus() == VadService.VadStatus.ERROR
                        || vadResult.getProcessedData() == null) {
//                logger.info("VAD处理结果为空或出错 - SessionId: {}", sessionId);
                    return;
                }

                if (vadResult.getStatus() == VadService.VadStatus.NO_SPEECH || vadResult.getStatus() == VadService.VadStatus.SPEECH_END) {
                    return;
                }

                byte[] realtimeAudio = convertToRealtimeFormat(vadResult.getProcessedData());
                if (realtimeAudio == null || realtimeAudio.length == 0) {
                    logger.warn("Audio data is empty or conversion failed");
                    return;
                }
                
                // 将音频数据编码为base64
                String base64Audio = Base64.getEncoder().encodeToString(realtimeAudio);
                
                JSONObject message = new JSONObject();
                message.put("type", "input_audio_buffer.append");
                message.put("audio", base64Audio);
                
               var result = webSocket.send(message.toString());
                if (result) {
                    // 标记缓冲区中现在有音频数据
                    hasAudioDataInBuffer.set(true);
                }
            } catch (Exception e) {
                logger.error("Failed to send audio data", e);
            }
        }
        
        public void sendTextInput(String text) {
            if (!isConnected()) {
                logger.debug("Connection not active for sending text input");
                return;
            }
            
            try {
                // 创建用户消息
                JSONObject userMessage = getJsonObject(text);

                boolean result = webSocket.send(userMessage.toString());
                if (!result) {
                    logger.warn("Failed to send text input to WebSocket for sessionId: {}", sessionId);
                    return;
                }
                
                // 检查响应是否超时
                checkResponseTimeout();
                
                // 检查是否已经有活跃响应，避免重复创建
                if (hasActiveResponse.get()) {
                    logger.debug("Active response already in progress for sessionId: {}, skipping response.create", sessionId);
                    return;
                }
                
                // 触发响应生成
                JSONObject responseMessage = new JSONObject()
                        .put("type", "response.create")
                        .put("response", new JSONObject()
                                        .put("modalities", new JSONArray()
                                                .put("audio")
                                                .put("text"))
                                        .put("conversation", "continuation") // 允许多轮
                        );
                result = webSocket.send(responseMessage.toString());
                if (result) {
                    // 标记已有活跃响应
                    hasActiveResponse.set(true);
                    responseCreationTime = System.currentTimeMillis(); // 记录创建时间
                    logger.debug("Response created for sessionId: {}", sessionId);
                } else {
                    logger.warn("Failed to send response.create to WebSocket for sessionId: {}", sessionId);
                }
                
            } catch (Exception e) {
                logger.error("Failed to send text input", e);
            }
        }
        
        public void close() {
            if (sessionClosed.compareAndSet(false, true)) {
                if (webSocket != null) {
                    webSocket.close(1000, "Session ended by server");
                }
                connected = false;
                sessionConfigured = false;
                // 重置音频数据标志、活跃响应标志和时间
                hasAudioDataInBuffer.set(false);
                hasActiveResponse.set(false);
                responseCreationTime = 0L; // 重置时间
                
                // 清理 VAD 会话
                vadService.resetSession(sessionId);
                
                logger.info("Realtime connection closed for sessionId: {}", sessionId);
            } else {
                logger.debug("Realtime connection already closed for sessionId: {}", sessionId);
            }
        }
        
        /**
         * 获取当前音频文件路径
         */
        public String getCurrentAudioFilePath() {
            return currentAudioFilePath;
        }
        
        private class RealtimeWebSocketListener extends WebSocketListener {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                // 将配置任务提交给虚拟线程执行器
                virtualThreadExecutor.execute(() -> {
                    try {
                        connected = true;
                        retryAttempts = 0; // 重置重试次数
                        sessionConfigured = false;
                        // 重置音频数据标志、活跃响应标志和时间
                        hasAudioDataInBuffer.set(false);
                        hasActiveResponse.set(false);
                        responseCreationTime = 0L; // 重置时间
                        logger.info("Realtime WebSocket connected for sessionId: {}", sessionId);
                        
                        // 发送会话配置
                        sendSessionConfiguration();
                    } catch (Exception e) {
                        logger.error("Failed to handle WebSocket onOpen for sessionId: {}", sessionId, e);
                    }
                });
            }
            
            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                // 将消息处理提交给虚拟线程执行器
                virtualThreadExecutor.execute(() -> {
                    try {
                        JSONObject message = new JSONObject(text);
                        String type = message.optString("type");
                        
                        logger.trace("Received realtime message: {} for sessionId: {}", type, sessionId);
                        
                        switch (type) {
                            case "session.created":
                                handleSessionCreated(message);
                                break;
                            case "session.updated":
                                handleSessionUpdated(message);
                                break;
                            case "response.audio.delta":
                                handleAudioDelta(message);
                                break;
                            case "response.text.delta":
                                handleTextDelta(message);
                                break;
                            case "response.done":
                                handleResponseDone(message);
                                break;
                            case "input_audio_buffer.speech_started":
                                handleSpeechStarted();
                                break;
                            case "input_audio_buffer.speech_stopped":
                                handleSpeechStopped();
                                break;
                            case "conversation.item.input_audio_transcription.completed":
                                handleTranscriptionCompleted(message);
                                break;
                            case "error":
                                handleError(message);
                                break;
                            default:
                                // 记录其他类型的消息
                                logger.trace("Unhandled realtime message type: {}", type);
                        }
                        
                    } catch (Exception e) {
                        logger.error("Failed to process realtime message", e);
                    }
                });
            }
            
            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
                // 将失败处理提交给虚拟线程执行器
                virtualThreadExecutor.execute(() -> {
                    try {
                        connected = false;
                        hasActiveResponse.set(false); // 重置活跃响应标志
                        responseCreationTime = 0L; // 重置时间
                        logger.error("Realtime WebSocket failed for sessionId: {}", sessionId, t);
                        if (response != null) {
                            logger.error("Response code: {}, message: {}", response.code(), response.message());
                            try {
                                String responseBody = response.body().string();
                                logger.error("Response body: {}", responseBody);
                            } catch (Exception e) {
                                logger.error("Failed to read response body", e);
                            }
                        }
                        
                        // 尝试重连
                        if (retryAttempts < MAX_RETRY_ATTEMPTS && !reconnecting.get()) {
                            logger.info("Attempting to reconnect for sessionId: {} (attempt {}/{})", 
                                       sessionId, retryAttempts + 1, MAX_RETRY_ATTEMPTS);
                            reconnecting.set(true);
                            CompletableFuture.runAsync(() -> {
                                try {
                                    Thread.sleep(RETRY_DELAY_MS * (retryAttempts + 1)); // 递增延迟
                                    retryAttempts++;
                                    connect();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    reconnecting.set(false);
                                }
                            }, virtualThreadExecutor);
                        } else {
                            logger.warn("Max retry attempts reached for sessionId: {}", sessionId);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to handle WebSocket failure for sessionId: {}", sessionId, e);
                    }
                });
            }
            
            @Override
            public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                // 将关闭处理提交给虚拟线程执行器
                virtualThreadExecutor.execute(() -> {
                    try {
                        connected = false;
                        sessionConfigured = false;
                        hasAudioDataInBuffer.set(false); // 重置音频数据标志
                        hasActiveResponse.set(false); // 重置活跃响应标志
                        responseCreationTime = 0L; // 重置时间
                        logger.info("Realtime WebSocket closing for sessionId: {}, code: {}, reason: {}", 
                                   sessionId, code, reason);
                    } catch (Exception e) {
                        logger.error("Failed to handle WebSocket closing for sessionId: {}", sessionId, e);
                    }
                });
            }
            
            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                // 将关闭处理提交给虚拟线程执行器
                virtualThreadExecutor.execute(() -> {
                    try {
                        connected = false;
                        sessionConfigured = false;
                        if (!sessionClosed.get()) {
                            logger.info("Realtime WebSocket closed for sessionId: {}, code: {}, reason: {}", 
                                       sessionId, code, reason);
                        }
                        // 确保会话关闭（如果还没有关闭的话）
                        close();
                    } catch (Exception e) {
                        logger.error("Failed to handle WebSocket closed for sessionId: {}", sessionId, e);
                    }
                });
            }
        }
        
        private void sendSessionConfiguration() {
            if (!isConnected()) {
                logger.warn("Cannot send session configuration, connection not active for sessionId: {}", sessionId);
                return;
            }
            
            try {
                JSONObject sessionUpdate = new JSONObject();
                sessionUpdate.put("type", "session.update");
                
                JSONObject session = new JSONObject();
                session.put("modalities", new JSONArray().put("text").put("audio"));
                session.put("instructions", "你是一个友好的AI助手，请友善的与用户对话。");
                session.put("voice", "alloy");
                session.put("input_audio_format", "pcm16");
                session.put("output_audio_format", "pcm16");
                // 只设置输入采样率，OpenAI会自动处理输出采样率
                session.put("input_audio_transcription", new JSONObject().put("model", "whisper-1"));
                session.put("turn_detection", new JSONObject().put("type", "server_vad"));
                session.put("temperature", 0.8);
                session.put("max_response_output_tokens", 4096);
                
                sessionUpdate.put("session", session);
                
                boolean result = webSocket.send(sessionUpdate.toString());
                if (!result) {
                    logger.error("Failed to send session configuration for sessionId: {}", sessionId);
                } else {
                    sessionConfigured = true;
                    logger.debug("Session configuration sent successfully for sessionId: {}", sessionId);
                }
                
            } catch (Exception e) {
                logger.error("Failed to send session configuration", e);
            }
        }
        
        private void handleAudioDelta(JSONObject message) {
            try {
                String base64Audio = message.optString("delta");
                if (base64Audio != null && !base64Audio.isEmpty()) {
                    byte[] pcmAudioData = Base64.getDecoder().decode(base64Audio);
                    logger.trace("Received audio delta from OpenAI: {} bytes PCM data", pcmAudioData.length);
                    
                    // 更新最后活动时间
                    sessionManager.updateLastActivity(sessionId);
                    
                    // 使用虚拟线程异步保存音频数据到缓冲区
                    if (audioSaveEnabled) {
                        CompletableFuture.runAsync(() -> {
                            try {
                                saveAudioToBuffer(pcmAudioData);
                            } catch (Throwable t) {
                                logger.warn("Audio buffer save failed for sessionId: {}", sessionId, t);
                            }
                        }, audioProcessingExecutor).exceptionally(throwable -> {
                            logger.warn("Audio buffer task failed for sessionId: {}", sessionId, throwable);
                            return null;
                        });
                    }
                    
                    // 使用虚拟线程异步处理音频转换和发送
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return convertPcmToOpus(pcmAudioData);
                        } catch (Throwable t) {  // 捕获所有错误，包括AssertionError
                            logger.warn("Audio conversion failed for sessionId: {}", sessionId, t);
                            return null;
                        }
                    }, audioProcessingExecutor)
                        .thenAcceptAsync(opusData -> {
                            if (opusData != null && opusData.length > 0) {
                                ChatSession chatSession = sessionManager.getSession(sessionId);
                                if (chatSession != null) {
                                    // 告诉设备停止录音
                                    audioService.sendStart(chatSession);
                                    audioService.sendRealTimeAudioChunk(chatSession, opusData);
                                }
                            }
                        }, virtualThreadExecutor)
                        .exceptionally(throwable -> {
                            logger.warn("Audio processing failed for sessionId: {}", sessionId, throwable);
                            return null;
                        });
                } else {
                    logger.debug("Received audio delta with empty data");
                }
            } catch (Exception e) {
                logger.error("Failed to handle audio delta for sessionId: {}", sessionId, e);
            }
        }
        
        private void handleTextDelta(JSONObject message) {
            try {
                String textDelta = message.optString("delta");
                if (textDelta != null && !textDelta.isEmpty()) {
                    logger.debug("Received text delta for sessionId: {}, text: {}", sessionId, textDelta);
                    
                    // 更新最后活动时间
                    sessionManager.updateLastActivity(sessionId);
                    
                    // 只发送文本增量到客户端，跳过TTS句子开始消息以确保音频准确性
                    ChatSession chatSession = sessionManager.getSession(sessionId);
                    if (chatSession != null) {
                        audioService.sendRealtimeTextDelta(chatSession, textDelta);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to handle text delta for sessionId: {}", sessionId, e);
            }
        }
        
        private void handleResponseDone(JSONObject message) {
            logger.info("Realtime response completed for sessionId: {}", sessionId);
            
            // 更新最后活动时间
            sessionManager.updateLastActivity(sessionId);
            
            // 重置活跃响应标志，允许创建新响应
            hasActiveResponse.set(false);
            responseCreationTime = 0L; // 重置时间
            
            // 使用虚拟线程异步保存音频文件
            if (audioSaveEnabled) {
                CompletableFuture.supplyAsync(this::saveAudioBufferToFile, fileIOExecutor)
                    .thenAcceptAsync(savedFilePath -> {
                        if (savedFilePath != null) {
                            currentAudioFilePath = savedFilePath;
                            logger.info("Audio file saved successfully: {}", savedFilePath);
                        }
                    }, virtualThreadExecutor)
                    .exceptionally(throwable -> {
                        logger.error("Failed to save audio file asynchronously for sessionId: {}", sessionId, throwable);
                        return null;
                    });
            }

            // 发送响应完成通知
            ChatSession chatSession = sessionManager.getSession(sessionId);
            if (chatSession != null) {
                // 仅发送响应完成通知，跳过TTS停止消息以确保音频准确性
                audioService.sendRealtimeResponseComplete(chatSession);
                
                // 可以添加其他响应完成后的处理逻辑
                logger.debug("Response completion notification sent for sessionId: {}", sessionId);
            }
        }
        
        private void handleSpeechStarted() {
            logger.debug("🎤 Speech started for sessionId: {}", sessionId);
        }
        
        private void handleSpeechStopped() {
            logger.debug("Speech stopped detected from server for sessionId: {}", sessionId);
            // 检查响应是否超时
            checkResponseTimeout();
            
            // 服务器通知语音停止，如果本地缓冲区有数据则提交
            // 但仅在没有活跃响应的时候才创建新响应
            if (hasAudioDataInBuffer.get() && !hasActiveResponse.get()) {
                // 发送提交请求
                JSONObject commitMessage = new JSONObject();
                commitMessage.put("type", "input_audio_buffer.commit");
                boolean result = webSocket.send(commitMessage.toString());
                if (result) {
                    // 重置音频数据标志
                    hasAudioDataInBuffer.set(false);
                    
                    // 创建响应
                    JSONObject responseMessage = new JSONObject();
                    responseMessage.put("type", "response.create");
                    
                    boolean responseResult = webSocket.send(responseMessage.toString());
                    if (responseResult) {
                        // 标记已有活跃响应
                        hasActiveResponse.set(true);
                        responseCreationTime = System.currentTimeMillis(); // 记录创建时间
                        
                        // 跳过TTS开始消息以确保音频准确性
                        logger.debug("Response created after server speech stop for sessionId: {}", sessionId);
                    }
                }
            }
        }
        
        private void handleTranscriptionCompleted(JSONObject message) {
            try {
                String transcript = message.optString("transcript");
                if (transcript != null && !transcript.isEmpty()) {
                    logger.info("Transcription completed for sessionId: {}, text: {}", sessionId, transcript);
                    
                    // 更新最后活动时间
                    sessionManager.updateLastActivity(sessionId);
                    
                    // 发送转录文本到客户端
                    ChatSession chatSession = sessionManager.getSession(sessionId);
                    if (chatSession != null) {
                        audioService.sendTranscriptionResult(chatSession, transcript);
                        
                        // 触发后续处理，例如表情符号处理等
                        // (如果需要的话，可以在这里添加更多处理逻辑)
                        logger.debug("Transcription sent to client for sessionId: {}", sessionId);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to handle transcription for sessionId: {}", sessionId, e);
            }
        }
        
        private void handleError(JSONObject message) {
            String errorMessage = message.optString("error", "null");
            String errorType = message.optString("type", "unknown");
            
            if (errorMessage != null && !errorMessage.equals("null")) {
                logger.error("Realtime API error for sessionId: {}, type: {}, error: {}", 
                           sessionId, errorType, errorMessage);
            } else {
                logger.error("Realtime API error for sessionId: {}, message: {}", sessionId, message);
            }
            
            // 重置活跃响应标志，以防错误是由于响应冲突引起的
            hasActiveResponse.set(false);
            responseCreationTime = 0L; // 重置时间
            
            // 尝试重新配置会话
            if (connected) {
                sendSessionConfiguration();
            }
        }
        
        private void handleSessionCreated(JSONObject message) {
            logger.debug("Realtime session created for sessionId: {}", sessionId);
            sessionConfigured = true;
            hasActiveResponse.set(false); // 重置活跃响应标志
            responseCreationTime = 0L; // 重置时间
            
            // 更新最后活动时间
            sessionManager.updateLastActivity(sessionId);
        }
        
        private void handleSessionUpdated(JSONObject message) {
            logger.debug("Realtime session configuration updated for sessionId: {}", sessionId);
            hasActiveResponse.set(false); // 重置活跃响应标志
            responseCreationTime = 0L; // 重置时间
            
            // 更新最后活动时间
            sessionManager.updateLastActivity(sessionId);
        }
        
        /**
         * 转换音频格式到OpenAI Realtime API要求的格式
         * 输入：OPUS编码数据
         * 输出：mono PCM16 at 24kHz
         */
        private byte[] convertToRealtimeFormat(byte[] opusData) {
            if (opusData == null || opusData.length == 0) {
                logger.trace("Empty OPUS data received");
                return null;
            }
            
            try {
                // 检查PCM16数据长度是否为偶数（每样本2字节）
                if (opusData.length % 2 != 0) {
                    logger.warn("Invalid PCM16 data length: {}, truncating to even length", opusData.length);
                    // 截断到偶数长度
                    byte[] evenLengthData = new byte[opusData.length - 1];
                    System.arraycopy(opusData, 0, evenLengthData, 0, evenLengthData.length);
                    opusData = evenLengthData;
                }
                
                // 第二步：采样率转换 16kHz -> 24kHz
                return upsampleTo24kHz(opusData);
                
            } catch (Exception e) {
                logger.error("Failed to convert OPUS to Realtime format", e);
                return null;
            }
        }
        
        /**
         * 将16kHz PCM16数据上采样到24kHz
         * 使用线性插值算法进行更高质量的采样率转换
         */
        private byte[] upsampleTo24kHz(byte[] pcm16Data) {
            if (pcm16Data == null || pcm16Data.length == 0) {
                return null;
            }
            
            try {
                // 验证数据长度为偶数
                if (pcm16Data.length % 2 != 0) {
                    logger.warn("Invalid PCM16 data length for upsampling: {}", pcm16Data.length);
                    return null;
                }
                
                int inputSampleCount = pcm16Data.length / 2; // 16位PCM，每样本2字节
                // 从16kHz到24kHz: 24000 / 16000 = 1.5倍采样点
                int outputSampleCount = (int) Math.ceil(inputSampleCount * 1.5); // 使用ceil确保不会丢失数据
                
                if (outputSampleCount == 0) {
                    return null;
                }
                
                byte[] outputAudio = new byte[outputSampleCount * 2];
                
                for (int i = 0; i < outputSampleCount; i++) {
                    // 目标索引对应的源索引
                    double sourceIndex = i / 1.5;
                    int index1 = (int) Math.floor(sourceIndex);
                    int index2 = Math.min(index1 + 1, inputSampleCount - 1);
                    
                    // 预防越界
                    if (index1 >= inputSampleCount) {
                        index1 = inputSampleCount - 1;
                    }
                    
                    // 获取两个样本点的值（16位有符号小端序）
                    short sample1 = (short) ((pcm16Data[index1 * 2] & 0xFF) | (pcm16Data[index1 * 2 + 1] << 8));
                    short sample2 = (short) ((pcm16Data[index2 * 2] & 0xFF) | (pcm16Data[index2 * 2 + 1] << 8));
                    
                    // 线性插值
                    double fraction = sourceIndex - index1;
                    short interpolatedSample = (short) (sample1 + (sample2 - sample1) * fraction);
                    
                    // 写入输出数组（16位有符号小端序）
                    outputAudio[i * 2] = (byte) (interpolatedSample & 0xFF);
                    outputAudio[i * 2 + 1] = (byte) ((interpolatedSample >> 8) & 0xFF);
                }
                
                logger.trace("Audio upsampled: 16kHz->24kHz, {} samples -> {} samples", 
                    inputSampleCount, outputSampleCount);
                return outputAudio;
                
            } catch (Exception e) {
                logger.error("Failed to upsample audio to 24kHz", e);
                return null;
            }
        }
        
        /**
         * 将OpenAI返回的PCM16音频转换为OPUS格式发送给设备
         * OpenAI返回的PCM可能是24kHz，需要先下采样到16kHz再编码为OPUS
         */
        private byte[] convertPcmToOpus(byte[] pcmData) {
            if (pcmData == null || pcmData.length == 0) {
                return null;
            }
            
            try {
                // 检查PCM16数据长度是否为偶数（每样本2字节）
                if (pcmData.length % 2 != 0) {
                    // 截断到偶数长度
                    byte[] evenLengthData = new byte[pcmData.length - 1];
                    System.arraycopy(pcmData, 0, evenLengthData, 0, evenLengthData.length);
                    pcmData = evenLengthData;
                }
                
                if (pcmData.length == 0) {
                    return null;
                }
                
                // OpenAI返回24kHz PCM，需要下采样到16kHz
                byte[] pcm16kHz = downsampleTo16kHz(pcmData);
                
                if (pcm16kHz == null || pcm16kHz.length == 0) {
                    return null;
                }
                
                // 将PCM16编码为OPUS - 使用锁保护，防止并发访问OpusProcessor
                List<byte[]> opusFramesList;
                synchronized (opusConversionLock) {
                    opusFramesList = opusProcessor.pcmToOpus(sessionId, pcm16kHz, false);
                }
                
                if (opusFramesList != null && !opusFramesList.isEmpty()) {
                    // 将所有OPUS帧合并为一个数组
                    int totalLength = opusFramesList.stream().mapToInt(frame -> frame.length).sum();
                    if (totalLength <= 0) {
                        return null;
                    }
                    
                    byte[] opusData = new byte[totalLength];
                    int offset = 0;
                    for (byte[] frame : opusFramesList) {
                        if (frame != null && frame.length > 0) {
                            System.arraycopy(frame, 0, opusData, offset, frame.length);
                            offset += frame.length;
                        }
                    }
                    return opusData;
                }
                
                return null;
                
            } catch (Throwable t) {  // 捕获所有错误，包括AssertionError
                logger.warn("PCM to OPUS conversion failed for sessionId: {}", sessionId, t);
                return null;
            }
        }
        
        /**
         * 从24kHz下采样到16kHz
         * 使用更精确的下采样算法
         */
        private byte[] downsampleTo16kHz(byte[] pcmData) {
            if (pcmData == null || pcmData.length == 0) {
                return null;
            }
            
            try {
                if (pcmData.length % 2 != 0) {
                    logger.warn("Invalid PCM data length for downsampling: {}", pcmData.length);
                    return null;
                }
                
                int inputSampleCount = pcmData.length / 2; // 16位PCM，每样本2字节
                // 从24kHz到16kHz: 16000 / 24000 = 2/3倍采样点
                int outputSampleCount = (int) (inputSampleCount * 2.0 / 3.0);
                
                if (outputSampleCount == 0) {
                    logger.warn("Downsampling would result in zero samples, returning original data");
                    return pcmData;
                }
                
                byte[] outputAudio = new byte[outputSampleCount * 2];
                
                // 使用更精确的下采样: 每3个24kHz样本中取2个16kHz样本
                for (int i = 0; i < outputSampleCount; i++) {
                    // 计算在原始24kHz数据中的位置
                    int sourceIndex = (int) (i * 1.5); // 1.5 = 24000/16000
                    if (sourceIndex >= inputSampleCount) {
                        sourceIndex = inputSampleCount - 1; // 防止越界
                    }
                    
                    // 复制样本（16位小端序）
                    outputAudio[i * 2] = pcmData[sourceIndex * 2];
                    outputAudio[i * 2 + 1] = pcmData[sourceIndex * 2 + 1];
                }
                
                logger.trace("Audio downsampled: 24kHz->16kHz, {} samples -> {} samples", 
                    inputSampleCount, outputSampleCount);
                return outputAudio;
                
            } catch (Exception e) {
                logger.error("Failed to downsample audio to 16kHz", e);
                return pcmData; // 失败时返回原始数据
            }
        }
        
        /**
         * 初始化音频文件保存（线程安全）
         * 创建新的音频文件并返回文件路径
         * 注意：此方法应在audioBufferLock同步块内调用
         */
        private String initializeAudioFile() {
            try {
                java.nio.file.Path audioDir = java.nio.file.Paths.get(audioSavePath);
                if (!java.nio.file.Files.exists(audioDir)) {
                    java.nio.file.Files.createDirectories(audioDir);
                }
                
                // 生成唯一的文件名，使用时间戳和sessionId
                String timestamp = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
                String fileName = String.format("realtime_%s_%s.wav", sessionId, timestamp);
                String filePath = audioSavePath + fileName;
                
                logger.debug("Initialized audio file for saving: {}", filePath);
                return filePath;
                
            } catch (Exception e) {
                logger.error("Failed to initialize audio file for sessionId: {}", sessionId, e);
                return null;
            }
        }
        
        /**
         * 初始化音频文件保存使用临时文件（线程安全）
         * 创建新的音频临时文件并返回文件路径
         * 注意：此方法应在audioBufferLock同步块内调用
         */
        private java.nio.file.Path initializeTempAudioFile() {
            try {
                // 生成唯一的临时文件名
                String tempFileNamePrefix = String.format("realtime_%s_", sessionId);
                
                java.nio.file.Path tempFile = Files.createTempFile(tempFileNamePrefix, ".wav");
                
                logger.debug("Initialized temporary audio file for saving: {}", tempFile.toString());
                return tempFile;
                
            } catch (Exception e) {
                logger.error("Failed to initialize temporary audio file for sessionId: {}", sessionId, e);
                return null;
            }
        }
        
        /**
         * 保存音频数据到缓冲区（线程安全）
         */
        private void saveAudioToBuffer(byte[] pcmData) {
            if (pcmData != null && pcmData.length > 0) {
                synchronized (audioBufferLock) {
                    try {
                        audioBuffer.write(pcmData);
                        logger.trace("Added {} bytes to audio buffer for sessionId: {}", pcmData.length, sessionId);
                    } catch (IOException e) {
                        logger.error("Failed to write audio data to buffer for sessionId: {}", sessionId, e);
                    }
                }
            }
        }
        
        /**
         * 将缓冲区中的音频数据保存为WAV文件（线程安全）
         * @return 保存的文件路径，如果保存失败返回null
         */
        private String saveAudioBufferToFile() {
            synchronized (audioBufferLock) {
                if (audioBuffer.size() == 0) {
                    logger.debug("Audio buffer is empty, nothing to save for sessionId: {}", sessionId);
                    return null;
                }
                
                java.nio.file.Path tempFile = null;
                try {
                    tempFile = initializeTempAudioFile();
                    if (tempFile == null) {
                        return null;
                    }
                    
                    // 获取音频数据的副本，避免在文件写入过程中被修改
                    byte[] audioData = audioBuffer.toByteArray();
                    
                    // 创建WAV文件头 - 使用24kHz采样率
                    byte[] wavHeader = createWavHeader(audioData.length, 24000, 16, 1); // 24kHz, 16-bit, mono
                    
                    // 使用try-with-resources写入临时文件
                    try {
                        Files.write(tempFile, wavHeader, StandardOpenOption.WRITE);
                        Files.write(tempFile, audioData, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                    } catch (IOException e) {
                        logger.error("Failed to write to temporary audio file for sessionId: {}", sessionId, e);
                        return null;
                    }
                    
                    // 生成最终文件路径
                    String filePath = initializeAudioFile();
                    if (filePath == null) {
                        return null;
                    }
                    
                    // 将临时文件移动到最终位置
                    java.nio.file.Path finalPath = java.nio.file.Paths.get(filePath);
                    java.nio.file.Files.move(tempFile, finalPath);
                    
                    logger.debug("Successfully saved audio file: {} ({} bytes audio data)", filePath, audioData.length);
                    
                    // 清空缓冲区
                    audioBuffer.reset();
                    
                    return filePath;
                    
                } catch (Exception e) {
                    logger.error("Failed to save audio buffer to file for sessionId: {}", sessionId, e);
                    return null;
                } finally {
                    // 确保临时文件被删除，防止文件句柄泄漏
                    if (tempFile != null && Files.exists(tempFile)) {
                        try {
                            Files.deleteIfExists(tempFile);
                        } catch (Exception e) {
                            logger.warn("Failed to delete temporary audio file: {} for sessionId: {}", tempFile.toString(), sessionId, e);
                        }
                    }
                }
            }
        }
        
        /**
         * 创建WAV文件头
         */
        private byte[] createWavHeader(int audioDataLength, int sampleRate, int bitsPerSample, int channels) {
            byte[] header = new byte[44];
            
            // RIFF header
            header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
            int fileSize = audioDataLength + 36;
            header[4] = (byte) (fileSize & 0xff);
            header[5] = (byte) ((fileSize >> 8) & 0xff);
            header[6] = (byte) ((fileSize >> 16) & 0xff);
            header[7] = (byte) ((fileSize >> 24) & 0xff);
            
            // WAVE header
            header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
            
            // fmt subchunk
            header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; // subchunk1 size
            header[20] = 1; header[21] = 0; // audio format (PCM)
            header[22] = (byte) channels; header[23] = 0; // number of channels
            
            // sample rate
            header[24] = (byte) (sampleRate & 0xff);
            header[25] = (byte) ((sampleRate >> 8) & 0xff);
            header[26] = (byte) ((sampleRate >> 16) & 0xff);
            header[27] = (byte) ((sampleRate >> 24) & 0xff);
            
            // byte rate
            int byteRate = sampleRate * channels * bitsPerSample / 8;
            header[28] = (byte) (byteRate & 0xff);
            header[29] = (byte) ((byteRate >> 8) & 0xff);
            header[30] = (byte) ((byteRate >> 16) & 0xff);
            header[31] = (byte) ((byteRate >> 24) & 0xff);
            
            // block align
            int blockAlign = channels * bitsPerSample / 8;
            header[32] = (byte) (blockAlign & 0xff);
            header[33] = (byte) ((blockAlign >> 8) & 0xff);
            
            // bits per sample
            header[34] = (byte) (bitsPerSample & 0xff);
            header[35] = (byte) ((bitsPerSample >> 8) & 0xff);
            
            // data subchunk
            header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
            header[40] = (byte) (audioDataLength & 0xff);
            header[41] = (byte) ((audioDataLength >> 8) & 0xff);
            header[42] = (byte) ((audioDataLength >> 16) & 0xff);
            header[43] = (byte) ((audioDataLength >> 24) & 0xff);
            
            return header;
        }
        
        /**
         * 检查响应是否超时，如果超时则重置活跃响应状态
         */
        private void checkResponseTimeout() {
            if (hasActiveResponse.get() && responseCreationTime > 0) {
                long currentTime = System.currentTimeMillis();
                // 如果响应创建时间超过60秒，则认为超时
                if (currentTime - responseCreationTime > 60000) { // 60秒超时
                    logger.warn("Response timeout detected for sessionId: {}, resetting active response flag", sessionId);
                    hasActiveResponse.set(false);
                    responseCreationTime = 0L;
                }
            }
        }
        

    }

    @NotNull
    private JSONObject getJsonObject(String text) {
        JSONObject userMessage = new JSONObject();
        userMessage.put("type", "conversation.item.create");

        JSONObject item = new JSONObject();
        item.put("type", "message");
        item.put("role", "user");

        JSONArray content = new JSONArray();
        JSONObject textContent = new JSONObject();
        textContent.put("type", "input_text");
        textContent.put("text", text);
        content.put(textContent);

        item.put("content", content);
        userMessage.put("item", item);
        return userMessage;
    }

    private static class Message {
        /**
         * 角色: assistant / user
         */
        public String role;

        /**
         * text + transcript
         */
        public List<String> texts;

        /**
         * 音频/图片等资源
         */
        public List<Media> media;

        public Message(String role) {
            this.role = role;
        }

        {
            texts = new ArrayList<>();
            media = new ArrayList<>();
        }

        public List<String> getTexts() {
            return texts;
        }
    }

    private static class Media {
        /**
         * 类型：audio / image / video / other
         */
        public String type;

        /**
         * 文件URL
         */
        public String url;

        /**
         * 文件格式  pcm16 / mp3 / wav / png / jpeg / etc
         */
        public String format;

        public Media(String type, String url, String format) {
            this.type = type;
            this.url = url;
            this.format = format;
        }
    }

    /**
     * 解析实时对话的响应数据
     */
    private static List<Message> parseRealtimeResponse(JSONObject response) {
        List<Message> result = new ArrayList<>();
        JSONArray outputArr = response.optJSONArray("output");
        if (outputArr == null) return result;

        for (int i = 0; i < outputArr.length(); i++) {
            JSONObject outputObj = outputArr.getJSONObject(i);
            if (!"message".equals(outputObj.optString("type"))) continue;

            String role = outputObj.optString("role", "assistant");
            JSONArray contentArr = outputObj.optJSONArray("content");

            Message msg = new Message(role);

            if (contentArr != null) {
                for (int j = 0; j < contentArr.length(); j++) {
                    JSONObject content = contentArr.getJSONObject(j);
                    String type = content.optString("type");

                    // 文本类
                    if (content.has("text")) {
                        msg.texts.add(content.getString("text"));
                    }
                    if (content.has("transcript")) {
                        msg.texts.add(content.getString("transcript"));
                    }

                    // 媒体类
                    if (content.has("url")) {
                        String url = content.getString("url");
                        String format = content.optString("format", "unknown");
                        msg.media.add(new Media(type, url, format));
                    }
                }
            }

            result.add(msg);
        }
        return result;
    }
    
    /**
     * Spring容器销毁时的清理方法
     */
    @Override
    public void destroy() throws Exception {
        logger.info("Destroying RealtimeService and cleaning up all resources");
        cleanupAllSessions();
        
        // 使用@PreDestroy方法进行清理
        shutdownExecutors();
        
        logger.info("RealtimeService destroyed successfully");
    }
    
    /**
     * 使用@PreDestroy注解进行优雅关闭
     */
    @PreDestroy
    public void preDestroy() {
        logger.info("Pre-destroy called for RealtimeService, initiating graceful shutdown");
        cleanupAllSessions();
        shutdownExecutors();
        
        // 关闭共享的OkHttpClient
        if (sharedClient != null) {
            try {
                // 关闭Dispatcher中的线程池
                sharedClient.dispatcher().executorService().shutdown();
                try {
                    if (!sharedClient.dispatcher().executorService().awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warn("Dispatcher executor did not terminate in time, forcing shutdown");
                        sharedClient.dispatcher().executorService().shutdownNow();
                    }
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while waiting for dispatcher shutdown");
                    Thread.currentThread().interrupt();
                    sharedClient.dispatcher().executorService().shutdownNow();
                }
                
                // 关闭连接池
                sharedClient.connectionPool().evictAll();
                
                logger.info("Shared OkHttpClient resources cleaned up");
            } catch (Exception e) {
                logger.error("Error during OkHttpClient cleanup", e);
            }
        }
        
        logger.info("RealtimeService gracefully shutdown completed");
    }
    
    /**
     * 关闭所有执行器服务
     */
    private void shutdownExecutors() {
        logger.debug("Shutting down executors");
        
        shutdownExecutorService(virtualThreadExecutor, "virtualThreadExecutor");
        shutdownExecutorService(audioProcessingExecutor, "audioProcessingExecutor");
        shutdownExecutorService(fileIOExecutor, "fileIOExecutor");
    }
    
    /**
     * 安全关闭执行器服务
     */
    private void shutdownExecutorService(ExecutorService executor, String executorName) {
        if (executor != null) {
            logger.debug("Shutting down {}...", executorName);
            executor.shutdown(); // 禁止提交新任务
            
            try {
                // 等待最多10秒让现有任务完成
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warn("{} did not terminate in time, forcing shutdown", executorName);
                    executor.shutdownNow(); // 强制取消所有挂起的任务
                    
                    // 再等5秒让强制关闭完成
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.error("{} failed to terminate after forced shutdown", executorName);
                    }
                } else {
                    logger.debug("{} terminated successfully", executorName);
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for {} to terminate", executorName);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}