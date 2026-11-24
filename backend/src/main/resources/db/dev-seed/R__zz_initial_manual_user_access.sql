-- Give seeded demo accounts their configured starting access. Later role edits do not change it.
INSERT INTO user_permission_grant (id, user_id, permission_code, branch_id)
SELECT UUID_TO_BIN(UUID()), access.user_id, access.permission_code, access.branch_id
FROM (
    SELECT DISTINCT urs.user_id, rp.permission_code, urs.branch_id
    FROM user_role_scope urs
    JOIN role_permission rp ON rp.role_id = urs.role_id
    LEFT JOIN user_permission_override uo
      ON uo.user_id = urs.user_id AND uo.permission_code = rp.permission_code
    WHERE urs.valid_from <= CURRENT_DATE()
      AND (urs.valid_to IS NULL OR urs.valid_to >= CURRENT_DATE())
      AND (uo.allowed IS NULL OR uo.allowed = TRUE)
) access
LEFT JOIN user_permission_grant upg
  ON upg.user_id = access.user_id AND upg.permission_code = access.permission_code
    AND upg.branch_id <=> access.branch_id
WHERE upg.id IS NULL;

INSERT INTO user_permission_grant (id, user_id, permission_code, branch_id)
SELECT UUID_TO_BIN(UUID()), u.id, 'JOB.READ', NULL
FROM app_user u
LEFT JOIN user_permission_override uo ON uo.user_id = u.id AND uo.permission_code = 'JOB.READ'
LEFT JOIN user_permission_grant upg
  ON upg.user_id = u.id AND upg.permission_code = 'JOB.READ' AND upg.branch_id IS NULL
WHERE (uo.allowed IS NULL OR uo.allowed = TRUE)
  AND upg.id IS NULL;
