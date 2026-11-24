import { uploadProblem } from './upload-validation';

describe('uploadProblem', () => {
  it('rejects empty, oversized and unsupported files before upload', () => {
    expect(uploadProblem(new File([], 'empty.pdf', { type: 'application/pdf' }))).toBe('errors.codes.FILE_EMPTY');
    const large = new File(['x'], 'large.pdf', { type: 'application/pdf' });
    Object.defineProperty(large, 'size', { value: 25 * 1024 * 1024 + 1 });
    expect(uploadProblem(large)).toBe('errors.codes.FILE_TOO_LARGE');
    expect(uploadProblem(new File(['x'], 'script.exe', { type: 'application/x-msdownload' }))).toBe('errors.codes.FILE_TYPE_NOT_ALLOWED');
    expect(uploadProblem(new File(['x'], 'evidence.pdf', { type: 'application/pdf' }))).toBeNull();
  });
});
