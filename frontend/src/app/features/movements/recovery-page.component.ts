import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Message } from 'primeng/message';
import { Observable } from 'rxjs';
import { problemKey, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { uploadProblem } from '../../core/files/upload-validation';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { DateTimePickerComponent } from '../../shared/components/date-time-picker/date-time-picker.component';
import { MovementContext, MovementsService, Recovery, RecoveryAsset } from './movements.service';

@Component({
  selector: 's2s-recovery-page',
  imports: [FormsModule, RouterLink, TranslatePipe, Message, PageHeaderComponent, DateTimePickerComponent],
  templateUrl: './recovery-page.component.html',
  styleUrl: './movements-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RecoveryPageComponent {
  private readonly service = inject(MovementsService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  readonly jobId = input.required<string>();
  protected readonly context = signal<MovementContext | null>(null);
  protected readonly data = signal<Recovery | null>(null);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.opsRecoveryWrite));
  protected readonly canApprove = computed(() => this.auth.hasPermission(Permissions.opsRecoveryApprove));
  protected disembarkLocal = '';
  protected reason = '';
  protected drafts: Record<string, { status: string; notes: string }> = {};

  constructor() { effect(() => { const id = this.jobId(); untracked(() => this.reload(id)); }); }
  protected reload(id = this.jobId()): void {
    this.service.context(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: context => this.context.set(context), error: err => this.fail(err),
    });
    this.service.recovery(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: data => { this.data.set(data); this.drafts = Object.fromEntries(data.assets.map(a =>
        [a.allocationId, { status: a.status === 'UNRECORDED' ? 'RETURNED' : a.status, notes: a.notes ?? '' }])); },
      error: err => this.fail(err),
    });
  }
  protected disembark(): void {
    const row = this.data(); const zone = this.context()?.timeZone;
    if (!row || !zone) return;
    const at = this.toUtc(this.disembarkLocal, zone);
    if (!at) { this.errorKey.set('movements.invalidTime'); return; }
    this.run(this.service.disembark(this.jobId(), row, at, this.reason), () => this.reload());
  }
  protected record(row: RecoveryAsset): void {
    const draft = this.drafts[row.allocationId];
    if (draft) this.run(this.service.record(this.jobId(), row, draft.status, draft.notes, this.reason), () => this.reload());
  }
  protected resolve(row: RecoveryAsset): void {
    this.run(this.service.resolve(this.jobId(), row, this.reason), () => this.reload());
  }
  protected file(row: RecoveryAsset, event: Event): void {
    const upload = (event.target as HTMLInputElement).files?.[0];
    const problem = upload && uploadProblem(upload);
    if (problem) { this.errorKey.set(problem); return; }
    if (upload) this.run(this.service.recoveryFile(this.jobId(), row, upload), () => this.reload());
  }
  protected close(): void {
    const row = this.data();
    if (row) this.run(this.service.close(this.jobId(), row), () => this.reload());
  }
  protected display(value: string | null): string {
    if (!value) return '—';
    return new Intl.DateTimeFormat(undefined, { timeZone: this.context()?.timeZone ?? 'UTC',
      dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
  }
  private run(request: Observable<unknown>, done: () => void): void {
    this.busy.set(true); this.errorKey.set(null);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy.set(false); this.reason = ''; done(); },
      error: err => { this.busy.set(false); this.fail(err); },
    });
  }
  private fail(err: unknown): void { this.errorKey.set(problemKey(toProblem(err))); }
  private toUtc(value: string, zone: string): string | null {
    if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d$/.test(value)) return null;
    const wanted = Date.parse(`${value}:00Z`);
    if (!Number.isFinite(wanted)) return null;
    let guess = wanted;
    for (let i = 0; i < 3; i++) guess += wanted - this.zonedMillis(guess, zone);
    return this.zonedMillis(guess, zone) === wanted ? new Date(guess).toISOString() : null;
  }
  private zonedMillis(time: number, zone: string): number {
    const parts = new Intl.DateTimeFormat('en-GB', { timeZone: zone, year: 'numeric', month: '2-digit',
      day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(time));
    const part = (name: string) => Number(parts.find(p => p.type === name)?.value ?? 0);
    return Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second'));
  }
}
