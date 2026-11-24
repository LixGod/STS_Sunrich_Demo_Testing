import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { NAV_GROUPS } from '../../layout/nav.config';

/**
 * Only planned groups have a placeholder. A group that has since been delivered (e.g. an old bookmark to
 * /coming-soon/commercial) goes to its first screen instead; an unknown group goes to the dashboard.
 */
export const comingSoonGuard: CanActivateFn = (route) => {
  const group = NAV_GROUPS.find((g) => g.id === route.paramMap.get('group'));
  if (group?.phase !== undefined) return true;
  return inject(Router).parseUrl(group?.items[0]?.route ?? '/dashboard');
};
