package com.jingshanghui.pos.member.application.port;

/** 开业迁移创建最小会员主体与加密身份的 Owner 受控端口。 */
public interface BusinessMigrationMemberPort {
    MemberMigrationResult importMember(MemberMigrationCommand command);

    /**
     * 身份原文只在进程内短暂存在，Owner 必须立即加密且不得记录日志。
     * @param commandId Member Owner 稳定幂等命令 ULID
     * @param memberId 迁移生成的会员主体 ULID
     * @param identityId 迁移生成的会员身份 ULID
     * @param identityType 身份类型
     * @param identityValue 身份原文，仅供进程内加密
     * @param rowSha256 冻结迁移行 SHA-256
     * @param correlationId 全链路关联标识
     */
    record MemberMigrationCommand(String commandId, String memberId, String identityId,
                                  String identityType, String identityValue,
                                  String rowSha256, String correlationId) {
    }

    /**
     * 不返回身份原文，只返回稳定主体和脱敏别名。
     * @param memberId 稳定会员主体 ULID
     * @param alias 最小化展示的脱敏别名
     * @param state 会员主体状态
     * @param rowSha256 已接收迁移行摘要
     * @param replay 是否返回既有幂等结果
     */
    record MemberMigrationResult(String memberId, String alias, String state,
                                 String rowSha256, boolean replay) {
    }
}
