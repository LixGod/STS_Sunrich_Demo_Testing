import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, CursorPage, toHttpParams } from '../../../core/api/api.models';

export interface AuditEvent {
  seq: number;
  occurredAt: string;
  actorUserId: string | null;
  actorName: string | null;
  action: string;
  entityType: string | null;
  entityId: string | null;
  details: unknown;
}

export interface AuditQuery {
  entityType?: string;
  entityId?: string;
  after?: string | null;
  limit?: number;
}

@Injectable({ providedIn: 'root' })
export class AuditTrailService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE_URL)}/audit-events`;

  list(query: AuditQuery): Observable<CursorPage<AuditEvent>> {
    return this.http.get<CursorPage<AuditEvent>>(this.url, {
      params: toHttpParams({ ...query, limit: query.limit ?? 20 }),
    });
  }
}
