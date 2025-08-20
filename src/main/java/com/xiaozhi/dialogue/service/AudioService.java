package com.xiaozhi.dialogue.service;

import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.communication.common.SessionManager;
import com.xiaozhi.utils.AudioUtils;
import com.xiaozhi.utils.OpusProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 音频服务，负责处理音频的流式和非流式发送
 */
@Service
public class AudioService {
    private static final Logger logger = LoggerFactory.getLogger(AudioService.class);

    // 帧发送时间间隔略小于OPUS_FRAME_DURATION_MS，避免因某些调度原因，导致没能在规定时间内发送，设备出现杂音
    private static final long OPUS_FRAME_SEND_INTERVAL_MS = AudioUtils.OPUS_FRAME_DURATION_MS;
    
    // 预缓冲帧数量
    private static final int PRE_BUFFER_FRAMES = 3;

    // 仅播放文本的 Sleep 时长
    private static final long ONLY_TEXT_SLEEP_TIME_MS = 1000;

    @Autowired
    private OpusProcessor opusProcessor;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private MessageService messageService;

    // 使用虚拟线程池处理定时任务
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(),
            Thread.ofVirtual().name("audio-scheduler-", 0).factory());

    // 存储每个会话最后一次发送帧的时间戳
    private final Map<String, AtomicLong> lastFrameSentTime = new ConcurrentHashMap<>();

    // 存储每个会话当前是否正在播放音频
    private final Map<String, AtomicBoolean> isPlaying = new ConcurrentHashMap<>();
    
    // 存储首帧发送状态
    private final Map<String, AtomicBoolean> firstFrameSent = new ConcurrentHashMap<>();

    // 存储每个会话的调度任务
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    // 存储每个会话的音频发送任务
    private final Map<String, CompletableFuture<?>> sendAudioTasks = new ConcurrentHashMap<>();

    // 存储播放开始时间（纳秒）
    private final Map<String, Long> playStartTimes = new ConcurrentHashMap<>();
    
    // 存储播放位置（毫秒）
    private final Map<String, Long> playPositions = new ConcurrentHashMap<>();

    /**
     * 发送TTS开始消息
     */
    public void sendStart(ChatSession session) {
        messageService.sendTtsMessage(session, null, "start");
    }

    /**
     * 发送TTS句子开始消息
     */
    public void sendSentenceStart(ChatSession session, String text) {
        messageService.sendTtsMessage(session, text, "sentence_start");
    }

    /**
     * 发送停止消息
     */
    public CompletableFuture<Void> sendStop(ChatSession session) {
        return sendStop(session, false);
    }

    /**
     * 发送停止消息
     */
    public CompletableFuture<Void> sendStop(ChatSession session, boolean stopByAudioTaskInner) {
        String sessionId = session.getSessionId();

        try {
            // 如果在播放音乐，则不停止
            if (sessionManager.isMusicPlaying(sessionId)) {
                return CompletableFuture.completedFuture(null);
            }

            // 标记播放结束
            AtomicBoolean playingState = isPlaying.computeIfAbsent(sessionId, k -> new AtomicBoolean());
            playingState.set(false);
            
            // 取消调度任务
            cancelScheduledTask(sessionId);
            
            // 清理播放时间信息
            cleanTimers(sessionId);
            
            CompletableFuture<Void> sendTtsMessageFuture = CompletableFuture.runAsync(()->messageService.sendTtsMessage(session, null, "stop"));
            // 检查是否需要关闭会话
            if (sessionManager.isCloseAfterChat(sessionId)) {
                sendTtsMessageFuture.thenRun(() -> {
                    sessionManager.closeSession(sessionId);
                });
            }
            sessionManager.setPlaying(sessionId, false);

            if(!stopByAudioTaskInner){
                CompletableFuture<?> sendAudioTask = sendAudioTasks.remove(sessionId);
                if(sendAudioTask != null && !sendAudioTask.isDone()){
                    sendAudioTask.cancel(true);
                }
            }
            return sendTtsMessageFuture;
        } catch (Exception e) {
            logger.error("发送停止消息失败", e);
            AtomicBoolean playingState = isPlaying.computeIfAbsent(sessionId, k -> new AtomicBoolean());
            playingState.set(false);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 检查会话是否正在播放音频
     */
    public boolean isPlaying(String sessionId) {
        return isPlaying.containsKey(sessionId) && 
               isPlaying.get(sessionId).get();
    }

    /**
     * 发送音频消息
     *
     * @param session   WebSocketSession会话
     * @param sentence  句子对象
     * @param isFirst   是否是开始消息
     * @param isLast    是否是结束消息
     * @return 操作完成的CompletableFuture
     */
    public CompletableFuture<Void> sendAudioMessage(
            ChatSession session,
            DialogueService.Sentence sentence,
            boolean isFirst,
            boolean isLast) {

        String sessionId = session.getSessionId();
        String audioPath = sentence.getAudioPath();
        String text = sentence.getText();

        // 标记开始播放
        AtomicBoolean playingState = isPlaying.computeIfAbsent(sessionId, k -> new AtomicBoolean(true));
        playingState.set(true);

        // 创建一个 CompletableFuture 链来处理整个流程
        CompletableFuture<Void> startFuture = isFirst ? CompletableFuture.runAsync(()->sendStart(session))
                : CompletableFuture.completedFuture(null);
        
        logger.info("向设备发送音频消息（sendAudioMessage） - SessionId: {}, 文本: {}, 音频路径: {}", sessionId, text, audioPath);

        if (audioPath == null) {
            if(text != null && !text.isEmpty()) {
                // 发送句子开始标记
                CompletableFuture<Void> sentenceStartFuture = startFuture.thenRun(() -> sendSentenceStart(session, text + "--语音合成异常!!"));

                // 发送句子表情
                CompletableFuture<Void> emotionFuture = sentenceStartFuture.thenRun(() -> sendSentenceEmotion(session, sentence, null));

                // 使用单独的变量存储播放状态引用
                final AtomicBoolean finalPlayingState = playingState;

                return emotionFuture.thenCompose(v -> {
                    finalPlayingState.set(false);
                    try {
                        TimeUnit.MILLISECONDS.sleep(ONLY_TEXT_SLEEP_TIME_MS);
                    } catch (InterruptedException e) {
                        logger.error("等待异常提示播放失败", e);
                    }

                    if (isLast) {
                        return sendStop(session);
                    }
                    return CompletableFuture.completedFuture(null);
                });
            }
            // 如果没有音频路径但是结束消息，发送结束标记
            if (isLast) {
                return startFuture.thenCompose(v -> sendStop(session));
            }
            playingState.set(false);
            return startFuture;
        }
        
        // 使用单独的变量存储播放状态引用
        final AtomicBoolean finalPlayingState = playingState;
        
        // 发送句子开始标记
        CompletableFuture<Void> sentenceStartFuture = startFuture.thenRun(() -> sendSentenceStart(session, text));
        
        // 发送句子表情
        CompletableFuture<Void> emotionFuture = sentenceStartFuture.thenRun(() -> sendSentenceEmotion(session, sentence, null));

        // 处理音频文件
        return emotionFuture.thenCompose(v -> CompletableFuture.supplyAsync(() -> {
            String fullPath = audioPath;
            File audioFile = new File(fullPath);
            if (!audioFile.exists()) {
                logger.warn("音频文件不存在: {}", fullPath);
                return null;
            }

            List<byte[]> opusFrames;

            try {
                if (audioPath.contains(".opus")) {
                    // 如果是opus文件，直接读取opus帧数据
                    opusFrames = opusProcessor.readOpus(audioFile);
                } else {
                    // 如果不是opus文件，按照原来的逻辑处理
                    byte[] audioData = AudioUtils.readAsPcm(fullPath);
                    // 将PCM转换为Opus帧
                    opusFrames = opusProcessor.pcmToOpus(sessionId, audioData, false);
                }
                return opusFrames;
            } catch (Exception e) {
                logger.error("处理音频文件失败: {}", fullPath, e);
                return null;
            }
        })).thenCompose(opusFrames -> {
            if (opusFrames == null || opusFrames.isEmpty()) {
                finalPlayingState.set(false);
                if (isLast) {
                    return sendStop(session);
                }
                return CompletableFuture.completedFuture(null);
            }

            // 确保播放状态为true
            finalPlayingState.set(true);
            
            // 创建发送帧的CompletableFuture
            CompletableFuture<Void> sendFramesFuture = new CompletableFuture<>();
            
            try {
                // 初始化播放时间和位置
                playStartTimes.put(sessionId, System.nanoTime());
                playPositions.put(sessionId, 0L);
                
                // 预缓冲处理
                int preBufferCount = Math.min(PRE_BUFFER_FRAMES, opusFrames.size());
                for (int i = 0; i < preBufferCount; i++) {
                    sendOpusFrame(session, opusFrames.get(i));
                    // 更新播放位置
                    playPositions.put(sessionId, (i + 1) * OPUS_FRAME_SEND_INTERVAL_MS);
                }
                
                // 创建帧发送任务
                final int[] frameIndex = {preBufferCount};
                
                Runnable frameTask = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!finalPlayingState.get() || frameIndex[0] >= opusFrames.size() || !session.isOpen()) {
                                // 完成非流式音频处理
                                endTask(sessionId, sendFramesFuture);
                                return;
                            }
                            
                            // 更新活跃时间
                            sessionManager.updateLastActivity(sessionId);
                            
                            // 发送当前帧
                            byte[] frame = opusFrames.get(frameIndex[0]++);
                            sendOpusFrame(session, frame);
                            
                            // 更新播放位置
                            Long position = playPositions.get(sessionId);
                            if (position != null) {
                                playPositions.put(sessionId, position + OPUS_FRAME_SEND_INTERVAL_MS);
                            }
                            
                            // 计算下一帧的发送时间
                            if (frameIndex[0] < opusFrames.size()) {
                                scheduleNextFrame(sessionId, this);
                            } else {
                                // 所有帧已发送完成
                                endTask(sessionId, sendFramesFuture);
                            }
                            
                        } catch (Exception e) {
                            // 发生错误，取消调度任务
                            logger.error("非流式帧处理失败", e);
                            endTask(sessionId, sendFramesFuture, e);
                        }
                    }
                };
                
                // 如果还有帧需要发送，启动精确时间调度
                if (frameIndex[0] < opusFrames.size()) {
                    scheduleNextFrame(sessionId, frameTask);
                } else {
                    // 所有帧已在预缓冲中发送完毕
                    endTask(sessionId, sendFramesFuture);
                }
                
            } catch (Exception e) {
                logger.error("音频帧发送初始化失败", e);
                endTask(sessionId, sendFramesFuture, e);
            }
            
            // 返回帧发送Future
            return sendFramesFuture;
        }).whenComplete((result, error) -> {
            // 无论成功还是失败，都标记播放结束
            finalPlayingState.set(false);
            
            // 取消调度任务
            cancelScheduledTask(sessionId);
            
            // 清理播放时间信息
            cleanTimers(sessionId);
        }).thenCompose(v -> {
            // 发送停止消息（只有在isLast为true时才发送）
            if (isLast) {
                return sendStop(session, true);
            }
            return CompletableFuture.completedFuture(null);
        }).exceptionally(error -> {
            logger.error("发送音频消息失败", error);
            
            // 如果发生错误但仍然是结束消息，确保发送stop
            if (isLast) {
                try {
                    sendStop(session, true);
                } catch (Exception e) {
                    logger.error("发送停止消息失败", e);
                }
            }
            return null;
        });
    }

    /**
     * 发送Opus帧数据
     */
    public void sendOpusFrame(ChatSession session, byte[] opusFrame) throws IOException {
        messageService.sendBinaryMessage(session, opusFrame);
    }

    /**
     * 发送表情信息。如果句子里没有分析出表情，则默认返回 happy
     */
    private void sendSentenceEmotion(ChatSession session, DialogueService.Sentence sentence, String defaultEmotion) {
        List<String> moods = sentence.getMoods();
        if (moods != null && !moods.isEmpty()) {
            messageService.sendEmotion(session, moods.get(0));
        } else if (defaultEmotion != null) {
            messageService.sendEmotion(session, defaultEmotion);
        }
    }

    /**
     * 清理会话资源
     */
    public void cleanupSession(String sessionId) {
        lastFrameSentTime.remove(sessionId);
        isPlaying.remove(sessionId);
        cleanTimers(sessionId);
        cancelScheduledTask(sessionId);
        opusProcessor.cleanup(sessionId);
    }
    
    /**
     * 结束非流式任务
     */
    private void endTask(String sessionId, CompletableFuture<Void> future) {
        endTask(sessionId, future, null);
    }
    
    /**
     * 结束非流式任务（带异常）
     */
    private void endTask(String sessionId, CompletableFuture<Void> future, Throwable error) {
        // 取消调度任务
        cancelScheduledTask(sessionId);
        
        // 清理播放时间信息
        cleanTimers(sessionId);
        
        // 完成Future
        if (error != null) {
            future.completeExceptionally(error);
        } else {
            future.complete(null);
        }
    }
    
    /**
     * 清理计时器资源
     */
    private void cleanTimers(String sessionId) {
        playStartTimes.remove(sessionId);
        playPositions.remove(sessionId);
    }
    
    /**
     * 计算并调度下一帧的发送时间
     */
    private void scheduleNextFrame(String sessionId, Runnable frameTask) {
        Long startTime = playStartTimes.get(sessionId);
        Long position = playPositions.get(sessionId);
        
        if (startTime == null || position == null) {
            // 如果没有时间信息，使用固定间隔
            ScheduledFuture<?> future = scheduler.schedule(frameTask, OPUS_FRAME_SEND_INTERVAL_MS, TimeUnit.MILLISECONDS);
            scheduledTasks.put(sessionId, future);
            return;
        }

        // 计算预期发送时间（纳秒级精度）
        long expectedTime = startTime + position * 1_000_000;
        long currentTime = System.nanoTime();
        long delayNanos = expectedTime - currentTime;
        
        ScheduledFuture<?> future;
        if (delayNanos <= 0) {
            // 如果当前时间已经超过预期时间，立即发送
            future = scheduler.schedule(frameTask, 0, TimeUnit.NANOSECONDS);
        } else {
            // 延迟到精确时间点再发送
            future = scheduler.schedule(frameTask, delayNanos, TimeUnit.NANOSECONDS);
        }

        scheduledTasks.put(sessionId, future);
    }
    
    /**
     * 取消调度任务
     */
    public void cancelScheduledTask(String sessionId) {
        ScheduledFuture<?> task = scheduledTasks.remove(sessionId);
        if (task != null && !task.isDone()) {
            task.cancel(false);
        }
    }

    // ========== OpenAI Realtime 相关方法 ==========

    /**
     * 发送实时音频块到客户端
     */
    public void sendRealTimeAudioChunk(ChatSession session, byte[] audioData) {
        String sessionId = session.getSessionId();
        try {
            if (session.isOpen()) {
                // 将PCM音频数据转换为Opus格式并发送
                byte[] opusData = opusProcessor.encodeToOpus(audioData);
                session.sendBinaryMessage(opusData);
                
                logger.debug("发送实时音频块 - SessionId: {}, 数据大小: {} bytes", sessionId, opusData.length);
            }
        } catch (Exception e) {
            logger.error("发送实时音频块失败 - SessionId: " + sessionId, e);
        }
    }

    /**
     * 发送实时文本增量到客户端
     */
    public void sendRealtimeTextDelta(ChatSession session, String textDelta) {
        try {
            messageService.sendRealtimeTextDelta(session, textDelta);
            logger.debug("发送实时文本增量 - SessionId: {}, 文本: {}", session.getSessionId(), textDelta);
        } catch (Exception e) {
            logger.error("发送实时文本增量失败 - SessionId: " + session.getSessionId(), e);
        }
    }

    /**
     * 发送实时响应完成通知
     */
    public void sendRealtimeResponseComplete(ChatSession session) {
        try {
            messageService.sendRealtimeResponseComplete(session);
            logger.info("发送实时响应完成通知 - SessionId: {}", session.getSessionId());
        } catch (Exception e) {
            logger.error("发送实时响应完成通知失败 - SessionId: " + session.getSessionId(), e);
        }
    }

    /**
     * 发送转录结果到客户端
     */
    public void sendTranscriptionResult(ChatSession session, String transcript) {
        try {
            messageService.sendTranscriptionResult(session, transcript);
            logger.info("发送转录结果 - SessionId: {}, 文本: {}", session.getSessionId(), transcript);
        } catch (Exception e) {
            logger.error("发送转录结果失败 - SessionId: " + session.getSessionId(), e);
        }
    }

}