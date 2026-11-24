import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../../core/auth/auth.service';
import { CanDirective } from './can.directive';

@Component({
  imports: [CanDirective],
  template: `<button *s2sCan="'MASTER.ORG.WRITE'">Add</button><span *s2sCan="['X', 'AUDIT.READ']">Audit</span>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class HostComponent {}

describe('CanDirective', () => {
  it('renders content only for held permissions and reacts to sign-in/sign-out', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toBe('');

    TestBed.inject(AuthService).login('u', 'p').subscribe();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/auth/login')
      .flush({ id: '1', fullName: 'U', workEmail: 'u@x', language: 'en', permissions: ['AUDIT.READ'], grants: [] });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toBe('Audit');

    TestBed.inject(AuthService).clearSession();
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toBe('');
  });
});
