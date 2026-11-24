import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { NAV_GROUPS } from '../../layout/nav.config';
import { PageHeaderComponent } from '../page-header/page-header.component';

/** Placeholder for navigation groups delivered in later phases, so the full structure is visible from Phase 1. */
@Component({
  selector: 's2s-coming-soon',
  imports: [TranslatePipe, PageHeaderComponent],
  templateUrl: './coming-soon.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComingSoonComponent {
  /** Route parameter `:group`. */
  readonly group = input('');

  protected readonly navGroup = computed(() => NAV_GROUPS.find((g) => g.id === this.group()));
}
