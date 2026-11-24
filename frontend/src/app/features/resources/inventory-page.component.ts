import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import { InventoryRow, InventoryUnit, ResourcesService } from './resources.service';

/** Equipment stock per model: original, in use, available, out of service and the revised count after damage and loss. */
@Component({
  selector: 's2s-inventory-page',
  imports: [RouterLink, TranslatePipe, Button, Message, EmptyStateComponent, PageHeaderComponent, UtcDateTimePipe],
  template: `
    <s2s-page-header [heading]="'resources.inventory.title' | translate" [subtitle]="'resources.inventory.subtitle' | translate" />
    @if (error()) { <p-message severity="error" role="alert" [text]="'errors.loadFailed' | translate" /> }
    <section class="s2s-panel">
      <ul class="items">
        @for (r of rows(); track r.modelId) {
          <li>
            <div class="line1"><strong>{{ r.modelName }}</strong><span class="s2s-muted">{{ r.categoryLabel }} · {{ r.modelCode }}</span></div>
            <dl class="figures">
              <div><dt>{{ 'resources.inventory.original' | translate }}</dt><dd>{{ r.original }}</dd></div>
              <div><dt>{{ 'resources.inventory.inUse' | translate }}</dt><dd>{{ r.inUse }}</dd></div>
              <div><dt>{{ 'resources.available' | translate }}</dt><dd>{{ r.available }}</dd></div>
              <div><dt>{{ 'resources.maintenance' | translate }}</dt><dd>{{ r.maintenance }}</dd></div>
              <div><dt>{{ 'resources.damaged' | translate }}</dt><dd [class.alert]="r.damaged > 0">{{ r.damaged }}</dd></div>
              <div><dt>{{ 'resources.lost' | translate }}</dt><dd [class.alert]="r.lost > 0">{{ r.lost }}</dd></div>
              <div><dt>{{ 'resources.inventory.revised' | translate }}</dt><dd><strong>{{ r.revised }}</strong></dd></div>
            </dl>
            <p-button [text]="true" size="small" [label]="(open() === r.modelId ? 'resources.inventory.hideUnits' : 'resources.inventory.showUnits') | translate"
              (onClick)="toggle(r)" />
            @if (open() === r.modelId) {
              <ul class="units">
                @for (u of units(); track u.assetId) {
                  <li>
                    <div class="line1"><strong>{{ u.assetIdentifier }}</strong><span class="chip" [class.alert]="u.status === 'DAMAGED' || u.status === 'LOST'">{{ 'resources.' + u.status.toLowerCase() | translate }}</span>
                      @if (u.jobId) { <a [routerLink]="['/jobs', u.jobId]">{{ 'resources.inventory.onJob' | translate: { job: u.jobNumber } }}</a> }</div>
                    @for (h of u.history; track h.at) {
                      <p class="s2s-muted">{{ h.at | utcDateTime }} · <a [routerLink]="['/jobs', h.jobId, 'recovery']">{{ h.jobNumber }}</a> ·
                        {{ (h.result === 'SHORT' ? 'resources.lost' : 'resources.damaged') | translate }}@if (h.resolution) { · {{ h.resolution }} }</p>
                    }
                  </li>
                } @empty { <li class="s2s-muted">{{ 'resources.inventory.noUnits' | translate }}</li> }
              </ul>
            }
          </li>
        } @empty {
          <li><s2s-empty-state messageKey="resources.inventory.empty" icon="pi pi-box" /></li>
        }
      </ul>
    </section>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .items, .units { list-style: none; margin: 0; padding: 0; }
    .items > li { padding: 0.75rem 0; border-top: 1px solid var(--s2s-border); overflow-wrap: anywhere; }
    .units { margin-top: 0.25rem; padding-left: 0.75rem; border-left: 3px solid var(--s2s-border); }
    .units li { padding: 0.4rem 0; }
    .units p { margin: 0.2rem 0 0; font-size: 0.85rem; }
    .line1 { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
    .figures { display: grid; grid-template-columns: repeat(auto-fit, minmax(6.5rem, 1fr)); gap: 0.25rem 1rem; margin: 0.4rem 0 0; }
    .figures > * { min-width: 0; }
    .figures dt { font-size: 0.75rem; color: var(--s2s-text-muted); }
    .figures dd { margin: 0; font-size: 1.1rem; font-variant-numeric: tabular-nums; }
    .chip { font-size: 0.8rem; padding: 0.1rem 0.5rem; border: 1px solid var(--s2s-border); border-radius: 999px; }
    .alert { color: var(--p-primary-600, #84252e); font-weight: 600; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InventoryPageComponent {
  private readonly api = inject(ResourcesService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly rows = signal<InventoryRow[]>([]);
  protected readonly units = signal<InventoryUnit[]>([]);
  protected readonly open = signal<string | null>(null);
  protected readonly error = signal(false);

  constructor() {
    this.api.inventory().pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (rows) => { this.error.set(false); this.rows.set(rows); }, error: () => this.error.set(true) });
  }

  protected toggle(row: InventoryRow): void {
    if (this.open() === row.modelId) { this.open.set(null); return; }
    this.open.set(row.modelId); this.units.set([]);
    this.api.inventoryUnits(row.modelId).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (units) => this.units.set(units), error: () => this.error.set(true) });
  }
}
