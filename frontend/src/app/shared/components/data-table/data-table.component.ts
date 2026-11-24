import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { IconField } from 'primeng/iconfield';
import { InputIcon } from 'primeng/inputicon';
import { InputText } from 'primeng/inputtext';
import { TableLazyLoadEvent, TableModule } from 'primeng/table';
import { Observable, Subject, catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { Page, PageQuery, QueryValue } from '../../../core/api/api.models';
import { AuthService } from '../../../core/auth/auth.service';
import { UtcDateTimePipe } from '../../pipes/utc-datetime.pipe';
import { EmptyStateComponent } from '../empty-state/empty-state.component';
import { StatusTagComponent } from '../status-tag/status-tag.component';
import { CellValue, ColumnDef, RowAction } from './table.models';

type LoadState = 'loading' | 'ready' | 'error';

/**
 * Server-paged table. Paging, sorting and search always run on the server; a new request cancels the previous
 * one; search is debounced. Holds no business logic: columns, loader and row actions come from the page.
 */
@Component({
  selector: 's2s-data-table',
  imports: [
    TableModule, RouterLink, TranslatePipe, Button, IconField, InputIcon, InputText,
    UtcDateTimePipe, EmptyStateComponent, StatusTagComponent,
  ],
  templateUrl: './data-table.component.html',
  styleUrl: './data-table.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DataTableComponent<T extends { id: string }> {
  private readonly auth = inject(AuthService);

  readonly columns = input.required<ColumnDef<T>[]>();
  readonly load = input.required<(query: PageQuery) => Observable<Page<T>>>();
  readonly actions = input<RowAction<T>[]>([]);
  /** Extra server filters; any change returns to the first page. */
  readonly params = input<Record<string, QueryValue>>({});
  readonly defaultSort = input<string | undefined>(undefined);
  readonly searchable = input(true);
  readonly stackedToolbar = input(false);
  readonly pageSizes = input<number[]>([10, 25, 50]);
  readonly captionKey = input('table.records');
  readonly emptyKey = input('table.empty');

  protected readonly query = signal<PageQuery>({ page: 0, size: 25 });
  protected readonly rows = signal<T[]>([]);
  protected readonly total = signal(0);
  protected readonly state = signal<LoadState>('loading');

  protected readonly visibleActions = computed(() =>
    this.actions().filter((a) => !a.permission || this.auth.hasPermission(a.permission)),
  );
  protected readonly colspan = computed(() => this.columns().length + (this.visibleActions().length ? 1 : 0));
  protected readonly sortState = computed(() => {
    const [field, direction] = (this.query().sort ?? '').split(',');
    return { field, direction: direction === 'desc' ? 'desc' : 'asc' };
  });

  private readonly search$ = new Subject<string>();
  private readonly requests$ = new Subject<PageQuery>();

  constructor() {
    const destroyRef = inject(DestroyRef);

    this.requests$
      .pipe(
        switchMap((query) => {
          this.state.set('loading');
          return this.load()(query).pipe(
            map((page) => ({ page })),
            catchError(() => of({ page: null })),
          );
        }),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe(({ page }) => {
        if (page) {
          this.rows.set(page.items);
          this.total.set(page.totalElements);
          this.state.set('ready');
        } else {
          this.state.set('error');
        }
      });

    this.search$
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed(destroyRef))
      .subscribe((q) => this.fetch({ ...this.query(), q, page: 0 }));

    // First load, and reload from page 0 whenever the page's filters change.
    effect(() => {
      const filters = this.params();
      const sort = this.defaultSort();
      untracked(() => this.fetch({ ...this.query(), sort: this.query().sort ?? sort, filters, page: 0 }));
    });
  }

  /** Re-runs the current query, e.g. after a record changed. */
  reload(): void {
    this.fetch(this.query());
  }

  protected onLazyLoad(event: TableLazyLoadEvent): void {
    const size = event.rows ?? this.query().size;
    const page = Math.floor((event.first ?? 0) / size);
    const field = typeof event.sortField === 'string' ? event.sortField : undefined;
    const sort = field ? `${field},${event.sortOrder === -1 ? 'desc' : 'asc'}` : this.query().sort;
    const next = { ...this.query(), page, size, sort };
    if (JSON.stringify(next) !== JSON.stringify(this.query())) {
      this.fetch(next);
    }
  }

  protected onSearch(event: Event): void {
    this.search$.next((event.target as HTMLInputElement).value.trim());
  }

  protected cellValue(row: T, column: ColumnDef<T>, rowIndex: number): CellValue {
    const serialIndex = this.query().page * this.query().size + rowIndex + 1;
    return column.value ? column.value(row, serialIndex) : ((row as unknown as Record<string, CellValue>)[column.key] ?? null);
  }

  protected isVisible(action: RowAction<T>, row: T): boolean {
    return action.visible ? action.visible(row) : true;
  }

  private fetch(query: PageQuery): void {
    this.query.set(query);
    this.requests$.next(query);
  }
}
