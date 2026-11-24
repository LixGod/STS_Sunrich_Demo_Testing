import { Directive, TemplateRef, ViewContainerRef, effect, inject, input } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';

/**
 * Renders its content only if the user holds the permission (or any of a list):
 * `<p-button *s2sCan="'MASTER.ORG.WRITE'" … />`. Display only — the server authorises every request.
 */
@Directive({ selector: '[s2sCan]' })
export class CanDirective {
  private readonly auth = inject(AuthService);
  private readonly template = inject(TemplateRef<unknown>);
  private readonly container = inject(ViewContainerRef);

  readonly s2sCan = input.required<string | readonly string[]>();

  private shown = false;

  constructor() {
    effect(() => {
      this.auth.currentUser();
      const required = this.s2sCan();
      const allowed = this.auth.hasAnyPermission(typeof required === 'string' ? [required] : required);
      if (allowed && !this.shown) {
        this.container.createEmbeddedView(this.template);
        this.shown = true;
      } else if (!allowed && this.shown) {
        this.container.clear();
        this.shown = false;
      }
    });
  }
}
