import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { MooringModel, MooringObject, MooringSnapshot } from './mooring-plan.service';

@Component({
  selector: 's2s-mooring-plan-canvas',
  imports: [TranslatePipe],
  templateUrl: './mooring-plan-canvas.component.html',
  styleUrl: './mooring-plan-canvas.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MooringPlanCanvasComponent {
  readonly model = input.required<MooringModel>();
  readonly snapshot = input.required<MooringSnapshot>();
  readonly layerUrls = input<Record<string, string>>({});
  readonly editable = input(false);
  readonly moved = output<{ id: string; xM: number; yM: number }>();
  readonly objectSelected = output<string>();

  protected readonly view = signal({ x: -200, y: -100, width: 400, height: 200 });
  protected readonly viewBox = computed(() => { const v = this.view(); return `${v.x} ${v.y} ${v.width} ${v.height}`; });
  protected readonly selected = signal<string | null>(null);
  private drag: { id: string; x: number; y: number; originalX: number; originalY: number } | null = null;

  protected vessel(id: string) { return this.snapshot().vessels.find(v => v.jobVesselId === id); }
  protected value(id: string, code: string): number | null {
    return this.vessel(id)?.dimensions.find(d => d.code === code)?.value ?? null;
  }
  protected rect(id: string): { x: number; y: number; width: number; height: number } | null {
    const bow = this.value(id, 'BOW_TO_MANIFOLD');
    const stern = this.value(id, 'STERN_TO_MANIFOLD');
    const beam = this.value(id, 'BEAM');
    const vessel = this.model().vessels.find(v => v.jobVesselId === id);
    if (bow === null || stern === null || beam === null || !vessel || vessel.lateralOffsetM === null) return null;
    return { x: vessel.orientation === 'BOW_UP' ? -bow : -stern, y: vessel.lateralOffsetM - beam / 2,
      width: bow + stern, height: beam };
  }
  protected point(id: string | null): MooringObject | undefined { return this.model().objects.find(o => o.id === id); }
  protected rulerTicks(): number[] {
    const step = this.model().rulerStepM;
    if (!step || step <= 0) return [];
    return Array.from({ length: 25 }, (_, i) => (i - 12) * step);
  }
  protected zoom(factor: number): void {
    const v = this.view(); const width = Math.max(40, Math.min(1200, v.width * factor));
    const height = width / 2;
    this.view.set({ x: v.x + (v.width - width) / 2, y: v.y + (v.height - height) / 2, width, height });
  }
  protected pan(dx: number): void { this.view.update(v => ({ ...v, x: v.x + dx })); }
  protected start(event: PointerEvent, obj: MooringObject): void {
    if (!this.editable()) return;
    this.selected.set(obj.id);
    this.drag = { id: obj.id, x: event.clientX, y: event.clientY, originalX: obj.xM, originalY: obj.yM };
    (event.currentTarget as Element).setPointerCapture(event.pointerId);
  }
  protected end(event: PointerEvent): void {
    if (!this.drag) return;
    const svg = (event.currentTarget as Element).closest('svg');
    const box = svg?.getBoundingClientRect();
    if (box && box.width && box.height) {
      const v = this.view();
      const xM = this.round(this.drag.originalX + (event.clientX - this.drag.x) * v.width / box.width);
      const yM = this.round(this.drag.originalY + (event.clientY - this.drag.y) * v.height / box.height);
      if (xM !== this.drag.originalX || yM !== this.drag.originalY) this.moved.emit({ id: this.drag.id, xM, yM });
      else this.objectSelected.emit(this.drag.id);
    } else this.objectSelected.emit(this.drag.id);
    this.drag = null;
  }
  protected keyMove(event: KeyboardEvent, obj: MooringObject): void {
    if (!this.editable()) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.selected.set(obj.id);
      this.objectSelected.emit(obj.id);
      return;
    }
    const moves: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    const move = moves[event.key];
    if (!move) return;
    event.preventDefault();
    this.moved.emit({ id: obj.id, xM: this.round(obj.xM + move[0]), yM: this.round(obj.yM + move[1]) });
  }
  private round(value: number): number { return Math.round(value * 1000) / 1000; }
}
