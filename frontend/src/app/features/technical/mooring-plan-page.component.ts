import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { Subscription, catchError, of } from 'rxjs';
import { API_BASE_URL, problemKey, toProblem } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { Permissions } from '../../core/auth/permissions';
import { DocumentViewerComponent, ViewableFile } from '../../shared/components/document-viewer/document-viewer.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { ReasonDialogComponent } from '../../shared/components/reason-dialog/reason-dialog.component';
import { StatusTagComponent } from '../../shared/components/status-tag/status-tag.component';
import { JobDocument, JobTechnicalService } from './job-technical.service';
import { MooringPlanCanvasComponent } from './mooring-plan-canvas.component';
import { MooringCapacity, MooringLayer, MooringLine, MooringModel, MooringObject, MooringOverview,
  MooringParticular, MooringPlanService, MooringRevision, MooringRule, MooringVessel } from './mooring-plan.service';

const SYMBOLS = ['PRIMARY_FENDER', 'SECONDARY_FENDER', 'TUG', 'STS_SUPPORT_CRAFT', 'CONSTANT_HEADING_MOORING',
  'MANOEUVRING_MOORING', 'FENDER_MOORING', 'MOORING_POINT', 'ANNOTATION'];
const PARTS = ['PRIMARY_FENDER_SIZE', 'OIL_BOOM_LENGTH'];

@Component({
  selector: 's2s-mooring-plan-page',
  imports: [ReactiveFormsModule, RouterLink, TranslatePipe, Button, Message, PageHeaderComponent, StatusTagComponent,
    ReasonDialogComponent, DocumentViewerComponent, MooringPlanCanvasComponent],
  templateUrl: './mooring-plan-page.component.html',
  styleUrl: './mooring-plan-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MooringPlanPageComponent {
  private readonly api = inject(MooringPlanService);
  private readonly technical = inject(JobTechnicalService);
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private loadRequests = new Subscription();
  private revisionRequest?: Subscription;
  private loadedJobId = '';
  private jobVersion = 0;
  readonly jobId = input.required<string>();

  protected readonly symbols = SYMBOLS;
  protected readonly parts = PARTS;
  protected readonly overview = signal<MooringOverview | null>(null);
  protected readonly revision = signal<MooringRevision | null>(null);
  protected readonly model = signal<MooringModel | null>(null);
  protected readonly documents = signal<JobDocument[]>([]);
  protected readonly layerUrls = signal<Record<string, string>>({});
  protected readonly rule = signal<MooringRule | null>(null);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly objectId = signal<string | null>(null);
  protected readonly lineId = signal<string | null>(null);
  protected readonly objectEditorOpen = signal(false);
  protected readonly lineEditorOpen = signal(false);
  protected readonly layerId = signal<string | null>(null);
  protected readonly dirty = signal(false);
  protected readonly busy = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly shareError = signal<string | null>(null);
  protected readonly viewing = signal<ViewableFile | null>(null);
  protected readonly returnOpen = signal(false);
  protected readonly operatorStep = signal<1 | 2 | 3>(1);
  protected readonly can = computed(() => new Set(this.revision()?.allowedActions ?? []));
  protected readonly editable = computed(() => this.can().has('EDIT'));
  protected readonly canManageColumns = computed(() => this.auth.hasPermission(Permissions.masterTechnicalWrite));
  protected readonly canReadJpo = computed(() => this.auth.hasPermission(Permissions.techJpoRead));

  protected readonly rulerForm = new FormGroup({ step: new FormControl<number | null>(null, Validators.required) });
  protected readonly vesselForm = new FormGroup({
    jobVesselId: new FormControl('', { nonNullable: true }),
    heading: new FormControl<'CONSTANT' | 'MANOEUVRING' | null>(null),
    lateralOffsetM: new FormControl<number | null>(null),
    orientation: new FormControl<'BOW_UP' | 'BOW_DOWN'>('BOW_UP', { nonNullable: true }),
  });
  protected readonly objectForm = new FormGroup({
    type: new FormControl('MOORING_POINT', { nonNullable: true }),
    xM: new FormControl(0, { nonNullable: true }), yM: new FormControl(0, { nonNullable: true }),
    jobVesselId: new FormControl<string | null>(null), label: new FormControl<string | null>(null),
  });
  protected readonly lineForm = new FormGroup({
    label: new FormControl('', { nonNullable: true, validators: Validators.required }),
    jobVesselId: new FormControl('', { nonNullable: true, validators: Validators.required }),
    fromPointId: new FormControl<string | null>(null), toPointId: new FormControl<string | null>(null),
    berthingOrder: new FormControl<number | null>(null), unmooringOrder: new FormControl<number | null>(null),
  });
  protected readonly particularForm = new FormGroup({
    jobVesselId: new FormControl('', { nonNullable: true }), code: new FormControl('', { nonNullable: true }),
    value: new FormControl<number | null>(null), sourceDocumentId: new FormControl<string | null>(null),
    sourcePage: new FormControl<number | null>(null), sourceText: new FormControl<string | null>(null),
  });
  protected readonly capacityForm = new FormGroup({
    lineId: new FormControl('', { nonNullable: true }), columnCode: new FormControl('', { nonNullable: true }),
    value: new FormControl<number | null>(null), sourceDocumentId: new FormControl<string | null>(null),
    sourcePage: new FormControl<number | null>(null), sourceText: new FormControl<string | null>(null),
  });
  protected readonly layerForm = new FormGroup({
    jobVesselId: new FormControl('', { nonNullable: true }), documentVersionId: new FormControl('', { nonNullable: true }),
    xM: new FormControl(0, { nonNullable: true }), yM: new FormControl(0, { nonNullable: true }),
    scale: new FormControl(1, { nonNullable: true }), visible: new FormControl(true, { nonNullable: true }),
  });
  protected readonly columnForm = new FormGroup({
    code: new FormControl('', { nonNullable: true }), definition: new FormControl('', { nonNullable: true }),
    unit: new FormControl('', { nonNullable: true }), evidenceRequirement: new FormControl('', { nonNullable: true }),
  });
  protected readonly ruleForm = new FormGroup({
    toleranceM: new FormControl<number | null>(null), active: new FormControl(false, { nonNullable: true }),
  });

  constructor() {
    effect(() => { const id = this.jobId(); untracked(() => this.reload(id)); });
  }

  protected reload(id = this.jobId()): void {
    this.loadRequests.unsubscribe();
    this.loadRequests = new Subscription();
    this.revisionRequest?.unsubscribe();
    if (id !== this.loadedJobId) {
      this.loadedJobId = id;
      this.jobVersion++;
      this.overview.set(null);
      this.revision.set(null);
      this.model.set(null);
      this.documents.set([]);
      this.layerUrls.set({});
      this.selectedId.set(null);
      this.dirty.set(false);
      this.busy.set(false);
      this.objectEditorOpen.set(false);
      this.lineEditorOpen.set(false);
      this.viewing.set(null);
      this.returnOpen.set(false);
      this.operatorStep.set(1);
    }
    this.errorKey.set(null);
    this.loadRequests.add(this.api.overview(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: overview => {
        this.overview.set(overview);
        const selected = overview.revisions.find(r => r.id === this.selectedId()) ?? overview.revisions[0];
        if (selected) this.select(selected.id); else { this.revision.set(null); this.model.set(null); }
      }, error: error => this.fail(error),
    }));
    this.loadRequests.add(this.technical.documents(id).pipe(catchError(() => of([])), takeUntilDestroyed(this.destroyRef))
      .subscribe(documents => { this.documents.set(documents); const model = this.model(); if (model) this.refreshLayers(model); }));
    if (this.canManageColumns()) this.loadRequests.add(this.api.rule().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: rule => { this.rule.set(rule); this.ruleForm.reset(rule); },
    }));
  }

  protected select(id: string): void {
    if (this.dirty() || (this.objectEditorOpen() && this.objectForm.dirty) || (this.lineEditorOpen() && this.lineForm.dirty)) {
      if (id === this.revision()?.id || !window.confirm(this.translate.instant('mooring.discardDraft'))) return;
    }
    this.revisionRequest?.unsubscribe();
    this.revisionRequest = this.api.revision(this.jobId(), id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: revision => this.accept(revision), error: error => this.fail(error),
    });
  }

  protected create(): void { this.run(() => this.api.create(this.jobId())); }
  protected save(): void {
    const revision = this.revision(); const model = this.model();
    if (!revision || !model || this.objectForm.dirty || this.lineForm.dirty) return;
    this.run(() => this.api.save(this.jobId(), revision, model));
  }
  protected submit(): void { const r = this.revision(); if (r) this.run(() => this.api.submit(this.jobId(), r)); }
  protected approve(): void { const r = this.revision(); if (r) this.run(() => this.api.decide(this.jobId(), r, 'APPROVE', null)); }
  protected returnWith(reason: string | null): void {
    const r = this.revision(); if (r) this.run(() => this.api.decide(this.jobId(), r, 'RETURN', reason));
    this.returnOpen.set(false);
  }
  protected issue(): void { const r = this.revision(); if (r) this.run(() => this.api.issue(this.jobId(), r)); }
  protected verify(key: string): void { const r = this.revision(); if (r) this.run(() => this.api.verify(this.jobId(), r, key)); }
  protected pdfUrl(): string { const r = this.revision(); return r ? this.api.pdfUrl(this.jobId(), r) : '#'; }
  protected shareIssuedPlan(): void {
    const revision = this.revision();
    if (!revision?.pdfSha256) return;
    this.shareError.set(null);
    this.http.get(this.pdfUrl(), { responseType: 'blob' }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: blob => {
        const file = new File([blob], `${this.overview()?.jobNumber?.replaceAll('/', '-') ?? 'Job'}-Mooring-r${revision.revisionNo}.pdf`,
          { type: 'application/pdf' });
        if (!navigator.canShare?.({ files: [file] })) {
          this.shareError.set('This browser cannot share PDF files. Open the issued PDF to download it.');
          return;
        }
        void navigator.share({ files: [file], title: 'Issued mooring plan' }).catch(error => {
          if (error?.name !== 'AbortError') this.shareError.set('The plan was not shared. Please try again.');
        });
      },
      error: () => this.shareError.set('Could not load the issued PDF.'),
    });
  }

  private run(call: () => import('rxjs').Observable<MooringRevision>): void {
    const version = this.jobVersion;
    this.busy.set(true); this.errorKey.set(null);
    call().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => {
        if (version !== this.jobVersion) return;
        this.busy.set(false); this.revisionRequest?.unsubscribe(); this.accept(r);
        this.api.overview(this.jobId()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: o => { if (version === this.jobVersion) this.overview.set(o); },
          error: e => { if (version === this.jobVersion) this.fail(e); },
        });
      },
      error: e => { if (version === this.jobVersion) { this.busy.set(false); this.fail(e); } },
    });
  }
  private accept(r: MooringRevision): void {
    this.revision.set(r); this.model.set(structuredClone(r.model)); this.selectedId.set(r.id); this.dirty.set(false);
    this.rulerForm.reset({ step: r.model.rulerStepM });
    this.chooseVessel(r.model.vessels[0]?.jobVesselId ?? '');
    this.chooseObject(null, true); this.chooseLine(null, true); this.chooseLayer(null);
    this.objectEditorOpen.set(false); this.lineEditorOpen.set(false);
    this.refreshLayers(r.model);
  }
  private fail(error: unknown): void { this.errorKey.set(problemKey(toProblem(error))); }
  private change(mutator: (model: MooringModel) => void): void {
    if (!this.editable()) return;
    const current = this.model(); if (!current) return;
    const next = { ...current }; mutator(next); this.model.set(next); this.dirty.set(true);
  }

  protected saveRuler(): void { this.change(m => { m.rulerStepM = this.rulerForm.controls.step.value; }); }
  protected chooseVessel(id: string): void {
    const vessel = this.model()?.vessels.find(v => v.jobVesselId === id);
    if (vessel) this.vesselForm.reset(vessel);
  }
  protected saveVessel(): void {
    const value = this.vesselForm.getRawValue() as MooringVessel;
    this.change(m => { m.vessels = m.vessels.map(v => v.jobVesselId === value.jobVesselId ? value : v); });
  }
  protected applyStandardLayout(): void {
    const r = this.revision();
    const m = this.model();
    if (!r || !m) return;
    const vessels = r.snapshot.vessels;
    const mother = vessels.find(v => v.role === 'MOTHER') ?? vessels[0];
    const daughter = vessels.find(v => v.role === 'DAUGHTER') ?? vessels[1] ?? mother;
    if (!mother || !daughter) return;

    const motherBeam = mother.dimensions.find(d => d.code === 'BEAM')?.value ?? 36;
    const daughterBeam = daughter.dimensions.find(d => d.code === 'BEAM')?.value ?? 30;
    const lateralOffset = (motherBeam / 2) + 6 + (daughterBeam / 2);
    const fenderY = (motherBeam / 2) + 3;

    const mBowId = crypto.randomUUID();
    const mMid1Id = crypto.randomUUID();
    const mMid2Id = crypto.randomUUID();
    const mSternId = crypto.randomUUID();
    const dBowId = crypto.randomUUID();
    const dMid1Id = crypto.randomUUID();
    const dMid2Id = crypto.randomUUID();
    const dSternId = crypto.randomUUID();

    const objects: MooringObject[] = [
      { id: crypto.randomUUID(), type: 'PRIMARY_FENDER', xM: -20, yM: fenderY, jobVesselId: null, label: 'Primary Fender 1' },
      { id: crypto.randomUUID(), type: 'PRIMARY_FENDER', xM: -7, yM: fenderY, jobVesselId: null, label: 'Primary Fender 2' },
      { id: crypto.randomUUID(), type: 'PRIMARY_FENDER', xM: 7, yM: fenderY, jobVesselId: null, label: 'Primary Fender 3' },
      { id: crypto.randomUUID(), type: 'PRIMARY_FENDER', xM: 20, yM: fenderY, jobVesselId: null, label: 'Primary Fender 4' },
      { id: crypto.randomUUID(), type: 'SECONDARY_FENDER', xM: -45, yM: fenderY, jobVesselId: null, label: 'Secondary Fender Fwd' },
      { id: crypto.randomUUID(), type: 'SECONDARY_FENDER', xM: 45, yM: fenderY, jobVesselId: null, label: 'Secondary Fender Aft' },
      { id: mBowId, type: 'MOORING_POINT', xM: -75, yM: motherBeam / 2, jobVesselId: mother.jobVesselId, label: 'Mother Bow Chock' },
      { id: mMid1Id, type: 'MOORING_POINT', xM: -10, yM: motherBeam / 2, jobVesselId: mother.jobVesselId, label: 'Mother Fwd Spring Bitt' },
      { id: mMid2Id, type: 'MOORING_POINT', xM: 10, yM: motherBeam / 2, jobVesselId: mother.jobVesselId, label: 'Mother Aft Spring Bitt' },
      { id: mSternId, type: 'MOORING_POINT', xM: 75, yM: motherBeam / 2, jobVesselId: mother.jobVesselId, label: 'Mother Stern Chock' },
      { id: dBowId, type: 'MOORING_POINT', xM: -60, yM: lateralOffset - (daughterBeam / 2), jobVesselId: daughter.jobVesselId, label: 'Daughter Bow Chock' },
      { id: dMid1Id, type: 'MOORING_POINT', xM: -10, yM: lateralOffset - (daughterBeam / 2), jobVesselId: daughter.jobVesselId, label: 'Daughter Fwd Spring Chock' },
      { id: dMid2Id, type: 'MOORING_POINT', xM: 10, yM: lateralOffset - (daughterBeam / 2), jobVesselId: daughter.jobVesselId, label: 'Daughter Aft Spring Chock' },
      { id: dSternId, type: 'MOORING_POINT', xM: 60, yM: lateralOffset - (daughterBeam / 2), jobVesselId: daughter.jobVesselId, label: 'Daughter Stern Chock' },
    ];

    const lines: MooringLine[] = [
      { id: crypto.randomUUID(), label: 'Head Line 1', jobVesselId: daughter.jobVesselId, fromPointId: dBowId, toPointId: mBowId, berthingOrder: 1, unmooringOrder: 6, capacities: [] },
      { id: crypto.randomUUID(), label: 'Head Line 2', jobVesselId: daughter.jobVesselId, fromPointId: dBowId, toPointId: mBowId, berthingOrder: 2, unmooringOrder: 5, capacities: [] },
      { id: crypto.randomUUID(), label: 'Fwd Spring Line', jobVesselId: daughter.jobVesselId, fromPointId: dMid1Id, toPointId: mMid2Id, berthingOrder: 3, unmooringOrder: 4, capacities: [] },
      { id: crypto.randomUUID(), label: 'Aft Spring Line', jobVesselId: daughter.jobVesselId, fromPointId: dMid2Id, toPointId: mMid1Id, berthingOrder: 4, unmooringOrder: 3, capacities: [] },
      { id: crypto.randomUUID(), label: 'Stern Line 1', jobVesselId: daughter.jobVesselId, fromPointId: dSternId, toPointId: mSternId, berthingOrder: 5, unmooringOrder: 2, capacities: [] },
      { id: crypto.randomUUID(), label: 'Stern Line 2', jobVesselId: daughter.jobVesselId, fromPointId: dSternId, toPointId: mSternId, berthingOrder: 6, unmooringOrder: 1, capacities: [] },
    ];

    this.change(draft => {
      draft.vessels = [
        { jobVesselId: mother.jobVesselId, heading: 'CONSTANT', lateralOffsetM: 0, orientation: 'BOW_UP' },
        { jobVesselId: daughter.jobVesselId, heading: 'MANOEUVRING', lateralOffsetM: lateralOffset, orientation: 'BOW_UP' },
      ];
      draft.objects = objects;
      draft.lines = lines;
      draft.rulerStepM = draft.rulerStepM ?? 10;
    });
    this.operatorStep.set(2);
  }
  protected chooseObject(id: string | null, force = false): boolean {
    if (!force && this.objectEditorOpen() && this.objectForm.dirty) {
      if (id === this.objectId() || !window.confirm(this.translate.instant('mooring.discardObjectEdit'))) return false;
    }
    this.objectId.set(id);
    this.objectEditorOpen.set(true);
    const obj = this.model()?.objects.find(o => o.id === id);
    this.objectForm.reset(obj ?? { type: 'MOORING_POINT', xM: 0, yM: 0, jobVesselId: null, label: null });
    return true;
  }
  protected editObjectFromCanvas(id: string): void {
    if (!this.chooseObject(id)) return;
    this.operatorStep.set(2);
    requestAnimationFrame(() => document.getElementById('mooring-object-editor')?.scrollIntoView({ block: 'nearest' }));
  }
  protected saveObject(): void {
    const value = this.objectForm.getRawValue(); const id = this.objectId() ?? crypto.randomUUID();
    const obj: MooringObject = { ...value, id };
    this.change(m => { m.objects = [...m.objects.filter(o => o.id !== id), obj]; }); this.objectId.set(id);
    this.objectForm.markAsPristine();
  }
  protected addSymbol(symbol: string): void {
    if (!this.chooseObject(null)) return;
    this.objectEditorOpen.set(false);
    this.objectForm.patchValue({ type: symbol });
    this.saveObject();
  }
  protected deleteObject(): void {
    const id = this.objectId(); if (!id) return;
    this.change(m => { m.objects = m.objects.filter(o => o.id !== id); m.lines = m.lines.map(l => ({ ...l,
      fromPointId: l.fromPointId === id ? null : l.fromPointId, toPointId: l.toPointId === id ? null : l.toPointId })); });
    this.chooseObject(null, true);
    this.objectEditorOpen.set(false);
  }
  protected moveObject(move: { id: string; xM: number; yM: number }): void {
    this.change(m => { m.objects = m.objects.map(o => o.id === move.id ? { ...o, xM: move.xM, yM: move.yM } : o); });
  }
  protected chooseLine(id: string | null, force = false): void {
    if (!force && this.lineEditorOpen() && this.lineForm.dirty) {
      if (id === this.lineId() || !window.confirm(this.translate.instant('mooring.discardDraft'))) return;
    }
    this.lineId.set(id); this.lineEditorOpen.set(true); const line = this.model()?.lines.find(l => l.id === id);
    this.lineForm.reset(line ?? { label: '', jobVesselId: this.model()?.vessels[0]?.jobVesselId ?? '',
      fromPointId: null, toPointId: null, berthingOrder: (this.model()?.lines.length ?? 0) + 1,
      unmooringOrder: 1 });
  }
  protected saveLine(): void {
    const value = this.lineForm.getRawValue(); if (!value.label.trim() || !value.jobVesselId) return;
    const id = this.lineId() ?? crypto.randomUUID(); const line: MooringLine = { ...value, id,
      label: value.label.trim(), capacities: this.model()?.lines.find(l => l.id === id)?.capacities ?? [] };
    const newLine = this.lineId() === null;
    const customUnmooring = this.lineForm.controls.unmooringOrder.dirty;
    this.change(m => {
      let next = line;
      if (newLine && !customUnmooring) {
        if (m.lines.every(l => l.unmooringOrder === m.lines.length - (l.berthingOrder ?? 0) + 1)) {
          m.lines = m.lines.map(l => ({ ...l, unmooringOrder: (l.unmooringOrder ?? 0) + 1 }));
        } else next = { ...line, unmooringOrder: Math.max(0, ...m.lines.map(l => l.unmooringOrder ?? 0)) + 1 };
      }
      m.lines = [...m.lines.filter(l => l.id !== id), next];
    }); this.lineId.set(id); this.lineForm.markAsPristine();
  }
  protected deleteLine(): void {
    const id = this.lineId(); if (!id) return;
    this.change(m => { m.lines = m.lines.filter(l => l.id !== id); }); this.chooseLine(null, true); this.lineEditorOpen.set(false);
  }
  protected chooseParticular(vesselId: string, code: string): void {
    const part = this.model()?.particulars.find(p => p.jobVesselId === vesselId && p.code === code);
    this.particularForm.reset(part ?? { jobVesselId: vesselId, code, value: null, sourceDocumentId: null, sourcePage: null, sourceText: null });
  }
  protected saveParticular(): void {
    const value = this.particularForm.getRawValue(); const part: MooringParticular = { ...value, unit: 'm' };
    this.change(m => { m.particulars = [...m.particulars.filter(p => p.jobVesselId !== part.jobVesselId || p.code !== part.code), part]; });
  }
  protected particularKey(): string { const { jobVesselId, code } = this.particularForm.getRawValue(); return `particular:${jobVesselId}:${code}`; }
  protected chooseCapacity(lineId: string, columnCode: string): void {
    const value = this.model()?.lines.find(l => l.id === lineId)?.capacities.find(c => c.columnCode === columnCode);
    this.capacityForm.reset({ lineId, columnCode, value: value?.value ?? null, sourceDocumentId: value?.sourceDocumentId ?? null,
      sourcePage: value?.sourcePage ?? null, sourceText: value?.sourceText ?? null });
  }
  protected saveCapacity(): void {
    const { lineId, columnCode, value, sourceDocumentId, sourcePage, sourceText } = this.capacityForm.getRawValue();
    const column = this.revision()?.snapshot.capacityColumns.find(c => c.code === columnCode);
    const capacity: MooringCapacity = { columnCode, value, unit: column?.unit ?? null, sourceDocumentId, sourcePage, sourceText };
    this.change(m => { m.lines = m.lines.map(l => l.id === lineId ? { ...l, capacities: [...l.capacities.filter(c => c.columnCode !== columnCode), capacity] } : l); });
  }
  protected capacityKey(): string { const { lineId, columnCode } = this.capacityForm.getRawValue(); return `capacity:${lineId}:${columnCode}`; }
  protected chooseLayer(id: string | null): void {
    this.layerId.set(id); const layer = this.model()?.layers.find(l => l.id === id);
    this.layerForm.reset(layer ?? { jobVesselId: this.model()?.vessels[0]?.jobVesselId ?? '',
      documentVersionId: '', xM: 0, yM: 0, scale: 1, visible: true });
  }
  protected saveLayer(): void {
    const value = this.layerForm.getRawValue(); if (!value.documentVersionId) return;
    const id = this.layerId() ?? crypto.randomUUID(); const layer: MooringLayer = { ...value, id };
    this.change(m => { m.layers = [...m.layers.filter(l => l.id !== id), layer]; }); this.layerId.set(id);
    this.refreshLayers(this.model()!);
  }
  protected deleteLayer(): void {
    const id = this.layerId(); if (!id) return;
    this.change(m => { m.layers = m.layers.filter(l => l.id !== id); }); this.chooseLayer(null);
  }
  protected chooseColumn(code: string): void {
    const column = this.revision()?.snapshot.capacityColumns.find(c => c.code === code);
    if (column) this.columnForm.reset({ code, definition: column.definition ?? '', unit: column.unit ?? '',
      evidenceRequirement: column.evidenceRequirement ?? '' });
  }
  protected saveColumn(): void {
    const value = this.columnForm.getRawValue(); const column = this.revision()?.snapshot.capacityColumns.find(c => c.code === value.code);
    if (!column) return;
    const version = this.jobVersion;
    const selectedId = this.selectedId();
    this.busy.set(true);
    this.api.defineColumn(column, value.definition, value.unit, value.evidenceRequirement).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { if (version !== this.jobVersion) return; this.busy.set(false); if (selectedId === this.selectedId() && selectedId) this.select(selectedId); },
      error: e => { if (version === this.jobVersion) { this.busy.set(false); this.fail(e); } },
    });
  }
  protected saveRule(): void {
    const rule = this.rule(); if (!rule) return;
    const version = this.jobVersion;
    const selectedId = this.selectedId();
    const value = this.ruleForm.getRawValue(); this.busy.set(true);
    this.api.updateRule(rule, value.toleranceM, value.active).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: updated => { if (version !== this.jobVersion) return; this.busy.set(false); this.rule.set(updated); if (selectedId === this.selectedId() && selectedId) this.select(selectedId); },
      error: e => { if (version === this.jobVersion) { this.busy.set(false); this.fail(e); } },
    });
  }
  protected verified(key: string): boolean { return this.revision()?.snapshot.verifiedValues.some(v => v.key === key) ?? false; }
  protected capacityDisplay(line: MooringLine, code: string): string {
    const value = line.capacities.find(c => c.columnCode === code);
    return value?.value === null || value?.value === undefined ? '—' : String(value.value);
  }
  protected documentFor(id: string | null): JobDocument | undefined { return this.documents().find(d => d.id === id); }
  protected vesselName(id: string): string {
    const vessel = this.revision()?.snapshot.vessels.find(v => v.jobVesselId === id);
    return vessel?.name ?? vessel?.role ?? 'Vessel';
  }
  protected sourceDocs(vesselId: string): JobDocument[] {
    return this.documents().filter(d => d.jobVesselId === null || d.jobVesselId === vesselId);
  }
  protected imageDocs(vesselId: string): JobDocument[] {
    return this.documents().filter(d => d.jobVesselId === vesselId && ['image/png', 'image/jpeg'].includes(d.contentType));
  }
  protected viewDocument(id: string | null): void {
    const doc = this.documentFor(id); if (doc) this.viewing.set({ id: doc.fileId, name: doc.fileName, contentType: doc.contentType });
  }
  private refreshLayers(model: MooringModel): void {
    const version = this.jobVersion;
    const urls: Record<string, string> = {};
    this.layerUrls.set(urls);
    model.layers.forEach(layer => {
      const doc = this.documentFor(layer.documentVersionId);
      if (!doc || !['image/png', 'image/jpeg'].includes(doc.contentType)) return;
      this.http.post<{ url: string }>(`${this.base}/files/${doc.fileId}/download-token`, null)
        .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: response => {
          if (version === this.jobVersion && /^\/api\/v1\/files\/download\/[A-Za-z0-9_-]+$/.test(response.url)) {
            urls[layer.documentVersionId] = `${response.url}/preview`; this.layerUrls.set({ ...urls });
          }
        } });
    });
  }
}
