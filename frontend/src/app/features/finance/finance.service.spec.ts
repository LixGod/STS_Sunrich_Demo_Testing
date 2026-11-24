import { formatMoney, invoiceTotals, localToday, toBig } from './finance.service';

describe('invoiceTotals', () => {
  it('rounds each line and its tax half up to the currency decimals, exactly', () => {
    const t = invoiceTotals([
      { quantity: '1', unitPrice: '10000', rate: 18 },
      { quantity: '2.5', unitPrice: '250.333', rate: '18' },
      { quantity: '3', unitPrice: '0.1', rate: null },
    ], 2);
    expect(t.lines.map((l) => [l.amount.toFixed(2), l.tax.toFixed(2)])).toEqual([['10000.00', '1800.00'], ['625.83', '112.65'], ['0.30', '0.00']]);
    expect(t.net.toFixed(2)).toBe('10626.13');
    expect(t.tax.toFixed(2)).toBe('1912.65');
    expect(t.gross.toFixed(2)).toBe('12538.78');
  });

  it('treats unfinished input as zero and honours zero-decimal currencies', () => {
    expect(invoiceTotals([{ quantity: '', unitPrice: 'abc', rate: 5 }], 2).gross.toFixed(2)).toBe('0.00');
    expect(invoiceTotals([{ quantity: '1', unitPrice: '100.5', rate: null }], 0).net.toFixed(0)).toBe('101');
  });

  it('sums rounded lines instead of rounding their unrounded sum', () => {
    const totals = invoiceTotals([
      { quantity: '1', unitPrice: '0.005', rate: null },
      { quantity: '1', unitPrice: '0.005', rate: null },
    ], 2);
    expect(totals.net.toFixed(2)).toBe('0.02');
  });
});

describe('formatMoney', () => {
  it('groups thousands and keeps the currency decimals', () => {
    expect(formatMoney(12538.48)).toBe('12,538.48');
    expect(formatMoney(10000, 2)).toBe('10,000.00');
    expect(formatMoney(toBig('-1234.5'), 2)).toBe('-1,234.50');
    expect(formatMoney(500, 0)).toBe('500');
  });
});

describe('localToday', () => {
  it('formats date using local year, month, and day', () => {
    const d = new Date(2026, 9, 22, 2, 30);
    expect(localToday(d)).toBe('2026-10-22');
  });
});
