import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Select } from 'primeng/select';
import { PageQuery } from '../../core/api/api.models';
import { LanguageService } from '../../core/i18n/language.service';
import { DataTableComponent } from '../../shared/components/data-table/data-table.component';
import { ColumnDef, RowAction } from '../../shared/components/data-table/table.models';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { TechnicalService, VesselRow } from './technical.service';

/** Vessels with the state of their particulars (unverified until a second person verifies them, FR-TECH-01). */
@Component({
  selector: 's2s-vessel-list',
  imports: [FormsModule, TranslatePipe, Select, DataTableComponent, PageHeaderComponent],
  template: `
    <s2s-page-header [heading]="'nav.vesselParticulars' | translate" [subtitle]="'technical.listSubtitle' | translate" />
    <section class="s2s-panel">
      <s2s-data-table [columns]="columns" [load]="load" [params]="params()" [actions]="actions" defaultSort="name,asc"
        captionKey="nav.vesselParticulars" emptyKey="technical.noVessels">
        <p-select tableToolbar [options]="typeOptions()" optionLabel="label" optionValue="value" [showClear]="true"
          [placeholder]="'masters.vessel.type' | translate" [ariaLabel]="'masters.vessel.type' | translate"
          [ngModel]="vesselType()" (ngModelChange)="vesselType.set($event ?? null)" />
      </s2s-data-table>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VesselListComponent {
  private readonly service = inject(TechnicalService);
  private readonly router = inject(Router);
  private readonly translate = inject(TranslateService);
  private readonly language = inject(LanguageService);

  protected readonly vesselType = signal<string | null>(null);
  protected readonly params = computed(() => ({ active: true, vesselType: this.vesselType() }));
  protected readonly typeOptions = computed(() => {
    this.language.current();
    return ['CRUDE_TANKER', 'PRODUCT_TANKER', 'CHEMICAL_TANKER', 'LPG_CARRIER', 'LNG_CARRIER', 'BULK_CARRIER', 'OTHER']
      .map((value) => ({ value, label: this.translate.instant(`enums.vesselType.${value}`) }));
  });
  protected readonly load = (query: PageQuery) => this.service.vessels(query);

  protected readonly columns: ColumnDef<VesselRow>[] = [
    { key: 'name', headerKey: 'masters.name', sortField: 'name', link: (row) => ['/technical/vessels', row.id] },
    { key: 'imo', headerKey: 'masters.vessel.imo', sortField: 'imo', width: '8rem' },
    { key: 'vesselType', headerKey: 'masters.vessel.type', type: 'translated', value: (row) => `enums.vesselType.${row.vesselType}` },
    { key: 'particularsStatus', headerKey: 'technical.particularsStatus', type: 'status', width: '10rem' },
  ];

  protected readonly actions: RowAction<VesselRow>[] = [
    { icon: 'pi pi-angle-right', labelKey: 'common.open', run: (row) => void this.router.navigate(['/technical/vessels', row.id]) },
  ];
}
