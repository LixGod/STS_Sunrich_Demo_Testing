import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { AuthService } from '../../../core/auth/auth.service';
import { Permissions } from '../../../core/auth/permissions';

/** Section links of one enquiry: details and the combined estimate/quotation workflow. */
@Component({
  selector: 's2s-enquiry-tabs',
  imports: [RouterLink, RouterLinkActive, TranslatePipe],
  template: `
    <nav class="tabs" [attr.aria-label]="'enquiry.tabs.label' | translate">
      @for (tab of tabs(); track tab.key) {
        <a [routerLink]="tab.link" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }" ariaCurrentWhenActive="page">
          <i [class]="tab.icon" aria-hidden="true"></i>{{ 'enquiry.tabs.' + tab.key | translate }}
        </a>
      }
    </nav>
  `,
  styles: `
    .tabs {
      display: flex;
      gap: 0.25rem;
      overflow-x: auto;
      overflow-y: hidden;
      scrollbar-width: none;
      border-bottom: 1px solid var(--s2s-border);
    }
    a {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      padding: 0.6rem 0.9rem;
      color: var(--s2s-text-muted);
      text-decoration: none;
      white-space: nowrap;
      border-bottom: 2px solid transparent;
      margin-bottom: -1px;
      font-weight: 600;
    }
    a:hover,
    a:focus-visible {
      color: var(--p-primary-color);
    }
    a.active {
      color: var(--p-primary-color);
      border-bottom-color: var(--p-primary-color);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EnquiryTabsComponent {
  private readonly auth = inject(AuthService);

  readonly enquiryId = input.required<string>();

  protected readonly tabs = computed(() => {
    this.auth.currentUser();
    const base = ['/commercial/enquiries', this.enquiryId()];
    return [
      { key: 'overview', icon: 'pi pi-inbox', link: base, show: true },
      { key: 'estimate', icon: 'pi pi-calculator', link: [...base, 'estimate'],
        show: this.auth.hasAnyPermission([Permissions.estimateRead, Permissions.quotationRead]) },
    ].filter((tab) => tab.show);
  });
}
