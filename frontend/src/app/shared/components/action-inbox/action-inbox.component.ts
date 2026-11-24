import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Subscription } from 'rxjs';
import { API_BASE_URL, Page, ifMatch, toProblem } from '../../../core/api/api.models';
import { AuthService } from '../../../core/auth/auth.service';
import { UtcDateTimePipe } from '../../pipes/utc-datetime.pipe';

interface Alert {
  id: string; jobId: string; jobNumber: string; code: string; label: string; route: string; state: string;
  conditionActive: boolean; dueAt: string | null; createdAt: string; escalatedAt: string | null;
  acknowledgedAt: string | null; resolvedAt: string | null; resolutionReason: string | null; version: number;
}
interface Rule { code: string; label: string; leadMinutes: number | null; escalationMinutes: number | null; enabled: boolean; version: number }

@Component({
  selector: 's2s-action-inbox', imports: [FormsModule, RouterLink, TranslatePipe, UtcDateTimePipe],
  templateUrl: './action-inbox.component.html', styleUrl: './action-inbox.component.scss', changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActionInboxComponent {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  private listRequest?: Subscription;
  readonly compact = input(false);
  protected readonly data = signal<Page<Alert> | null>(null);
  protected readonly error = signal('');
  protected readonly busy = signal(false);
  protected readonly rules = signal<Rule[]>([]);
  protected readonly canConfigure = computed(() => this.auth.hasPermission('MASTER.ORG.WRITE'));
  protected state = '';
  protected reason = '';
  protected page = 0;
  protected readonly size = computed(() => this.compact() ? 5 : 25);

  constructor() {
    try { this.state = localStorage.getItem(`s2s.inbox.${this.auth.currentUser()?.id}`) ?? ''; } catch { /* Storage is optional for a saved filter. */ }
    if (!['', 'OPEN', 'ACKNOWLEDGED', 'RESOLVED'].includes(this.state)) this.state = '';
    effect(() => { this.compact(); untracked(() => this.reload()); });
  }
  protected filter(): void {
    this.page = 0;
    try { localStorage.setItem(`s2s.inbox.${this.auth.currentUser()?.id}`, this.state); } catch { /* The filter still applies without storage. */ }
    this.reload();
  }
  protected reload(): void {
    this.listRequest?.unsubscribe();
    this.listRequest = this.http.get<Page<Alert>>(`${this.base}/alerts`, { params: { page: this.page, size: this.size(), ...(this.state ? { state: this.state } : {}) } })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (data) => { this.data.set(data); this.error.set(''); }, error: (e: unknown) => this.failed(e) });
  }
  protected action(row: Alert, action: string): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.http.post(`${this.base}/alerts/${row.id}/actions`, { action, reason: this.reason }, { headers: { 'If-Match': ifMatch(row.version) } })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: () => { this.busy.set(false); this.reload(); }, error: (e: unknown) => this.failed(e) });
  }
  protected loadRules(): void {
    this.http.get<Rule[]>(`${this.base}/alerts/rules`).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (rules) => this.rules.set(rules), error: (e: unknown) => this.failed(e) });
  }
  protected saveRule(rule: Rule): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.http.put<Rule>(`${this.base}/alerts/rules/${rule.code}`, { leadMinutes: rule.leadMinutes, escalationMinutes: rule.escalationMinutes,
      enabled: rule.enabled, reason: this.reason }, { headers: { 'If-Match': ifMatch(rule.version) } })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: () => { this.busy.set(false); this.loadRules(); }, error: (e: unknown) => this.failed(e) });
  }
  private failed(error: unknown): void { this.busy.set(false); this.error.set(toProblem(error).detail ?? 'errors.loadFailed'); }
}
