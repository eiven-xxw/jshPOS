package com.jingshanghui.pos.operations.migration;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class ExceptionCenterSqlPolicyTest {
    @Test void declaresControlledAndAppendOnlyTablesWithTenantFilters(){
        String sql=resource("/db/migration/V202608230073__gate7d_exception_center.sql").toLowerCase();
        for(String table:new String[]{"ops_exception_case","ops_exception_observation","ops_exception_lease_event",
            "ops_exception_action_plan","ops_exception_repair_command","ops_exception_review","ops_exception_state_event",
            "ops_exception_audit_event","ops_exception_command","ops_exception_outbox"})assertThat(sql).contains("create table "+table);
        assertThat(sql).contains("control").contains("append_only").contains("cannot be deleted");
        String mapper=resource("/mapper/operations/ExceptionCenterMapper.xml").toLowerCase();
        assertThat(mapper).doesNotContain("select *").doesNotContain("delete from ops_exception");
        // 12 个查询与 5 个受控更新都必须显式携带可信租户条件；INSERT 的租户值由应用层写入对象绑定。
        assertThat(count(mapper,"tenant_id=#{tenantid}")).isEqualTo(17);
        assertThat(mapper).contains("for update").contains("record_version=#{expectedversion}");
    }
    @Test void freezesPermissionsAndNoExternalSuccess(){String sql=resource("/db/migration/V202608230074__gate7d_exception_center_permissions.sql").toLowerCase();
        for(String p:new String[]{"operations:exception:read","operations:exception:scan","operations:exception:claim",
            "operations:exception:operate","operations:exception:repair","operations:exception:review","operations:exception:close"})assertThat(sql).contains(p);}
    private int count(String v,String n){int c=0,p=0;while((p=v.indexOf(n,p))>=0){c++;p+=n.length();}return c;}
    private String resource(String name){try(var in=getClass().getResourceAsStream(name)){if(in==null)throw new IllegalStateException(name);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}}
}
