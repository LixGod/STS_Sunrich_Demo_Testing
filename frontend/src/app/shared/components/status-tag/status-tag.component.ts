import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { Tag } from 'primeng/tag';

type Severity = 'success' | 'secondary' | 'info' | 'warn' | 'danger' | 'contrast';

const SEVERITY: Record<string, Severity> = {
  ACTIVE: 'success',
  INACTIVE: 'secondary',
  SUSPENDED: 'warn',
  DEACTIVATED: 'secondary',
  DRAFT: 'secondary',
  DONE: 'success',
  UNDER_REVIEW: 'info',
  APPROVED: 'success',
  ISSUED: 'contrast',
  SUPERSEDED: 'secondary',
  UNVERIFIED: 'warn',
  VERIFIED: 'success',
  PENDING_CONFIRMATION: 'warn',
  CONFIRMED: 'success',
  COSTING: 'info',
  QUOTATION_APPROVAL: 'info',
  QUOTED: 'info',
  ACCEPTED: 'success',
  LOST: 'secondary',
  CANCELLED: 'secondary',
  PENDING_APPROVAL: 'warn',
  REJECTED: 'danger',
  OWNER_REJECTED: 'danger',
  OPEN: 'info',
  UNPAID: 'danger',
  PARTIAL: 'warn',
  PAID: 'success',
};

/** Status chip. Pass `active` for masters (Active/Inactive) or `status` for workflow states. */
@Component({
  selector: 's2s-status-tag',
  imports: [Tag, TranslatePipe],
  templateUrl: './status-tag.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusTagComponent {
  readonly active = input<boolean | undefined>(undefined);
  readonly status = input<string | undefined>(undefined);

  protected readonly code = computed(() => {
    const status = this.status();
    if (status) return status;
    return this.active() === false ? 'INACTIVE' : 'ACTIVE';
  });
  protected readonly severity = computed(() => SEVERITY[this.code()] ?? 'info');
}
