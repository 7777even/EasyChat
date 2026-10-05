package com.easychat.entity.query;

import com.easychat.entity.enums.SortOption;


public class BaseParam {
	private SimplePage simplePage;
	private Integer pageNo;
	private Integer pageSize;

	/**
	 * 排序 —— 白名单三件套（2026-10-06 起，替代原 {@code orderBy} 字符串）。
	 *
	 * <p>原字段是 {@code private String orderBy}，Mapper 用 {@code order by ${query.orderBy}}
	 * 拼进 SQL（违反 AGENTS §6.2-3）。{@code AdminGroupController#loadGroup} 与
	 * {@code AdminUserInfoBeautyController#loadBeautyAccountList} 直接绑定 {@code *Query}、
	 * Controller 与 Service 均未设排序 → 调用方可把任意字符串送进 SQL。
	 *
	 * <p>现在：
	 * <ul>
	 *   <li>{@link #sortField} / {@link #sortDirection} 是**调用方可绑定**的原始入参，
	 *       本身不参与 SQL；</li>
	 *   <li>Service 用 {@link SortOption#fromHttp} 把它们解析成白名单枚举；</li>
	 *   <li>{@link #sortOption} 是解析结果，Mapper 的 {@code <choose>} 按它选 XML 内字面量。</li>
	 * </ul>
	 *
	 * <p>**刻意不提供 orderBy 字段**：留着它就等于留着「写任意串进 SQL」的后门。
	 * 直接删掉的好处是 —— 任何漏改的调用点都会**编译失败**，而不是静默继续注入。
	 */
	private String sortField;
	private String sortDirection;
	private SortOption sortOption;

	public SimplePage getSimplePage() {
		return simplePage;
	}

	public void setSimplePage(SimplePage simplePage) {
		this.simplePage = simplePage;
	}

	public Integer getPageNo() {
		return pageNo;
	}

	public void setPageNo(Integer pageNo) {
		this.pageNo = pageNo;
	}

	public Integer getPageSize() {
		return pageSize;
	}

	public void setPageSize(Integer pageSize) {
		this.pageSize = pageSize;
	}

	public String getSortField() {
		return sortField;
	}

	public void setSortField(String sortField) {
		this.sortField = sortField;
	}

	public String getSortDirection() {
		return sortDirection;
	}

	public void setSortDirection(String sortDirection) {
		this.sortDirection = sortDirection;
	}

	public SortOption getSortOption() {
		return sortOption;
	}

	public void setSortOption(SortOption sortOption) {
		this.sortOption = sortOption;
	}
}
