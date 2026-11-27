import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/api/api.models';

interface MailboxStatus {
  host: string; port: number; username: string; folder: string;
  enabled: boolean; passwordSet: boolean; serverReady: boolean;
}

@Component({
  selector: 's2s-mailbox-connection',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mailbox-panel">
      <h1>Mailbox connection</h1>
      <p>Connect the shared IMAP inbox for Step 4 documents. Every attachment will wait for Operations to confirm its job, vessel, and document item.</p>
      @if (!status()?.serverReady) {
        <p class="feedback error" role="alert">Mailbox encryption is not configured on the server. Contact the STS administrator before saving a mailbox.</p>
      }
      @if (status()?.enabled) { <p class="feedback" role="status">Connected to {{ status()?.username }}. New attachments are checked about every two minutes.</p> }
      @if (notice()) { <p class="feedback" role="status">{{ notice() }}</p> }
      @if (error()) { <p class="feedback error" role="alert">{{ error() }}</p> }
      <form (ngSubmit)="connect()">
        <label>IMAP server
          <input type="text" name="host" required autocomplete="off" placeholder="mail.example.com"
            [ngModel]="host()" (ngModelChange)="host.set($event)" [disabled]="busy()" />
        </label>
        <label>Port
          <input type="number" name="port" required min="1" max="65535"
            [ngModel]="port()" (ngModelChange)="port.set($event)" [disabled]="busy()" />
        </label>
        <label>Mailbox address or login
          <input type="text" name="username" required autocomplete="username" placeholder="captain@example.com"
            [ngModel]="username()" (ngModelChange)="username.set($event)" [disabled]="busy()" />
        </label>
        <label>Password or app password
          <input type="password" name="password" autocomplete="new-password"
            [placeholder]="status()?.passwordSet ? 'Leave blank to keep saved password' : 'Enter mailbox password'"
            [ngModel]="password()" (ngModelChange)="password.set($event)" [disabled]="busy()" />
        </label>
        <label>Folder <input type="text" value="INBOX" disabled /></label>
        <p class="hint">STS reads INBOX over encrypted IMAP. It does not mark emails as read or send mail. The password is encrypted on the server and never displayed again.</p>
        <div class="actions">
          <button type="button" (click)="test()" [disabled]="busy() || !valid()">Test connection</button>
          <button type="submit" [disabled]="busy() || !valid() || !status()?.serverReady">Connect mailbox</button>
          @if (status()?.enabled) { <button type="button" (click)="disconnect()" [disabled]="busy()">Disconnect</button> }
        </div>
      </form>
    </section>
  `,
  styles: [`
    :host{display:block;max-width:48rem;margin:0 auto}.mailbox-panel{padding:1.5rem;border:1px solid var(--s2s-border);border-radius:.6rem;background:var(--s2s-surface)}
    h1{margin-top:0}label{display:flex;flex-direction:column;gap:.3rem;margin:1rem 0;font-weight:600}input{max-width:26rem;padding:.55rem;border:1px solid var(--s2s-border);border-radius:.3rem}
    .actions{display:flex;flex-wrap:wrap;gap:.6rem;margin-top:1.2rem}.hint{color:var(--s2s-text-muted);font-size:.9rem}.error{color:var(--s2s-danger,#a32929)}
  `],
})
export class MailboxConnectionComponent {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(API_BASE_URL)}/technical/mailbox-connection`;
  private readonly destroyRef = inject(DestroyRef);
  protected readonly status = signal<MailboxStatus | null>(null);
  protected readonly host = signal('');
  protected readonly port = signal(993);
  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');

  constructor() {
    this.http.get<MailboxStatus>(this.base).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => this.apply(value), error: () => this.error.set('Could not load mailbox settings.'),
    });
  }

  protected valid(): boolean {
    return !!this.host().trim() && this.port() >= 1 && this.port() <= 65535 && !!this.username().trim()
      && (!!this.password() || !!this.status()?.passwordSet);
  }

  protected test(): void { this.send('test'); }
  protected connect(): void { this.send('connect'); }

  protected disconnect(): void {
    this.busy.set(true); this.error.set(''); this.notice.set('');
    this.http.post<MailboxStatus>(`${this.base}/disconnect`, {}).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => { this.apply(value); this.busy.set(false); this.notice.set('Mailbox disconnected. Manual upload remains available.'); },
      error: error => this.fail(error),
    });
  }

  private send(action: 'test' | 'connect'): void {
    if (this.busy() || !this.valid()) return;
    this.busy.set(true); this.error.set(''); this.notice.set('');
    const body = { host: this.host().trim(), port: Number(this.port()), username: this.username().trim(),
      password: this.password() || null, folder: 'INBOX' };
    const request: Observable<MailboxStatus | { connected: boolean }> = action === 'test'
      ? this.http.post<{ connected: boolean }>(`${this.base}/test`, body)
      : this.http.put<MailboxStatus>(this.base, body);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => {
        this.busy.set(false);
        if (action === 'connect') { this.apply(value as MailboxStatus); this.password.set(''); }
        this.notice.set(action === 'connect' ? 'Mailbox connected. Incoming attachments will appear in Step 4.' : 'IMAP connection successful.');
      },
      error: error => this.fail(error),
    });
  }

  private apply(value: MailboxStatus): void {
    this.status.set(value); this.host.set(value.host); this.port.set(value.port); this.username.set(value.username);
  }

  private fail(error: HttpErrorResponse): void {
    this.busy.set(false);
    this.error.set(typeof error.error?.detail === 'string' ? error.error.detail : 'Mailbox connection failed. Check the details and try again.');
  }
}
