package com.xiaozhi.common.interceptor;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.common.web.AjaxResult;
import com.xiaozhi.entity.SysUser;
import com.xiaozhi.service.SysUserService;
import com.xiaozhi.utils.CmsUtils;
import com.xiaozhi.utils.JwtTokenUtil;

import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    @Resource
    private SysUserService userService;

    @Resource
    private JwtTokenUtil jwtTokenUtil;

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationInterceptor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 不需要认证的路径
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/api/user/login",
            "/api/user/add", 
            "/api/user/sendEmailCaptcha",
            "/api/user/checkCaptcha",
            "/api/user/checkUser",
            "/api/device/ota",
            "/api/openai",
            "/audio/",
            "/uploads/",
            "/ws/");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // 对于预检请求（OPTIONS），直接允许通过，不需要认证
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        // 检查是否是公共路径
        if (isPublicPath(path)) {
            return true;
        }

        // 检查是否有@UnLogin注解
        if (hasUnLoginAnnotation(handler)) {
            logger.debug("接口 {} 标记为不需要登录验证", path);
            return true;
        }

        // 从Authorization头获取JWT令牌
        String token = getTokenFromRequest(request);
        logger.debug("JWT Token from request: {}", token); // 添加调试日志
        if (token != null && jwtTokenUtil.validateToken(token, null)) {
            // 从JWT中解析用户信息并设置到请求属性
            Integer userId = jwtTokenUtil.getUserIdFromToken(token);
            String username = jwtTokenUtil.getUsernameFromToken(token);
            logger.debug("Parsed userId: {}, username: {} from JWT", userId, username); // 添加调试日志
            
            // 查询数据库获取完整用户信息
            SysUser user = userService.selectUserByUserId(userId);
            if (user != null) {
                // 将用户信息存储在请求属性中
                request.setAttribute(CmsUtils.USER_ATTRIBUTE_KEY, user);
                CmsUtils.setUser(request, user);
                return true;
            } else {
                logger.warn("User not found for userId: {}", userId);
            }
        } else if (token != null) {
            logger.warn("JWT token validation failed for token: {}", token);
        }

        // 如果JWT验证失败，尝试使用Session和Cookie作为备选方案（向后兼容）
        // 获取会话
        HttpSession session = request.getSession(false);
        if (session != null) {
            // 检查会话中是否有用户
            Object userObj = session.getAttribute(SysUserService.USER_SESSIONKEY);
            if (userObj != null) {
                SysUser user = (SysUser) userObj;
                // 将用户信息存储在请求属性中
                request.setAttribute(CmsUtils.USER_ATTRIBUTE_KEY, user);
                CmsUtils.setUser(request, user);
                return true;
            }
        }

        // 尝试从Cookie中获取用户名
        if (tryAuthenticateWithCookies(request, response)) {
            return true;
        }

        // 处理未授权的请求
        handleUnauthorized(request, response);
        return false;
    }

    /**
     * 从请求中获取JWT令牌
     */
    private String getTokenFromRequest(HttpServletRequest request) {
        // 尝试获取Authorization头（大小写不敏感）
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    /**
     * 尝试使用Cookie进行认证
     */
    private boolean tryAuthenticateWithCookies(HttpServletRequest request, HttpServletResponse response) {
        // 检查是否有username cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("username".equals(cookie.getName())) {
                    String username = cookie.getValue();
                    if (StringUtils.isNotBlank(username)) {
                        SysUser user = userService.selectUserByUsername(username);
                        if (user != null) {
                            // 将用户存储在会话和请求属性中
                            HttpSession session = request.getSession(true);
                            session.setAttribute(SysUserService.USER_SESSIONKEY, user);
                            request.setAttribute(CmsUtils.USER_ATTRIBUTE_KEY, user);
                            CmsUtils.setUser(request, user);
                            return true;
                        }
                    }
                    break;
                }
            }
        }
        return false;
    }

    /**
     * 处理未授权的请求
     */
    private void handleUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 设置CORS头，确保认证失败的响应也能被前端接收
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "*");
        response.setHeader("Access-Control-Expose-Headers", "Authorization");
        
        // 检查是否是AJAX请求
        String ajaxTag = request.getHeader("Request-By");
        String head = request.getHeader("X-Requested-With");

        if ((ajaxTag != null && ajaxTag.trim().equalsIgnoreCase("Ext"))
                || (head != null && !head.equalsIgnoreCase("XMLHttpRequest"))) {
            response.addHeader("_timeout", "true");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            // 返回JSON格式的错误信息
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");

            AjaxResult result = AjaxResult.error(com.xiaozhi.common.web.HttpStatus.FORBIDDEN, "用户未登录");
            try {
                objectMapper.writeValue(response.getOutputStream(), result);
            } catch (Exception e) {
                logger.error("写入响应失败", e);
                throw e;
            }
        }
    }

    /**
     * 检查是否是公共路径
     */
    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * 检查处理器是否有@UnLogin注解
     */
    private boolean hasUnLoginAnnotation(Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return false;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        Method method = handlerMethod.getMethod();
        Class<?> controllerClass = handlerMethod.getBeanType();

        // 检查方法上是否有@UnLogin注解
        UnLogin methodAnnotation = method.getAnnotation(UnLogin.class);
        if (methodAnnotation != null && methodAnnotation.value()) {
            return true;
        }

        // 检查类上是否有@UnLogin注解
        UnLogin classAnnotation = controllerClass.getAnnotation(UnLogin.class);
        if (classAnnotation != null && classAnnotation.value()) {
            return true;
        }

        return false;
    }
}