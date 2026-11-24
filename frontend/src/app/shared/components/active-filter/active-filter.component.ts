import { ChangeDetectionStrategy, Component, computed, inject, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Select } from 'primeng/select';
import { LanguageService } from '../../../core/i18n/language.service';

/** Active / Inactive / All filter for master lists. `null` means all. */
@Component({
  selector: 's2s-active-filter',
  imports: [FormsModule, TranslatePipe, Select],
  templateUrl: './active-filter.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActiveFilterComponent {
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);

  readonly value = model<boolean | null>(true);

  protected readonly options = computed(() => {
    this.language.current();
    return [
      { label: this.translate.instant('filters.active'), value: true },
      { label: this.translate.instant('filters.inactive'), value: false },
      { label: this.translate.instant('filters.all'), value: null },
    ];
  });
}
