-- Keep the permission code stable for existing grants while showing the operational role as Marine.
UPDATE role SET name='Marine', description='Marine document collection, vessel review, JPO and mooring planning.'
WHERE code='TECHNICAL';
