import { DeferBlockBehavior } from '@angular/core/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SwUpdate, VersionEvent } from '@angular/service-worker';
import { provideTranslateService } from '@ngx-translate/core';
import { Subject } from 'rxjs';
import { AppComponent } from './app.component';

describe('AppComponent PWA status', () => {
  const updates = new Subject<VersionEvent>();

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([]), provideTranslateService(),
        { provide: SwUpdate, useValue: { isEnabled: true, versionUpdates: updates.asObservable() } }],
      deferBlockBehavior: DeferBlockBehavior.Manual,
    });
    await TestBed.compileComponents();
  });

  it('shows an offline status and clears it when connectivity returns', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    window.dispatchEvent(new Event('offline'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('pwa.offline');
    window.dispatchEvent(new Event('online'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('pwa.offline');
  });

  it('offers a manual refresh only after a version is ready', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button.pwa-refresh')).toBeNull();
    updates.next({ type: 'VERSION_READY', currentVersion: { hash: 'old' }, latestVersion: { hash: 'new' } });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button.pwa-refresh')).not.toBeNull();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    fixture.nativeElement.querySelector('button.pwa-refresh').click();
    expect(confirm).toHaveBeenCalledOnce();
    confirm.mockRestore();
  });
});
