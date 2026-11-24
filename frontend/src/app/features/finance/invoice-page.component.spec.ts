import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { Subject, of } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { FinanceService, Invoice } from './finance.service';
import { InvoicePageComponent } from './invoice-page.component';

describe('InvoicePageComponent', () => {
  it('clears the old invoice and ignores its late save after the ID changes', () => {
    const first = new Subject<Invoice>();
    const second = new Subject<Invoice>();
    const oldSave = new Subject<Invoice>();
    TestBed.configureTestingModule({ providers: [
      provideRouter([]), provideTranslateService(),
      { provide: AuthService, useValue: { hasPermission: () => false } },
      { provide: FinanceService, useValue: {
        invoice: (id: string) => id === 'first' ? first : second,
        options: () => of({ costHeads: [] }),
      } },
    ] });
    TestBed.overrideComponent(InvoicePageComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(InvoicePageComponent);
    fixture.componentRef.setInput('id', 'first');
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp['invoice'].set({ id: 'first' } as Invoice);
    comp['run'](oldSave);

    fixture.componentRef.setInput('id', 'second');
    fixture.detectChanges();
    expect(first.observed).toBe(false);
    expect(comp['invoice']()).toBeNull();
    comp['invoice'].set({ id: 'second' } as Invoice);
    oldSave.next({ id: 'first' } as Invoice);
    expect(comp['invoice']()?.id).toBe('second');
  });
});
