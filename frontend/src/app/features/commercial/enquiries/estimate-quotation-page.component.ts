import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { of, switchMap } from 'rxjs';
import { problemKey, toProblem } from '../../../core/api/api.models';
import { AuthService } from '../../../core/auth/auth.service';
import { Permissions } from '../../../core/auth/permissions';
import { EstimatePageComponent } from '../estimates/estimate-page.component';
import { EnquiryService } from './enquiry.service';
import { QuotationService, addWorkingDays } from '../quotations/quotation.service';
import { QuotationPageComponent } from '../quotations/quotation-page.component';
import { EnquiryTabsComponent } from './enquiry-tabs.component';

/** One enquiry tab for pricing the job and issuing its quotation. */
@Component({
  selector: 's2s-estimate-quotation-page',
  imports: [EnquiryTabsComponent, EstimatePageComponent, QuotationPageComponent, TranslatePipe, Button, Message],
  template: `
    <s2s-enquiry-tabs [enquiryId]="id()" />
    @if (canEstimate()) {
      <s2s-estimate-page [id]="id()" [embedded]="true" [refreshKey]="estimateRefresh()"
        (selectedEstimateIdChange)="selectEstimate($event)" (saved)="refreshQuotation()" />
    }
    @if (canQuotation() && canEstimate() && selectedEstimateId() && !pdfVisible()) {
      <div class="preview-action">
        <p-button [label]="'quotation.showPdf' | translate" icon="pi pi-file-pdf" [loading]="pdfLoading()" (onClick)="showSelectedPdf()" />
      </div>
    }
    @if (pdfError(); as key) { <p-message severity="error" role="alert" [text]="key | translate" /> }
    @if (canQuotation() && (pdfVisible() || !canEstimate())) {
      <section id="quotation"><s2s-quotation-page [id]="id()" [embedded]="true" [estimateId]="selectedEstimateId()" [refreshKey]="quotationRefresh()" /></section>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .preview-action { display: flex; justify-content: flex-end; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EstimateQuotationPageComponent {
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly quotations = inject(QuotationService);
  private readonly enquiries = inject(EnquiryService);
  private readonly fragment = toSignal(this.route.fragment, { initialValue: this.route.snapshot.fragment });
  readonly id = input.required<string>();
  protected readonly selectedEstimateId = signal<string | null>(null);
  protected readonly showPdf = signal(false);
  protected readonly pdfLoading = signal(false);
  protected readonly pdfError = signal<string | null>(null);
  protected readonly estimateRefresh = signal(0);
  protected readonly quotationRefresh = signal(0);
  protected readonly pdfVisible = computed(() => this.showPdf() || this.fragment() === 'quotation');
  protected readonly canEstimate = computed(() => { this.auth.currentUser(); return this.auth.hasPermission(Permissions.estimateRead); });
  protected readonly canQuotation = computed(() => { this.auth.currentUser(); return this.auth.hasPermission(Permissions.quotationRead); });

  protected refreshQuotation(): void {
    this.quotationRefresh.update((value) => value + 1);
  }

  protected selectEstimate(id: string | null): void {
    if (this.selectedEstimateId() && this.selectedEstimateId() !== id && this.pdfVisible()) {
      this.showPdf.set(false);
      void this.router.navigate([], { relativeTo: this.route });
    }
    this.selectedEstimateId.set(id);
    this.pdfError.set(null);
    if (id && this.canQuotation() && !this.pdfVisible()) {
      this.quotations.overview(this.id()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: (overview) => {
          if (overview.versions.some((v) => v.estimateId === id && v.status === 'ACCEPTED')) this.showPdf.set(true);
        },
        error: () => undefined,
      });
    }
  }

  protected showSelectedPdf(): void {
    const estimateId = this.selectedEstimateId();
    if (!estimateId || this.pdfLoading()) return;
    this.pdfLoading.set(true);
    this.pdfError.set(null);
    let created = false;
    this.quotations.overview(this.id()).pipe(
      switchMap((overview) => {
        if (overview.versions.some((version) => version.estimateId === estimateId)) return of(overview);
        if (!overview.canCreateVersion) {
          this.pdfError.set('quotation.finishOpenVersion');
          return of(null);
        }
        return this.enquiries.get(this.id()).pipe(switchMap((enquiry) => {
          created = true;
          return this.quotations.createVersion(this.id(), estimateId, {
            validUntil: addWorkingDays(new Date(), 5), tariffId: null, inrFxRate: null,
            paymentTerms: null, advanceReceived: null, advanceGiven: null,
            termsText: null, scopeOfServices: enquiry.scopeOfServices ?? null,
          });
        }));
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: (overview) => {
        this.pdfLoading.set(false);
        if (!overview) return;
        if (created) this.estimateRefresh.update((value) => value + 1);
        this.showPdf.set(true);
        void this.router.navigate([], { relativeTo: this.route, fragment: 'quotation' }).then(() => {
          setTimeout(() => document.getElementById('quotation')?.scrollIntoView({ block: 'start' }), 50);
        });
      },
      error: (error: unknown) => {
        this.pdfLoading.set(false);
        this.pdfError.set(problemKey(toProblem(error)));
      },
    });
  }
}
