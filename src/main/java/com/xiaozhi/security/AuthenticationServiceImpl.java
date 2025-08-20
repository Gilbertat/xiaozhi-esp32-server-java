package com.xiaozhi.security;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Service;

/**
 * 密码加密与验证
 *
 * @author Joey
 *
 */
@Service
public class AuthenticationServiceImpl implements AuthenticationService {

    private static final String salt = "joey@zhou";

    /**
     * @param rawPassword
     * @return 加密后的密码
     */
    public String encryptPassword(String rawPassword) {
        String saltPassword = rawPassword + salt;
        StringBuilder result = new StringBuilder();
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] bytes = messageDigest.digest(saltPassword.getBytes("UTF-8"));
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1)
                    result.append("0");
                result.append(hex);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("No such algorithm MD5");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 not supported!");
        }
        return result.toString();
    }

    /**
     * 密码验证
     * 
     * @param rawPassword
     * @param encryptPassword
     * @return 是否相同
     */
    public Boolean isPasswordValid(String rawPassword, String encryptPassword) {
        String encodePassword = encryptPassword(rawPassword);
        return encodePassword.equals(encryptPassword);
    }

    public static void main(String[] args) {
        AuthenticationServiceImpl authenticationService = new AuthenticationServiceImpl();
        System.out.println(authenticationService.encryptPassword("qwe123456"));
    }

}