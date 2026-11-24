import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ConfirmationService, MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Select } from 'primeng/select';
import { PageQuery, problemKey, toProblem } from '../../../core/api/api.models';
import { Permissions } from '../../../core/auth/permissions';
import { LanguageService } from '../../../core/i18n/language.service';
import { LookupService } from '../../../core/lookups/lookup.service';
import { ActiveFilterComponent } from '../../../shared/components/active-filter/active-filter.component';
import { DataTableComponent } from '../../../shared/components/data-table/data-table.component';
import { ColumnDef, RowAction } from '../../../shared/components/data-table/table.models';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { CanDirective } from '../../../shared/directives/can.directive';
import { CLIENT_ROLES, Client, ClientRole, ClientService } from './client.service';

@Component({
  selector: 's2s-client-list',
  imports: [FormsModule, RouterLink, TranslatePipe, Button, Select, DataTableComponent, PageHeaderComponent, ActiveFilterComponent, CanDirective],
  templateUrl: './client-list.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClientListComponent {
  private readonly service = inject(ClientService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly confirmation = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly lookups = inject(LookupService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly table = viewChild(DataTableComponent);

  protected readonly permissions = Permissions;
  protected readonly active = signal<boolean | null>(true);
  protected readonly role = signal<ClientRole | null>(null);
  protected readonly params = computed(() => ({ active: this.active(), role: this.role() }));
  protected readonly roleOptions = computed(() => {
    this.language.current();
    return CLIENT_ROLES.map((value) => ({ value, label: this.translate.instant(`enums.clientRole.${value}`) }));
  });
  protected readonly load = (query: PageQuery) => this.service.list(query);

  protected readonly columns: ColumnDef<Client>[] = [
    { key: 'code', headerKey: 'masters.code', sortField: 'code', link: (row) => [row.id], width: '9rem' },
    { key: 'legalName', headerKey: 'masters.client.legalName', sortField: 'legalName' },
    { key: 'roles', headerKey: 'masters.client.roles', value: (row) => row.roles.join(', ') },
    { key: 'countryCode', headerKey: 'masters.client.country', width: '7rem' },
    { key: 'emails', headerKey: 'masters.client.email', value: (row) => row.emails.join(', ') },
    { key: 'phone', headerKey: 'masters.client.phone', value: (row) => (row.phones.length ? row.phones : row.phone ? [row.phone] : []).join(', '), width: '12rem' },
    { key: 'active', headerKey: 'masters.status', type: 'active', sortField: 'active', width: '8rem' },
  ];

  protected readonly actions: RowAction<Client>[] = [
    { icon: 'pi pi-pencil', labelKey: 'common.edit', run: (row) => void this.router.navigate([row.id], { relativeTo: this.route }) },
    {
      icon: 'pi pi-trash',
      labelKey: 'common.delete',
      severity: 'danger',
      permission: Permissions.masterCommercialWrite,
      run: (row) => this.confirmDelete(row),
    },
  ];

  private confirmDelete(row: Client): void {
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
