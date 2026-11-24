import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

/** Page title with optional subtitle; projected content becomes the page's primary actions. */
@Component({
  selector: 's2s-page-header',
  imports: [RouterLink, TranslatePipe],
  templateUrl: './page-header.component.html',
  styleUrl: './page-header.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PageHeaderComponent {
  readonly heading = input.required<string>();
  readonly subtitle = input<string | null | undefined>(undefined);
  readonly backLink = input<string | null>(null);
}
