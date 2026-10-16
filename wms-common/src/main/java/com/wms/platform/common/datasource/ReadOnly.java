package com.wms.platform.common.datasource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记"可走只读从库"的方法（P5.3 读写分离）。
 *
 * <p><b>为什么必须显式标注、而不是给读操作自动走从库（关键取舍）</b>：
 * 复制是**异步**的，从库天然落后主库若干毫秒到数秒。如果默认让"读"走从库：
 * <ul>
 *   <li>"写入后立即读"会读到旧值——最典型的是**下单/审批后马上查状态**，
 *       表现为"刚提交完，列表里没有"（用户会认为提交失败，重复提交）；</li>
 *   <li>参与**业务判断**的读会基于过期数据做决策：
 *       例如用从库读到的库存余额去判断"是否够扣减"、用从库读到的冻结额去判断"是否需要垫付"——
 *       这类判断错了就是**资金与库存差错**。</li>
 * </ul>
 * 所以本项目的约定是：**默认主库；只有明确"浏览类、允许轻微延迟"的查询才标注本注解**。
 * 宁可少利用从库，也不能让业务判断读到旧数据。
 *
 * <p><b>适合标注的场景</b>：列表/看板/报表类查询（多租户后台列表、影像列表、价目表明细查看）。
 * <br><b>绝不应标注的场景</b>：库存/资金校验、写后立即读、对账、状态机前置校验、任何返回值参与后续写决策的查询。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadOnly {

    /**
     * 说明为什么这个读可以走从库（写清楚原因，避免后人随手复制注解）。
     *
     * @return 原因
     */
    String value() default "";
}
