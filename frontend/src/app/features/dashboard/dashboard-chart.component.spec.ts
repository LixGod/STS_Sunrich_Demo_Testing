import Big from 'big.js';
import { sharePercent } from './dashboard-chart.component';

it('shows a share of the total without combining currencies or dividing by zero', () => {
  expect(sharePercent(new Big('1000.50'), new Big('600.30'))).toBe(100);
  expect(sharePercent(new Big('600.30'), new Big('1000.50'))).toBe(60);
  expect(sharePercent(new Big(0), new Big(0))).toBe(0);
});
