import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { Observable, Subject, throwError } from 'rxjs';
import { Page, PageQuery, QueryValue } from '../../../core/api/api.models';
import { DataTableComponent } from './data-table.component';
import { ColumnDef } from './table.models';

interface Row {
  id: string;
  name: string;
}

@Component({
  imports: [DataTableComponent],
  template: `<s2s-data-table [columns]="columns" [load]="load" [params]="params()" defaultSort="name,asc" />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class HostComponent {
  readonly table = viewChild.required(DataTableComponent<Row>);
  readonly params = signal<Record<string, QueryValue>>({ active: true });
  readonly columns: ColumnDef<Row>[] = [{ key: 'name', headerKey: 'Name', sortField: 'name' }];
  readonly queries: PageQuery[] = [];
  responses: Subject<Page<Row>>[] = [];
  failNext = false;
  readonly load = (query: PageQuery): Observable<Page<Row>> => {
    this.queries.push(query);
    if (this.failNext) return throwError(() => new Error('down'));
    const response = new Subject<Page<Row>>();
    this.responses.push(response);
    return response;
  };
}

function page(names: string[]): Page<Row> {
  return { items: names.map((name, i) => ({ id: String(i), name })), page: 0, size: 25, totalElements: names.length };
}

describe('DataTableComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideTranslateService()],
    });
  });

  async function create() {
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    return fixture;
  }

  it('loads the first page once, with the default sort and the page filters', async () => {
    const fixture = await create();
    const host = fixture.componentInstance;
    expect(host.queries).toEqual([{ page: 0, size: 25, sort: 'name,asc', filters: { active: true } }]);

    host.responses[0].next(page(['Mumbai', 'Kandla']));
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Mumbai');
    expect(fixture.nativeElement.textContent).toContain('Kandla');
  });

  it('returns to the first page when a filter changes', async () => {
    const fixture = await create();
    const host = fixture.componentInstance;
    host.params.set({ active: false });
    await fixture.whenStable();
    expect(host.queries.at(-1)).toEqual(expect.objectContaining({ page: 0, filters: { active: false } }));
  });

  it('ignores a slow earlier response once a newer request was made', async () => {
    const fixture = await create();
    const host = fixture.componentInstance;
    host.table().reload();
    host.responses[1].next(page(['Newer']));
    host.responses[0].next(page(['Stale']));
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Newer');
    expect(fixture.nativeElement.textContent).not.toContain('Stale');
  });

  it('debounces search input and sends one trimmed query', async () => {
    const fixture = await create();
    const host = fixture.componentInstance;
    vi.useFakeTimers();
    try {
      const input: HTMLInputElement = fixture.nativeElement.querySelector('input[type=search]');
      for (const text of ['m', 'mu', ' mum ']) {
        input.value = text;
        input.dispatchEvent(new Event('input'));
      }
      vi.advanceTimersByTime(299);
      expect(host.queries.length).toBe(1);
      vi.advanceTimersByTime(1);
      expect(host.queries.at(-1)).toEqual(expect.objectContaining({ q: 'mum', page: 0 }));
      expect(host.queries.length).toBe(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('shows a retry option when loading fails', async () => {
    const fixture = await create();
    const host = fixture.componentInstance;
    host.failNext = true;
    host.table().reload();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role=alert]')?.textContent).toContain('table.loadFailed');
  });
});
