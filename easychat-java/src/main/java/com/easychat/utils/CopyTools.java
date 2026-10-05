package com.easychat.utils;

import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Bean 属性拷贝工具类
 *
 * <p>全仓使用频次最高的工具类之一（22 处调用：Controller 出参、Service 内 DTO 转换）。
 *
 * <p><b>2026-10-05 修复</b>：原先实例化失败时 {@code catch} 内只
 * {@code e.printStackTrace()} —— 打到 <b>stdout</b>（不进 logger、不受日志级别控制、
 * 生产环境通常被丢弃），随后 {@code BeanUtils.copyProperties(s, null)} 抛出
 * Spring 的 {@code IllegalArgumentException("Target must not be null")}，
 * <b>真实原因（目标类没有无参构造器）在异常信息里完全没有体现</b>，
 * 排障只能靠那段大概率已消失的 stdout 栈迹。
 * 现改为用 logger 记录并抛出指向目标类名的业务异常。
 */
public class CopyTools {

    private static final Logger logger = LoggerFactory.getLogger(CopyTools.class);

    private CopyTools() {
    }

    /**
     * 实例化目标类型。
     *
     * @throws BusinessException 实例化失败时抛出，异常信息含目标类名与根因
     */
    private static <T> T newInstance (Class<T> classz) {
        try {
            java.lang.reflect.Constructor<T> ctor = classz.getDeclaredConstructor();
            if (!ctor.canAccess(null)) {
                ctor.setAccessible(true);
            }
            return ctor.newInstance();
        } catch (Exception e) {
            logger.error("CopyTools 实例化目标类型失败：{}（根因：{}）", classz.getName(), e.toString());
            throw new BusinessException(
                    "对象拷贝失败：目标类型 " + classz.getName() + " 无法实例化，请确认其具备无参构造器");
        }
    }

    public static <T, S> List<T> copyList (List<S> sList, Class<T> classz) {
        if (sList == null) {
            return new ArrayList<>();
        }
        List<T> list = new ArrayList<T>(sList.size());
        for (S s : sList) {
            list.add(copy(s, classz));
        }
        return list;
    }

    public static <T, S> T copy (S s, Class<T> classz) {
        T t = newInstance(classz);
        BeanUtils.copyProperties(s, t);
        return t;
    }
}
