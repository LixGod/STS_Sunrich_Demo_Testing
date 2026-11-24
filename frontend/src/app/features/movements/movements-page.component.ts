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
import { ClockOverview, Movement, MovementContext, MovementInput, MovementsService } from './movements.service';

@Component({
  selector: 's2s-movements-page',
  imports: [FormsModule, RouterLink, TranslatePipe, Message, PageHeaderComponent, DateTimePickerComponent],
  templateUrl: './movements-page.component.html',
  styleUrl: './movements-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MovementsPageComponent {
  private readonly service = inject(MovementsService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  readonly jobId = input.required<string>();
  readonly embedded = input(false);
  protected readonly context = signal<MovementContext | null>(null);
  protected readonly rows = signal<Movement[]>([]);
  protected readonly clock = signal<ClockOverview | null>(null);
  protected readonly page = signal(0);
  protected readonly total = signal(0);
  protected readonly editing = signal<Movement | null>(null);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly now = signal(Date.now());
  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.opsMovementWrite));
  protected readonly canApprove = computed(() => this.auth.hasPermission(Permissions.opsMovementApprove));
  protected readonly canApproveChange = computed(() => this.auth.hasPermission(Permissions.opsMovementChangeApprove));
  protected readonly hasActiveDeparture = computed(() => this.rows().some(r => r.status === 'ACTIVE'));
  protected readonly remaining = computed(() => {
    const c = this.clock();
    return c?.expiryAt ? Math.round((Date.parse(c.expiryAt) - (c.clock.stopAt ? Date.parse(c.clock.stopAt) : this.now())) / 60000) : null;
  });
  protected form = this.emptyForm();
  protected basis = { hours: 48, reason: '' };
  protected adjustment = { hours: '', reason: '' };
  protected decisionReason = '';
  protected deliveryOutcome = '';
  protected returnLocal = '';
  protected confirmedDeparture = false;

  constructor() {
    effect(() => { const id = this.jobId(); untracked(() => this.reload(id)); });
    const timer = setInterval(() => this.now.set(Date.now()), 60000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  protected reload(id = this.jobId()): void {
    this.service.context(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => { this.context.set(value);
        if (!this.editing() && !this.form.departedLocal) this.form.departedLocal = this.toLocal(new Date().toISOString());
        this.loadPage(0); this.loadClock(); },
      error: err => this.fail(err),
    });
  }
  protected loadPage(page: number): void {
    this.service.list(this.jobId(), page).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => { this.rows.set(value.items); this.page.set(value.page); this.total.set(value.totalElements); },
      error: err => this.fail(err),
    });
  }
  private loadClock(): void {
    this.service.clock(this.jobId()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => { this.clock.set(value); this.basis.hours = value.clock.basisHours; },
      error: err => this.fail(err),
    });
  }
  protected edit(row: Movement | null): void {
    this.editing.set(row);
    this.confirmedDeparture = false;
    this.form = row ? { departedLocal: this.toLocal(row.departedAt),
      returnedLocal: row.returnedAt ? this.toLocal(row.returnedAt) : '', reason: '' }
      : this.emptyForm();
  }
  protected save(): void {
    const row = this.editing();
    const done = () => { this.edit(null); this.loadPage(0); this.loadClock(); };
    const zone = this.context()?.timeZone;
    if (!zone) return;
    const departedAt = this.toUtc(this.form.departedLocal, zone);
    if (!departedAt) {
      this.errorKey.set('movements.invalidTime'); return;
    }
    if (!row) {
      if (this.confirmedDeparture) this.run(this.service.dispatch(this.jobId(), departedAt), done);
      return;
    }
    const returnedAt = this.form.returnedLocal ? this.toUtc(this.form.returnedLocal, zone) : null;
    if ((this.form.returnedLocal && !returnedAt) || (returnedAt && Date.parse(returnedAt) <= Date.parse(departedAt))) {
      this.errorKey.set('movements.invalidTime'); return;
    }
    const payload: MovementInput = { craftId: row.craftId, departedAt, returnedAt,
      allocationIds: row.assets.map(a => a.allocationId), serviceNotes: row.serviceNotes,
      standbyNotes: row.standbyNotes, delayNotes: row.delayNotes,
      damageNotes: row.damageNotes, reason: this.form.reason };
    this.run(this.service.correct(this.jobId(), row, payload), done);
  }
  protected checkReturned(row: Movement, checked: boolean): void {
    if (!checked) return;
    if (!this.returnLocal) {
      const dep = Date.parse(row.departedAt);
      const now = Date.now();
      this.returnLocal = this.toLocal(new Date(now > dep ? now : dep + 60_000).toISOString());
    }
    this.returned(row);
  }
  protected returned(row: Movement): void {
    const zone = this.context()?.timeZone;
    const at = zone ? this.toUtc(this.returnLocal, zone) : null;
    if (!at) { this.errorKey.set('movements.invalidTime'); return; }
    this.run(this.service.returned(this.jobId(), row, at), () => { this.returnLocal = ''; this.loadPage(this.page()); this.loadClock(); });
  }
  protected void(row: Movement): void {
    this.run(this.service.void(this.jobId(), row, this.decisionReason), () => { this.decisionReason = ''; this.loadPage(this.page()); });
  }
  protected decideMovement(row: Movement, decision: string): void {
    this.run(this.service.decideMovement(this.jobId(), row, decision, this.decisionReason),
      () => { this.decisionReason = ''; this.loadPage(this.page()); this.loadClock(); });
  }
  protected file(row: Movement, event: Event): void {
    const upload = (event.target as HTMLInputElement).files?.[0];
    const problem = upload && uploadProblem(upload);
    if (problem) { this.errorKey.set(problem); return; }
    if (upload) this.run(this.service.movementFile(this.jobId(), row.id, upload), () => this.loadPage(this.page()));
  }
  protected overrideBasis(): void {
    const selected = this.clock()?.selection;
    if (selected) this.run(this.service.basis(this.jobId(), selected, this.basis.hours, this.basis.reason),
      () => { this.basis.reason = ''; this.loadClock(); });
  }
  protected adjust(): void {
    this.run(this.service.adjustment(this.jobId(), this.adjustment.hours, this.adjustment.reason),
      () => { this.adjustment = { hours: '', reason: '' }; this.loadClock(); });
  }
  protected decideAdjustment(id: string, decision: string): void {
    const row = this.clock()?.adjustments.find(a => a.id === id);
    if (row) this.run(this.service.decideAdjustment(this.jobId(), row, decision, this.decisionReason),
      () => { this.decisionReason = ''; this.loadClock(); });
  }
  protected release(): void {
    const notice = this.clock()?.notice;
    if (notice) this.run(this.service.release(this.jobId(), notice), () => this.loadClock());
  }
  protected delivery(): void {
    const notice = this.clock()?.notice;
    if (notice) this.run(this.service.delivery(this.jobId(), notice, this.deliveryOutcome), () => this.loadClock());
  }
  protected display(value: string | null): string {
    if (!value) return '—';
    return new Intl.DateTimeFormat(undefined, { timeZone: this.context()?.timeZone ?? 'UTC',
      dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
  }
  private emptyForm() { return { departedLocal: this.context() ? this.toLocal(new Date().toISOString()) : '', returnedLocal: '', reason: '' }; }
  private run(request: Observable<unknown>, done: () => void): void {
    this.busy.set(true); this.errorKey.set(null);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy.set(false); done(); },
      error: err => { this.busy.set(false); this.fail(err); },
    });
  }
  private fail(err: unknown): void { this.errorKey.set(problemKey(toProblem(err))); }
  private toLocal(instant: string): string {
    const parts = new Intl.DateTimeFormat('en-GB', { timeZone: this.context()?.timeZone ?? 'UTC', year: 'numeric',
      month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(instant));
    const part = (name: string) => parts.find(p => p.type === name)?.value ?? '00';
    return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`;
  }
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
