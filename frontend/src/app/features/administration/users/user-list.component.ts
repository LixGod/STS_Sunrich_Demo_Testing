import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Select } from 'primeng/select';
import { PageQuery } from '../../../core/api/api.models';
import { Permissions } from '../../../core/auth/permissions';
import { LanguageService } from '../../../core/i18n/language.service';
import { DataTableComponent } from '../../../shared/components/data-table/data-table.component';
import { ColumnDef, RowAction } from '../../../shared/components/data-table/table.models';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { CanDirective } from '../../../shared/directives/can.directive';
import { UserService, UserStatus, UserSummary } from './user.service';

@Component({
  selector: 's2s-user-list',
  imports: [FormsModule, RouterLink, TranslatePipe, Button, Select, DataTableComponent, PageHeaderComponent, CanDirective],
  templateUrl: './user-list.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserListComponent {
  private readonly service = inject(UserService);
  private readonly router = inject(Router);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);

  protected readonly permissions = Permissions;
  protected readonly status = signal<UserStatus | null>(null);
  protected readonly params = computed(() => ({ status: this.status() }));
  protected readonly load = (query: PageQuery) => this.service.list(query);

  protected readonly statusOptions = computed(() => {
    this.language.current();
    return (['ACTIVE', 'SUSPENDED', 'DEACTIVATED'] as const).map((value) => ({
      label: this.translate.instant(`status.${value}`),
      value,
    }));
  });

  protected readonly columns: ColumnDef<UserSummary>[] = [
    { key: 'fullName', headerKey: 'users.fullName', sortField: 'fullName', value: row => row.fullName || this.translate.instant('users.draftUser'), link: (row) => ['/administration/users', row.id] },
    { key: 'workEmail', headerKey: 'users.workEmail', sortField: 'workEmail' },
    { key: 'department', headerKey: 'users.department' },
    { key: 'lastLoginAt', headerKey: 'users.lastLogin', sortField: 'lastLoginAt', type: 'datetime' },
    { key: 'status', headerKey: 'masters.status', sortField: 'status', type: 'status', width: '9rem' },
  ];

  protected readonly actions: RowAction<UserSummary>[] = [
    { icon: 'pi pi-angle-right', labelKey: 'common.open', run: (row) => void this.router.navigate(['/administration/users', row.id]) },
  ];
}
