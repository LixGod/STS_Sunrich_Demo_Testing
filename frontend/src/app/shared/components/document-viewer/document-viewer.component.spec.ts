import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { DocumentViewerComponent } from './document-viewer.component';

describe('DocumentViewerComponent', () => {
  it('can request a fresh token when a preview token expires', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideTranslateService()] });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DocumentViewerComponent);
    fixture.componentRef.setInput('file', { id: 'a', name: 'A', contentType: 'application/pdf' });
    await fixture.whenStable();
    http.expectOne('/api/v1/files/a/download-token').flush({}, { status: 404, statusText: 'Expired' });
    await fixture.whenStable();
    fixture.nativeElement.querySelector('button.refresh-preview').click();
    http.expectOne('/api/v1/files/a/download-token').flush({ url: '/api/v1/files/download/new-token' });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('iframe').getAttribute('src')).toBe('/api/v1/files/download/new-token');
  });

  it('cancels the previous token request when another file is selected', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideTranslateService()] });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DocumentViewerComponent);
    fixture.componentRef.setInput('file', { id: 'a', name: 'A', contentType: 'application/pdf' });
    await fixture.whenStable();
    const first = http.expectOne('/api/v1/files/a/download-token');

    fixture.componentRef.setInput('file', { id: 'b', name: 'B', contentType: 'application/pdf' });
    await fixture.whenStable();
    expect(first.cancelled).toBe(true);
    http.expectOne('/api/v1/files/b/download-token').flush({ url: '/api/v1/files/download/b' });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.name').textContent).toBe('B');
    expect(fixture.nativeElement.querySelector('iframe').getAttribute('src')).toBe('/api/v1/files/download/b');
  });
});
