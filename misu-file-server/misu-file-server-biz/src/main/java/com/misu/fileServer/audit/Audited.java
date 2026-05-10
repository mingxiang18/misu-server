package com.misu.fileServer.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注关键写操作 — 切面会自动落审计日志。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    /** 操作类型，建议使用 AuditAction 中的常量 */
    String value();

    /** SpEL 表达式从入参里取目标 (openType, virtualPath)；为空则从入参 DTO 里按通用字段名探测 */
    String openTypeExpr() default "";
    String virtualPathExpr() default "";
}
