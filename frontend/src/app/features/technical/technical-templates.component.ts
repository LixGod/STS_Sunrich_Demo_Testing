import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LookupService, LookupOption } from '../../core/lookups/lookup.service';
import { forkJoin } from 'rxjs';
import { ChecklistTemplate, JobTechnicalService, RequestTemplate } from './job-technical.service';

type RequestDraft = Pick<RequestTemplate, 'name' | 'emailSubject' | 'emailBody' | 'items'>;
type ChecklistDraft = Pick<ChecklistTemplate, 'code' | 'name' | 'appliesTo' | 'items'>;

@Component({
  selector: 's2s-technical-templates',
  imports: [FormsModule, RouterLink],
  templateUrl: './technical-templates.component.html',
  styleUrl: './technical-templates.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TechnicalTemplatesComponent {
  private readonly api = inject(JobTechnicalService);
  private readonly lookups = inject(LookupService);
  protected readonly requests = signal<RequestTemplate[]>([]);
  protected readonly checklists = signal<ChecklistTemplate[]>([]);
  protected readonly documentTypes = signal<LookupOption[]>([]);
  protected readonly currentRequest = signal<RequestTemplate | null>(null);
  protected readonly currentChecklist = signal<ChecklistTemplate | null>(null);
  protected readonly requestDraft = signal<RequestDraft>({ name: '', emailSubject: '', emailBody: '', items: [] });
  protected readonly checklistDraft = signal<ChecklistDraft>({ code: '', name: '', appliesTo: 'EACH_VESSEL', items: [] });
  protected readonly ownerConfirmation = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);

  constructor() { this.reload(); }

  protected reload(): void {
    this.api.requestTemplates().subscribe({ next: x => { this.requests.set(x); if (!this.currentRequest() && x.length) this.selectRequest(x[0]); },
      error: () => this.error.set('Unable to load request templates.') });
    this.api.templates().subscribe({ next: x => { this.checklists.set(x); if (!this.currentChecklist() && x.length) this.selectChecklist(x[0]); },
      error: () => this.error.set('Unable to load checklist templates.') });
    forkJoin([this.lookups.documentTypes('VESSEL'), this.lookups.documentTypes('JOB')])
      .subscribe({ next: ([vessel, job]) => this.documentTypes.set([...vessel, ...job]) });
  }

  protected selectRequest(template: RequestTemplate): void {
    this.currentRequest.set(template);
    this.requestDraft.set({ name: template.name, emailSubject: template.emailSubject,
      emailBody: template.emailBody, items: template.items.map(x => ({ ...x })) });
  }
  protected selectRequestId(id: string): void {
    const selected = this.requests().find(x => x.id === id);
    if (selected) this.selectRequest(selected); else this.newRequest();
  }
  protected newRequest(): void {
    this.currentRequest.set(null);
    this.requestDraft.set({ name: '', emailSubject: 'Document request for {JOB_NUMBER}',
      emailBody: '{REQUEST_ITEMS}', items: [] });
  }
  protected setRequestHeader(key: 'name' | 'emailSubject' | 'emailBody', value: string): void {
    this.requestDraft.update(x => ({ ...x, [key]: value }));
  }
  protected setRequestItem(index: number, key: keyof RequestTemplate['items'][number], value: string | boolean | null): void {
    this.requestDraft.update(x => ({ ...x, items: x.items.map((item, i) => i === index ? { ...item, [key]: value } : item) }));
  }
  protected addRequestItem(): void {
    this.requestDraft.update(x => ({ ...x, items: [...x.items,
      { code: '', label: '', kind: 'DOCUMENT', appliesTo: 'EACH_VESSEL', documentTypeId: null, required: true }] }));
  }
  protected removeRequestItem(index: number): void {
    this.requestDraft.update(x => ({ ...x, items: x.items.filter((_, i) => i !== index) }));
  }
  protected saveRequest(): void {
    this.busy.set(true); this.error.set(null);
    this.api.saveRequestTemplate(this.requestDraft(), this.currentRequest() ?? undefined).subscribe({
      next: x => { this.busy.set(false); this.notice.set('Request template saved.'); this.currentRequest.set(x); this.reload(); },
      error: () => { this.busy.set(false); this.error.set('Could not save the request template. Check codes and fields.'); },
    });
  }
  protected publishRequest(): void {
    const current = this.currentRequest(); if (!current) return;
    this.busy.set(true); this.error.set(null);
    this.api.publishRequestTemplate(current, this.ownerConfirmation()).subscribe({
      next: x => { this.busy.set(false); this.notice.set('Request template published.'); this.currentRequest.set(x); this.reload(); },
      error: () => { this.busy.set(false); this.error.set('Cannot publish until the count and checklists are confirmed.'); },
    });
  }

  protected selectChecklist(template: ChecklistTemplate): void {
    this.currentChecklist.set(template);
    this.checklistDraft.set({ code: template.code, name: template.name,
      appliesTo: template.appliesTo, items: template.items.map(x => ({ ...x })) });
  }
  protected selectChecklistId(id: string): void {
    const selected = this.checklists().find(x => x.id === id);
    if (selected) this.selectChecklist(selected); else this.newChecklist();
  }
  protected newChecklist(): void {
    this.currentChecklist.set(null);
    this.checklistDraft.set({ code: '', name: '', appliesTo: 'EACH_VESSEL', items: [] });
  }
  protected setChecklistHeader(key: 'code' | 'name' | 'appliesTo', value: string): void {
    this.checklistDraft.update(x => ({ ...x, [key]: value }));
  }
  protected setChecklistItem(index: number, key: keyof ChecklistTemplate['items'][number], value: string | boolean): void {
    this.checklistDraft.update(x => ({ ...x, items: x.items.map((item, i) => i === index ? { ...item, [key]: value } : item) }));
  }
  protected addChecklistItem(): void {
    this.checklistDraft.update(x => ({ ...x, items: [...x.items, { code: '', label: '', answerType: 'YES_NO', required: true }] }));
  }
  protected removeChecklistItem(index: number): void {
    this.checklistDraft.update(x => ({ ...x, items: x.items.filter((_, i) => i !== index) }));
  }
  protected saveChecklist(): void {
    this.busy.set(true); this.error.set(null);
    this.api.saveChecklistTemplate(this.checklistDraft(), this.currentChecklist() ?? undefined).subscribe({
      next: x => { this.busy.set(false); this.notice.set('Checklist template saved.'); this.currentChecklist.set(x); this.reload(); },
      error: () => { this.busy.set(false); this.error.set('Could not save the checklist template.'); },
    });
  }
  protected publishChecklist(): void {
    const current = this.currentChecklist(); if (!current) return;
    this.busy.set(true); this.error.set(null);
    this.api.publishChecklistTemplate(current).subscribe({
      next: x => { this.busy.set(false); this.notice.set('Checklist template published.'); this.currentChecklist.set(x); this.reload(); },
      error: () => { this.busy.set(false); this.error.set('Add the owner-approved checklist questions before publishing.'); },
    });
  }
}
