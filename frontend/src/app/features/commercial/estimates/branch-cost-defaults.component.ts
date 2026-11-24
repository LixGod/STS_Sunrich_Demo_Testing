import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ConfirmationService, MessageService } from 'primeng/api';
import { catchError, of } from 'rxjs';
import { LookupOption, LookupService } from '../../../core/lookups/lookup.service';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { BranchCostDefault, BranchCostDefaultService, EquipmentOption } from './estimate.service';

@Component({
  selector: 's2s-branch-cost-defaults',
  imports: [TranslatePipe, PageHeaderComponent],
  templateUrl: './branch-cost-defaults.component.html',
  styleUrl: './branch-cost-defaults.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BranchCostDefaultsComponent {
  private readonly service = inject(BranchCostDefaultService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly lookups = inject(LookupService);
  private readonly confirmation = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly translate = inject(TranslateService);
  protected readonly branches = toSignal(this.lookups.branches().pipe(catchError(() => of([] as LookupOption[]))), { initialValue: [] });
  protected readonly branchId = signal<string | null>(null);
  protected readonly rows = signal<BranchCostDefault[]>([]);
  protected readonly equipment = signal<EquipmentOption[]>([]);
  protected readonly selectedId = signal('');
  protected readonly manualUsdPrice = signal('');
  protected readonly selected = computed(() => this.equipment().find(option => option.modelId === this.selectedId()));
  protected readonly editingId = signal<string | null>(null);
  protected readonly editPrice = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  constructor() {
    effect(() => { if (!this.branchId() && this.branches().length) this.branchId.set(this.branches()[0].value); });
    effect(() => { const id = this.branchId(); if (id) this.refresh(id); });
  }

  protected selectBranch(id: string): void { this.branchId.set(id); this.selectedId.set(''); this.editingId.set(null); this.error.set(null); }
  protected selectEquipment(id: string): void { this.selectedId.set(id); this.manualUsdPrice.set(''); this.error.set(null); }
  protected equipmentName(row: BranchCostDefault): string {
    return this.equipment().find(model => model.modelId === row.equipmentModelId)?.name ?? row.description ?? 'Equipment';
  }
  protected alreadyAdded(id: string): boolean { return this.rows().some(row => row.equipmentModelId === id); }

  protected add(): void {
    const branchId = this.branchId();
    const model = this.selected();
    if (!branchId || !model || this.saving()) return;
    if (this.alreadyAdded(model.modelId)) { this.error.set('This equipment is already in the branch cost lines.'); return; }
    const price = model.standardRateCurrency === 'USD' ? model.standardRateUsd : Number(this.manualUsdPrice());
    if (!model.costHeadId || price == null || !Number.isFinite(price) || price < 0 ||
        (model.standardRateCurrency !== 'USD' && this.manualUsdPrice().trim() === '')) {
      this.error.set('Set an expense cost head and rate. For non-USD inventory rates, enter the branch default price in USD.');
      return;
    }
    this.saving.set(true);
    this.service.create(branchId, {
      equipmentModelId: model.modelId, costHeadId: model.costHeadId, description: model.name,
      quantity: 1, unitPrice: price, active: true,
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.saving.set(false); this.selectedId.set(''); this.refresh(branchId); },
      error: () => { this.saving.set(false); this.error.set('Could not add this equipment cost line.'); },
    });
  }

  protected startEdit(row: BranchCostDefault): void {
    this.editingId.set(row.id);
    this.editPrice.set(String(row.unitPrice));
    this.error.set(null);
  }

  protected cancelEdit(): void {
    this.editingId.set(null);
    this.error.set(null);
  }

  protected saveEdit(row: BranchCostDefault): void {
    const branchId = this.branchId();
    const price = Number(this.editPrice());
    if (!branchId || this.saving()) return;
    if (!Number.isFinite(price) || price < 0) {
      this.error.set('Enter a valid non-negative USD rate.');
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.service.update(branchId, row, {
      equipmentModelId: row.equipmentModelId, costHeadId: row.costHeadId, description: row.description,
      quantity: row.quantity, unitPrice: price, active: row.active,
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.saving.set(false);
        this.editingId.set(null);
        this.messages.add({ severity: 'success', summary: this.translate.instant('common.saved') });
        this.refresh(branchId);
      },
      error: () => { this.saving.set(false); this.error.set('Could not update this cost line.'); },
    });
  }

  protected toggle(row: BranchCostDefault): void {
    const branchId = this.branchId();
    if (!branchId || this.saving()) return;
    this.saving.set(true); this.error.set(null);
    this.service.update(branchId, row, {
      equipmentModelId: row.equipmentModelId, costHeadId: row.costHeadId, description: row.description,
      quantity: row.quantity, unitPrice: row.unitPrice, active: !row.active,
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.saving.set(false); this.refresh(branchId); },
      error: () => { this.saving.set(false); this.error.set('Could not update this cost line.'); },
    });
  }

  protected confirmDelete(row: BranchCostDefault): void {
    const branchId = this.branchId();
    if (!branchId || this.saving()) return;
    this.confirmation.confirm({
      header: this.translate.instant('common.delete'),
      message: this.translate.instant('common.confirmDelete'),
      acceptLabel: this.translate.instant('common.delete'),
      rejectLabel: this.translate.instant('common.cancel'),
      acceptButtonProps: { severity: 'danger' },
      accept: () => {
        this.saving.set(true);
        this.error.set(null);
        this.service.delete(branchId, row).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: () => {
            this.saving.set(false);
            if (this.editingId() === row.id) this.editingId.set(null);
            this.messages.add({ severity: 'success', summary: this.translate.instant('common.deleted') });
            this.refresh(branchId);
          },
          error: () => { this.saving.set(false); this.error.set('Could not delete this cost line.'); },
        });
      },
    });
  }

  private refresh(id: string): void {
    this.service.equipmentOptions(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: options => this.equipment.set(options),
      error: () => this.error.set('Could not load equipment inventory.'),
    });
    this.service.list(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: rows => this.rows.set(rows),
      error: () => this.error.set('Could not load branch cost defaults.'),
    });
  }
}
