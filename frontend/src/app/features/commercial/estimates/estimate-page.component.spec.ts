import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../../core/auth/auth.service';
import { LookupService } from '../../../core/lookups/lookup.service';
import { EnquiryService } from '../enquiries/enquiry.service';
import { EstimatePageComponent } from './estimate-page.component';
import { Estimate, EstimateRequest, EstimateService } from './estimate.service';

describe('EstimatePageComponent extra hours and accepted editing', () => {
  it('shows extra-hours checkbox inside the ADD-TARIFF revenue line, reveals editable hours and per-hour cost when checked, and emits saved', () => {
    const estimate: Estimate = {
      id: 'est-1', enquiryId: 'enq-1', versionNo: 1, currency: 'USD', notes: null, tariffId: null, inrFxRate: null,
      lines: [
        { lineNo: 1, kind: 'REVENUE', costHeadId: 'h-sts', costHeadCode: 'STS-SERVICE',
          costHeadName: 'STS service charge', description: null, quantity: 1, unitPrice: 10000,
          amount: 10000, equipmentModelId: null, extraHours: false },
      ],
      totalCost: 0, totalRevenue: 10000, margin: 10000, marginPercent: 100, locked: true, editable: true,
      updatedAt: null, version: 1,
    };
    let lastSaved: EstimateRequest | null = null;
    const estimateService = {
      list: () => of([{ id: 'est-1', versionNo: 1, currency: 'USD', inrFxRate: null, totalCost: 0, totalRevenue: 10000, marginPercent: 100, locked: true, updatedAt: null }]),
      get: () => of(estimate),
      equipmentOptions: () => of([]),
      update: (_id: string, _version: number | null, request: EstimateRequest) => {
        lastSaved = request;
        return of({ ...estimate, tariffId: request.tariffId, version: 2 });
      },
    };

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideTranslateService(),
        MessageService,
        { provide: AuthService, useValue: { hasPermission: () => true, hasAnyPermission: () => true, currentUser: () => null } },
        { provide: EstimateService, useValue: estimateService },
        { provide: EnquiryService, useValue: { get: () => of({ id: 'enq-1', reference: 'ENQ/2026/00001', status: 'ACCEPTED', editable: true, client: null, specialItems: [] }) } },
        {
          provide: LookupService,
          useValue: {
            currencies: () => of([{ value: 'USD', label: 'USD — US dollar' }]),
            tariffs: () => of([{
              value: 't-1', label: 'DEMO-STS — Standard (USD, 48 h, from 2026-01-01)',
              meta: { currency: 'USD', baseHours: '48', extraHourRate: '450' },
            }]),
            costHeads: () => of([
              { value: 'h-sts', label: 'STS-SERVICE — STS service charge', meta: { kind: 'REVENUE', code: 'STS-SERVICE' } },
              { value: 'h-add', label: 'ADD-TARIFF — Additional tariff (24 / 48 / 72 hrs)', meta: { kind: 'REVENUE', code: 'ADD-TARIFF' } },
            ]),
          },
        },
      ],
    });

    const fixture = TestBed.createComponent(EstimatePageComponent);
    fixture.componentRef.setInput('id', 'enq-1');
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const savedSpy = vi.fn();
    component.saved.subscribe(savedSpy);

    // STS-SERVICE revenue line does not render an Extra hours checkbox; its amount shows USD.
    expect(fixture.nativeElement.querySelector('input[type="checkbox"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.line .amount output')?.textContent?.trim()).toBe('USD 10,000.00');

    // Adding an ADD-TARIFF revenue line shows the checkbox and editable Per hour cost inside that line, hiding Hours until checked.
    component['addLine']('REVENUE');
    component['rows'][1].controls.costHeadId.setValue('h-add');
    fixture.detectChanges();

    const checkbox = () => fixture.nativeElement.querySelector('#extra-hours-1') as HTMLInputElement;
    expect(checkbox()).toBeTruthy();
    expect(checkbox().disabled).toBe(false);
    expect(checkbox().checked).toBe(false);
    expect(fixture.nativeElement.querySelector('#qty-1')).toBeNull();
    expect(fixture.nativeElement.querySelector('#price-1')).toBeTruthy();

    // Entering Per hour cost with Extra hours unchecked saves the deferred rate line (quantity 0) without inflating revenue.
    component['rows'][1].controls.unitPrice.setValue(275);
    fixture.detectChanges();
    expect(component['amountOf'](component['rows'][1]).toString()).toBe('0');
    expect(component['totals']().revenue.toString()).toBe('10000');
    component['save']();
    expect(lastSaved!.lines).toEqual(expect.arrayContaining([
      expect.objectContaining({ extraHours: false, quantity: '0', unitPrice: '275' }),
    ]));

    // Checking Extra hours without a tariff reveals Hours alongside editable Per hour cost.
    component['addLine']('REVENUE');
    component['rows'][1].controls.costHeadId.setValue('h-add');
    fixture.detectChanges();
    checkbox().checked = true;
    checkbox().dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(component['extraHoursEnabled']()).toBe(true);
    expect(fixture.nativeElement.querySelector('#qty-1')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('#price-1')).toBeTruthy();

    // Selecting a tariff auto-fills Per hour cost, and Commercial can still override it manually.
    component['form'].controls.tariffId.setValue('t-1');
    component['tariffChanged']();
    fixture.detectChanges();
    const extraRow = component['rows'][1];
    expect(extraRow.controls.unitPrice.value).toBe(450);

    extraRow.controls.quantity.setValue(4);
    extraRow.controls.unitPrice.setValue(500);
    fixture.detectChanges();
    expect(component['amountOf'](extraRow).toString()).toBe('2000');
    expect(component['totals']().revenue.toString()).toBe('12000');

    component['form'].controls.inrFxRate.setValue(85);
    component['save']();
    expect(savedSpy).toHaveBeenCalledTimes(2);
    expect(component['form'].enabled).toBe(true);
    expect(component['editable']()).toBe(true);
    expect(lastSaved!.inrFxRate).toBe(85);
    expect(lastSaved!.lines).toEqual(expect.arrayContaining([
      expect.objectContaining({ extraHours: true, quantity: '4', unitPrice: '500' }),
    ]));

    // Unchecking hides Hours again, keeps Per hour cost, and excludes the line from totals while still saving rate.
    component['addLine']('REVENUE');
    component['rows'][1].controls.costHeadId.setValue('h-add');
    component['toggleRowExtraHours'](component['rows'][1], true);
    component['rows'][1].controls.quantity.setValue(2);
    component['rows'][1].controls.unitPrice.setValue(300);
    fixture.detectChanges();

    checkbox().checked = false;
    checkbox().dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(component['extraHoursEnabled']()).toBe(false);
    expect(fixture.nativeElement.querySelector('#qty-1')).toBeNull();
    expect(fixture.nativeElement.querySelector('#price-1')).toBeTruthy();
    expect(component['totals']().revenue.toString()).toBe('10000');
  });
});
