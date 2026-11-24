import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { Subject, catchError, debounceTime, of, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { problemKey, toProblem } from '../../core/api/api.models';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { DateTimePickerComponent } from '../../shared/components/date-time-picker/date-time-picker.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { Allocation, AllocationInput, ResourceChoice, ResourceJobContext, ResourcePolicy, ResourcesService } from './resources.service';

type Kind = 'ASSET' | 'PERSON';

@Component({
  selector: 's2s-job-resources-page',
  imports: [ReactiveFormsModule, RouterLink, TranslatePipe, Button, Message, PageHeaderComponent, StatusTagComponent, DateTimePickerComponent],
  templateUrl: './job-resources-page.component.html',
  styleUrl: './job-resources-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JobResourcesPageComponent {
  private readonly service = inject(ResourcesService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly choiceSearch = new Subject<{ jobId: string; q: string }>();
  readonly jobId = input.required<string>();

  protected readonly context = signal<ResourceJobContext | null>(null);
  protected readonly assets = signal<ResourceChoice[]>([]);
  protected readonly people = signal<ResourceChoice[]>([]);
  protected readonly rows = signal<Allocation[]>([]);
  protected readonly policy = signal<ResourcePolicy | null>(null);
  protected readonly page = signal(0);
  protected readonly total = signal(0);
  protected readonly editing = signal<Allocation | null>(null);
  protected readonly collision = signal(false);
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly success = signal(false);
  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.opsAllocationWrite));
  protected readonly canApprove = computed(() => this.auth.hasPermission(Permissions.opsAllocationApprove));
  protected readonly choices = computed(() => {
    const kind = this.selectedKind();
    if (kind === 'ASSET') return this.assets().map(a => ({ id: a.id, label: `${a.identifier} · ${a.category}` }));
    return this.people().map(p => ({ id: p.id, label: `${p.identifier} · ${p.label}` }));
  });

  protected readonly form = new FormGroup({
    kind: new FormControl<Kind>('ASSET', { nonNullable: true }),
    resourceId: new FormControl('', { nonNullable: true, validators: Validators.required }),
    fromLocal: new FormControl('', { nonNullable: true, validators: Validators.required }),
    toLocal: new FormControl('', { nonNullable: true, validators: Validators.required }),
    exceptionReason: new FormControl('', { nonNullable: true }),
  });
  protected readonly decisionReason = new FormControl('', { nonNullable: true });
  protected readonly resourceSearch = new FormControl('', { nonNullable: true });
  protected readonly selectedKind = signal<Kind>('ASSET');

  constructor() {
    effect(() => { const id = this.jobId(); untracked(() => this.reload(id)); });
    this.form.controls.kind.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(kind => {
      this.selectedKind.set(kind); this.form.controls.resourceId.setValue('');
    });
    this.choiceSearch.pipe(
      debounceTime(250),
      switchMap(({ jobId, q }) => this.service.choices(jobId, q).pipe(catchError(error => {
        this.errorKey.set(problemKey(toProblem(error))); return of(null);
      }))),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(result => { if (result) { this.assets.set(result.assets); this.people.set(result.people); this.policy.set(result.policy); } });
  }

  protected reload(id = this.jobId()): void {
    this.errorKey.set(null);
    this.service.context(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: context => {
        this.context.set(context);
        this.loadChoices();
        this.loadPage(0);
      }, error: error => this.errorKey.set(problemKey(toProblem(error))),
    });
  }

  private loadChoices(): void {
    this.choiceSearch.next({ jobId: this.jobId(), q: this.resourceSearch.value });
  }

  protected search(): void { if (this.context()) this.loadChoices(); }

  protected loadPage(page: number): void {
    this.service.allocations(this.jobId(), page).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: result => { this.rows.set(result.items); this.page.set(result.page); this.total.set(result.totalElements); },
      error: error => this.errorKey.set(problemKey(toProblem(error))),
    });
  }

  protected edit(row: Allocation | null): void {
    this.editing.set(row); this.collision.set(false); this.errorKey.set(null);
    this.form.reset({ kind: row?.kind === 'PERSON' ? 'PERSON' : 'ASSET', resourceId: row?.resourceId ?? '',
      fromLocal: row ? this.toLocal(row.startAt) : '', toLocal: row ? this.toLocal(row.endAt) : '',
      exceptionReason: row?.exceptionReason ?? '' }, { emitEvent: false });
    this.selectedKind.set(row?.kind === 'PERSON' ? 'PERSON' : 'ASSET');
  }

  protected save(): void {
    const context = this.context();
    if (!context || this.form.invalid) { this.form.markAllAsTouched(); return; }
    const v = this.form.getRawValue();
    const startAt = this.toUtc(v.fromLocal, context.timeZone);
    const endAt = this.toUtc(v.toLocal, context.timeZone);
    if (!startAt || !endAt || Date.parse(endAt) <= Date.parse(startAt)) {
      this.errorKey.set('resources.invalidInterval'); return;
    }
    const input: AllocationInput = { kind: v.kind, resourceId: v.resourceId, startAt, endAt,
      exceptionReason: v.exceptionReason.trim() || null };
    this.saving.set(true); this.errorKey.set(null);
    const editing = this.editing();
    const request = editing ? this.service.amendAllocation(this.jobId(), editing, input) : this.service.createAllocation(this.jobId(), input);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.saving.set(false); this.success.set(true); this.edit(null); this.loadPage(0); },
      error: error => { this.saving.set(false); const problem = toProblem(error);
        this.collision.set(problem.code === 'ALLOCATION_COLLISION'); this.errorKey.set(problemKey(problem)); },
    });
  }

  protected decide(row: Allocation, decision: 'APPROVE' | 'RETURN'): void {
    const reason = this.decisionReason.value.trim();
    if (!reason) { this.errorKey.set('resources.reasonRequired'); return; }
    this.saving.set(true);
    this.service.decideAllocation(this.jobId(), row, decision, reason).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.saving.set(false); this.decisionReason.setValue(''); this.loadPage(this.page()); },
      error: error => { this.saving.set(false); this.errorKey.set(problemKey(toProblem(error))); },
    });
  }

  protected cancel(row: Allocation): void {
    const reason = this.decisionReason.value.trim();
    if (!reason) { this.errorKey.set('resources.reasonRequired'); return; }
    this.saving.set(true);
    this.service.cancelAllocation(this.jobId(), row, reason).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.saving.set(false); this.decisionReason.setValue(''); this.loadPage(this.page()); },
      error: error => { this.saving.set(false); this.errorKey.set(problemKey(toProblem(error))); },
    });
  }

  protected display(instant: string): string {
    const zone = this.context()?.timeZone ?? 'UTC';
    return new Intl.DateTimeFormat(undefined, { timeZone: zone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(instant));
  }

  private toLocal(instant: string): string {
    const zone = this.context()?.timeZone ?? 'UTC';
    const parts = new Intl.DateTimeFormat('en-GB', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(instant));
    const part = (name: string) => parts.find(p => p.type === name)?.value ?? '00';
    return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`;
  }

  /** Convert a chosen wall time in the job zone to UTC; reject impossible DST times. */
  private toUtc(value: string, zone: string): string | null {
    if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d$/.test(value)) return null;
    const wanted = Date.parse(`${value}:00Z`);
    if (!Number.isFinite(wanted)) return null;
    let guess = wanted;
    for (let i = 0; i < 3; i++) {
      const shown = this.zonedMillis(guess, zone);
      guess += wanted - shown;
    }
    return this.zonedMillis(guess, zone) === wanted ? new Date(guess).toISOString() : null;
  }

  private zonedMillis(time: number, zone: string): number {
    const parts = new Intl.DateTimeFormat('en-GB', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(time));
    const part = (name: string) => Number(parts.find(p => p.type === name)?.value ?? 0);
    return Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second'));
  }
}
