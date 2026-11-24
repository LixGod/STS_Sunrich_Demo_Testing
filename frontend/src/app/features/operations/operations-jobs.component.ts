import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { EMPTY, Subject, catchError, debounceTime, startWith, switchMap } from 'rxjs';
import { EmptyStateComponent } from '../../shared/components/empty-state/empty-state.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import { OperationsJob, OperationsService } from './operations.service';

/** Jobs in the user's operations scope, newest first, with the latest event and open incidents. */
@Component({
  selector: 's2s-operations-jobs',
  imports: [FormsModule, RouterLink, TranslatePipe, Button, InputText, Message, EmptyStateComponent, PageHeaderComponent, StatusTagComponent, UtcDateTimePipe],
  template: `
    <s2s-page-header [heading]="'ops.listTitle' | translate" [subtitle]="'ops.listSubtitle' | translate" />
    @if (error()) { <p-message severity="error" role="alert" [text]="'errors.loadFailed' | translate" /> }
    <section class="s2s-panel">
      <label for="ops-search" class="s2s-muted">{{ 'ops.search' | translate }}</label>
      <input pInputText id="ops-search" type="search" [ngModel]="query()" (ngModelChange)="search($event)" class="search" />
      <ul class="jobs">
        @for (j of jobs(); track j.jobId) {
          <li>
            <a [routerLink]="['/jobs', j.jobId, 'operations']">
              <strong>{{ j.jobNumber }}</strong> <s2s-status-tag [status]="j.status" />
              <span class="s2s-muted">{{ j.motherVessel }} / {{ j.daughterVessel }}</span>
              <span class="s2s-muted">
                @if (j.lastEventLabel) { {{ j.lastEventLabel }} · {{ j.lastEventAt | utcDateTime }} } @else { {{ 'ops.noEvents' | translate }} }
                @if (j.openIncidents) { · {{ 'ops.openIncidents' | translate: { count: j.openIncidents } }} }
              </span>
            </a>
          </li>
        } @empty {
          <li><s2s-empty-state messageKey="ops.noJobs" icon="pi pi-sync" /></li>
        }
      </ul>
      @if (pages() > 1) {
        <div class="pager">
          <p-button [label]="'reports.previous' | translate" icon="pi pi-chevron-left" [text]="true"
            [disabled]="page() === 0" (onClick)="go(page() - 1)" />
          <span>{{ 'reports.pageOf' | translate: { page: page() + 1, pages: pages() } }}</span>
          <p-button [label]="'reports.next' | translate" icon="pi pi-chevron-right" iconPos="right" [text]="true"
            [disabled]="page() + 1 >= pages()" (onClick)="go(page() + 1)" />
        </div>
      }
    </section>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .search { width: 100%; max-width: 24rem; margin: 0.25rem 0 0.75rem; display: block; }
    .jobs { list-style: none; margin: 0; padding: 0; }
    .jobs a { display: flex; flex-direction: column; gap: 0.2rem; padding: 0.75rem 0; border-top: 1px solid var(--s2s-border); color: inherit;
      text-decoration: none; min-height: 44px; }
    .jobs a:hover strong, .jobs a:focus-visible strong { color: var(--p-primary-color); }
    .pager { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 0.5rem; margin-top: 0.75rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OperationsJobsComponent {
  private readonly service = inject(OperationsService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly search$ = new Subject<string>();

  protected readonly jobs = signal<OperationsJob[]>([]);
  protected readonly query = signal('');
  protected readonly page = signal(0);
  protected readonly total = signal(0);
  protected readonly pages = computed(() => Math.ceil(this.total() / 50));
  protected readonly error = signal(false);

  constructor() {
    this.search$.pipe(
      startWith(''),
      debounceTime(300),
      switchMap((q) => this.service.jobs(q, this.page(), 50).pipe(
        catchError(() => { this.error.set(true); return EMPTY; }),
      )),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((page) => {
      this.error.set(false);
      this.jobs.set(page.items);
      this.total.set(page.totalElements);
    });
  }

  protected search(value: string): void {
    this.query.set(value);
    this.page.set(0);
    this.search$.next(value.trim());
  }

  protected go(page: number): void {
    this.page.set(page);
    this.search$.next(this.query().trim());
  }
}
