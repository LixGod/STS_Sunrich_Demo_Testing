/** Fast browser feedback; the backend remains authoritative and checks file signatures. */
export function uploadProblem(file: File): string | null {
  if (file.size === 0) return 'errors.codes.FILE_EMPTY';
  if (file.size > 25 * 1024 * 1024) return 'errors.codes.FILE_TOO_LARGE';
  if (!['application/pdf', 'image/png', 'image/jpeg'].includes(file.type) && !/\.(pdf|png|jpe?g)$/i.test(file.name))
    return 'errors.codes.FILE_TYPE_NOT_ALLOWED';
  return null;
}
