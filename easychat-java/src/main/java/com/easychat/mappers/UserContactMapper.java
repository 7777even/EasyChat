package com.easychat.mappers;

import org.apache.ibatis.annotations.Param;

/**
 * 联系人 数据库操作接口
 */
public interface UserContactMapper<T,P> extends BaseMapper<T,P> {

	/**
	 * 根据UserIdAndContactId更新
	 */
	 Integer updateByUserIdAndContactId(@Param("bean") T t,@Param("userId") String userId,@Param("contactId") String contactId);


	/**
	 * 根据UserIdAndContactId删除
	 */
	 Integer deleteByUserIdAndContactId(@Param("userId") String userId,@Param("contactId") String contactId);


	/**
	 * 根据UserIdAndContactId获取对象
	 */
	 T selectByUserIdAndContactId(@Param("userId") String userId,@Param("contactId") String contactId);

	/**
	 * 修改群成员角色（群主/管理员/成员）
	 */
	Integer updateRole(@Param("userId") String userId, @Param("contactId") String contactId, @Param("role") Integer role);

	/**
	 * 修改禁言到期时间（设为NULL解除禁言）
	 */
	Integer updateMuteEndTime(@Param("userId") String userId, @Param("contactId") String contactId, @Param("muteEndTime") java.util.Date muteEndTime);

}
