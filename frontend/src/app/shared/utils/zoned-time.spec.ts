import { fromUtc, toUtc } from './zoned-time';

describe('job time conversion', () => {
  it('uses the selected zone and round trips an existing instant', () => {
    const local = new Date(2026, 9, 19, 10, 0);
    expect(toUtc(local, 'Asia/Dubai')).toBe('2026-10-19T06:00:00.000Z');
    expect(fromUtc('2026-10-19T06:00:00Z', 'Asia/Dubai')).toEqual(local);
  });

  it('rejects a wall time skipped by daylight saving', () => {
    expect(toUtc(new Date(2026, 2, 29, 2, 30), 'Europe/Berlin')).toBeNull();
  });

  it('rejects a wall time repeated by daylight saving', () => {
    expect(toUtc(new Date(2026, 9, 25, 2, 30), 'Europe/Berlin')).toBeNull();
  });
});
