import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { of } from 'rxjs';
import { LookupService } from '../../../core/lookups/lookup.service';
import { EnquiryService } from '../enquiries/enquiry.service';
import { EstimateService } from '../estimates/estimate.service';
import { QuotationPageComponent } from './quotation-page.component';
import { AdvancePaymentStatus, QuotationOverview, QuotationService, QuotationVersion } from './quotation.service';

function makeOverview(overrides: Partial<QuotationVersion> = {}): QuotationOverview {
  const version: QuotationVersion = {
    id: 'qv-1', versionNo: 1, status: 'ACCEPTED', estimateId: 'est-1', estimateVersionNo: 1,
    currency: 'USD', inrFxRate: null, totalAmount: 5000, finalAmount: 5900, advancePaid: 0, advanceRemaining: 5900,
    advanceStatus: 'UNPAID' as AdvancePaymentStatus, advancePayments: [],
    validUntil: '2026-11-30', paymentTerms: null, advanceReceived: null, advanceGiven: null,
    termsText: null, scopeOfServices: null, tariff: null, lines: [],
    submittedByName: null, submittedAt: null, decidedByName: null, decidedAt: null, decisionReason: null,
    issuedByName: 'Commercial', issuedAt: '2026-11-20T09:00:00Z', issuedTo: 'owner@demo.invalid',
    issueChannel: 'EMAIL', issueNote: null, pdfSha256: 'abc',
    acceptance: {
      acceptedAt: '2026-11-20T10:00:00Z', acceptedByName: 'Owner Rep', workOrderReference: null,
      paymentTerms: null, specialConditions: null, afterValidity: false,
      recordedByName: 'Commercial', recordedAt: '2026-11-20T10:00:00Z', jobId: 'job-1', jobNumber: 'JOB/2026/00001',
    },
    allowedActions: ['EDIT', 'RECORD_ADVANCE'], updatedAt: null, version: 1,
    ...overrides,
  };
  return {
    enquiryId: 'enq-1', enquiryReference: 'ENQ/2026/00001', enquiryStatus: 'ACCEPTED',
    quotationId: 'q-1', reference: 'QUO/2026/00001', versions: [version],
    canCreateVersion: false, job: { id: 'job-1', jobNumber: 'JOB/2026/00001' },
  };
}

describe('QuotationPageComponent advance tracking', () => {
  it('shows advance panel only when accepted, records multiple advances, disables input at 100%, and reopens on cost increase', () => {
    let currentOverview = makeOverview({ status: 'ISSUED', allowedActions: ['EDIT', 'ACCEPT'] });
    const recorded: { amount: number; note: string | null }[] = [];
    const quotationService = {
      overview: () => of(currentOverview),
      previewPages: () => of({ pages: 0 }),
      pdfUrl: () => '/pdf',
      previewPageUrl: () => '/preview',
      recordAdvance: (_v: QuotationVersion, amount: number, note: string | null) => {
        recorded.push({ amount, note });
        const paid = currentOverview.versions[0].advancePaid + amount;
        const finalAmount = currentOverview.versions[0].finalAmount;
        const remaining = Math.max(0, finalAmount - paid);
        const status: AdvancePaymentStatus = remaining === 0 ? 'PAID' : 'PARTIAL';
        currentOverview = makeOverview({
          finalAmount,
          advancePaid: paid,
          advanceRemaining: remaining,
          advanceStatus: status,
          advancePayments: [
            ...currentOverview.versions[0].advancePayments,
            { id: `p-${recorded.length}`, amount, note, recordedByName: 'Commercial', recordedAt: '2026-11-20T11:00:00Z' },
          ],
          allowedActions: remaining > 0 ? ['EDIT', 'RECORD_ADVANCE'] : ['EDIT'],
          version: (currentOverview.versions[0].version ?? 1) + 1,
        });
        return of(currentOverview);
      },
    };

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideTranslateService(),
        MessageService,
        { provide: QuotationService, useValue: quotationService },
        { provide: EstimateService, useValue: { list: () => of([]) } },
        { provide: EnquiryService, useValue: { get: () => of({ id: 'enq-1', reference: 'ENQ/2026/00001', status: 'ACCEPTED', client: null }) } },
        { provide: LookupService, useValue: { tariffs: () => of([]) } },
      ],
    });

    const fixture = TestBed.createComponent(QuotationPageComponent);
    fixture.componentRef.setInput('id', 'enq-1');
    fixture.detectChanges();

    // Not accepted yet -> advance panel is hidden.
    expect(fixture.nativeElement.querySelector('.advance-panel')).toBeNull();

    // Once accepted -> shows Final Amount, Advance Paid, Advance Left and input form.
    currentOverview = makeOverview();
    fixture.componentInstance['reload']();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.advance-panel')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.advance-form')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.advance-summary')?.textContent).toContain('USD 5,900.00');

    // First partial advance (2,000) -> remaining becomes 3,900 and form stays open.
    fixture.componentInstance['advanceForm'].setValue({ amount: 2000, note: 'Tranche 1' });
    fixture.componentInstance['recordAdvance']();
    fixture.detectChanges();
    expect(recorded).toEqual([{ amount: 2000, note: 'Tranche 1' }]);
    expect(fixture.nativeElement.querySelector('.advance-summary')?.textContent).toContain('USD 2,000.00');
    expect(fixture.nativeElement.querySelector('.advance-summary')?.textContent).toContain('USD 3,900.00');
    expect(fixture.nativeElement.querySelector('.advance-form')).toBeTruthy();

    // Second advance pays remaining (3,900) -> 100% PAID, form hides.
    fixture.componentInstance['fillRemainingAdvance']();
    fixture.componentInstance['recordAdvance']();
    fixture.detectChanges();
    expect(fixture.componentInstance['selected']()!.advanceStatus).toBe('PAID');
    expect(fixture.nativeElement.querySelector('.advance-form')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.advance-history li')).toHaveLength(2);

    // Costing increases (final 7,080) -> status reverts to PARTIAL, remaining 1,180, form reopens.
    currentOverview = makeOverview({
      totalAmount: 6000, finalAmount: 7080, advancePaid: 5900, advanceRemaining: 1180,
      advanceStatus: 'PARTIAL', advancePayments: currentOverview.versions[0].advancePayments,
      allowedActions: ['EDIT', 'RECORD_ADVANCE'],
    });
    fixture.componentInstance['reload']();
    fixture.detectChanges();
    expect(fixture.componentInstance['selected']()!.advanceStatus).toBe('PARTIAL');
    expect(fixture.nativeElement.querySelector('.advance-summary')?.textContent).toContain('USD 1,180.00');
    expect(fixture.nativeElement.querySelector('.advance-form')).toBeTruthy();
  });
});
