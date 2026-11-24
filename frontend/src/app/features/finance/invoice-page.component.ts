import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormArray, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Tag } from 'primeng/tag';
import { Textarea } from 'primeng/textarea';
import { Observable, Subscription } from 'rxjs';
import { Problem, problemKey, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { applyServerErrors } from '../../shared/components/form-errors/form-errors.component';
import { FormFieldComponent } from '../../shared/components/form-field/form-field.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { ReasonDialogComponent } from '../../shared/components/reason-dialog/reason-dialog.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { UtcDateTimePipe } from '../../shared/pipes/utc-datetime.pipe';
import { FinanceService, Invoice, InvoiceInput, InvoiceLine, InvoiceOptions, formatMoney, invoiceTotals, toBig } from './finance.service';

type OpenDialog = 'approve' | 'return' | 'issue' | null;
type LineGroup = FormGroup<{
  source: FormControl<'QUOTATION' | 'MANUAL'>; costHeadCode: FormControl<string>; description: FormControl<string>; quantity: FormControl<string>;
  unitPrice: FormControl<string>; extraHours: FormControl<boolean>;
}>;

const QUANTITY = /^\d{1,9}(\.\d{1,3})?$/;
const PRICE = /^\d{1,12}(\.\d{1,4})?$/;

/**
 * An invoice or credit note (FR-J11): lines from the accepted quotation plus supplementary lines,
 * live totals, submitted, approved by someone else and issued with a number and a fixed PDF.
 */
@Component({
  selector: 's2s-invoice-page',
  imports: [
    ReactiveFormsModule, RouterLink, TranslatePipe, Button, Dialog, InputText, Message, Select, Tag, Textarea, FormFieldComponent, PageHeaderComponent,
    ReasonDialogComponent, StatusTagComponent, UtcDateTimePipe,
  ],
  templateUrl: './invoice-page.component.html',
  styleUrl: './invoice-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvoicePageComponent {
  private readonly service = inject(FinanceService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private loadedId = '';
  private recordVersion = 0;
  private loadRequest?: Subscription;

  /** Invoice id (route parameter). */
  readonly id = input.required<string>();

  protected readonly invoice = signal<Invoice | null>(null);
  protected readonly options = signal<InvoiceOptions>({ costHeads: [] });
  protected readonly saving = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly dialog = signal<OpenDialog>(null);
  private readonly changes = signal(0);

  protected readonly can = computed(() => new Set(this.invoice()?.allowedActions ?? []));
  protected readonly editable = computed(() => this.can().has('EDIT'));
  protected readonly hasTax = computed(() => toBig(this.invoice()?.taxAmount).gt(0));
  protected readonly dirty = computed(() => (this.changes(), this.form.dirty));
  protected readonly canRecordReceipt = computed(() => {
    const i = this.invoice();
    return !!i && i.docType === 'INVOICE' && i.status === 'ISSUED' && toBig(i.outstandingAmount).gt(0) && this.auth.hasPermission(Permissions.finReceiptWrite);
  });

  protected readonly form = new FormGroup({
    invoiceDate: new FormControl('', { nonNullable: true, validators: Validators.required }),
    dueDate: new FormControl('', { nonNullable: true }),
    billingName: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    billingAddress: new FormControl('', { nonNullable: true, validators: Validators.maxLength(1000) }),
    paymentTerms: new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) }),
    notes: new FormControl('', { nonNullable: true, validators: Validators.maxLength(2000) }),
    lines: new FormArray<LineGroup>([]),
  });

  protected readonly costHeadOptions = computed(() => {
    this.changes();
    const known = new Map(this.options().costHeads.map((h) => [h.code, `${h.code} — ${h.name}`]));
    for (const l of this.invoice()?.lines ?? []) if (!known.has(l.costHeadCode)) known.set(l.costHeadCode, `${l.costHeadCode} — ${l.costHeadName}`);
    return [...known].map(([value, label]) => ({ value, label }));
  });

  /** What is on screen, totalled exactly; the saved figures come from the server. */
  protected readonly totals = computed(() => {
    this.changes();
    const minor = this.invoice()?.minorUnits ?? 2;
    const lines = this.form.controls.lines.getRawValue().map((l) => ({
      quantity: l.quantity, unitPrice: l.unitPrice, rate: null,
    }));
    return invoiceTotals(lines, minor);
  });

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
    this.service.options().pipe(takeUntilDestroyed()).subscribe({ next: (o) => { this.options.set(o); this.changes.update((n) => n + 1); } });
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.changes.update((n) => n + 1));
  }

  protected money(value: string | number | ReturnType<typeof toBig>): string {
    return formatMoney(value, this.invoice()?.minorUnits ?? 2);
  }

  protected addLine(): void {
    this.form.controls.lines.push(line({ source: 'MANUAL', costHeadCode: '', description: '', quantity: '1', unitPrice: '', extraHours: false }));
    this.form.markAsDirty();
  }

  protected removeLine(index: number): void {
    this.form.controls.lines.removeAt(index);
    this.form.markAsDirty();
  }

  protected save(): void {
    const i = this.invoice();
    if (!i || this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const input: InvoiceInput = {
      invoiceDate: v.invoiceDate, dueDate: v.dueDate || null, billingName: v.billingName.trim(), billingAddress: v.billingAddress.trim() || null,
      paymentTerms: v.paymentTerms.trim() || null, notes: v.notes.trim() || null,
      lines: v.lines.map((l) => ({
        source: l.source, costHeadCode: l.costHeadCode, description: l.description.trim() || null, quantity: l.quantity.trim(), unitPrice: l.unitPrice.trim(),
        extraHours: l.extraHours,
      })),
    };
    this.run(this.service.save(i, input), this.form);
  }

  protected act(action: 'submit' | 'issue'): void {
    const i = this.invoice();
    if (i) this.run(this.service.action(i, action));
  }

  protected decide(decision: 'APPROVE' | 'RETURN', reason: string | null): void {
    const i = this.invoice();
    if (i) this.run(this.service.decide(i, decision, reason));
  }

  protected creditNote(): void {
    const i = this.invoice();
    if (!i) return;
    const version = this.recordVersion;
    this.saving.set(true);
    this.service.creditNote(i).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (c) => {
        if (version !== this.recordVersion) return;
        this.saving.set(false);
        void this.router.navigate(['/finance/invoices', c.id]);
      },
      error: (e: unknown) => { if (version === this.recordVersion) this.fail(toProblem(e)); },
    });
  }

  protected pdfUrl(i: Invoice): string {
    return this.service.pdfUrl(i.id);
  }

  protected closeDialog(open: boolean): void {
    if (!open) this.dialog.set(null);
  }

  protected reload(): void {
    this.load(this.id());
  }

  private load(id: string): void {
    this.loadRequest?.unsubscribe();
    if (id !== this.loadedId) {
      this.loadedId = id;
      this.recordVersion++;
      this.invoice.set(null);
      this.dialog.set(null);
      this.saving.set(false);
    }
    this.errorKey.set(null);
    this.loadRequest = this.service.invoice(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (i) => this.show(i),
      error: () => this.errorKey.set('errors.loadFailed'),
    });
  }

  private show(i: Invoice): void {
    this.invoice.set(i);
    const lines = this.form.controls.lines;
    lines.clear({ emitEvent: false });
    i.lines.forEach((l) => lines.push(line(fromLine(l)), { emitEvent: false }));
    this.form.reset({
      invoiceDate: i.invoiceDate, dueDate: i.dueDate ?? '', billingName: i.billingName, billingAddress: i.billingAddress ?? '',
      paymentTerms: i.paymentTerms ?? '', notes: i.notes ?? '',
    }, { emitEvent: false });
    if (i.allowedActions.includes('EDIT')) this.form.enable({ emitEvent: false });
    else this.form.disable({ emitEvent: false });
    this.changes.update((n) => n + 1);
  }

  private run(call: Observable<Invoice>, form?: FormGroup): void {
    const version = this.recordVersion;
    this.saving.set(true);
    this.errorKey.set(null);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (i) => {
        if (version !== this.recordVersion) return;
        this.saving.set(false);
        this.dialog.set(null);
        this.show(i);
      },
      error: (e: unknown) => { if (version === this.recordVersion) this.fail(toProblem(e), form); },
    });
  }

  private fail(problem: Problem, form?: FormGroup): void {
    this.saving.set(false);
    this.dialog.set(null);
    this.errorKey.set(form && applyServerErrors(form, problem) ? 'errors.codes.VALIDATION_FAILED' : problemKey(problem));
    if (problem.code === 'INVOICE_INCOMPLETE') this.reload();
  }
}

function fromLine(l: InvoiceLine) {
  return {
    source: l.source, costHeadCode: l.costHeadCode, description: l.description ?? '', quantity: String(l.quantity), unitPrice: String(l.unitPrice),
    extraHours: l.extraHours,
  };
}

function line(l: ReturnType<typeof fromLine>): LineGroup {
  return new FormGroup({
    source: new FormControl(l.source, { nonNullable: true }),
    costHeadCode: new FormControl(l.costHeadCode, { nonNullable: true, validators: Validators.required }),
    description: new FormControl(l.description, { nonNullable: true, validators: Validators.maxLength(300) }),
    quantity: new FormControl(l.quantity, { nonNullable: true, validators: [Validators.required, Validators.pattern(QUANTITY), positive] }),
    unitPrice: new FormControl(l.unitPrice, { nonNullable: true, validators: [Validators.required, Validators.pattern(PRICE)] }),
    extraHours: new FormControl(l.extraHours ?? false, { nonNullable: true }),
  });
}

function positive(control: AbstractControl<string>): ValidationErrors | null {
  return QUANTITY.test(control.value) && !toBig(control.value).gt(0) ? { pattern: true } : null;
}
