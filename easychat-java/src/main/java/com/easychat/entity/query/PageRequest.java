package com.easychat.entity.query;

import javax.validation.Valid;

/**
 * 通用分页请求包装器
 * <p>
 * 所有分页查询接口统一使用此包装器，将分页元数据与业务查询条件解耦。
 * 分页出参使用 PageResult<T>，完整响应结构为 Result<PageResult<T>>。
 *
 * @param <T> 具体的业务查询对象类型
 */
public class PageRequest<T> {

    /** 全局统一分页默认值（唯一真理源，禁止在 Service 层重复判空兜底） */
    public static final int DEFAULT_PAGE_NUM = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 页码（从 1 开始） */
    private Integer pageNum;

    /** 每页大小 */
    private Integer pageSize;

    /** 业务查询条件对象（GET 请求使用 query.xxx 点号前缀传参） */
    @Valid
    private T query;

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public T getQuery() {
        return query;
    }

    public void setQuery(T query) {
        this.query = query;
    }

    /**
     * 手写 getter 覆盖：未传时返回默认值，调用方直接使用 int，无需判空
     */
    public int getPageNumInt() {
        return pageNum != null ? pageNum : DEFAULT_PAGE_NUM;
    }

    public int getPageSizeInt() {
        return pageSize != null ? pageSize : DEFAULT_PAGE_SIZE;
    }

    /**
     * 计算偏移量（用于 MyBatis 分页）
     */
    public int getOffset() {
        return (getPageNumInt() - 1) * getPageSizeInt();
    }

    /**
     * 工厂方法
     */
    public static <T> PageRequest<T> of(Integer pageNum, Integer pageSize, T query) {
        PageRequest<T> request = new PageRequest<>();
        request.setPageNum(pageNum);
        request.setPageSize(pageSize);
        request.setQuery(query);
        return request;
    }
}
