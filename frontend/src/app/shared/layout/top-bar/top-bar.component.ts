import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Button } from 'primeng/button';
import { Select } from 'primeng/select';
import { AuthService } from '../../../core/auth/auth.service';
import { LanguageService } from '../../../core/i18n/language.service';

@Component({
  selector: 's2s-top-bar',
  imports: [RouterLink, FormsModule, TranslatePipe, Button, Select],
  templateUrl: './top-bar.component.html',
  styleUrl: './top-bar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TopBarComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly language = inject(LanguageService);

  readonly navOpen = input(false);
  readonly toggleNav = output<void>();

  protected readonly user = this.auth.currentUser;

  protected changeLanguage(code: string): void {
    void this.language.use(code, true);
  }

  protected signOut(): void {
    this.auth.logout().subscribe({
      next: () => void this.router.navigate(['/login']),
      error: () => undefined,
    });
  }
}
