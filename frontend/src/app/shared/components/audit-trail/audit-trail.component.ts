import { JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { UtcDateTimePipe } from '../../pipes/utc-datetime.pipe';
import { AuditEvent, AuditTrailService } from './audit-trail.service';

/**
 * Who changed what and when (SRS FR-UM-04). Newest first, loaded in small pages on demand ("Load more").
 * Without an entity filter it shows the whole trail visible to the reader's scope.
 */
@Component({
  selector: 's2s-audit-trail',
  imports: [TranslatePipe, Button, UtcDateTimePipe, JsonPipe],
  templateUrl: './audit-trail.component.html',
  styleUrl: './audit-trail.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuditTrailComponent {
  private readonly service = inject(AuditTrailService);
  private readonly destroyRef = inject(DestroyRef);

  readonly entityType = input<string | undefined>(undefined);
  readonly entityId = input<string | undefined>(undefined);
  readonly pageSize = input(20);

  protected readonly events = signal<AuditEvent[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly failed = signal(false);
  protected readonly expanded = signal<number | null>(null);

  constructor() {
    effect(() => {
      const type = this.entityType();
      const id = this.entityId();
      untracked(() => {
        this.events.set([]);
        this.load(type, id, null);
      });
    });
  }

  protected loadMore(): void {
    this.load(this.entityType(), this.entityId(), this.nextCursor());
  }

  protected toggle(seq: number): void {
    this.expanded.update((current) => (current === seq ? null : seq));
  }

  private load(entityType: string | undefined, entityId: string | undefined, after: string | null): void {
    this.loading.set(true);
    this.failed.set(false);
    this.service
      .list({ entityType, entityId, after, limit: this.pageSize() })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.events.update((current) => [...current, ...page.items]);
          this.nextCursor.set(page.nextCursor);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.failed.set(true);
        },
      });
  }
}
