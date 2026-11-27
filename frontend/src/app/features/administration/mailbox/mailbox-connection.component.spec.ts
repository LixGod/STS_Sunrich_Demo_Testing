import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MailboxConnectionComponent } from './mailbox-connection.component';

describe('MailboxConnectionComponent', () => {
  it('keeps the saved password hidden and tests before connecting', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(MailboxConnectionComponent);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/technical/mailbox-connection').flush({
      host: 'mail.example.com', port: 993, username: 'captain@example.com', folder: 'INBOX',
      enabled: false, passwordSet: true, serverReady: true,
    });
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('input[name="password"]') as HTMLInputElement).value).toBe('');
    fixture.componentInstance['test']();
    const tested = http.expectOne('/api/v1/technical/mailbox-connection/test');
    expect(tested.request.method).toBe('POST');
    expect(tested.request.body.password).toBeNull();
    tested.flush({ connected: true });
    fixture.componentInstance['connect']();
    const saved = http.expectOne('/api/v1/technical/mailbox-connection');
    expect(saved.request.method).toBe('PUT');
    saved.flush({ host: 'mail.example.com', port: 993, username: 'captain@example.com', folder: 'INBOX',
      enabled: true, passwordSet: true, serverReady: true });
    expect(fixture.componentInstance['status']()?.enabled).toBe(true);
    http.verify();
  });
});
