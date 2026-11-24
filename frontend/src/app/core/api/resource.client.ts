import { HttpClient, HttpContext } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Page, PageQuery, SILENT_ERRORS, ifMatch, pageParams } from './api.models';

export interface Versioned {
  id: string;
  version: number | null;
}

export interface ActiveStatusChange {
  active: boolean;
  reason?: string | null;
}

/**
 * Typed calls for a standard master resource: paged list, read, create, versioned update and status change.
 * Writes are "silent" so forms can show validation and conflict messages next to the fields.
 */
export class ResourceClient<TDto extends Versioned, TRequest> {
  protected readonly silent = new HttpContext().set(SILENT_ERRORS, true);

  constructor(
    protected readonly http: HttpClient,
    protected readonly url: string,
  ) {}

  list(query: PageQuery): Observable<Page<TDto>> {
    return this.http.get<Page<TDto>>(this.url, { params: pageParams(query) });
  }

  get(id: string): Observable<TDto> {
    return this.http.get<TDto>(`${this.url}/${id}`);
  }

  create(request: TRequest): Observable<TDto> {
    return this.http.post<TDto>(this.url, request, { context: this.silent });
  }

  update(id: string, version: number | null, request: TRequest): Observable<TDto> {
    return this.http.put<TDto>(`${this.url}/${id}`, request, {
      headers: { 'If-Match': ifMatch(version) },
      context: this.silent,
    });
  }

  changeStatus(id: string, version: number | null, change: ActiveStatusChange): Observable<TDto> {
    return this.http.patch<TDto>(`${this.url}/${id}/status`, change, {
      headers: { 'If-Match': ifMatch(version) },
      context: this.silent,
    });
  }

  delete(id: string, version: number | null): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`, {
      headers: { 'If-Match': ifMatch(version) },
      context: this.silent,
    });
  }
}
