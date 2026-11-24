import { ChangeDetectionStrategy, Component, ElementRef, effect, input, viewChild } from '@angular/core';
import Big from 'big.js';
import { BarController, BarElement, CategoryScale, Chart, ChartConfiguration, Legend, LinearScale, Tooltip } from 'chart.js';

Chart.register(BarController, BarElement, CategoryScale, LinearScale, Tooltip, Legend);

export function sharePercent(part: Big, whole: Big): number {
  return whole.gt(0) ? Math.max(0, Math.min(100, Number(part.div(whole).times(100).toFixed(2)))) : 0;
}

/** A bar chart for the dashboard. The canvas carries a plain-text summary so the figures are never colour or picture only. */
@Component({
  selector: 's2s-dashboard-chart',
  template: '<div class="chart-frame" [style.height]="height()"><canvas #canvas role="img" [attr.aria-label]="summary()"></canvas></div>',
  styles: '.chart-frame { position: relative; }',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardChartComponent {
  private readonly canvas = viewChild<ElementRef<HTMLCanvasElement>>('canvas');
  readonly config = input.required<ChartConfiguration<'bar'>>();
  readonly summary = input.required<string>();
  readonly height = input('13rem');

  constructor() {
    effect((onCleanup) => {
      const canvas = this.canvas()?.nativeElement;
      if (!canvas) return;
      Chart.defaults.color = getComputedStyle(canvas).getPropertyValue('--s2s-text').trim() || '#29272b';
      const config = this.config();
      const chart = new Chart(canvas, {
        ...config,
        options: { responsive: true, maintainAspectRatio: false, animation: false, ...config.options },
      });
      onCleanup(() => chart.destroy());
    });
  }
}
