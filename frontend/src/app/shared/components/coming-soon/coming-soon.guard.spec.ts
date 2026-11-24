import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree, convertToParamMap, provideRouter } from '@angular/router';
import { comingSoonGuard } from './coming-soon.guard';

function run(group: string): boolean | UrlTree {
  const route = { paramMap: convertToParamMap({ group }) } as ActivatedRouteSnapshot;
  return TestBed.runInInjectionContext(() => comingSoonGuard(route, {} as RouterStateSnapshot)) as boolean | UrlTree;
}

describe('comingSoonGuard', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));

  it('sends an old link to a delivered group to its first screen', () => {
    expect((run('commercial') as UrlTree).toString()).toBe('/commercial/enquiries');
    expect((run('technical') as UrlTree).toString()).toBe('/technical/vessels');
    expect((run('operations') as UrlTree).toString()).toBe('/operations');
    expect((run('finance') as UrlTree).toString()).toBe('/finance/invoices');
    expect((run('reports') as UrlTree).toString()).toBe('/reports/documents');
  });

  it('sends an unknown group to the dashboard', () => {
    expect((run('nonsense') as UrlTree).toString()).toBe('/dashboard');
  });
});
