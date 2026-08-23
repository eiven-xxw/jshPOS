-- 商业 V1 组合根前向修复：已发布的 Gate 4C 与 Gate 7C 迁移曾重复占用 9200540—9200543。
-- 不修改历史迁移；在后续迁移执行前，把成本权限及角色绑定幂等迁到独立保留区间。
DELIMITER $$
CREATE PROCEDURE jsh_repair_gate4c_gate7c_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9201540 AND 9201543 AND NOT (
          (menu_id=9201540 AND perms='inventory:cost-balance:read') OR
          (menu_id=9201541 AND perms='inventory:cost-ledger:read') OR
          (menu_id=9201542 AND perms='inventory:cost-policy:publish') OR
          (menu_id=9201543 AND perms='inventory:cost-rebuild')
        )
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 4C forward repair target menu ID collision';
    END IF;

    INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
    SELECT role_id,
           CASE menu_id
             WHEN 9200540 THEN 9201540 WHEN 9200541 THEN 9201541
             WHEN 9200542 THEN 9201542 WHEN 9200543 THEN 9201543
           END
      FROM sys_role_menu
     WHERE menu_id IN (9200540,9200541,9200542,9200543)
       AND EXISTS (
           SELECT 1 FROM sys_menu source_menu
            WHERE source_menu.menu_id=sys_role_menu.menu_id
              AND source_menu.perms=CASE sys_role_menu.menu_id
                WHEN 9200540 THEN 'inventory:cost-balance:read'
                WHEN 9200541 THEN 'inventory:cost-ledger:read'
                WHEN 9200542 THEN 'inventory:cost-policy:publish'
                WHEN 9200543 THEN 'inventory:cost-rebuild'
              END
       );

    DELETE role_menu
      FROM sys_role_menu role_menu
      JOIN sys_menu source_menu ON source_menu.menu_id=role_menu.menu_id
     WHERE (source_menu.menu_id=9200540 AND source_menu.perms='inventory:cost-balance:read')
        OR (source_menu.menu_id=9200541 AND source_menu.perms='inventory:cost-ledger:read')
        OR (source_menu.menu_id=9200542 AND source_menu.perms='inventory:cost-policy:publish')
        OR (source_menu.menu_id=9200543 AND source_menu.perms='inventory:cost-rebuild');

    DELETE source_menu
      FROM sys_menu source_menu
      JOIN sys_menu target_menu
        ON target_menu.menu_id=CASE source_menu.menu_id
             WHEN 9200540 THEN 9201540 WHEN 9200541 THEN 9201541
             WHEN 9200542 THEN 9201542 WHEN 9200543 THEN 9201543
           END
       AND target_menu.perms=source_menu.perms
     WHERE (source_menu.menu_id=9200540 AND source_menu.perms='inventory:cost-balance:read')
        OR (source_menu.menu_id=9200541 AND source_menu.perms='inventory:cost-ledger:read')
        OR (source_menu.menu_id=9200542 AND source_menu.perms='inventory:cost-policy:publish')
        OR (source_menu.menu_id=9200543 AND source_menu.perms='inventory:cost-rebuild');

    UPDATE sys_menu
       SET menu_id=CASE menu_id
         WHEN 9200540 THEN 9201540 WHEN 9200541 THEN 9201541
         WHEN 9200542 THEN 9201542 WHEN 9200543 THEN 9201543
       END
     WHERE (menu_id=9200540 AND perms='inventory:cost-balance:read')
        OR (menu_id=9200541 AND perms='inventory:cost-ledger:read')
        OR (menu_id=9200542 AND perms='inventory:cost-policy:publish')
        OR (menu_id=9200543 AND perms='inventory:cost-rebuild');

    IF EXISTS (
        SELECT 1 FROM sys_menu WHERE
           (menu_id=9200540 AND perms='inventory:cost-balance:read') OR
           (menu_id=9200541 AND perms='inventory:cost-ledger:read') OR
           (menu_id=9200542 AND perms='inventory:cost-policy:publish') OR
           (menu_id=9200543 AND perms='inventory:cost-rebuild')
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 4C forward repair did not converge';
    END IF;
END$$
DELIMITER ;

CALL jsh_repair_gate4c_gate7c_menu_ids();
DROP PROCEDURE jsh_repair_gate4c_gate7c_menu_ids;
