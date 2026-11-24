-- Freeze existing effective role access as explicit per-user grants before roles stop supplying permissions.
CREATE TABLE user_permission_grant (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    permission_code VARCHAR(80) NOT NULL,
    branch_id BINARY(16) NULL,
    PRIMARY KEY (id),
    KEY ix_user_permission_grant_user (user_id),
    CONSTRAINT fk_upg_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_upg_permission FOREIGN KEY (permission_code) REFERENCES permission (code),
    CONSTRAINT fk_upg_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
) ENGINE = InnoDB;

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
) access;

-- A direct allow was company-wide, even when the user's roles were branch-limited.
INSERT INTO user_permission_grant (id, user_id, permission_code, branch_id)
SELECT UUID_TO_BIN(UUID()), uo.user_id, uo.permission_code, NULL
FROM user_permission_override uo
LEFT JOIN user_permission_grant upg
  ON upg.user_id = uo.user_id AND upg.permission_code = uo.permission_code AND upg.branch_id IS NULL
WHERE uo.allowed = TRUE
  AND upg.id IS NULL;

-- JOB.READ was previously universal unless explicitly blocked.
INSERT INTO user_permission_grant (id, user_id, permission_code, branch_id)
SELECT UUID_TO_BIN(UUID()), u.id, 'JOB.READ', NULL
FROM app_user u
LEFT JOIN user_permission_override uo ON uo.user_id = u.id AND uo.permission_code = 'JOB.READ'
LEFT JOIN user_permission_grant upg
  ON upg.user_id = u.id AND upg.permission_code = 'JOB.READ' AND upg.branch_id IS NULL
WHERE (uo.allowed IS NULL OR uo.allowed = TRUE)
  AND upg.id IS NULL;
