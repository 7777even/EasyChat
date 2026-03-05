-- 通话记录表
CREATE TABLE IF NOT EXISTS call_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    call_id VARCHAR(100) NOT NULL COMMENT '通话ID',
    caller_id VARCHAR(50) NOT NULL COMMENT '发起人ID',
    caller_name VARCHAR(100) COMMENT '发起人昵称',
    callee_id VARCHAR(50) NOT NULL COMMENT '接收人ID（单聊）或群组ID（群聊）',
    callee_name VARCHAR(100) COMMENT '接收人昵称或群组名称',
    call_type TINYINT NOT NULL COMMENT '通话类型：0-语音 1-视频',
    is_group TINYINT DEFAULT 0 COMMENT '是否群组通话：0-否 1-是',
    call_status TINYINT NOT NULL COMMENT '通话状态：0-呼叫中 1-已接听 2-已拒绝 3-已取消 4-超时未接听 5-已结束',
    start_time DATETIME COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    duration INT DEFAULT 0 COMMENT '通话时长（秒）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_caller_id (caller_id),
    INDEX idx_callee_id (callee_id),
    INDEX idx_call_id (call_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话记录表';

-- 群组通话参与者表
CREATE TABLE IF NOT EXISTS call_participants (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    call_id VARCHAR(100) NOT NULL COMMENT '通话ID',
    user_id VARCHAR(50) NOT NULL COMMENT '用户ID',
    user_name VARCHAR(100) COMMENT '用户昵称',
    join_time DATETIME COMMENT '加入时间',
    leave_time DATETIME COMMENT '离开时间',
    duration INT DEFAULT 0 COMMENT '参与时长（秒）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_call_id (call_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群组通话参与者表';
