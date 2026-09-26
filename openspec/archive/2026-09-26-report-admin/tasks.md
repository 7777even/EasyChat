# Tasks：举报处理与管理端审计

- [x] T1 openspec 四件套（proposal/design/tasks/spec-delta/.openspec.yaml）
- [x] T2 后端 PO/Query/VO/枚举/Mapper：ReportAuditLog、ReportQuery/ReportAuditQuery、AdminReportVO/AdminReportDetailVO/ReportAuditLogVO、ReportTypeEnum/HandleActionEnum、ReportReadMapper(UNION)、ReportAuditLogMapper；扩展 MomentReport/MessageReport 字段
- [x] T3 后端 AdminReportService+Controller+错误码：四接口、CODE_2702、注入 userInfoService/momentMapper 等
- [x] T4 迁移 SQL + easychat.sql 同步：migration-006（ALTER+CREATE）、基线补字段与新表
- [x] T5 前端 ReportList.vue + Api/路由/菜单
- [x] T6 构建验证：mvn clean compile + electron-vite build + 三道门禁
- [x] T7 QA/Retro + 归档 + 按域提交推送

## 验收标准
- [x] 管理员可分页查看全部举报，按类型/状态/理由/时间过滤
- [x] 管理员可查看单条举报详情（含被举报内容全文与双方信息）
- [x] 管理员可处置：置已处理/已驳回，记录处理人/时间/备注，可选删内容/封禁发布者
- [x] 每次处置动作写入一条不可变审计日志，可按要求查询
- [x] 非管理员调用管理端接口被 `checkAdmin` 拦截（CODE_404）
- [x] 编译/构建/三道门禁全绿；按域拆分提交并推送成功
