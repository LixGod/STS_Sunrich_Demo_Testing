import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { TranslatePipe } from '@ngx-translate/core';
import { API_BASE_URL } from '../../../core/api/api.models';
import { Subscription } from 'rxjs';

/** The file to show: id plus enough metadata to pick a viewer. */
export interface ViewableFile {
  id: string;
  name: string;
  contentType: string;
}

const DOWNLOAD_PATH = /^\/api\/v1\/files\/download\/[A-Za-z0-9_-]+$/;

/**
 * Shows a stored PDF or image with the browser's own viewer (no PDF library). It fetches a short-lived download token
 * first; the file is served by Spring with framing allowed for this site only.
 */
@Component({
  selector: 's2s-document-viewer',
  imports: [TranslatePipe],
  template: `
    @if (file(); as current) {
      <div class="viewer">
        <div class="bar">
          <span class="name" [title]="current.name">{{ current.name }}</span>
          <button type="button" class="refresh-preview" (click)="refresh()">{{ 'documents.refreshPreview' | translate }}</button>
          @if (rawUrl(); as url) { <a [href]="url" target="_blank" rel="noopener">{{ 'documents.openInTab' | translate }}</a> }
        </div>
        @if (error()) {
          <p class="s2s-muted message">{{ 'documents.viewFailed' | translate }}</p>
        } @else if (url(); as safe) {
          @if (current.contentType === 'application/pdf') {
            <iframe [src]="safe" [title]="current.name"></iframe>
          } @else {
            <img [src]="rawUrl()" [alt]="current.name" />
          }
        } @else {
          <p class="s2s-muted message">{{ 'common.loading' | translate }}</p>
        }
      </div>
    } @else {
      <p class="s2s-muted empty">{{ 'documents.nothingSelected' | translate }}</p>
    }
  `,
  styles: `
    :host { display: block; }
    .viewer { display: flex; flex-direction: column; height: 100%; min-height: 24rem; border: 1px solid var(--s2s-border); border-radius: var(--s2s-radius); overflow: hidden; }
    .bar { display: flex; justify-content: space-between; gap: 0.75rem; padding: 0.5rem 0.75rem; border-bottom: 1px solid var(--s2s-border); font-size: 0.875rem; }
    .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
    .refresh-preview { margin-left: auto; border: 0; background: none; color: var(--s2s-brand); cursor: pointer; text-decoration: underline; }
    iframe { flex: 1; width: 100%; min-height: 24rem; border: 0; }
    img { max-width: 100%; height: auto; display: block; margin: 0 auto; }
    .message, .empty { padding: 1rem; margin: 0; }
    .empty { border: 1px dashed var(--s2s-border); border-radius: var(--s2s-radius); text-align: center; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocumentViewerComponent {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly sanitizer = inject(DomSanitizer);

  readonly file = input<ViewableFile | null>(null);

  protected readonly url = signal<SafeResourceUrl | null>(null);
  protected readonly rawUrl = signal<string | null>(null);
  protected readonly error = signal(false);
  private request?: Subscription;

  constructor() {
    effect((onCleanup) => {
      const file = this.file();
      untracked(() => this.load(file));
      onCleanup(() => this.request?.unsubscribe());
    });
  }

  protected refresh(): void { this.load(this.file()); }

  private load(file: ViewableFile | null): void {
    this.request?.unsubscribe();
    this.url.set(null);
    this.rawUrl.set(null);
    this.error.set(false);
    if (!file) return;
    this.request = this.http.post<{ url: string }>(`${this.base}/files/${file.id}/download-token`, null)
      .subscribe({
        next: ({ url }) => {
          // Only the server's own download path is ever framed.
          if (!DOWNLOAD_PATH.test(url)) {
            this.error.set(true);
            return;
          }
          this.rawUrl.set(url);
          this.url.set(this.sanitizer.bypassSecurityTrustResourceUrl(url));
        },
        error: () => this.error.set(true),
      });
  }
}
