import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { Subject } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { JobDocument, JobTechnicalService } from './job-technical.service';
import { MooringPlanPageComponent } from './mooring-plan-page.component';
import { MooringModel, MooringOverview, MooringPlanService, MooringRule } from './mooring-plan.service';

describe('MooringPlanPageComponent', () => {
  it('clears the old plan and cancels its requests when the job changes', () => {
    const firstOverview = new Subject<MooringOverview>();
    const secondOverview = new Subject<MooringOverview>();
    const firstDocuments = new Subject<JobDocument[]>();
    const secondDocuments = new Subject<JobDocument[]>();
    const oldRuleSave = new Subject<MooringRule>();
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideTranslateService(),
      { provide: AuthService, useValue: { hasPermission: () => false } },
      { provide: MooringPlanService, useValue: { overview: (id: string) => id === 'first' ? firstOverview : secondOverview,
        updateRule: () => oldRuleSave } },
      { provide: JobTechnicalService, useValue: { documents: (id: string) => id === 'first' ? firstDocuments : secondDocuments } },
    ] });
    TestBed.overrideComponent(MooringPlanPageComponent, { set: { template: '' } });
    const fixture = TestBed.createComponent(MooringPlanPageComponent);
    fixture.componentRef.setInput('jobId', 'first');
    fixture.detectChanges();
    const comp = fixture.componentInstance;
    comp['model'].set({ schemaVersion: 1, vessels: [], layers: [], objects: [], lines: [], particulars: [], rulerStepM: null } as MooringModel);
    comp['dirty'].set(true);
    comp['selectedId'].set('old');
    comp['rule'].set({ toleranceM: 1, active: true, version: 1 });
    comp['saveRule']();

    fixture.componentRef.setInput('jobId', 'second');
    fixture.detectChanges();
    expect(firstOverview.observed).toBe(false);
    expect(firstDocuments.observed).toBe(false);
    expect(secondOverview.observed).toBe(true);
    expect(secondDocuments.observed).toBe(true);
    expect(comp['model']()).toBeNull();
    expect(comp['selectedId']()).toBeNull();
    expect(comp['dirty']()).toBe(false);
    oldRuleSave.next({ toleranceM: 2, active: false, version: 2 });
    expect(comp['selectedId']()).toBeNull();
    expect(comp['busy']()).toBe(false);
    expect(comp['rule']()?.version).toBe(1);
  });
});
