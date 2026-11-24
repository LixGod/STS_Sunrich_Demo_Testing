import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { AuditTrailComponent } from '../../../shared/components/audit-trail/audit-trail.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';

/** Whole audit trail within the reader's scope, newest first. */
@Component({
  selector: 's2s-audit-page',
  imports: [TranslatePipe, AuditTrailComponent, PageHeaderComponent],
  templateUrl: './audit-page.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuditPageComponent {}
