import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { API_BASE_URL, ifMatch, toProblem } from '../../core/api/api.models';

interface WorkflowAction { to: string; reasonRequired: boolean; blockers: { code: string; message: string }[] }
interface Workflow {
  status: string; revision: number; version: number; readinessRequestedBy: string | null; closureRequestedBy: string | null;
  canRequest: boolean; canOverride: boolean; actions: WorkflowAction[];
  history: { toStatus: string; reason: string | null; occurredAt: string }[];
}

@Component({
  selector: 's2s-job-workflow',
  imports: [FormsModule, TranslatePipe],
  templateUrl: './job-workflow.component.html',
  styleUrl: './job-workflow.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JobWorkflowComponent {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly destroyRef = inject(DestroyRef);
  readonly jobId = input.required<string>();
  readonly changed = output<void>();
  protected readonly data = signal<Workflow | null>(null);
  protected readonly selected = computed(() => this.data()?.actions.find((a) =>
    a.to === 'APPROVED_FOR_READINESS' && this.data()?.readinessRequestedBy ||
    a.to === 'CLOSED' && this.data()?.closureRequestedBy));
  protected readonly error = signal('');
  protected readonly busy = signal(false);
  protected reason = '';
  protected overrideReason = '';

  constructor() {
    effect((onCleanup) => {
      const request = this.http.post<Workflow>(`${this.base}/jobs/${this.jobId()}/workflow/sync`, {}).subscribe({
        next: (data) => { this.data.set(data); this.changed.emit(); }, error: () => this.error.set('errors.loadFailed'),
      });
      onCleanup(() => request.unsubscribe());
    });
  }

  protected save(requestStage?: string): void {
    const current = this.data();
    if (!current || this.busy()) return;
    const action = this.selected();
    if (!requestStage && (!action || action.reasonRequired && !this.reason.trim()
      || action.blockers.length > 0 && !this.overrideReason.trim())) return;
    this.busy.set(true);
    this.error.set('');
    const body = requestStage ? { stage: requestStage } : { to: action!.to, reason: this.reason, overrideReason: this.overrideReason };
    this.http.post<Workflow>(`${this.base}/jobs/${this.jobId()}/workflow/${requestStage ? 'requests' : 'transitions'}`, body,
      { headers: { 'If-Match': ifMatch(current.version) } }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (data) => {
        this.data.set(data); this.reason = ''; this.overrideReason = '';
        this.busy.set(false); this.changed.emit();
      },
      error: (error: unknown) => {
        const problem = toProblem(error);
        this.error.set(problem.errors?.map((e) => e.message).filter(Boolean).join(' ') || problem.detail || 'errors.saveFailed');
        this.busy.set(false);
      },
    });
  }
}
