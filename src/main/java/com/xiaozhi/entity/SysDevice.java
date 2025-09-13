package com.xiaozhi.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 设备表
 * 
 * @author Joey
 * 
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties({ "startTime", "endTime", "start", "limit", "userId", "code" })
@Schema(description = "设备信息")
public class SysDevice extends SysRole {
    public static final String DEVICE_STATE_ONLINE = "1";
    public static final String DEVICE_STATE_OFFLINE = "0";

    @Schema(description = "设备ID")
    private String deviceId;

    @Schema(description = "会话ID")
    private String sessionId;

    /**
     * 设备名称
     */
    @Schema(description = "设备名称")
    private String deviceName;

    /**
     * 设备状态
     */
    @Schema(description = "设备状态")
    private String state;

    /**
     * 设备对话次数
     */
    @Schema(description = "设备对话次数")
    private Integer totalMessage;

    /**
     * 验证码
     */
    @Schema(description = "验证码")
    private String code;

    /**
     * 音频文件
     */
    @Schema(description = "音频文件路径")
    private String audioPath;

    /**
     * 最后在线时间
     */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "最后在线时间")
    private String lastLogin;

    /**
     * WiFi名称
     */
    @Schema(description = "WiFi名称")
    private String wifiName;

    /**
     * IP
     */
    @Schema(description = "IP地址")
    private String ip;

    /**
     * 芯片型号
     */
    @Schema(description = "芯片型号")
    private String chipModelName;

    /**
     * 芯片类型
     */
    @Schema(description = "芯片类型")
    private String type;

    /**
     * 固件版本
     */
    @Schema(description = "固件版本")
    private String version;

    /**
     * 可用全局function的名称列表(逗号分割)，为空则使用所有全局function
     */
    @Schema(description = "可用全局function的名称列表")
    private String functionNames;

    /**
     * 设备昵称
     */
    @Schema(description = "设备昵称")
    private String deviceNickname;

}