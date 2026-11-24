-- Operations owns incident reporting and review; the reviewer must still be a different person.
INSERT IGNORE INTO role_permission (role_id, permission_code)
SELECT id, 'OPS.INCIDENT.REVIEW' FROM role WHERE code = 'OPERATIONS';

-- Keep existing HSE users' scope and dates when replacing their role.
DELETE old_scope FROM user_role_scope old_scope
JOIN role old_role ON old_role.id = old_scope.role_id AND old_role.code = 'HSE_REVIEWER'
JOIN role ops_role ON ops_role.code = 'OPERATIONS'
JOIN user_role_scope ops_scope ON ops_scope.user_id = old_scope.user_id AND ops_scope.role_id = ops_role.id
    AND ops_scope.legal_entity_id <=> old_scope.legal_entity_id
    AND ops_scope.branch_id <=> old_scope.branch_id
    AND ops_scope.valid_from = old_scope.valid_from
    AND ops_scope.valid_to <=> old_scope.valid_to;

UPDATE user_role_scope scope
JOIN role old_role ON old_role.id = scope.role_id AND old_role.code = 'HSE_REVIEWER'
JOIN role ops_role ON ops_role.code = 'OPERATIONS'
SET scope.role_id = ops_role.id;

DELETE rp FROM role_permission rp JOIN role r ON r.id = rp.role_id WHERE r.code = 'HSE_REVIEWER';
DELETE FROM role WHERE code = 'HSE_REVIEWER';

-- Retire the local-only HSE demonstration login after its role has been migrated.
UPDATE app_user SET status = 'DEACTIVATED', version = version + 1, updated_at = UTC_TIMESTAMP(6)
WHERE external_subject_id = 'dev-hse';
