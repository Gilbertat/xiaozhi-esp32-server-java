package com.xiaozhi.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * LLM\STT\TTS配置
 * 
 * @author Joey
 * 
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@Schema(description = "配置信息")
public class SysConfig extends Base<SysConfig> {
    @Schema(description = "配置ID")
    private Integer configId;

    @Schema(description = "用户ID")
    private Integer userId;

    @Schema(description = "设备ID")
    private String deviceId;

    @Schema(description = "角色ID")
    private Integer roleId;

    /**
     * 配置名称
     */
    @Schema(description = "配置名称")
    private String configName;

    /**
     * 配置描述
     */
    @Schema(description = "配置描述")
    private String configDesc;

    /**
     * 配置类型（llm\stt\tts）
     */
    @Schema(description = "配置类型（llm\\stt\\tts）")
    private String configType;

    /**
     * 模型类型（chat\vision\intent\embedding）
     */
    @Schema(description = "模型类型（chat\\vision\\intent\\embedding）")
    private String modelType;

    /**
     * 服务提供商 (openai\quen\vosk\aliyun\tencent等)
     */
    @Schema(description = "服务提供商 (openai\\quen\\vosk\\aliyun\\tencent等)")
    private String provider;

    @Schema(description = "服务提供商分配的AppId")
    private String appId;

    @Schema(description = "服务提供商分配的ApiKey")
    private String apiKey;

    @Schema(description = "服务提供商分配的ApiSecret")
    private String apiSecret;

    @Schema(description = "服务提供商分配的Access Key")
    private String ak;

    @Schema(description = "服务提供商分配的Secret Key")
    private String sk;

    @Schema(description = "服务提供商的API地址")
    private String apiUrl;

    @Schema(description = "服务提供商的基础URL（用于支持自定义API端点）")
    private String baseUrl;

    @Schema(description = "模型名称（如gpt-3.5-turbo、whisper-1、tts-1等）")
    private String modelName;

    @Schema(description = "服务提供商状态")
    private String state;

    @Schema(description = "是否作为默认配置")
    private String isDefault;

}
