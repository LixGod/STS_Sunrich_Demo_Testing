import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { AuthService } from '../../../core/auth/auth.service';
import { NAV_GROUPS } from '../nav.config';
import { SideNavComponent } from './side-nav.component';

describe('SideNavComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideTranslateService()],
    });
  });

  function signIn(permissions: string[], roleCodes: string[] = [], directPermissions: string[] = [], globalPermissions: string[] = []) {
    TestBed.inject(AuthService).login('u', 'p').subscribe();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/login').flush({
      id: 'u', fullName: 'User', workEmail: 'u@example.com', language: 'en', permissions,
      grants: roleCodes.map((roleCode) => ({ roleCode, branchId: null })),
      directPermissions, globalPermissions,
    });
  }

  it('highlights only the current operations destination', async () => {
    signIn(['OPS.LIVE.READ', 'MASTER.RESOURCE.READ', 'MASTER.OPERATIONS.WRITE', 'JOB.READ'], [], [], ['MASTER.RESOURCE.READ']);
    const router = TestBed.inject(Router);
    router.resetConfig([{ path: '**', component: SideNavComponent }]);
    const fixture = TestBed.createComponent(SideNavComponent);
    fixture.detectChanges();

    for (const [url, activeRoute] of [
      ['/operations/inventory', '/operations/inventory'],
      ['/operations/lists', '/operations/lists'],
      ['/operations', '/operations'],
      ['/operations?search=vessel', '/operations'],
      ['/jobs/123', '/jobs'],
    ]) {
      await router.navigateByUrl(url);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      const activeRoutes = [...fixture.nativeElement.querySelectorAll('a.link.active')]
        .map((link: HTMLAnchorElement) => link.getAttribute('href'));
      expect(activeRoutes).toEqual([activeRoute]);
    }
    TestBed.inject(HttpTestingController).verify();
  });

  it('keeps admin destinations unique and groups setup links under collapsed sections', () => {
    signIn([
      'ADMIN.USER.READ', 'ADMIN.ROLE.READ', 'AUDIT.READ', 'MASTER.ORG.READ', 'MASTER.COMMERCIAL.READ',
      'MASTER.FINANCE.READ', 'MASTER.TECHNICAL.READ', 'MASTER.RESOURCE.READ',
    ], ['STS_ADMIN']);
    const fixture = TestBed.createComponent(SideNavComponent);
    fixture.detectChanges();

    const links = [...fixture.nativeElement.querySelectorAll('a.link')] as HTMLAnchorElement[];
    const routes = links.map((link) => link.getAttribute('href'));
    expect(routes).toContain('/administration/users');
    expect(routes).toContain('/administration/roles');
    expect(routes).not.toContain('/inbox');
    expect(new Set(routes).size).toBe(routes.length);
    expect(fixture.nativeElement.querySelectorAll('details.subgroup').length).toBe(4);
    expect([...fixture.nativeElement.querySelectorAll('details.subgroup')].every((section: HTMLDetailsElement) => !section.open)).toBe(true);
    TestBed.inject(HttpTestingController).verify();
  });

  it('shows only master sections permitted for the signed-in role', () => {
    signIn(['MASTER.ORG.READ', 'MASTER.FINANCE.READ'], ['STS_ADMIN']);
    const fixture = TestBed.createComponent(SideNavComponent);
    fixture.detectChanges();

    const routes = [...fixture.nativeElement.querySelectorAll('a.link')].map((link: HTMLAnchorElement) => link.getAttribute('href'));
    const sections = [...fixture.nativeElement.querySelectorAll('details.subgroup summary')].map((summary: HTMLElement) => summary.textContent);
    expect(routes).toContain('/administration/branches');
    expect(routes).toContain('/administration/tariffs');
    expect(routes).not.toContain('/administration/users');
    expect(sections).toEqual(['nav.organizationMasters', 'nav.financeMasters']);
    TestBed.inject(HttpTestingController).verify();
  });

  it('places setup pages under STS Admin and user management under Super Admin', () => {
    signIn(['ADMIN.USER.READ', 'MASTER.ORG.READ', 'MASTER.FINANCE.READ'], ['SYSTEM_ADMIN']);
    const fixture = TestBed.createComponent(SideNavComponent);
    fixture.detectChanges();
    const groups = [...fixture.nativeElement.querySelectorAll('details.group')] as HTMLDetailsElement[];
    const stsAdmin = groups.find(group => group.querySelector('.group-title')?.textContent?.includes('nav.stsAdmin'));
    const superAdmin = groups.find(group => group.querySelector('.group-title')?.textContent?.includes('nav.superAdmin'));
    const routes = (group?: HTMLDetailsElement) => [...(group?.querySelectorAll('a.link') ?? [])]
      .map(link => link.getAttribute('href'));
    expect(routes(stsAdmin)).toContain('/administration/branches');
    expect(routes(stsAdmin)).toContain('/administration/tariffs');
    expect(routes(stsAdmin)).not.toContain('/administration/users');
    expect(routes(superAdmin)).toContain('/administration/users');
    expect(routes(superAdmin)).not.toContain('/administration/branches');
  });

  it('shows separate Super Admin and STS Admin groups according to access', () => {
    signIn(['ADMIN.USER.READ', 'ADMIN.USER.WRITE', 'ADMIN.ROLE.READ', 'AUDIT.READ']);
    const superAdmin = TestBed.createComponent(SideNavComponent);
    superAdmin.detectChanges();
    let routes = [...superAdmin.nativeElement.querySelectorAll('a.link')].map((link: HTMLAnchorElement) => link.getAttribute('href'));
    expect(routes).toContain('/administration/users');
    expect(routes).toContain('/administration/roles');
    expect(routes).toContain('/administration/audit');
    expect(routes).not.toContain('/administration/branches');
    expect([...superAdmin.nativeElement.querySelectorAll('.group-title')].map((item: HTMLElement) => item.textContent?.trim())).toContain('nav.superAdmin');
    expect([...superAdmin.nativeElement.querySelectorAll('.group-title')].map((item: HTMLElement) => item.textContent?.trim())).not.toContain('nav.stsAdmin');
    superAdmin.destroy();
    TestBed.inject(HttpTestingController).verify();

    signIn([
      'MASTER.ORG.READ', 'MASTER.ORG.WRITE', 'MASTER.COMMERCIAL.READ', 'MASTER.COMMERCIAL.WRITE',
      'MASTER.FINANCE.READ', 'MASTER.FINANCE.WRITE', 'MASTER.TECHNICAL.READ', 'MASTER.TECHNICAL.WRITE',
      'MASTER.RESOURCE.READ', 'MASTER.RESOURCE.WRITE', 'MASTER.OPERATIONS.WRITE',
    ], ['STS_ADMIN']);
    const stsAdmin = TestBed.createComponent(SideNavComponent);
    stsAdmin.detectChanges();
    routes = [...stsAdmin.nativeElement.querySelectorAll('a.link')].map((link: HTMLAnchorElement) => link.getAttribute('href'));
    expect(routes).toContain('/administration/branches');
    expect(routes).toContain('/administration/equipment');
    expect(routes).not.toContain('/commercial/masters');
    expect(routes).not.toContain('/administration/users');
    expect(routes).not.toContain('/administration/audit');
    expect([...stsAdmin.nativeElement.querySelectorAll('.group-title')].map((item: HTMLElement) => item.textContent?.trim())).toContain('nav.stsAdmin');
    expect([...stsAdmin.nativeElement.querySelectorAll('.group-title')].map((item: HTMLElement) => item.textContent?.trim())).not.toContain('nav.superAdmin');
    stsAdmin.destroy();
    TestBed.inject(HttpTestingController).verify();
  });

  it('does not show STS Admin setup pages to a Commercial user who only has lookup access', () => {
    signIn(['MASTER.COMMERCIAL.READ'], ['COMMERCIAL']);
    const fixture = TestBed.createComponent(SideNavComponent);
    fixture.detectChanges();

    const routes = [...fixture.nativeElement.querySelectorAll('a.link')].map((link: HTMLAnchorElement) => link.getAttribute('href'));
    const headings = [...fixture.nativeElement.querySelectorAll('.group-title')].map((item: HTMLElement) => item.textContent?.trim());
    expect(headings).not.toContain('nav.stsAdmin');
    expect(routes).not.toContain('/administration/cargo-grades');
    TestBed.inject(HttpTestingController).verify();
  });

  it('shows only the setup module directly granted by Super Admin', () => {
    signIn(['MASTER.COMMERCIAL.READ', 'MASTER.ORG.READ'], ['COMMERCIAL'], ['MASTER.ORG.READ']);
    const fixture = TestBed.createComponent(SideNavComponent);
    fixture.detectChanges();
    const routes = [...fixture.nativeElement.querySelectorAll('a.link')].map((link: HTMLAnchorElement) => link.getAttribute('href'));
    expect(routes).toContain('/administration/branches');
    expect(routes).not.toContain('/administration/cargo-grades');
  });

  it('shows setup to a custom role with global write access', () => {
    signIn(['MASTER.COMMERCIAL.READ', 'MASTER.COMMERCIAL.WRITE'], ['CUSTOM_ADMIN'], [],
      ['MASTER.COMMERCIAL.READ', 'MASTER.COMMERCIAL.WRITE']);
    const fixture = TestBed.createComponent(SideNavComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href="/administration/units"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/administration/users"]')).toBeNull();
  });

  it('keeps all configured destinations unique across groups and sections', () => {
    const routes = NAV_GROUPS.flatMap((group) => [
      ...group.items.map((item) => item.route),
      ...(group.sections ?? []).flatMap((section) => section.items.map((item) => item.route)),
    ]);
    expect(new Set(routes).size).toBe(routes.length);
  });

  it('shows vessel particulars only with technical read access', () => {
    signIn(['TECH.VESSEL.READ']);
    const fixture = TestBed.createComponent(SideNavComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href="/technical/vessels"]')).not.toBeNull();
    fixture.destroy();

    signIn([]);
    const restricted = TestBed.createComponent(SideNavComponent);
    restricted.detectChanges();
    expect(restricted.nativeElement.querySelector('a[href="/technical/vessels"]')).toBeNull();
    TestBed.inject(HttpTestingController).verify();
  });

  it('hides Inventory when resource read is limited to a branch', () => {
    signIn(['MASTER.RESOURCE.READ'], ['OPERATIONS']);
    const fixture = TestBed.createComponent(SideNavComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href="/operations/inventory"]')).toBeNull();
  });
});
