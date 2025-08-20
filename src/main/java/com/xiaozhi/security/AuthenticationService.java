package com.xiaozhi.security;

/**
 * 密码加密与验证
 * 
 * @author Joey
 * 
 */

public interface AuthenticationService {
  /**
   * 密码加密
   * 
   * @param rawPassword
   * @return 加密后的密码
   */
   String encryptPassword(String rawPassword);

  /**
   * 密码验证
   * 
   * @param rawPassword
   * @param encryptPassword
   * @return 是否相同
   */
   Boolean isPasswordValid(String rawPassword, String encryptPassword);
}