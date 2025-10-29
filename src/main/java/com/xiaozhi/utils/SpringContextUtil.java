package com.xiaozhi.utils;

import com.xiaozhi.entity.SysUser;
import com.xiaozhi.service.SysUserService;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class SpringContextUtil implements ApplicationContextAware {
    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        applicationContext = context;
    }

    public static <T> T getBean(Class<T> clazz) {
        return applicationContext.getBean(clazz);
    }

    public static SysUser getUserFromRequest(HttpServletRequest request) {
        // 尝试从Request Attributes获取（适用于已验证的请求）
        Object userObj = RequestContextHolder.currentRequestAttributes().getAttribute(CmsUtils.USER_ATTRIBUTE_KEY, RequestAttributes.SCOPE_REQUEST);
        if (userObj instanceof SysUser) {
            return (SysUser) userObj;
        }
        return null;
    }

    public static Integer getUserIdFromRequest(HttpServletRequest request) {
        SysUser user = getUserFromRequest(request);
        if (user != null) {
            return user.getUserId();
        }
        return null;
    }
}