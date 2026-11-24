import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRouteSnapshot, NavigationEnd, Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { filter, map } from 'rxjs';

export interface Crumb {
  labelKey: string;
  url: string;
}

/** Builds the trail from `data.breadcrumb` translation keys declared on the active routes. */
@Component({
  selector: 's2s-breadcrumb',
  imports: [RouterLink, TranslatePipe],
  templateUrl: './breadcrumb.component.html',
  styleUrl: './breadcrumb.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BreadcrumbComponent {
  private readonly router = inject(Router);

  protected readonly crumbs = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => buildCrumbs(this.router.routerState.snapshot.root)),
    ),
    { initialValue: buildCrumbs(this.router.routerState.snapshot.root) },
  );
}

export function buildCrumbs(root: ActivatedRouteSnapshot): Crumb[] {
  const crumbs: Crumb[] = [];
  const segments: string[] = [];
  let route: ActivatedRouteSnapshot | null = root;
  while (route) {
    segments.push(...route.url.map((s) => s.path));
    const key = route.routeConfig?.data?.['breadcrumb'] as string | undefined;
    if (key && crumbs.at(-1)?.labelKey !== key) {
      crumbs.push({ labelKey: key, url: '/' + segments.join('/') });
    }
    route = route.firstChild;
  }
  return crumbs;
}
