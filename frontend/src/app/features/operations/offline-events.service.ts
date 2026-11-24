import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, tap, throwError } from 'rxjs';
import { toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { EventInput, OperationEvent, OperationsService } from './operations.service';

export interface OfflineEvent {
  id: string; jobId: string; input: EventInput; error: string; discardedReason?: string;
}

/** Device drafts are separate from the official event log; only an acknowledged server save removes one. */
@Injectable({ providedIn: 'root' })
export class OfflineEventsService {
  private readonly auth = inject(AuthService);
  private readonly operations = inject(OperationsService);
  private readonly revision = signal(0);
  readonly drafts = computed(() => { this.revision(); try { return this.read(); } catch { return []; } });
  readonly storageError = computed(() => { this.revision(); try { this.read(); return false; } catch { return true; } });

  save(jobId: string, input: EventInput, original?: OfflineEvent): OfflineEvent {
    const rows = this.read();
    // shortcut: bounded text-only device drafts; use IndexedDB when offline attachments are approved.
    if (!original && rows.length >= 100) throw new Error('ops.offline.full');
    const id = original?.id ?? crypto.randomUUID();
    const row: OfflineEvent = { id, jobId, error: '', input: { ...input, clientEventId: id,
      deviceRecordedAt: original?.input.deviceRecordedAt ?? new Date().toISOString() } };
    this.write([...rows.filter((r) => r.id !== id), row]);
    return row;
  }

  sync(row: OfflineEvent, reason = ''): Observable<OperationEvent> {
    const owner = this.key();
    return this.operations.createEvent(row.jobId, { ...row.input, conflictReason: reason.trim() || null }).pipe(
      tap(() => this.write(this.read(owner).filter((r) => r.id !== row.id), owner)),
      catchError((error: unknown) => {
        const problem = toProblem(error);
        const message = problem.detail ?? (problem.status === 0 ? 'ops.offline.network' : 'errors.saveFailed');
        this.write(this.read(owner).map((r) => r.id === row.id ? { ...r, error: message } : r), owner);
        return throwError(() => error);
      }),
    );
  }

  discard(row: OfflineEvent, reason: string): void {
    if (!reason.trim()) return;
    this.write(this.read().map((r) => r.id === row.id ? { ...r, discardedReason: reason.trim() } : r));
  }

  private key(): string {
    const user = this.auth.currentUser();
    if (!user) throw new Error('auth.signIn');
    return `s2s.offline.events.${user.id}`;
  }
  private read(key = this.key()): OfflineEvent[] {
    const raw = localStorage.getItem(key);
    if (!raw) return [];
    const value: unknown = JSON.parse(raw);
    if (!Array.isArray(value) || value.some((r: Partial<OfflineEvent>) => typeof r?.id !== 'string' || typeof r.jobId !== 'string' || !r.input))
      throw new Error('ops.offline.unreadable');
    return value as OfflineEvent[];
  }
  private write(rows: OfflineEvent[], key = this.key()): void {
    localStorage.setItem(key, JSON.stringify(rows));
    this.revision.update((v) => v + 1);
  }
}
