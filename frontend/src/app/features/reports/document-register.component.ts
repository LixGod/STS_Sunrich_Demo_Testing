import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { EMPTY, Subject, catchError, debounceTime, startWith, switchMap } from 'rxjs';
import { LanguageService } from '../../core/i18n/language.service';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import { DOCUMENT_KINDS, DOCUMENT_STATUSES, RegisterFilters, RegisterRow, ReportsService, documentLink } from './reports.service';

const SIZE = 25;

/** Every generated document (FR-DOC-01) in one list: number, revision, status, who issued it and the PDF checksum. */
@Component({
  selector: 's2s-document-register',
  imports: [FormsModule, RouterLink, TranslatePipe, Button, InputText, Message, Select, EmptyStateComponent, PageHeaderComponent,
    StatusTagComponent, UtcDateTimePipe],
  template: `
    <s2s-page-header [heading]="'reports.documentsTitle' | translate" [subtitle]="'reports.documentsSubtitle' | translate" />
    @if (error()) { <p-message severity="error" role="alert" [text]="'errors.loadFailed' | translate" /> }
    <section class="s2s-panel">
      <div class="filters">
        <div class="field"><label class="s2s-muted" for="dr-kind">{{ 'reports.kind' | translate }}</label>
          <p-select inputId="dr-kind" [options]="kindOptions()" optionLabel="label" optionValue="value" [ngModel]="filters().kind"
            (ngModelChange)="set({ kind: $event })" appendTo="body" /></div>
        <div class="field"><label class="s2s-muted" for="dr-status">{{ 'reports.status' | translate }}</label>
          <p-select inputId="dr-status" [options]="statusOptions()" optionLabel="label" optionValue="value" [ngModel]="filters().status"
            (ngModelChange)="set({ status: $event })" appendTo="body" /></div>
        <div class="field"><label class="s2s-muted" for="dr-from">{{ 'reports.from' | translate }}</label>
          <input pInputText id="dr-from" type="date" [ngModel]="filters().from" (ngModelChange)="set({ from: $event || null })" /></div>
        <div class="field"><label class="s2s-muted" for="dr-to">{{ 'reports.to' | translate }}</label>
          <input pInputText id="dr-to" type="date" [ngModel]="filters().to" (ngModelChange)="set({ to: $event || null })" /></div>
        <div class="field wide"><label class="s2s-muted" for="dr-q">{{ 'reports.search' | translate }}</label>
          <input pInputText id="dr-q" type="search" [ngModel]="filters().q" (ngModelChange)="search$.next($event)" /></div>
      </div>
      <ul class="items">
        @for (r of rows(); track r.kind + r.id) {
          <li>
            <div class="line1">
              @if (link(r); as to) { <a [routerLink]="to"><strong>{{ ('reports.kinds.' + r.kind) | translate }}</strong></a> }
              @else { <strong>{{ ('reports.kinds.' + r.kind) | translate }}</strong> }
              <span>{{ r.number ?? r.jobNumber ?? '—' }}</span>
              <span class="s2s-muted">{{ 'reports.revision' | translate: { no: r.revision } }}</span>
              <s2s-status-tag [status]="r.status" />
            </div>
            <div class="line2 s2s-muted">
              @if (r.jobNumber && r.number) { <span>{{ r.jobNumber }}</span> }
              @if (r.issuedAt) { <span>{{ 'reports.issuedBy' | translate: { name: r.issuedBy ?? '—', at: (r.issuedAt | utcDateTime) } }}</span> }
              @else { <span>{{ 'reports.updated' | translate: { at: (r.updatedAt | utcDateTime) } }}</span> }
              @if (r.pdfSha256) { <span class="sha" [title]="r.pdfSha256">SHA-256 {{ r.pdfSha256.slice(0, 12) }}…</span> }
            </div>
          </li>
        } @empty {
          <li><s2s-empty-state messageKey="reports.noDocuments" icon="pi pi-folder-open" /></li>
        }
      </ul>
      @if (pages() > 1) {
        <div class="pager">
          <p-button [label]="'reports.previous' | translate" icon="pi pi-chevron-left" [text]="true" [disabled]="page() === 0" (onClick)="go(page() - 1)" />
          <span class="s2s-muted">{{ 'reports.pageOf' | translate: { page: page() + 1, pages: pages() } }}</span>
          <p-button [label]="'reports.next' | translate" icon="pi pi-chevron-right" iconPos="right" [text]="true" [disabled]="page() + 1 >= pages()" (onClick)="go(page() + 1)" />
        </div>
      }
    </section>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .filters { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: end; margin-bottom: 0.75rem; }
    .field { display: flex; flex-direction: column; gap: 0.25rem; flex: 1 1 10rem; min-width: 0; }
    .field.wide { flex-basis: 16rem; }
    .field input, .field :is(.p-select) { width: 100%; }
    .items { list-style: none; margin: 0; padding: 0; }
    .items li { padding: 0.75rem 0; border-top: 1px solid var(--s2s-border); overflow-wrap: anywhere; }
    .line1, .line2 { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
    .line2 { margin-top: 0.3rem; font-size: 0.85rem; }
    .sha { font-family: monospace; }
    .pager { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 0.5rem; margin-top: 0.5rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocumentRegisterComponent {
  private readonly service = inject(ReportsService);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);
  private readonly load$ = new Subject<void>();
  protected readonly search$ = new Subject<string>();

  protected readonly filters = signal<RegisterFilters>({ kind: null, status: null, from: null, to: null, q: null });
  protected readonly rows = signal<RegisterRow[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly error = signal(false);
  protected readonly pages = computed(() => Math.ceil(this.total() / SIZE));
  protected readonly kindOptions = computed(() => this.options('reports.allKinds', DOCUMENT_KINDS, 'reports.kinds.'));
  protected readonly statusOptions = computed(() => this.options('reports.allStatuses', DOCUMENT_STATUSES, 'status.'));
  protected readonly link = documentLink;

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.search$.pipe(debounceTime(300), takeUntilDestroyed(destroyRef)).subscribe((q) => this.set({ q: q.trim() || null }));
    this.load$.pipe(startWith(undefined), switchMap(() => this.service.documents(this.filters(), this.page(), SIZE)
      .pipe(catchError(() => { this.error.set(true); return EMPTY; }))), takeUntilDestroyed(destroyRef))
      .subscribe((p) => { this.error.set(false); this.rows.set(p.items); this.total.set(p.totalElements); });
  }

  protected set(change: Partial<RegisterFilters>): void { this.filters.update((f) => ({ ...f, ...change })); this.page.set(0); this.load$.next(); }
  protected go(page: number): void { this.page.set(page); this.load$.next(); }

  private options(allKey: string, values: readonly string[], prefix: string) {
    this.language.current();
    return [{ value: null as string | null, label: this.translate.instant(allKey) },
      ...values.map((v) => ({ value: v as string | null, label: this.translate.instant(prefix + v) }))];
  }
}
