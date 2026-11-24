import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterOutlet } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Toast } from 'primeng/toast';
import { fromEvent } from 'rxjs';

@Component({
  selector: 's2s-root',
  imports: [RouterOutlet, Toast, TranslatePipe],
  templateUrl: './app.component.html',
  styles: '.pwa-status{padding:.45rem 1rem;background:#fff4da;color:#352b1f;text-align:center}.pwa-refresh{margin-left:.65rem;border:0;background:none;color:inherit;font:inherit;font-weight:700;text-decoration:underline;cursor:pointer}',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  private readonly updates = inject(SwUpdate);
  private readonly translate = inject(TranslateService);
  protected readonly online = signal(navigator.onLine);
  protected readonly updateReady = signal(false);

  constructor() {
    fromEvent(window, 'online').pipe(takeUntilDestroyed()).subscribe(() => this.online.set(true));
    fromEvent(window, 'offline').pipe(takeUntilDestroyed()).subscribe(() => this.online.set(false));
    if (this.updates.isEnabled) {
      this.updates.versionUpdates.pipe(takeUntilDestroyed()).subscribe((event) => {
        if (event.type === 'VERSION_READY') this.updateReady.set(true);
      });
      // shortcut: the initial shared chunks are lazy SW assets; warm loaded files for the first offline reload only.
      const warmShell = () => {
        const warm = () => {
          const loaded = performance.getEntriesByType('resource').map((entry) => new URL(entry.name))
            .filter((url) => url.origin === location.origin && !url.pathname.startsWith('/api/') && /\.(js|css)$/.test(url.pathname));
          for (const url of loaded) void fetch(url.href).catch(() => undefined);
        };
        if (document.readyState === 'complete') warm();
        else window.addEventListener('load', warm, { once: true });
      };
      if (navigator.serviceWorker?.controller) warmShell();
      else navigator.serviceWorker?.addEventListener('controllerchange', warmShell, { once: true });
    }
  }

  protected refresh(): void {
    if (window.confirm(this.translate.instant('pwa.confirmRefresh'))) window.location.reload();
  }
}
