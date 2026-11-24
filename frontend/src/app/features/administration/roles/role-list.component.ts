import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { AuthService } from '../../../core/auth/auth.service';
import { Permissions } from '../../../core/auth/permissions';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { Role, UserService } from '../users/user.service';

@Component({
  selector: 's2s-role-list',
  imports: [FormsModule, TranslatePipe, PageHeaderComponent],
  templateUrl: './role-list.component.html',
  styleUrl: './role-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RoleListComponent {
  private readonly service = inject(UserService);
  private readonly auth = inject(AuthService);
  protected readonly canWrite = computed(() => this.auth.hasGlobalPermission(Permissions.adminRoleWrite));
  protected readonly roles = signal<Role[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly editing = signal<Role | null | undefined>(undefined);
  protected code = '';
  protected name = '';
  protected description = '';

  constructor() { this.reload(); }

  protected reload(): void {
    this.loading.set(true);
    this.service.roles().subscribe({
      next: roles => {
        this.roles.set(roles);
        this.loading.set(false);
      },
      error: () => { this.error.set('Could not load roles.'); this.loading.set(false); },
    });
  }

  protected edit(role: Role | null): void {
    this.editing.set(role);
    this.code = role?.code ?? '';
    this.name = role?.name ?? '';
    this.description = role?.description ?? '';
    this.error.set('');
  }

  protected save(): void {
    const current = this.editing();
    if (current === undefined || !this.canWrite() || !this.name.trim() || (!current && !/^[A-Z][A-Z0-9_]{1,39}$/.test(this.code))) return;
    this.saving.set(true);
    const request = { name: this.name.trim(), description: this.description.trim() || null, permissions: current?.permissions ?? [] };
    const operation = current ? this.service.updateRole(current, request) : this.service.createRole({ code: this.code, ...request });
    operation.subscribe({
      next: () => { this.editing.set(undefined); this.saving.set(false); this.error.set(''); this.reload(); },
      error: () => { this.saving.set(false); this.error.set('Could not save role. Reload and try again.'); },
    });
  }
}
