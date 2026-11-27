import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { AuthService } from '../../../core/auth/auth.service';
import { NAV_GROUPS, NavGroup } from '../nav.config';

@Component({
  selector: 's2s-side-nav',
  imports: [RouterLink, RouterLinkActive, TranslatePipe],
  templateUrl: './side-nav.component.html',
  styleUrl: './side-nav.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SideNavComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Emitted after a link is chosen, so the mobile drawer can close. */
  readonly navigated = output<void>();

  /** Only permitted links are displayed; planned groups remain discoverable below them. */
  protected readonly groups = computed<NavGroup[]>(() => {
    const user = this.auth.currentUser();
    const setupAccess = (permissions: readonly string[]) =>
      user?.grants.some(grant => grant.roleCode === 'STS_ADMIN' || grant.roleCode === 'SYSTEM_ADMIN') ||
      permissions.some(permission => user?.directPermissions?.includes(permission) ||
        this.auth.hasGlobalPermission(permission.replace(/\.READ$/, '.WRITE')));
    return NAV_GROUPS.map((group) => ({
        ...group,
        items: group.items.filter((item) => this.auth.hasAnyPermission(item.permissions) &&
          (!item.globalOnly || item.permissions.some(permission => this.auth.hasGlobalPermission(permission)))),
        sections: group.sections
          ?.map((section) => ({
            ...section,
            items: section.items.filter((item) => this.auth.hasAnyPermission(item.permissions) &&
              (!item.globalOnly || item.permissions.some(permission => this.auth.hasGlobalPermission(permission))) &&
              (group.id !== 'sts-admin' || section.labelKey === 'nav.operationsSetup' || setupAccess(item.permissions))),
          }))
          .filter((section) => section.items.length > 0),
      })).filter((group) => group.phase !== undefined || group.items.length > 0 || !!group.sections?.length);
  });

  protected readonly availableGroups = computed(() => this.groups().filter((group) => group.phase === undefined));
  protected readonly plannedGroups = computed(() => this.groups().filter((group) => group.phase !== undefined));
  protected readonly plannedOpen = signal(this.router.url.startsWith('/coming-soon/'));

  protected onPlannedToggle(event: Event): void {
    this.plannedOpen.set((event.target as HTMLDetailsElement).open);
  }
}
