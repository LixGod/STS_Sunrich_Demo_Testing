import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ConfirmationService, MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { PageQuery, problemKey, toProblem } from '../../../core/api/api.models';
import { Permissions } from '../../../core/auth/permissions';
import { LookupService } from '../../../core/lookups/lookup.service';
import { ActiveFilterComponent } from '../../../shared/components/active-filter/active-filter.component';
import { DataTableComponent } from '../../../shared/components/data-table/data-table.component';
import { ColumnDef, RowAction } from '../../../shared/components/data-table/table.models';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { CanDirective } from '../../../shared/directives/can.directive';
import { Branch, BranchService } from '../organisation.service';

@Component({
  selector: 's2s-branch-list',
  imports: [
    FormsModule, RouterLink, TranslatePipe, Button, DataTableComponent, PageHeaderComponent,
    ActiveFilterComponent, CanDirective,
  ],
  templateUrl: './branch-list.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BranchListComponent {
  private readonly service = inject(BranchService);
  private readonly router = inject(Router);
  private readonly confirmation = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly translate = inject(TranslateService);
  private readonly lookups = inject(LookupService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly table = viewChild(DataTableComponent);

  protected readonly permissions = Permissions;
  protected readonly active = signal<boolean | null>(true);
  protected readonly params = computed(() => ({ active: this.active() }));
  protected readonly load = (query: PageQuery) => this.service.list(query);

  protected readonly columns: ColumnDef<Branch>[] = [
    { key: 'code', headerKey: 'masters.code', sortField: 'code', link: (row) => ['/administration/branches', row.id], width: '8rem' },
    { key: 'name', headerKey: 'masters.name', sortField: 'name' },
    { key: 'timezone', headerKey: 'masters.timezone' },
    { key: 'active', headerKey: 'masters.status', type: 'active', sortField: 'active', width: '8rem' },
  ];

  protected readonly actions: RowAction<Branch>[] = [
    { icon: 'pi pi-pencil', labelKey: 'common.edit', run: (row) => void this.router.navigate(['/administration/branches', row.id]) },
    {
      icon: 'pi pi-trash',
      labelKey: 'common.delete',
      severity: 'danger',
      permission: Permissions.masterOrgWrite,
      run: (row) => this.confirmDelete(row),
    },
  ];

  private confirmDelete(row: Branch): void {
    this.confirmation.confirm({
      header: this.translate.instant('common.delete'),
      message: this.translate.instant('common.confirmDelete'),
      acceptLabel: this.translate.instant('common.delete'),
      rejectLabel: this.translate.instant('common.cancel'),
      acceptButtonProps: { severity: 'danger' },
      accept: () => {
        this.service.delete(row.id, row.version).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: () => {
            this.lookups.invalidate();
            this.messages.add({ severity: 'success', summary: this.translate.instant('common.deleted') });
            this.table()?.reload();
          },
          error: (error: unknown) => this.messages.add({ severity: 'error', summary: this.translate.instant(problemKey(toProblem(error))) }),
        });
      },
    });
  }
}
