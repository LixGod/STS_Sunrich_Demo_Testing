import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';

export type Loaded<T> = T | 'error' | null;

/** A quotation version waiting for the user's decision (GET /quotation-versions/pending-approval). */
export interface PendingApproval {
  versionId: string;
  enquiryId: string;
  enquiryReference: string;
  quotationReference: string;
  versionNo: number;
  clientName: string | null;
  currency: string;
  totalAmount: number;
  submittedByName: string | null;
  submittedAt: string | null;
}

/** Dashboard widget for approvers: quotations submitted by colleagues (never the user's own), oldest first. */
@Component({
  selector: 's2s-approvals-panel',
  imports: [RouterLink, TranslatePipe, MoneyPipe, UtcDateTimePipe],
  templateUrl: './approvals-panel.component.html',
  styleUrl: './approvals-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApprovalsPanelComponent {
  readonly approvals = input<Loaded<PendingApproval[]>>(null);
}
