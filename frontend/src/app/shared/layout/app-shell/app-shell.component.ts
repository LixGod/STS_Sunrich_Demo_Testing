import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { ConfirmDialog } from 'primeng/confirmdialog';
import { BreadcrumbComponent } from '../breadcrumb/breadcrumb.component';
import { SideNavComponent } from '../side-nav/side-nav.component';
import { TopBarComponent } from '../top-bar/top-bar.component';

/** Signed-in layout: top bar, permission-filtered side navigation (a drawer on small screens), breadcrumb, page. */
@Component({
  selector: 's2s-app-shell',
  imports: [RouterOutlet, TranslatePipe, TopBarComponent, SideNavComponent, BreadcrumbComponent, ConfirmDialog],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppShellComponent {
  protected readonly navOpen = signal(false);

  protected toggleNav(): void {
    this.navOpen.update((open) => !open);
  }

  protected closeNav(): void {
    this.navOpen.set(false);
  }
}
