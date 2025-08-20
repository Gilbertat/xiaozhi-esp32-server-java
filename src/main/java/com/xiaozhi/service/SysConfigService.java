package com.xiaozhi.service;

import com.xiaozhi.common.web.PageFilter;
import com.xiaozhi.entity.SysConfig;

import java.util.List;

/**
 * 配置
 * 
 * @author Joey
 * 
 */
public interface SysConfigService {

  /**
   * 添加配置
   * 
   * @param config
   * @return
   */
  int add(SysConfig config);

  /**
   * 修改配置
   * 
   * @param config
   * @return
   */
  int update(SysConfig config);

  /**
   * 查询
   * 
   * @param config;
   * @return
   */
  List<SysConfig> query(SysConfig config, PageFilter pageFilter);

  /**
   * 查询配置
   * 
   * @param configId;
   * @return
   */
  SysConfig selectConfigById(Integer configId);

  /**
   * 查询默认配置
   */
  SysConfig selectModelType(String modelType);

  /**
   * 根据条件查询配置（返回第一个匹配的）
   * 
   * @param config 查询条件
   * @return 匹配的配置
   */
  SysConfig selectConfigByConditions(SysConfig config);

  /**
   * 根据条件查询配置（返回第一个匹配的）
   * 
   * @param config 查询条件
   * @return 匹配的配置
   */
  SysConfig selectConfigByCondition(SysConfig config);
}