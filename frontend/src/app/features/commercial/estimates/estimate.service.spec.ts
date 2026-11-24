import { EquipmentOption, equipmentRate, estimateTotals, filterEstimateCostHeads, lineAmount } from './estimate.service';

describe('estimate totals', () => {
  it('computes exact decimal amounts where JavaScript numbers would not', () => {
    // 0.1 x 3 is 0.30000000000000004 in floating point.
    expect(lineAmount(3, 0.1).toString()).toBe('0.3');
    // Half-up to 4 decimals, like the server.
    expect(lineAmount(1.5, 999.99).toFixed(4)).toBe('1499.9850');
    expect(lineAmount(0.333, 0.00015).toFixed(4)).toBe('0.0000');
    expect(lineAmount(null, 5).toString()).toBe('0');
  });

  it('sums cost and revenue separately and derives the margin', () => {
    const totals = estimateTotals([
      { kind: 'COST', quantity: 2, unitPrice: 1250.125 },
      { kind: 'COST', quantity: 1.5, unitPrice: 999.99 },
      { kind: 'REVENUE', quantity: 1, unitPrice: 10000 },
    ]);
    expect(totals.cost.toFixed(4)).toBe('4000.2350');
    expect(totals.revenue.toFixed(4)).toBe('10000.0000');
    expect(totals.margin.toFixed(4)).toBe('5999.7650');
    expect(totals.marginPercent?.toFixed(2)).toBe('60.00');
  });

  it('has no margin percentage without revenue', () => {
    const totals = estimateTotals([{ kind: 'COST', quantity: 1, unitPrice: 100 }]);
    expect(totals.margin.toFixed(2)).toBe('-100.00');
    expect(totals.marginPercent).toBeNull();
  });
});

describe('inventory estimate defaults', () => {
  const item: EquipmentOption = {
    modelId: 'fender', name: 'Fender', categoryLabel: 'Fenders', costHeadId: 'head',
    standardRate: 125.5, standardRateCurrency: 'USD', available: 4,
  };

  it('uses the inventory rate only in its configured currency', () => {
    expect(equipmentRate(item, 'USD')).toBe(125.5);
    expect(equipmentRate(item, 'EUR')).toBeNull();
    expect(equipmentRate(undefined, 'USD')).toBeNull();
  });
});

describe('estimate cost head filtering', () => {
  const heads = [
    { value: 'h-exp', meta: { kind: 'EXPENSE', code: 'FENDERS' } },
    { value: 'h-rev', meta: { kind: 'REVENUE', code: 'STS-SERVICE' } },
    { value: 'h-special', meta: { kind: 'REVENUE', code: 'SPECIAL-REQ' } },
  ];

  it('shows SPECIAL-REQ only when the enquiry has a special requirement or an existing line uses it', () => {
    expect(filterEstimateCostHeads(heads, 'REVENUE', false).map((h) => h.value)).toEqual(['h-rev']);
    expect(filterEstimateCostHeads(heads, 'REVENUE', true).map((h) => h.value)).toEqual(['h-rev', 'h-special']);
    expect(filterEstimateCostHeads(heads, 'REVENUE', false, new Set(['h-special'])).map((h) => h.value))
      .toEqual(['h-rev', 'h-special']);
    expect(filterEstimateCostHeads(heads, 'COST', true).map((h) => h.value)).toEqual(['h-exp']);
  });
});
