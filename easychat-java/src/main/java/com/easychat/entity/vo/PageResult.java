package com.easychat.entity.vo;

import java.util.List;

/**
 * 分页结果出参 - 统一分页查询的响应结构
 * <p>
 * 接口出参统一包含在 Result<PageResult<T>> 中：
 * {
 *   "code": 0,
 *   "message": "success",
 *   "data": {
 *     "list": [...],
 *     "total": 100
 *   }
 * }
 */
public class PageResult<T> {

    /** 当前页数据列表 */
    private List<T> list;

    /** 总记录数 */
    private Long total;

    public PageResult() {
    }

    public PageResult(List<T> list, Long total) {
        this.list = list;
        this.total = total;
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }
}
