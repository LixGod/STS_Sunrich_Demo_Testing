import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { Subject, of } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { LanguageService } from '../../core/i18n/language.service';
import { JobTechnicalService } from './job-technical.service';
import { JpoPageComponent } from './jpo-page.component';
import { JpoRevision, JpoService } from './jpo.service';

describe('JpoPageComponent', () => {
  it('guards dirty revision selection and cancels the previous revision load', () => {
    const next = new Subject<JpoRevision>();
    const last = new Subject<JpoRevision>();
    const oldSave = new Subject<JpoRevision>();
    const submittedJobs: string[] = [];
    TestBed.configureTestingModule({ providers: [
      provideTranslateService(),
      { provide: AuthService, useValue: { hasPermission: () => false } },
      { provide: LanguageService, useValue: { current: () => 'en' } },
      { provide: JobTechnicalService, useValue: { documents: () => of([]) } },
      { provide: JpoService, useValue: {
        overview: () => of({ jobId: 'job-1', jobNumber: 'JOB-1', revisions: [], canCreate: false }),
        revision: (_jobId: string, id: string) => id === 'next' ? next : last,
        save: () => oldSave,
        submit: (jobId: string, revision: JpoRevision) => { submittedJobs.push(jobId); return of(revision); },
      } },
    ] });
    TestBed.overrideComponent(JpoPageComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(JpoPageComponent);
    fixture.componentRef.setInput('jobId', 'job-1');
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp['selectedId'].set('old');
    comp['revision'].set({ id: 'old' } as JpoRevision);
    comp['form'].markAsDirty();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValue(true);

    comp['select']('next');
    expect(comp['selectedId']()).toBe('old');
    expect(next.observed).toBe(false);
    comp['select']('next');
    expect(next.observed).toBe(true);
    expect(comp['revision']()).toBeNull();
    comp['select']('last');
    expect(next.observed).toBe(false);
    expect(last.observed).toBe(true);

    comp['revision'].set({ id: 'old', content: {} } as JpoRevision);
    comp['form'].markAsDirty();
    comp['changes'].update(n => n + 1);
    comp['submit']();
    fixture.componentRef.setInput('jobId', 'job-2');
    fixture.detectChanges();
    oldSave.next({ id: 'old', content: {} } as JpoRevision);
    expect(submittedJobs).toEqual(['job-1']);
    expect(comp['revision']()).toBeNull();
    confirm.mockRestore();
  });
});
