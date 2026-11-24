import { HttpEventType } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { uploadProblem } from '../../core/files/upload-validation';
import { LookupService, LookupOption } from '../../core/lookups/lookup.service';
import { Subject, forkJoin, takeUntil } from 'rxjs';
import { DocumentViewerComponent, ViewableFile } from '../../shared/components/document-viewer/document-viewer.component';
import { JobDocument, JobRequest, JobTechnicalJob, JobTechnicalService, RequestItem,
  VoyageField, VoyageVersion, CompatibilityReview, EmailPreview, ChecklistResponse, ChecklistTemplate } from './job-technical.service';

interface EditableValue { value: string; sourceDocumentId: string | null; sourcePage: number | null }

@Component({
  selector: 's2s-job-technical',
  imports: [FormsModule, RouterLink, TranslatePipe, DocumentViewerComponent],
  templateUrl: './job-technical.component.html',
  styleUrl: './job-technical.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JobTechnicalComponent {
  private readonly api = inject(JobTechnicalService);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);
  private readonly lookups = inject(LookupService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly jobChanged = new Subject<void>();
  private loadedJobId = '';
  readonly jobId = input.required<string>();

  protected readonly job = signal<JobTechnicalJob | null>(null);
  protected readonly request = signal<JobRequest | null>(null);
  protected readonly documents = signal<JobDocument[]>([]);
  protected readonly fields = signal<VoyageField[]>([]);
  protected readonly voyages = signal<Record<string, VoyageVersion[]>>({});
  protected readonly editValues = signal<Record<string, Record<string, EditableValue>>>({});
  protected readonly confirmed = signal<Record<string, boolean>>({});
  protected readonly returnReasons = signal<Record<string, string>>({});
  protected readonly review = signal<CompatibilityReview | null>(null);
  protected readonly email = signal<EmailPreview | null>(null);
  protected readonly emailSubject = signal('');
  protected readonly emailBody = signal('');
  protected readonly checklists = signal<ChecklistResponse[]>([]);
  protected readonly templates = signal<ChecklistTemplate[]>([]);
  protected readonly selectedTemplate = signal('');
  protected readonly selectedChecklistVessel = signal('');
  protected readonly documentTypes = signal<LookupOption[]>([]);
  protected readonly extraItem = signal({ code: '', label: '', kind: 'DOCUMENT', documentTypeId: '',
    jobVesselId: '', required: true, reason: '', phase: 'PRE_ARRIVAL' as 'PRE_ARRIVAL' | 'ON_ARRIVAL' | 'POST_DEPARTURE' });
  protected readonly selectedPhase = signal<'PRE_ARRIVAL' | 'ON_ARRIVAL' | 'POST_DEPARTURE'>('PRE_ARRIVAL');
  protected readonly checklistAnswers = signal<Record<string, Record<string, string>>>({});
  protected readonly checklistSigned = signal<Record<string, string>>({});
  protected readonly viewing = signal<ViewableFile | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly canWrite = computed(() => this.auth.hasPermission(Permissions.techJobWrite));
  protected readonly canReview = computed(() => this.auth.hasPermission(Permissions.techJobReview));
  protected readonly canManageTemplates = computed(() => this.auth.hasPermission(Permissions.masterTechnicalWrite));
  protected readonly activeItems = computed(() => (this.request()?.items ?? []).filter(item => item.status !== 'REMOVED'));
  protected readonly receivedCount = computed(() => this.activeItems().filter(item => this.received(item)).length);

  protected readonly phaseCounts = computed(() => {
    const items = this.activeItems();
    const count = (p: string) => {
      const phaseItems = items.filter(i => (i.phase ?? 'PRE_ARRIVAL') === p);
      const received = phaseItems.filter(i => this.received(i)).length;
      return { total: phaseItems.length, received };
    };
    return {
      PRE_ARRIVAL: count('PRE_ARRIVAL'),
      ON_ARRIVAL: count('ON_ARRIVAL'),
      POST_DEPARTURE: count('POST_DEPARTURE'),
    };
  });

  protected readonly readiness = computed(() => {
    const required = this.activeItems().filter(item => item.required && (item.phase ?? 'PRE_ARRIVAL') === 'PRE_ARRIVAL');
    const missing = required.filter(item => item.status !== 'REVIEWED').length;
    const isReady = required.length > 0 && missing === 0;
    const summary = isReady
      ? 'All required pre-arrival items have been reviewed.'
      : missing > 0
        ? `${missing} required pre-arrival item(s) still need to be received and reviewed.`
        : 'Awaiting the pre-arrival document request.';
    return { isReady, summary };
  });

  protected readonly activePhaseItems = computed(() =>
    this.activeItems().filter(item => (item.phase ?? 'PRE_ARRIVAL') === this.selectedPhase())
  );

  /** One column per vessel (Mother first), plus "Whole job" only when an item is not tied to a vessel. */
  protected readonly gridColumns = computed(() => {
    const vessels = [...(this.job()?.vessels ?? [])].sort((a, b) => (a.role === 'MOTHER' ? 0 : 1) - (b.role === 'MOTHER' ? 0 : 1))
      .map(v => ({ id: v.id, label: `${v.role === 'MOTHER' ? 'Mother' : 'Daughter'} · ${v.name ?? ''}` }));
    return this.activePhaseItems().some(item => !item.jobVesselId) ? [...vessels, { id: 'JOB', label: 'Whole job' }] : vessels;
  });

  /** One row per requested document in current phase, in template order. */
  protected readonly gridRows = computed(() => {
    const rows = new Map<string, { code: string; label: string; cells: Record<string, RequestItem> }>();
    for (const item of this.activePhaseItems()) {
      const row = rows.get(item.code) ?? { code: item.code, label: item.label, cells: {} };
      row.cells[item.jobVesselId ?? 'JOB'] = item;
      rows.set(item.code, row);
    }
    return [...rows.values()];
  });
  protected received(item: RequestItem): boolean {
    return item.status === 'RECEIVED' || item.status === 'REVIEWED' || item.status === 'WAIVED'
      || (item.documentVersionIds?.length ?? 0) > 0 || this.itemDocs(item).length > 0;
  }
  /** Typed answers (WhatsApp number etc.) save when the field is left, only if they changed. */
  protected saveText(item: RequestItem, text: string): void {
    if (text.trim() && text.trim() !== (item.responseText ?? '')) this.setItem(item, 'RECEIVED', text.trim());
  }
  protected gaDocument(vesselId: string): JobDocument | undefined {
    const typeId = this.documentTypes().find(type => type.meta?.['code'] === 'GA-PLAN')?.value;
    return this.documents().filter(doc => doc.jobVesselId === vesselId && doc.documentTypeId === typeId)
      .sort((a, b) => b.versionNo - a.versionNo)[0];
  }
  protected canVerifyVersion(version: VoyageVersion): boolean {
    return this.canReview() && version.submittedBy !== this.auth.currentUser()?.id;
  }

  constructor() {
    effect(() => { const id = this.jobId(); untracked(() => this.reload(id)); });
    forkJoin([this.lookups.documentTypes('VESSEL'), this.lookups.documentTypes('JOB')])
      .subscribe({ next: ([vessel, job]) => this.documentTypes.set([...vessel, ...job]) });
  }

  protected reload(id = this.jobId()): void {
    this.jobChanged.next();
    if (id !== this.loadedJobId) {
      this.loadedJobId = id;
      this.job.set(null); this.request.set(null); this.documents.set([]); this.fields.set([]);
      this.voyages.set({}); this.editValues.set({}); this.confirmed.set({}); this.returnReasons.set({});
      this.review.set(null); this.email.set(null); this.emailSubject.set(''); this.emailBody.set('');
      this.checklists.set([]); this.checklistAnswers.set({}); this.checklistSigned.set({});
      this.selectedChecklistVessel.set('');
      this.extraItem.update(item => ({ ...item, code: '', label: '', jobVesselId: '', documentTypeId: '', reason: '' }));
      this.viewing.set(null); this.busy.set(false); this.notice.set(null);
    }
    this.error.set(null);
    this.api.job(id).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (job) => { this.job.set(job); this.loadDetails(id, job); },
      error: () => this.error.set('Unable to load this job.'),
    });
  }

  private loadDetails(id: string, job: JobTechnicalJob): void {
    forkJoin([this.api.request(id), this.api.documents(id)]).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ([request, documents]) => {
        this.request.set(request); this.documents.set(documents);
        if (this.canWrite() && request.id && request.items.some(item => (item.kind === 'DOCUMENT' || item.kind === 'CHECKLIST') && item.status === 'PENDING'
          && (item.documentVersionIds.length > 0 || documents.some(doc => (item.jobVesselId ? doc.jobVesselId === item.jobVesselId : true) && doc.documentTypeId === item.documentTypeId)))) {
          this.api.initialise(id).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({ next: linked => this.request.set(linked),
            error: () => this.error.set('Could not link documents. Reload and try again.') });
        }
        job.vessels.forEach(vessel => this.loadVoyage(vessel.id, documents.some(d => d.jobVesselId === vessel.id)));
      },
      error: () => this.error.set('Unable to load the document request.'),
    });
    this.api.voyageFields(id).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({ next: x => this.fields.set(x) });
    this.api.review(id).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({ next: x => this.review.set(x) });
    this.api.checklists(id).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({ next: x => { this.checklists.set(x); this.hydrateChecklists(x); } });
    this.api.templates().pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({ next: x => this.templates.set(x.filter(t => t.status === 'PUBLISHED')) });
  }

  protected vesselName(id: string | null): string {
    if (!id) return 'Job';
    const vessel = this.job()?.vessels.find(v => v.id === id);
    return vessel ? `${vessel.role} · ${vessel.name ?? 'Unnamed vessel'}` : 'Unknown vessel';
  }

  protected docsFor(vesselId: string | null): JobDocument[] {
    return this.documents().filter(d => d.jobVesselId === vesselId);
  }

  protected itemDocs(item: RequestItem): JobDocument[] {
    const matched = item.documentVersionIds.map(id => this.documents().find(d => d.id === id)).filter((d): d is JobDocument => !!d);
    if (matched.length) return matched;
    if (item.documentTypeId) {
      const byType = this.documents().filter(d =>
        d.documentTypeId === item.documentTypeId && (item.jobVesselId ? d.jobVesselId === item.jobVesselId : true)
      ).sort((a, b) => b.versionNo - a.versionNo);
      if (byType.length) return [byType[0]];
    }
    if (item.code === 'GA' && item.jobVesselId) {
      const ga = this.gaDocument(item.jobVesselId);
      return ga ? [ga] : [];
    }
    return [];
  }

  protected primaryDoc(vesselId: string): JobDocument | undefined {
    const sourceId = this.latestVoyage(vesselId)?.values?.[0]?.sourceDocumentId;
    return (sourceId ? this.documents().find(d => d.id === sourceId) : undefined) ?? this.docsFor(vesselId)[0];
  }

  protected documentLabel(id: string | null): string {
    const d = this.documents().find(x => x.id === id);
    return d ? `${d.fileName} · v${d.versionNo}` : 'Select source document';
  }

  protected startRequest(): void {
    if (this.busy()) return;
    this.busy.set(true); this.error.set(null);
    this.api.initialise(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: x => { this.request.set(x); this.busy.set(false); this.notice.set('Request created.'); },
      error: () => { this.busy.set(false); this.error.set('The request template is still pending or could not be created.'); },
    });
  }

  protected setItem(item: RequestItem, status: string, responseText?: string, reason?: string): void {
    if (this.busy()) return;
    this.busy.set(true); this.error.set(null);
    this.api.changeItem(this.jobId(), item, status, responseText ?? item.responseText, reason ?? null).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: x => { this.request.set(x); this.busy.set(false); },
      error: () => { this.busy.set(false); this.error.set('Could not update the item. Reload and try again.'); },
    });
  }

  protected setExtra(key: keyof ReturnType<typeof this.extraItem>, value: string | boolean): void {
    this.extraItem.update(x => ({ ...x, [key]: value }));
  }
  protected addItem(): void {
    const x = this.extraItem();
    if (!x.code.trim() || !x.label.trim() || !x.reason.trim()) return;
    this.busy.set(true);
    this.api.addItem(this.jobId(), { code: x.code, label: x.label, kind: x.kind,
      documentTypeId: x.kind === 'DOCUMENT' ? x.documentTypeId || null : null,
      jobVesselId: x.jobVesselId || null, required: x.required, reason: x.reason,
      phase: x.phase || this.selectedPhase() }).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: request => { this.busy.set(false); this.request.set(request); this.extraItem.set({ code: '', label: '', kind: 'DOCUMENT',
        documentTypeId: '', jobVesselId: '', required: true, reason: '', phase: this.selectedPhase() }); },
      error: () => { this.busy.set(false); this.error.set('Could not add the job-specific item.'); },
    });
  }

  protected upload(item: RequestItem, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const problem = uploadProblem(file);
    if (problem) { this.error.set(this.translate.instant(problem)); input.value = ''; return; }
    this.busy.set(true); this.error.set(null);
    // Optimistically mark item as received so the checkbox immediately checks in the UI
    this.request.update(req => {
      if (!req) return req;
      return {
        ...req,
        items: req.items.map(it => it.id === item.id ? { ...it, status: 'RECEIVED' } : it),
      };
    });
    this.api.upload(this.jobId(), item, file).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: e => {
        if (e.type === HttpEventType.Response) {
          this.busy.set(false);
          const doc = e.body as JobDocument;
          if (doc) {
            this.documents.update(docs => [doc, ...docs.filter(d => d.id !== doc.id)]);
          }
          this.api.request(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe(x => this.request.set(x));
          this.api.documents(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe(x => this.documents.set(x));
          if (item.jobVesselId) this.loadVoyage(item.jobVesselId);
          this.api.review(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe(r => this.review.set(r));
          this.notice.set('Document uploaded and checkbox marked received.');
          input.value = '';
        }
      },
      error: () => {
        this.busy.set(false);
        this.error.set('Upload failed. Check the file type and size.');
        this.api.request(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe(x => this.request.set(x));
        input.value = '';
      },
    });
  }

  protected showDocument(document: JobDocument): void {
    this.viewing.set({ id: document.fileId, name: document.fileName, contentType: document.contentType });
  }

  protected previewEmail(): void {
    this.api.email(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: x => { this.email.set(x); this.emailSubject.set(x.subject); this.emailBody.set(x.body); },
      error: () => this.error.set('Create the request before previewing the email.'),
    });
  }

  protected async copyEmail(): Promise<void> {
    const text = `Subject: ${this.emailSubject()}\n\n${this.emailBody()}`;
    try { await navigator.clipboard.writeText(text); this.notice.set('Email text copied. Send it from your mail app.'); }
    catch { this.error.set('Copy was unavailable. Select the text and copy it manually.'); }
  }

  private loadVoyage(vesselId: string, autoPopulateIfEmpty = false): void {
    this.api.voyage(this.jobId(), vesselId).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({ next: x => {
      this.voyages.update(all => ({ ...all, [vesselId]: x.versions }));
      x.versions.forEach(version => this.hydrateVoyage(version));
      if (autoPopulateIfEmpty && x.versions.length === 0 && this.canWrite()) {
        this.populateFromDoc(vesselId, true);
      }
    } });
  }

  private hydrateVoyage(version: VoyageVersion): void {
    const row: Record<string, EditableValue> = {};
    version.values.forEach(v => { row[v.fieldCode] = { value: v.value, sourceDocumentId: v.sourceDocumentId, sourcePage: v.sourcePage }; });
    this.editValues.update(all => ({ ...all, [version.id]: row, [version.jobVesselId]: row }));
  }

  protected latestVoyage(vesselId: string): VoyageVersion | null { return this.voyages()[vesselId]?.[0] ?? null; }
  protected vesselValue(vesselId: string, code: string): string {
    return this.editValues()[vesselId]?.[code]?.value ?? '';
  }
  protected changeVesselValue(vesselId: string, code: string, value: string | number | null): void {
    const current = this.editValues()[vesselId]?.[code] ?? { value: '', sourceDocumentId: this.primaryDoc(vesselId)?.id ?? null, sourcePage: 1 };
    const updated = { ...current, value: value == null ? '' : String(value) };
    this.editValues.update(all => ({ ...all, [vesselId]: { ...(all[vesselId] ?? {}), [code]: updated } }));
  }
  protected populateFromDoc(vesselId: string, silent = false): void {
    if (!silent) this.busy.set(true);
    this.api.populateVoyageFromDoc(this.jobId(), vesselId).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => {
        if (!silent) this.busy.set(false);
        this.voyages.update(all => ({ ...all, [vesselId]: [v] }));
        this.hydrateVoyage(v);
        this.api.request(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe(x => this.request.set(x));
        this.api.review(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe(r => this.review.set(r));
        if (!silent) this.notice.set('JPO values populated from uploaded document.');
      },
      error: () => { if (!silent) { this.busy.set(false); this.error.set('Upload a document for this vessel first.'); } },
    });
  }
  protected saveFromDoc(vesselId: string): void {
    const docId = this.primaryDoc(vesselId)?.id ?? null;
    const values = Object.entries(this.editValues()[vesselId] ?? {}).filter(([,v]) => v.value.trim()).map(([fieldCode,v]) => ({
      fieldCode, value: v.value, sourceDocumentId: v.sourceDocumentId ?? docId, sourcePage: v.sourcePage ?? 1,
    }));
    this.busy.set(true);
    this.api.saveVoyageFromDoc(this.jobId(), vesselId, values).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => {
        this.busy.set(false);
        this.voyages.update(all => ({ ...all, [vesselId]: [v] }));
        this.hydrateVoyage(v);
        this.api.request(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe(x => this.request.set(x));
        this.api.review(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe(r => this.review.set(r));
        this.notice.set('Voyage values saved.');
      },
      error: () => { this.busy.set(false); this.error.set('Could not save voyage values.'); },
    });
  }
  protected fieldValue(version: VoyageVersion, code: string): EditableValue {
    return this.editValues()[version.id]?.[code] ?? { value: '', sourceDocumentId: null, sourcePage: null };
  }
  protected changeValue(version: VoyageVersion, code: string, key: keyof EditableValue, value: string | number | null): void {
    const updated = { ...this.fieldValue(version, code) };
    if (key === 'value') updated.value = value == null ? '' : String(value);
    else if (key === 'sourcePage') updated.sourcePage = value == null || value === '' ? null : Number(value);
    else updated.sourceDocumentId = value == null || value === '' ? null : String(value);
    this.editValues.update(all => ({ ...all, [version.id]: {
      ...(all[version.id] ?? {}), [code]: updated,
    } }));
  }
  protected newVoyage(vesselId: string): void {
    this.busy.set(true);
    this.api.newVoyage(this.jobId(), vesselId).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy.set(false); this.loadVoyage(vesselId); },
      error: () => { this.busy.set(false); this.error.set('Could not start a voyage version.'); },
    });
  }
  protected saveVoyage(version: VoyageVersion): void {
    const values = Object.entries(this.editValues()[version.id] ?? {}).filter(([,v]) => v.value.trim()).map(([fieldCode,v]) => ({
      fieldCode, value: v.value, sourceDocumentId: v.sourceDocumentId, sourcePage: v.sourcePage,
    }));
    this.busy.set(true);
    this.api.saveVoyage(this.jobId(), version, version.notes, values).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: x => { this.busy.set(false); this.loadVoyage(x.jobVesselId); this.notice.set('Voyage values saved.'); },
      error: () => { this.busy.set(false); this.error.set('Could not save voyage values. Reload and check the sources.'); },
    });
  }
  protected voyageAction(version: VoyageVersion, action: 'submit' | 'verify' | 'return'): void {
    const body = action === 'verify' ? { confirmedFieldCodes: version.values.map(v => v.fieldCode) } :
      action === 'return' ? { reason: this.returnReasons()[version.id] ?? '' } : undefined;
    this.busy.set(true);
    this.api.voyageAction(this.jobId(), version, action, body).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: x => { this.busy.set(false); this.loadVoyage(x.jobVesselId); this.api.review(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe(r => this.review.set(r)); },
      error: () => { this.busy.set(false); this.error.set(`Could not ${action} this voyage version.`); },
    });
  }
  protected toggleConfirm(version: VoyageVersion, fieldCode: string, checked: boolean): void {
    this.confirmed.update(all => ({ ...all, [`${version.id}:${fieldCode}`]: checked }));
  }
  protected allConfirmed(version: VoyageVersion): boolean {
    return version.values.length > 0 && version.values.every(v => this.confirmed()[`${version.id}:${v.fieldCode}`]);
  }
  protected setReturnReason(version: VoyageVersion, reason: string): void {
    this.returnReasons.update(all => ({ ...all, [version.id]: reason }));
  }

  private hydrateChecklists(responses: ChecklistResponse[]): void {
    const answers: Record<string, Record<string, string>> = {};
    const signed: Record<string, string> = {};
    responses.forEach(r => { answers[r.id] = Object.fromEntries(Object.entries(r.answers).map(([k,v]) => [k,v ?? '']));
      signed[r.id] = r.signedDocumentId ?? ''; });
    this.checklistAnswers.set(answers); this.checklistSigned.set(signed);
  }
  protected changeAnswer(responseId: string, code: string, answer: string): void {
    this.checklistAnswers.update(all => ({ ...all, [responseId]: { ...(all[responseId] ?? {}), [code]: answer } }));
  }
  protected changeSigned(responseId: string, id: string): void {
    this.checklistSigned.update(all => ({ ...all, [responseId]: id }));
  }
  protected createChecklist(): void {
    if (!this.selectedTemplate()) return;
    this.busy.set(true);
    this.api.newChecklist(this.jobId(), this.selectedTemplate(), this.selectedChecklistVessel() || null).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy.set(false); this.api.checklists(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe(x => { this.checklists.set(x); this.hydrateChecklists(x); }); },
      error: () => { this.busy.set(false); this.error.set('Could not create the checklist response.'); },
    });
  }
  protected saveChecklist(response: ChecklistResponse, status: string): void {
    const answers = response.template.items.map(item => ({ itemCode: item.code,
      answer: this.checklistAnswers()[response.id]?.[item.code] || null }));
    this.busy.set(true);
    this.api.saveChecklist(this.jobId(), response, answers, this.checklistSigned()[response.id] || null, status).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy.set(false); this.api.checklists(this.jobId()).pipe(takeUntil(this.jobChanged), takeUntilDestroyed(this.destroyRef)).subscribe(x => { this.checklists.set(x); this.hydrateChecklists(x); }); },
      error: () => { this.busy.set(false); this.error.set('Could not save the checklist response.'); },
    });
  }
}
